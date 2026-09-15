#!/usr/bin/env bash
#
# Verifies that every native dependency of a staged PDAL bundle is either
# bundled itself or an allowed system library.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <classifier>" >&2
  exit 1
fi

CLASSIFIER="$1"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUNDLE_DIR="$ROOT_DIR/pdal-ffm-natives/src/main/resources/META-INF/pdal-native/$CLASSIFIER"
TMP_DIR="$ROOT_DIR/tmp/native-audit-$CLASSIFIER"
mkdir -p "$TMP_DIR"

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

OS_FAMILY="$(classifier_os)"

provided_libs_file="$TMP_DIR/provided-libs.txt"
missing_file="$TMP_DIR/missing.txt"
: > "$provided_libs_file"
: > "$missing_file"

collect_provided_libs() {
  case "$OS_FAMILY" in
    linux|osx)
      if [[ -d "$BUNDLE_DIR/lib" ]]; then
        find "$BUNDLE_DIR/lib" \( -type f -o -type l \) \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dylib.*' \) -print \
          | xargs -I{} basename "{}" \
          | sort -u > "$provided_libs_file"
      fi
      ;;
    windows)
      if [[ -d "$BUNDLE_DIR/bin" ]]; then
        find "$BUNDLE_DIR/bin" \( -type f -o -type l \) -iname '*.dll' -print \
          | xargs -I{} basename "{}" \
          | tr '[:lower:]' '[:upper:]' \
          | sort -u > "$provided_libs_file"
      fi
      ;;
  esac
}

is_provided() {
  local lib_name="$1"
  grep -Fxq "$lib_name" "$provided_libs_file"
}

