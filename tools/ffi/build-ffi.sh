#!/usr/bin/env bash
#
# Builds the minimal PDAL C ABI shim (libpdal_ffi) for the given classifier.
#
# Usage:
#   tools/ffi/build-ffi.sh <classifier> [--pdal-root <dir>] [--stage-dir <dir>]
#
# --pdal-root  Directory that contains the PDAL headers and libraries of the
#              pinned conda package (either <root>/include + <root>/lib or
#              <root>/Library/include + <root>/Library/lib on Windows).
#              Defaults to $CONDA_PREFIX.
# --stage-dir  Native bundle directory the shim is copied into. Defaults to
#              pdal-ffm-natives/src/main/resources/META-INF/pdal-native/<classifier>.
#
# The script is a no-op on hosts that cannot build the requested classifier
# (no cross compilation). This keeps staging scripts callable for other
# platforms, e.g. when inspecting foreign bundles locally.
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <classifier> [--pdal-root <dir>] [--stage-dir <dir>]" >&2
  exit 1
fi

CLASSIFIER="$1"
shift

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_FILE="$ROOT_DIR/native/pdal-ffi/pdal_ffi.cpp"
INCLUDE_FILE="$ROOT_DIR/native/pdal-ffi/pdal_ffi.h"
DEFAULT_STAGE_DIR="$ROOT_DIR/pdal-ffm-natives/src/main/resources/META-INF/pdal-native/$CLASSIFIER"

PDAL_ROOT="${CONDA_PREFIX:-}"
STAGE_DIR="$DEFAULT_STAGE_DIR"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pdal-root)
      PDAL_ROOT="$2"
      shift 2
      ;;
    --stage-dir)
      STAGE_DIR="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

host_os() {
  case "$(uname -s)" in
    Darwin) echo "osx" ;;
    Linux) echo "linux" ;;
    MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
    *) echo "unsupported" ;;
  esac
}

host_arch() {
  case "$(uname -m)" in
    arm64|aarch64) echo "aarch64" ;;
    x86_64|amd64) echo "x86_64" ;;
    *) echo "unsupported" ;;
  esac
}

HOST_CLASSIFIER="$(host_os)-$(host_arch)"

if [[ "$CLASSIFIER" != "$HOST_CLASSIFIER" ]]; then
  echo "Skipping FFI build for $CLASSIFIER: host is $HOST_CLASSIFIER (no cross compilation)." >&2
  exit 0
fi

# Resolve the payload root that contains include/, lib/ and (Windows) bin/.
resolve_payload_root() {
  local candidate="$1"
  if [[ -f "$candidate/include/pdal/pdal_export.hpp" ]]; then
    echo "$candidate"
    return 0
  fi
  if [[ -f "$candidate/Library/include/pdal/pdal_export.hpp" ]]; then
    echo "$candidate/Library"
    return 0
  fi
  return 1
}

if [[ -z "$PDAL_ROOT" ]]; then
  echo "No --pdal-root given and CONDA_PREFIX is unset. Activate the 'pdal' conda environment." >&2
  exit 1
fi

if ! PAYLOAD_ROOT="$(resolve_payload_root "$PDAL_ROOT")"; then
  echo "Could not locate PDAL headers below: $PDAL_ROOT" >&2
  exit 1
fi

INCLUDE_DIR="$PAYLOAD_ROOT/include"
LIB_DIR="$PAYLOAD_ROOT/lib"
mkdir -p "$STAGE_DIR"

case "$CLASSIFIER" in
  osx-*)
    OUTPUT="$STAGE_DIR/lib/libpdal_ffi.dylib"
    mkdir -p "$STAGE_DIR/lib"
    CXX="${CXX:-clang++}"
    echo "Building $OUTPUT with $CXX"
    "$CXX" -std=c++17 -O2 -fPIC -fvisibility=hidden -dynamiclib \
      -install_name "@rpath/libpdal_ffi.dylib" \
      -I "$INCLUDE_DIR" -I "$(dirname "$INCLUDE_FILE")" \
      "$SOURCE_FILE" \
      -L "$LIB_DIR" -lpdalcpp \
      -Wl,-rpath,@loader_path \
      -o "$OUTPUT"
    ;;
  linux-*)
    OUTPUT="$STAGE_DIR/lib/libpdal_ffi.so"
    mkdir -p "$STAGE_DIR/lib"
    CXX="${CXX:-c++}"
    echo "Building $OUTPUT with $CXX"
    "$CXX" -std=c++17 -O2 -fPIC -fvisibility=hidden -shared \
      -I "$INCLUDE_DIR" -I "$(dirname "$INCLUDE_FILE")" \
      "$SOURCE_FILE" \
      -L "$LIB_DIR" -lpdalcpp \
      -Wl,-rpath,'$ORIGIN' \
      -o "$OUTPUT"
    ;;
  windows-*)
    echo "On Windows, build the FFI shim with tools/ffi/build-ffi.ps1 instead." >&2
    exit 1
    ;;
  *)
    echo "Unsupported classifier: $CLASSIFIER" >&2
    exit 1
    ;;
esac

echo "FFI shim built: $OUTPUT"
