#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK_FILE="$ROOT_DIR/tools/natives/binaries.lock"
TMP_DIR="$ROOT_DIR/tmp/lock-closure"
mkdir -p "$TMP_DIR"

PYTHON_BIN="${PDAL_FFM_PYTHON:-}"
if [[ -z "$PYTHON_BIN" && -n "${CONDA_PREFIX:-}" && -x "$CONDA_PREFIX/bin/python3" ]]; then
  PYTHON_BIN="$CONDA_PREFIX/bin/python3"
fi
if [[ -z "$PYTHON_BIN" ]]; then
  for candidate in python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
    if command -v "$candidate" >/dev/null 2>&1 \
      && "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
      PYTHON_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$PYTHON_BIN" ]]; then
  echo "No Python >= 3.10 found (set PDAL_FFM_PYTHON to a suitable interpreter)." >&2
  exit 1
fi

"$PYTHON_BIN" - "$LOCK_FILE" "$TMP_DIR" "$@" <<'PY'
import argparse
import difflib
import fnmatch
import hashlib
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

LOCK_FILE = Path(sys.argv[1])
TMP_DIR = Path(sys.argv[2])
FILES_CACHE_DIR = TMP_DIR / "package-files"
ARCHIVE_CACHE_DIR = TMP_DIR / "archive-cache"
FILES_CACHE_DIR.mkdir(parents=True, exist_ok=True)
ARCHIVE_CACHE_DIR.mkdir(parents=True, exist_ok=True)

parser = argparse.ArgumentParser(prog="refresh-lock-closure.sh")
parser.add_argument("--check", action="store_true", help="Verify lock is up to date without writing")
parser.add_argument("--max-backtracks", type=int, default=2000, help="Maximum backtracking steps per classifier")
parser.add_argument("--debug-resolver", action="store_true", help="Print resolver decision trace")
args = parser.parse_args(sys.argv[3:])

if args.max_backtracks < 1:
    raise RuntimeError("--max-backtracks must be >= 1")

CLASSIFIERS = [
    "linux-x86_64",
    "linux-aarch64",
    "osx-x86_64",
    "osx-aarch64",
    "windows-x86_64",
]

SUBDIR = {
    "linux-x86_64": "linux-64",
    "linux-aarch64": "linux-aarch64",
    "osx-x86_64": "osx-64",
    "osx-aarch64": "osx-arm64",
    "windows-x86_64": "win-64",
}

EXTRA_RE = re.compile(r"^platform\.[^.]+\.extra_(url|sha256|archive|strip_prefix)_\d+=")
PLATFORM_LINE_RE = re.compile(r"^platform\.([^.]+)\.")
EXTRA_URL_KEY_RE = re.compile(r"^platform\.[^.]+\.extra_url_(\d+)$")


class MetadataFetchError(RuntimeError):
    pass


class ResolutionError(RuntimeError):
    pass


class BacktrackLimitError(ResolutionError):
    pass


# Artifacts that are listed by the anaconda.org files API but cannot be
# downloaded anymore (removed upstream). They are excluded from candidate
# domains and detected lazily when the resolver downloads a selected package.
UNAVAILABLE_CANDIDATES: set[tuple[str, str, str]] = set()


def candidate_key(candidate: dict) -> tuple[str, str, str]:
    return (candidate.get("name", ""), candidate.get("version", ""), candidate.get("build", ""))


@dataclass(frozen=True)
class ConstraintEntry:
    expr: str
    reason: str


@dataclass
class ResolverState:
    selected: dict[str, dict]
    constraints: dict[str, list[ConstraintEntry]]
    domains: dict[str, list[dict]]
    reasons: dict[str, list[str]]
    expanded: set[str]

    def copy(self) -> "ResolverState":
        return ResolverState(
            selected=dict(self.selected),
            constraints={pkg: list(entries) for pkg, entries in self.constraints.items()},
            domains={pkg: list(entries) for pkg, entries in self.domains.items()},
            reasons={pkg: list(entries) for pkg, entries in self.reasons.items()},
            expanded=set(self.expanded),
        )


def read_lock_lines() -> list[str]:
    return LOCK_FILE.read_text(encoding="utf-8").splitlines()


def parse_props(lines: list[str]) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in lines:
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key] = value
    return props


def normalize_url(url: str) -> str:
    return f"https:{url}" if url.startswith("//") else url


def package_name_from_url(url: str) -> str:
    parts = normalize_url(url).split("?")[0].split("/")
    if len(parts) < 4:
        raise RuntimeError(f"Cannot infer package name from URL: {url}")
    return parts[-4]


