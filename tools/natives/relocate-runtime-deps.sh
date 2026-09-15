#!/usr/bin/env bash
#
# Relocates a staged PDAL native bundle so that it can be loaded from any
# directory (Java extraction cache):
#   - Linux: rewrite absolute DT_NEEDED entries of bundled libraries
#   - macOS: normalize install names to @rpath and ad-hoc sign the binaries
#   - Windows: fail fast if conda placeholder markers remain in DLLs
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <classifier> <bundle-dir>" >&2
  exit 1
fi

CLASSIFIER="$1"
BUNDLE_DIR="$2"
TMP_ROOT="${TMPDIR:-/tmp}"
LIB_BASENAMES_FILE="$(mktemp "$TMP_ROOT/pdal-ffm-relocate-${CLASSIFIER}.XXXXXX")"
trap 'rm -f "$LIB_BASENAMES_FILE"' EXIT

if [[ ! -d "$BUNDLE_DIR" ]]; then
  echo "Bundle directory does not exist: $BUNDLE_DIR" >&2
  exit 1
fi

classifier_os() {
  case "$CLASSIFIER" in
    linux-*)
      echo "linux"
      ;;
    osx-*)
      echo "osx"
      ;;
    windows-*)
      echo "windows"
      ;;
    *)
      echo "Unsupported classifier: $CLASSIFIER" >&2
      exit 1
      ;;
  esac
}

contains_placeholder_marker() {
  local value="$1"
  if [[ "$value" == *"_h_env_placehold"* ]]; then
    return 0
  fi
  if [[ "$value" == *"/home/conda/feedstock_root"* ]]; then
    return 0
  fi
  if [[ "$value" == *"/opt/conda"* ]]; then
    return 0
  fi
  if [[ "$value" == *"C:\\b\\"* ]]; then
    return 0
  fi
  return 1
}

is_elf_binary() {
  local file="$1"
  local magic
  magic="$(LC_ALL=C dd if="$file" bs=4 count=1 2>/dev/null | od -An -t x1 | tr -d ' \n')"
  [[ "$magic" == "7f454c46" ]]
}