is_allowed_system_linux() {
  local lib="$1"
  case "$lib" in
    libc.so.*|libm.so.*|libpthread.so.*|libdl.so.*|librt.so.*|libgcc_s.so.*|libstdc++.so.*|libutil.so.*|libcrypt.so.*|libresolv.so.*|libnsl.so.*|libanl.so.*|ld-linux*.so.*|ld-musl-*.so.*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
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

is_allowed_system_osx() {
  local dep="$1"
  [[ "$dep" == /usr/lib/* || "$dep" == /System/Library/* ]]
}

is_allowed_system_windows() {
  local lib="$1"
  case "$lib" in
    API-MS-WIN-*.DLL|EXT-MS-WIN-*.DLL|KERNEL32.DLL|USER32.DLL|ADVAPI32.DLL|SHELL32.DLL|WS2_32.DLL|WSOCK32.DLL|OLE32.DLL|OLEAUT32.DLL|RPCRT4.DLL|COMDLG32.DLL|COMCTL32.DLL|GDI32.DLL|CRYPT32.DLL|WLDAP32.DLL|SECHOST.DLL|SHLWAPI.DLL|VERSION.DLL|WINMM.DLL|MSIMG32.DLL|UXTHEME.DLL|BCRYPT.DLL|IPHLPAPI.DLL|DNSAPI.DLL|NTDLL.DLL|ODBC32.DLL|SECUR32.DLL|USERENV.DLL|D3D11.DLL|D3DCOMPILER_47.DLL|DXGI.DLL|MSVCP140.DLL|VCRUNTIME140.DLL|VCRUNTIME140_1.DLL|CONCRT140.DLL|MPR.DLL|NETAPI32.DLL|PSAPI.DLL|PDBCOPY.DLL|DBGHELP.DLL|WTSAPI32.DLL)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

record_missing() {
  local required_by="$1"
  local dep="$2"
  echo "$dep required by $required_by" >> "$missing_file"
}

audit_macos() {
  if ! command -v otool >/dev/null 2>&1; then
    echo "otool is required for macOS audit" >&2
    exit 1
  fi

  local binary
  while IFS= read -r binary; do
    while IFS= read -r dep; do
      dep="${dep%% (*}"
      dep="${dep#${dep%%[![:space:]]*}}"
      [[ -z "$dep" ]] && continue

      if is_allowed_system_osx "$dep"; then
        continue
      fi
      if [[ "$dep" == /* ]]; then
        record_missing "$binary" "non-relocatable install name: $dep"
        continue
      fi

      local dep_base
      dep_base="$(basename "$dep")"
      if is_provided "$dep_base"; then
        continue
      fi
      if [[ "$dep" == @rpath/* || "$dep" == @loader_path/* || "$dep" == @executable_path/* ]]; then
        record_missing "$binary" "$dep_base"
        continue
      fi

      record_missing "$binary" "$dep"
    done < <(otool -L "$binary" | tail -n +2)
  done < <(find "$BUNDLE_DIR/lib" \( -type f -o -type l \) \( -name '*.dylib' -o -name '*.dylib.*' \) -print)
}

audit_linux() {
  if command -v readelf >/dev/null 2>&1; then
    local binary
    while IFS= read -r binary; do
      while IFS= read -r dep; do
        local dep_name="$dep"
        if [[ "$dep_name" == */* ]]; then
          if is_allowed_system_linux_absolute "$dep_name"; then
            continue
          fi
          record_missing "$binary" "absolute DT_NEEDED dependency: $dep_name"
          continue
        fi
        if is_provided "$dep_name"; then
          continue
        fi
        if is_allowed_system_linux "$dep_name"; then
          continue
        fi
        record_missing "$binary" "$dep_name"
      done < <(readelf -d "$binary" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
    done < <(find "$BUNDLE_DIR/lib" \( -type f -o -type l \) \( -name '*.so' -o -name '*.so.*' \) -print)
    return
  fi

  if command -v objdump >/dev/null 2>&1; then
    local binary
    while IFS= read -r binary; do
      while IFS= read -r dep; do
        local dep_name
        dep_name="$(trim "$dep")"
        [[ -z "$dep_name" ]] && continue
        if is_provided "$dep_name"; then
          continue
        fi
        if is_allowed_system_linux "$dep_name"; then
          continue
        fi
        record_missing "$binary" "$dep_name"
      done < <(objdump -p "$binary" | sed -n 's/^ *NEEDED *//p')
    done < <(find "$BUNDLE_DIR/lib" \( -type f -o -type l \) \( -name '*.so' -o -name '*.so.*' \) -print)
    return
  fi

  echo "Neither readelf nor objdump is available for linux audit" >&2
  exit 1
}

trim() {
  local value="$1"
  value="${value#${value%%[![:space:]]*}}"
  value="${value%${value##*[![:space:]]}}"
  printf '%s' "$value"
}

audit_windows() {
  local dumpbin_bin=""
  if command -v dumpbin >/dev/null 2>&1; then
    dumpbin_bin="dumpbin"
  elif command -v llvm-objdump >/dev/null 2>&1; then
    dumpbin_bin="llvm-objdump"
  fi

  if [[ -z "$dumpbin_bin" ]]; then
    echo "Neither dumpbin nor llvm-objdump is available for windows audit" >&2
    exit 1
  fi

  local binary
  while IFS= read -r binary; do
    if [[ "$dumpbin_bin" == "dumpbin" ]]; then
      while IFS= read -r dep; do
        dep="$(trim "$dep")"
        [[ -z "$dep" ]] && continue
        dep="$(echo "$dep" | tr '[:lower:]' '[:upper:]')"

        if is_provided "$dep"; then
          continue
        fi
        if is_allowed_system_windows "$dep"; then
          continue
        fi
        record_missing "$binary" "$dep"
      done < <(dumpbin /DEPENDENTS "$binary" 2>/dev/null | sed -n 's/^ *\([A-Za-z0-9._-]*\.dll\)$/\1/ip')
    else
      while IFS= read -r dep; do
        dep="$(trim "$dep")"
        [[ -z "$dep" ]] && continue
        dep="$(echo "$dep" | tr '[:lower:]' '[:upper:]')"

        if is_provided "$dep"; then
          continue
        fi
        if is_allowed_system_windows "$dep"; then
          continue
        fi
        record_missing "$binary" "$dep"
      done < <(llvm-objdump -p "$binary" | sed -n 's/^ *DLL Name: *//p')
    fi
  done < <(find "$BUNDLE_DIR/bin" \( -type f -o -type l \) -iname '*.dll' -print)
}

collect_provided_libs

case "$OS_FAMILY" in
  osx)
    audit_macos
    ;;
  linux)
    audit_linux
    ;;
  windows)
    audit_windows
    ;;
esac

if [[ -s "$missing_file" ]]; then
  echo "Missing runtime native dependencies detected for $CLASSIFIER:" >&2
  sort -u "$missing_file" >&2
  echo >&2
  echo "Add matching conda packages to tools/natives/binaries.lock roots/closure and regenerate lock extras." >&2
  exit 1
fi

echo "Runtime dependency audit passed for $CLASSIFIER"