def basename_from_url(url: str) -> str:
    return normalize_url(url).split("?")[0].split("/")[-1]


def version_key(version: str):
    tokens = re.findall(r"\d+|[A-Za-z]+", version)
    key = []
    for token in tokens:
        if token.isdigit():
            key.append((0, int(token)))
        else:
            key.append((1, token.lower()))
    return tuple(key)


PRE_RELEASE_LABELS = {"dev", "a", "alpha", "b", "beta", "c", "rc", "pre", "preview"}
POST_RELEASE_LABELS = {"post", "rev", "r"}


def trailing_order(extra_tokens):
    if not extra_tokens:
        return 0

    first = extra_tokens[0]
    if first[0] == 1:
        label = first[1]
        if label in PRE_RELEASE_LABELS:
            return -1
        if label in POST_RELEASE_LABELS:
            return 1

    return 1


def cmp_versions(a: str, b: str) -> int:
    ka = list(version_key(a))
    kb = list(version_key(b))
    common = min(len(ka), len(kb))

    for idx in range(common):
        ta = ka[idx]
        tb = kb[idx]
        if ta == tb:
            continue

        if ta[0] == tb[0]:
            return -1 if ta[1] < tb[1] else 1

        # numeric tokens outrank alpha tokens (e.g. 1.0.1 > 1.0rc1)
        return 1 if ta[0] == 0 else -1

    if len(ka) == len(kb):
        return 0

    if len(ka) > len(kb):
        order = trailing_order(ka[common:])
        return 1 if order > 0 else -1

    order = trailing_order(kb[common:])
    return -1 if order > 0 else 1


def satisfies(version: str, constraints: str) -> bool:
    constraints = constraints.strip()
    if not constraints:
        return True

    for part in [p.strip() for p in constraints.split(",") if p.strip()]:
        if part.startswith(">="):
            if cmp_versions(version, part[2:].strip()) < 0:
                return False
            continue
        if part.startswith(">"):
            if cmp_versions(version, part[1:].strip()) <= 0:
                return False
            continue
        if part.startswith("<="):
            if cmp_versions(version, part[2:].strip()) > 0:
                return False
            continue
        if part.startswith("<"):
            if cmp_versions(version, part[1:].strip()) >= 0:
                return False
            continue
        if part.startswith("=="):
            rhs = part[2:].strip()
            if rhs.endswith("*"):
                if not version.startswith(rhs[:-1]):
                    return False
            elif version != rhs:
                return False
            continue
        if part.startswith("="):
            if version != part[1:].strip():
                return False
            continue
        if part.startswith("!="):
            rhs = part[2:].strip()
            if rhs.endswith("*"):
                if version.startswith(rhs[:-1]):
                    return False
            elif version == rhs:
                return False
            continue

        if part.endswith("*"):
            if not version.startswith(part[:-1]):
                return False
        elif version != part:
            return False

    return True


def split_dep(spec: str) -> tuple[str, str]:
    spec = spec.strip()
    if not spec:
        return "", ""
    if " " not in spec:
        return spec, ""
    name, constraints = spec.split(" ", 1)
    return name.strip(), constraints.strip()


def split_version_build(constraints: str) -> tuple[str, str]:
    parts = constraints.strip().split()
    if not parts:
        return "", ""
    if len(parts) == 1:
        return parts[0], ""
    return parts[0], " ".join(parts[1:])


def matches_build(build: str, build_spec: str) -> bool:
    build_spec = build_spec.strip()
    if not build_spec or build_spec == "*":
        return True
    if not build:
        return False
    return fnmatch.fnmatchcase(build, build_spec)


def candidate_satisfies(candidate: dict, constraint_expr: str) -> bool:
    constraint_expr = constraint_expr.strip()
    if not constraint_expr:
        return True

    version_spec, build_spec = split_version_build(constraint_expr)
    if version_spec and version_spec != "*":
        if not satisfies(candidate["version"], version_spec):
            return False

    if build_spec and not matches_build(candidate.get("build", ""), build_spec):
        return False

    return True