is_allowed_system_linux_absolute() {
  local dep="$1"
  case "$dep" in
    /lib/*|/lib64/*|/usr/lib/*|/usr/lib64/*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

collect_bundle_lib_basenames() {
  case "$OS_FAMILY" in
    linux|osx)
      : > "$LIB_BASENAMES_FILE"
      while IFS= read -r file; do
        basename "$file"
      done < <(
        {
          if [[ -d "$BUNDLE_DIR/lib" ]]; then
            find "$BUNDLE_DIR/lib" \( -type f -o -type l \) \
              \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dylib.*' \) \
              -print
          fi
        }
      ) | sort -u > "$LIB_BASENAMES_FILE"
      ;;
    windows)
      : > "$LIB_BASENAMES_FILE"
      if [[ -d "$BUNDLE_DIR/bin" ]]; then
        while IFS= read -r file; do
          basename "$file"
        done < <(find "$BUNDLE_DIR/bin" \( -type f -o -type l \) -iname '*.dll' -print) \
          | tr '[:lower:]' '[:upper:]' \
          | sort -u > "$LIB_BASENAMES_FILE"
      fi
      ;;
  esac
}

is_bundle_lib() {
  local lib_name="$1"
  grep -Fxq "$lib_name" "$LIB_BASENAMES_FILE"
}

linux_binaries() {
  if [[ -d "$BUNDLE_DIR/lib" ]]; then
    find "$BUNDLE_DIR/lib" -type f \( -name '*.so' -o -name '*.so.*' \) -print | sort -u
  fi
}

linux_relocate_with_lief() {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "Missing required tool for linux relocation: readelf/patchelf or python3+lief" >&2
    exit 1
  fi

  python3 - "$BUNDLE_DIR" "$LIB_BASENAMES_FILE" <<'PY'
import pathlib
import sys

try:
    import lief
except Exception as exc:
    raise SystemExit(
        "Missing required tool for linux relocation: readelf/patchelf or python3+lief "
        f"({exc})"
    )

BUNDLE_DIR = pathlib.Path(sys.argv[1])
LIB_BASENAMES = {
    line.strip()
    for line in pathlib.Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
    if line.strip()
}
ALLOWED_SYSTEM_PREFIXES = ("/lib/", "/lib64/", "/usr/lib/", "/usr/lib64/")
PLACEHOLDER_MARKERS = (
    "_h_env_placehold",
    "/home/conda/feedstock_root",
    "/opt/conda",
    "C:\\b\\",
)


def linux_binaries():
    patterns = ("*.so", "*.so.*")
    roots = (BUNDLE_DIR / "lib",)
    seen = set()
    for root in roots:
        if not root.is_dir():
            continue
        for pattern in patterns:
            for path in root.rglob(pattern):
                if path.is_file() and not path.is_symlink():
                    seen.add(path)
    return sorted(seen)


for binary_path in linux_binaries():
    if binary_path.read_bytes()[:4] != b"\x7fELF":
        continue
    binary = lief.ELF.parse(str(binary_path))
    if binary is None:
        raise SystemExit(f"Failed to parse ELF binary: {binary_path}")

    changed = False
    for dependency in list(binary.libraries):
        if not dependency:
            continue
        if "/" not in dependency:
            continue

        dependency_base = pathlib.Path(dependency).name
        if dependency_base in LIB_BASENAMES:
            print(f"Rewriting DT_NEEDED in {binary_path}: {dependency} -> {dependency_base}")
            binary.remove_library(dependency)
            if dependency_base not in binary.libraries:
                binary.add_library(dependency_base)
            changed = True
            continue

        if dependency.startswith(ALLOWED_SYSTEM_PREFIXES):
            continue

        raise SystemExit(
            f"Non-relocatable absolute DT_NEEDED dependency '{dependency}' in {binary_path}"
        )

    if changed:
        binary.write(str(binary_path))

for binary_path in linux_binaries():
    if binary_path.read_bytes()[:4] != b"\x7fELF":
        continue
    binary = lief.ELF.parse(str(binary_path))
    if binary is None:
        raise SystemExit(f"Failed to parse ELF binary after relocation: {binary_path}")

    for dependency in binary.libraries:
        if not dependency:
            continue
        if any(marker in dependency for marker in PLACEHOLDER_MARKERS):
            raise SystemExit(
                f"Placeholder dependency remained after relocation: '{dependency}' in {binary_path}"
            )
PY
}

macos_main_binaries() {
  if [[ -d "$BUNDLE_DIR/lib" ]]; then
    find "$BUNDLE_DIR/lib" -type f \( -name '*.dylib' -o -name '*.dylib.*' \) -print | sort -u
  fi
}

macos_all_binaries() {
  macos_main_binaries | sort -u
}

windows_binaries() {
  if [[ -d "$BUNDLE_DIR/bin" ]]; then
    find "$BUNDLE_DIR/bin" -maxdepth 1 -type f -iname '*.dll' -print | sort -u
  fi
}

linux_relocate() {
  if ! command -v readelf >/dev/null 2>&1 || ! command -v patchelf >/dev/null 2>&1; then
    linux_relocate_with_lief
    return
  fi

  local binary dep dep_base
  while IFS= read -r binary; do
    if ! is_elf_binary "$binary"; then
      continue
    fi
    while IFS= read -r dep; do
      [[ -z "$dep" ]] && continue

      if [[ "$dep" == */* ]]; then
        dep_base="$(basename "$dep")"
        if is_bundle_lib "$dep_base"; then
          echo "Rewriting DT_NEEDED in $binary: $dep -> $dep_base"
          patchelf --replace-needed "$dep" "$dep_base" "$binary"
          continue
        fi

        if is_allowed_system_linux_absolute "$dep"; then
          continue
        fi

        echo "Non-relocatable absolute DT_NEEDED dependency '$dep' in $binary" >&2
        exit 1
      fi
    done < <(readelf -d "$binary" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
  done < <(linux_binaries)

  while IFS= read -r binary; do
    if ! is_elf_binary "$binary"; then
      continue
    fi
    while IFS= read -r dep; do
      [[ -z "$dep" ]] && continue
      if contains_placeholder_marker "$dep"; then
        echo "Placeholder dependency remained after relocation: '$dep' in $binary" >&2
        exit 1
      fi
    done < <(readelf -d "$binary" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
  done < <(linux_binaries)
}

has_macos_rpath() {
  local binary="$1"
  local wanted="$2"
  otool -l "$binary" | awk -v wanted="$wanted" '
    $1 == "cmd" && $2 == "LC_RPATH" { in_rpath = 1; next }
    in_rpath && $1 == "path" {
      if ($2 == wanted) {
        found = 1
      }
      in_rpath = 0
    }
    END {
      if (found == 1) {
        exit 0
      }
      exit 1
    }
  '
}

ensure_macos_rpath() {
  local binary="$1"
  local rpath="$2"
  if ! has_macos_rpath "$binary" "$rpath"; then
    echo "Adding LC_RPATH '$rpath' to $binary"
    install_name_tool -add_rpath "$rpath" "$binary"
  fi
}

codesign_macos_binary() {
  local binary="$1"
  if ! command -v codesign >/dev/null 2>&1; then
    echo "Missing required tool for macOS relocation: codesign" >&2
    exit 1
  fi
  echo "Ad-hoc signing $binary"
  codesign --force --sign - "$binary" >/dev/null
}

macos_relocate() {
  if ! command -v otool >/dev/null 2>&1; then
    echo "Missing required tool for macOS relocation: otool" >&2
    exit 1
  fi
  if ! command -v install_name_tool >/dev/null 2>&1; then
    echo "Missing required tool for macOS relocation: install_name_tool" >&2
    exit 1
  fi

  local binary dep dep_base

  while IFS= read -r binary; do
    dep_base="$(basename "$binary")"
    echo "Setting install name for $binary to @rpath/$dep_base"
    install_name_tool -id "@rpath/$dep_base" "$binary"
  done < <(macos_main_binaries)

  while IFS= read -r binary; do
    while IFS= read -r dep; do
      dep="${dep%% (*}"
      dep="${dep#${dep%%[![:space:]]*}}"
      [[ -z "$dep" ]] && continue

      case "$dep" in
        /usr/lib/*|/System/Library/*)
          continue
          ;;
        @rpath/*|@loader_path/*|@executable_path/*)
          continue
          ;;
      esac

      if [[ "$dep" == /* ]]; then
        dep_base="$(basename "$dep")"
        if is_bundle_lib "$dep_base"; then
          echo "Rewriting install name in $binary: $dep -> @rpath/$dep_base"
          install_name_tool -change "$dep" "@rpath/$dep_base" "$binary"
          continue
        fi
        echo "Non-relocatable absolute install name '$dep' in $binary" >&2
        exit 1
      fi

      echo "Unsupported install name '$dep' in $binary" >&2
      exit 1
    done < <(otool -L "$binary" | tail -n +2)
  done < <(macos_all_binaries)

  while IFS= read -r binary; do
    ensure_macos_rpath "$binary" "@loader_path"
  done < <(macos_main_binaries)

  while IFS= read -r binary; do
    codesign_macos_binary "$binary"
  done < <(macos_all_binaries)
}

windows_validate() {
  local binary
  while IFS= read -r binary; do
    if grep -aF -q \
      -e "_h_env_placehold" \
      -e "/home/conda/feedstock_root" \
      -e "/opt/conda" \
      -e "C:\\b\\" \
      "$binary"; then
      echo "Placeholder marker detected in Windows DLL: $binary" >&2
      exit 1
    fi
  done < <(windows_binaries)
}

OS_FAMILY="$(classifier_os)"
collect_bundle_lib_basenames

case "$OS_FAMILY" in
  linux)
    linux_relocate
    ;;
  osx)
    macos_relocate
    ;;
  windows)
    windows_validate
    ;;
esac

echo "Relocation/sanitization completed for $CLASSIFIER"
