#!/usr/bin/env bash
#
# Rewrites embedded conda build paths in bundled native libraries.
#
# conda-forge binaries bake absolute paths of the build machine into their
# strings, for example the default CA file of libcurl:
#
#   /Users/runner/miniforge3/conda-bld/.../_h_env_placehold.../ssl/cacert.pem
#
# A relocated bundle cannot provide those paths. libcurl uses the baked-in CA
# file when setting up the TLS store and fails before the handshake, so remote
# COPC/EPT reads fail even though a CA bundle is exported at runtime. The paths
# are rewritten to operating system paths that exist on the target platform;
# the bundled CA file is still applied at runtime through CURL_CA_BUNDLE and
# SSL_CERT_FILE.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <classifier> <bundle-dir>" >&2
  exit 1
fi

CLASSIFIER="$1"
BUNDLE_DIR="$2"

if [[ ! -d "$BUNDLE_DIR" ]]; then
  echo "Bundle directory does not exist: $BUNDLE_DIR" >&2
  exit 1
fi

python3 - "$CLASSIFIER" "$BUNDLE_DIR" <<'PY'
import pathlib
import sys

classifier = sys.argv[1]
bundle = pathlib.Path(sys.argv[2])

if classifier.startswith("osx"):
    ca_file = b"/etc/ssl/cert.pem"
    ssl_dir = b"/etc/ssl"
    roots = [bundle / "lib"]
elif classifier.startswith("linux"):
    ca_file = b"/etc/ssl/certs/ca-certificates.crt"
    ssl_dir = b"/etc/ssl/certs"
    roots = [bundle / "lib"]
else:
    # Windows uses schannel; there is no baked-in CA path.
    print("No embedded path updates for", classifier)
    sys.exit(0)

placeholder = b"_h_env_placehold"
patched_files = 0

for root in roots:
    if not root.is_dir():
        continue
    for binary in sorted(root.rglob("*")):
        if not binary.is_file() or binary.is_symlink():
            continue
        data = bytearray(binary.read_bytes())
        changed = False
        search_from = 0
        while True:
            index = data.find(placeholder, search_from)
            if index < 0:
                break
            start = data.rfind(b"\0", 0, index) + 1
            end = data.find(b"\0", index)
            if end < 0:
                break
            value = bytes(data[start:end])
            replacement = None
            if value.endswith(b"/ssl/cacert.pem"):
                replacement = ca_file
            elif value.endswith(b"/ssl"):
                replacement = ssl_dir
            if replacement is not None and len(replacement) <= len(value):
                data[start : start + len(replacement)] = replacement
                data[start + len(replacement) : end] = b"\0" * (end - start - len(replacement))
                changed = True
                print(f"patched {binary.name}: {value.decode(errors='replace')} -> {replacement.decode()}")
            search_from = end + 1
        if changed:
            binary.write_bytes(bytes(data))
            patched_files += 1

print(f"Embedded path cleanup completed for {classifier}: {patched_files} file(s) patched")
PY