def run_curl(url: str, target: Path):
    last_err = None
    for attempt in range(1, 6):
        try:
            subprocess.run(
                ["curl", "--retry", "5", "--retry-delay", "2", "--retry-all-errors", "-fL", url, "-o", str(target)],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            return
        except Exception as exc:
            last_err = exc
            time.sleep(min(2 * attempt, 10))
    raise MetadataFetchError(f"Failed to download {url}: {last_err}")


package_files_cache: dict[str, list[dict]] = {}


def fetch_package_files(package_name: str) -> list[dict]:
    if package_name in package_files_cache:
        return package_files_cache[package_name]

    cache_file = FILES_CACHE_DIR / f"{package_name}.json"
    if cache_file.exists():
        data = json.loads(cache_file.read_text(encoding="utf-8"))
    else:
        url = f"https://api.anaconda.org/package/conda-forge/{package_name}/files"
        tmp_file = cache_file.with_suffix(".tmp")
        print(f"Fetching package metadata: {package_name}")
        run_curl(url, tmp_file)
        tmp_file.replace(cache_file)
        data = json.loads(cache_file.read_text(encoding="utf-8"))

    package_files_cache[package_name] = data
    return data


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def compute_sha256(download_url: str, basename: str) -> str:
    normalized = normalize_url(download_url)
    safe_name = basename.replace("/", "__")
    archive_path = ARCHIVE_CACHE_DIR / safe_name
    if not archive_path.exists():
        tmp_file = archive_path.with_suffix(".tmp")
        run_curl(normalized, tmp_file)
        tmp_file.replace(archive_path)
    return sha256_file(archive_path)


def build_number(entry: dict) -> int:
    attrs = entry.get("attrs") or {}
    if attrs.get("build_number") is not None:
        try:
            return int(attrs.get("build_number"))
        except Exception:
            pass

    build = attrs.get("build") or ""
    m = re.search(r"_(\d+)$", build)
    if m:
        return int(m.group(1))
    return 0


def candidates_for(dep_name: str, subdir: str) -> list[dict]:
    out: list[dict] = []
    for entry in fetch_package_files(dep_name):
        basename = entry.get("basename", "")
        if not basename.endswith(".conda"):
            continue

        attrs = entry.get("attrs") or {}
        entry_subdir = attrs.get("subdir", "")
        if entry_subdir not in (subdir, "noarch"):
            continue

        out.append(
            {
                "name": dep_name,
                "version": entry.get("version", ""),
                "build": attrs.get("build", ""),
                "build_number": build_number(entry),
                "subdir": entry_subdir,
                "basename": basename,
                "download_url": normalize_url(entry.get("download_url", "")),
                "depends": list(attrs.get("depends") or []),
                "sha256": (entry.get("sha256") or "").strip(),
            }
        )

    return out


def pinned_candidate_from_url(url: str, subdir: str, package_name: str) -> dict:
    normalized = normalize_url(url)
    base = basename_from_url(url)

    for entry in fetch_package_files(package_name):
        basename = entry.get("basename", "")
        if not basename.endswith(".conda"):
            continue
        attrs = entry.get("attrs") or {}
        entry_subdir = attrs.get("subdir", "")
        if entry_subdir != subdir:
            continue

        full_basename = basename
        if full_basename.endswith(f"/{base}") or full_basename == base:
            return {
                "name": package_name,
                "version": entry.get("version", ""),
                "build": attrs.get("build", ""),
                "build_number": build_number(entry),
                "subdir": entry_subdir,
                "basename": full_basename,
                "download_url": normalize_url(entry.get("download_url", "")),
                "depends": list(attrs.get("depends") or []),
                "sha256": (entry.get("sha256") or "").strip(),
                "url": normalized,
            }

    raise ResolutionError(f"Pinned package not found in package metadata for {package_name}: {url}")


def describe_constraints(entries: list[ConstraintEntry]) -> str:
    if not entries:
        return "(none)"
    lines = []
    for entry in entries:
        if entry.expr:
            lines.append(f"- {entry.expr} (from {entry.reason})")
        else:
            lines.append(f"- unconstrained (from {entry.reason})")
    return "\n".join(lines)


def top_candidate_versions(candidates: list[dict], limit: int = 5) -> str:
    preview = candidates[:limit]
    if not preview:
        return "(no candidates)"
    items = [f"{c['version']} {c.get('build', '?')} [{c['basename']}]" for c in preview]
    return "\n".join(f"- {item}" for item in items)


class BacktrackingResolver:
    def __init__(self, *, classifier: str, subdir: str, max_backtracks: int, debug: bool):
        self.classifier = classifier
        self.subdir = subdir
        self.max_backtracks = max_backtracks
        self.debug = debug
        self.backtracks = 0
        self.all_candidates_cache: dict[str, list[dict]] = {}
        self.domain_cache: dict[tuple[str, tuple[str, ...]], list[dict]] = {}

    def log(self, message: str):
        if self.debug:
            print(f"[resolver:{self.classifier}] {message}", file=sys.stderr)

    def ensure_candidates(self, package: str) -> list[dict]:
        if package in self.all_candidates_cache:
            return self.all_candidates_cache[package]

        candidates = candidates_for(package, self.subdir)
        if not candidates:
            raise ResolutionError(f"No candidates found for package '{package}' in subdir '{self.subdir}/noarch'")

        candidates.sort(
            key=lambda c: (
                version_key(c["version"]),
                c["build_number"],
                1 if c["subdir"] == self.subdir else 0,
                c["basename"],
            ),
            reverse=True,
        )
        self.all_candidates_cache[package] = candidates
        return candidates

    def domain_for(self, package: str, entries: list[ConstraintEntry]) -> list[dict]:
        active = tuple(sorted({entry.expr for entry in entries if entry.expr}))
        key = (package, active)
        if key in self.domain_cache:
            return self.domain_cache[key]

        candidates = self.ensure_candidates(package)
        if not active:
            result = candidates
        else:
            result = [cand for cand in candidates if all(candidate_satisfies(cand, expr) for expr in active)]

        result = [cand for cand in result if candidate_key(cand) not in UNAVAILABLE_CANDIDATES]

        self.domain_cache[key] = result
        return result

    def update_domain(self, state: ResolverState, package: str):
        entries = state.constraints.get(package, [])
        state.domains[package] = self.domain_for(package, entries)

    def add_constraint(self, state: ResolverState, package: str, expr: str, reason: str):
        if package.startswith("__"):
            return

        normalized = expr.strip()
        entries = state.constraints.setdefault(package, [])
        reasons = state.reasons.setdefault(package, [])
        if reason not in reasons:
            reasons.append(reason)

        if normalized == "":
            if any(entry.expr == "" for entry in entries):
                return
            entries.append(ConstraintEntry("", reason))
            self.update_domain(state, package)
            if self.debug:
                self.log(f"constraint {package}: unconstrained ({reason}) -> domain={len(state.domains[package])}")
            return

        for entry in entries:
            if entry.expr == normalized and entry.reason == reason:
                return

        entries.append(ConstraintEntry(normalized, reason))
        self.update_domain(state, package)
        if self.debug:
            self.log(f"constraint {package}: {normalized} ({reason}) -> domain={len(state.domains[package])}")

    def add_spec_constraint(self, state: ResolverState, spec: str, reason: str):
        dep_name, dep_constraints = split_dep(spec)
        if not dep_name or dep_name.startswith("__"):
            return
        self.add_constraint(state, dep_name, dep_constraints, reason)

    def expand_selected(self, state: ResolverState, package: str):
        if package in state.expanded:
            return

        candidate = state.selected[package]
        state.expanded.add(package)
        for dep_spec in candidate.get("depends", []):
            dep_spec = dep_spec.strip()
            if not dep_spec:
                continue
            dep_name, dep_constraints = split_dep(dep_spec)
            if not dep_name or dep_name.startswith("__"):
                continue
            self.add_constraint(
                state,
                dep_name,
                dep_constraints,
                f"{package}@{candidate.get('version', '?')} requires '{dep_spec}'",
            )

    def normalize_state(self, state: ResolverState):
        changed = True
        while changed:
            changed = False
            for package in list(state.selected.keys()):
                if package not in state.expanded:
                    self.expand_selected(state, package)
                    changed = True

    def validate_state(self, state: ResolverState) -> str | None:
        for package, candidate in state.selected.items():
            if candidate_key(candidate) in UNAVAILABLE_CANDIDATES:
                return (
                    f"Selected candidate was removed upstream '{package}':\n"
                    f"  selected: {candidate['version']} {candidate.get('build', '?')} [{candidate['basename']}]"
                )

        for package, candidate in state.selected.items():
            entries = state.constraints.get(package, [])
            violations = [entry for entry in entries if entry.expr and not candidate_satisfies(candidate, entry.expr)]
            if violations:
                violated = "\n".join(f"- {entry.expr} (from {entry.reason})" for entry in violations)
                return (
                    f"Selected candidate conflict for '{package}':\n"
                    f"  selected: {candidate['version']} {candidate.get('build', '?')} [{candidate['basename']}]\n"
                    f"Violated constraints:\n{violated}\n"
                    f"All constraints for {package}:\n{describe_constraints(entries)}"
                )

        for package in sorted(state.constraints):
            if package in state.selected:
                continue
            entries = state.constraints[package]
            if package not in state.domains:
                self.update_domain(state, package)
            domain = state.domains[package]
            if not domain:
                candidates = self.ensure_candidates(package)
                reasons = "\n".join(f"- {reason}" for reason in state.reasons.get(package, [])) or "- (none)"
                return (
                    f"No compatible candidates for '{package}'.\n"
                    f"Constraints:\n{describe_constraints(entries)}\n"
                    f"Constraint origins:\n{reasons}\n"
                    f"Top available candidates:\n{top_candidate_versions(candidates)}"
                )

        return None

    def choose_next_package(self, state: ResolverState) -> str:
        choices = []
        for package in sorted(state.constraints):
            if package in state.selected:
                continue
            if package not in state.domains:
                self.update_domain(state, package)
            domain = state.domains[package]
            choices.append((len(domain), package))

        if not choices:
            raise ResolutionError("No unresolved packages left while solver expected work")

        choices.sort(key=lambda x: (x[0], x[1]))
        return choices[0][1]

    def solve(self, state: ResolverState) -> ResolverState:
        solved, last_conflict = self._search(state, depth=0)
        if solved is None:
            raise ResolutionError(
                "Unable to resolve a compatible dependency set.\n"
                f"Last conflict:\n{last_conflict or '(none)'}"
            )
        return solved

    def _search(self, state: ResolverState, depth: int) -> tuple[ResolverState | None, str | None]:
        self.normalize_state(state)

        conflict = self.validate_state(state)
        if conflict:
            if self.debug:
                self.log(f"{'  ' * depth}conflict -> {conflict.splitlines()[0]}")
            return None, conflict

        unresolved = [package for package in sorted(state.constraints) if package not in state.selected]
        if not unresolved:
            return state, None

        package = self.choose_next_package(state)
        entries = state.constraints[package]
        domain = state.domains.get(package)
        if domain is None:
            domain = self.domain_for(package, entries)
            state.domains[package] = domain

        if self.debug:
            self.log(f"{'  ' * depth}pick {package} ({len(domain)} candidates)")

        last_conflict = None
        for candidate in domain:
            if self.debug:
                self.log(f"{'  ' * depth}try {package}={candidate['version']} [{candidate['basename']}]")

            child = state.copy()
            child.selected[package] = candidate
            child.domains[package] = [candidate]

            solved, conflict = self._search(child, depth + 1)
            if solved is not None:
                return solved, None

            last_conflict = conflict
            self.backtracks += 1
            if self.backtracks > self.max_backtracks:
                raise BacktrackLimitError(
                    f"Exceeded max backtracks ({self.max_backtracks}) while resolving '{self.classifier}'.\n"
                    f"Last conflict:\n{last_conflict or '(none)'}"
                )

            if self.debug:
                self.log(f"{'  ' * depth}backtrack #{self.backtracks} from {package}={candidate['version']}")

        return None, last_conflict


def resolve_classifier(props: dict[str, str], classifier: str, existing_sha_by_url: dict[str, str]) -> list[str]:
    subdir = SUBDIR[classifier]
    main_url_key = f"platform.{classifier}.url"
    main_url = props.get(main_url_key)
    if not main_url:
        raise ResolutionError(f"Missing key: {main_url_key}")

    main_pkg = package_name_from_url(main_url)
    closure_roots = [r.strip() for r in props.get(f"platform.{classifier}.closure_roots", "").split(",") if r.strip()]

    pinned_main = pinned_candidate_from_url(main_url, subdir, main_pkg)

    max_attempts = 10
    for attempt in range(1, max_attempts + 1):
        resolver = BacktrackingResolver(
            classifier=classifier,
            subdir=subdir,
            max_backtracks=args.max_backtracks,
            debug=args.debug_resolver,
        )

        state = ResolverState(selected={main_pkg: pinned_main}, constraints={}, domains={}, reasons={}, expanded=set())
        resolver.add_constraint(state, main_pkg, "", f"pinned main package URL for {classifier}")

        for root_spec in closure_roots:
            resolver.add_spec_constraint(state, root_spec, f"closure_roots for {classifier}")

        solved = resolver.solve(state)
        solved.selected.pop(main_pkg, None)

        lines: list[str] = []
        unavailable: tuple[str, dict, MetadataFetchError] | None = None
        for idx, dep_name in enumerate(sorted(solved.selected), start=1):
            dep = solved.selected[dep_name]
            dep_url = dep["download_url"]
            dep_sha = existing_sha_by_url.get(dep_url) or dep.get("sha256") or None
            if not dep_sha:
                try:
                    dep_sha = compute_sha256(dep_url, dep["basename"])
                except MetadataFetchError as exc:
                    unavailable = (dep_name, dep, exc)
                    break

            lines.append(f"platform.{classifier}.extra_url_{idx}={dep_url}")
            lines.append(f"platform.{classifier}.extra_sha256_{idx}={dep_sha}")
            lines.append(f"platform.{classifier}.extra_archive_{idx}=conda")
            lines.append(f"platform.{classifier}.extra_strip_prefix_{idx}=.")

        if unavailable is None:
            return lines

        dep_name, dep, exc = unavailable
        key = candidate_key(dep)
        print(
            f"Skipping unavailable upstream artifact {key[0]} {key[1]} {key[2]} "
            f"(attempt {attempt}): {exc}",
            file=sys.stderr,
        )
        UNAVAILABLE_CANDIDATES.add(key)

    raise ResolutionError(
        f"Gave up resolving '{classifier}' after {max_attempts} attempts with removed upstream artifacts"
    )


def rewrite_lock(lines: list[str], blocks: dict[str, list[str]]) -> list[str]:
    filtered = [line for line in lines if not EXTRA_RE.match(line)]
    out: list[str] = []

    current_classifier = None
    inserted = True

    for line in filtered:
        m = PLATFORM_LINE_RE.match(line)
        line_classifier = m.group(1) if m else None

        if current_classifier and line_classifier != current_classifier and not inserted:
            out.extend(blocks.get(current_classifier, []))
            inserted = True

        if line_classifier and line_classifier != current_classifier:
            current_classifier = line_classifier
            inserted = False

        out.append(line)

    if current_classifier and not inserted:
        out.extend(blocks.get(current_classifier, []))

    return out


original_lines = read_lock_lines()
props = parse_props(original_lines)

EXISTING_SHA_BY_URL: dict[str, str] = {}
for key, value in props.items():
    m = EXTRA_URL_KEY_RE.match(key)
    if not m:
        continue
    sha_key = key.replace(".extra_url_", ".extra_sha256_")
    sha_val = props.get(sha_key)
    if sha_val:
        EXISTING_SHA_BY_URL[normalize_url(value)] = sha_val

for classifier in CLASSIFIERS:
    for required_key in (
        f"platform.{classifier}.url",
        f"platform.{classifier}.sha256",
        f"platform.{classifier}.archive",
        f"platform.{classifier}.strip_prefix",
        f"platform.{classifier}.entry_library",
        f"platform.{classifier}.preload_libraries",
        f"platform.{classifier}.plugin_path",
    ):
        if required_key not in props:
            raise RuntimeError(f"Missing key in lock file: {required_key}")

blocks: dict[str, list[str]] = {}
for classifier in CLASSIFIERS:
    try:
        blocks[classifier] = resolve_classifier(props, classifier, EXISTING_SHA_BY_URL)
    except MetadataFetchError as exc:
        raise RuntimeError(
            f"Metadata/network failure while resolving '{classifier}': {exc}"
        ) from exc
    except BacktrackLimitError as exc:
        raise RuntimeError(
            f"Resolver backtrack limit reached for '{classifier}'. "
            f"Increase --max-backtracks or inspect constraints with --debug-resolver.\n{exc}"
        ) from exc
    except ResolutionError as exc:
        raise RuntimeError(
            f"Resolver conflict for '{classifier}'. Use --debug-resolver for trace.\n{exc}"
        ) from exc

updated_lines = rewrite_lock(original_lines, blocks)
updated_text = "\n".join(updated_lines) + "\n"
original_text = LOCK_FILE.read_text(encoding="utf-8")

if args.check:
    if updated_text != original_text:
        diff = difflib.unified_diff(
            original_text.splitlines(),
            updated_text.splitlines(),
            fromfile=str(LOCK_FILE),
            tofile=f"{LOCK_FILE} (generated)",
            lineterm="",
        )
        for line in diff:
            print(line)
        print("binaries.lock is out of date. Run tools/natives/refresh-lock-closure.sh", file=sys.stderr)
        sys.exit(1)
    print("binaries.lock is up to date.")
    sys.exit(0)

LOCK_FILE.write_text(updated_text, encoding="utf-8")
print(f"Updated {LOCK_FILE}")
PY
