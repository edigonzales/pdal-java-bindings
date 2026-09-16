#!/usr/bin/env bash
#
# Stages the pinned PDAL native runtime for one classifier:
#   1. downloads and verifies the root package + resolver-managed closure
#   2. extracts lib/bin/share payloads into pdal-ffm-natives/src/main/resources
#   3. builds the minimal C ABI shim (tools/ffi/build-ffi.sh)
#   4. prunes non-runtime payload and relocates all binaries for the bundle
#   5. writes manifest.json
#
# Prerequisites: curl, cph (conda-package-handling) or python3 with
# conda_package_handling, plus the platform toolchain for the shim build.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <classifier>" >&2
  exit 1
fi

CLASSIFIER="$1"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK_FILE="$ROOT_DIR/tools/natives/binaries.lock"
TARGET_DIR="$ROOT_DIR/pdal-ffm-natives/src/main/resources/META-INF/pdal-native/$CLASSIFIER"
TMP_DIR="$ROOT_DIR/tmp/natives-$CLASSIFIER"

read_prop() {
  local key="$1"
  local line
  line="$(awk -F= -v wanted="$key" '$1 == wanted { sub(/^[^=]*=/, "", $0); print $0; exit }' "$LOCK_FILE")"
  if [[ -z "$line" ]]; then
    echo ""
  else
    echo "$line"
  fi
}

require_prop() {
  local key="$1"
  local value
  value="$(read_prop "$key")"
  if [[ -z "$value" ]]; then
    echo "Missing key in lock file: $key" >&2
    exit 1
  fi
  echo "$value"
}

normalize_url() {
  local url="$1"
  if [[ "$url" == //* ]]; then
    echo "https:$url"
  else
    echo "$url"
  fi
}

archive_suffix() {
  local archive_type="$1"
  case "$archive_type" in
    tar.gz)
      echo ".tar.gz"
      ;;
    zip)
      echo ".zip"
      ;;
    conda)
      echo ".conda"
      ;;
    *)
      echo ""
      ;;
  esac
}

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
      echo "Unsupported classifier OS family: $CLASSIFIER" >&2
      exit 1
      ;;
  esac
}

host_classifier() {
  local os arch
  case "$(uname -s)" in
    Darwin)
      os="osx"
      ;;
    Linux)
      os="linux"
      ;;
    MINGW*|MSYS*|CYGWIN*)
      os="windows"
      ;;
    *)
      echo "unsupported"
      return
      ;;
  esac

  case "$(uname -m)" in
    arm64|aarch64)
      arch="aarch64"
      ;;
    x86_64|amd64)
      arch="x86_64"
      ;;
    *)
      arch="unsupported"
      ;;
  esac

  echo "$os-$arch"
}

to_windows_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    echo "$1"
  fi
}

sha256_file() {
  local file="$1"

  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
    return
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
    return
  fi

  if command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$file" | awk '{print $NF}'
    return
  fi

  if command -v python >/dev/null 2>&1; then
    python - "$file" <<'PY'
import hashlib
import sys

h = hashlib.sha256()
with open(sys.argv[1], "rb") as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b""):
        h.update(chunk)
print(h.hexdigest())
PY
    return
  fi

  echo "No SHA-256 tool available (expected shasum, sha256sum, openssl, or python)." >&2
  exit 1
}

extract_archive() {
  local archive_file="$1"
  local archive_type="$2"
  local extract_dir="$3"

  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"

  case "$archive_type" in
    tar.gz)
      tar -xzf "$archive_file" -C "$extract_dir"
      ;;
    zip)
      unzip -q "$archive_file" -d "$extract_dir"
      ;;
    conda)
      if command -v cph >/dev/null 2>&1; then
        cph extract --dest "$extract_dir" "$archive_file" >/dev/null
      elif command -v python >/dev/null 2>&1; then
        python - "$archive_file" "$extract_dir" <<'PY'
import sys

archive = sys.argv[1]
dest = sys.argv[2]

try:
    import conda_package_handling.api as cph_api
except Exception as exc:
    raise SystemExit(f"Missing required tool: cph and Python module 'conda_package_handling' unavailable ({exc})")

try:
    cph_api.extract(archive, dest_dir=dest)
except TypeError:
    # Backward compatibility for older conda-package-handling signatures.
    cph_api.extract(archive, dest)
PY
      else
        echo "Missing required tool: cph (conda-package-handling CLI) and python fallback unavailable." >&2
        exit 1
      fi
      ;;
    *)
      echo "Unsupported archive type: $archive_type" >&2
      exit 1
      ;;
  esac
}

copy_sections() {
  local payload_dir="$1"
  local package_name="$2"
  local allow_empty="$3"
  local copied_any=false

  for root in "$payload_dir" "$payload_dir/Library"; do
    if [[ ! -d "$root" ]]; then
      continue
    fi

    for section in lib bin share; do
      if [[ -d "$root/$section" ]]; then
        mkdir -p "$TARGET_DIR/$section"
        cp -R "$root/$section/." "$TARGET_DIR/$section/"
        copied_any=true
      fi
    done

    if [[ "$OS_FAMILY" != "windows" && -f "$root/ssl/cacert.pem" ]]; then
      mkdir -p "$TARGET_DIR/ssl"
      cp "$root/ssl/cacert.pem" "$TARGET_DIR/ssl/cacert.pem"
      copied_any=true
    fi
  done

  if [[ "$copied_any" != "true" ]]; then
    if [[ "$allow_empty" == "true" ]]; then
      echo "Skipping $package_name: no lib/bin/share sections in payload ($payload_dir)" >&2
      return 1
    fi
    echo "Package payload did not contain lib/bin/share sections: $payload_dir ($package_name)" >&2
    exit 1
  fi

  return 0
}

normalize_windows_runtime_bins() {
  mkdir -p "$TARGET_DIR/bin"

  if [[ -d "$TARGET_DIR/lib" ]]; then
    while IFS= read -r dll_path; do
      local dll_name
      dll_name="$(basename "$dll_path")"
      local target_path="$TARGET_DIR/bin/$dll_name"
      if [[ ! -f "$target_path" && ! -L "$target_path" ]]; then
        cp "$dll_path" "$target_path"
      fi
    done < <(find "$TARGET_DIR/lib" -type f -iname '*.dll' -print)
  fi
}

build_ffi_shim() {
  local main_payload="$TMP_DIR/extracted-main"

  if [[ "$CLASSIFIER" != "$(host_classifier)" ]]; then
    if [[ "${PDAL_FFM_ALLOW_FOREIGN_STAGE:-false}" != "true" ]]; then
      echo "Cannot stage $CLASSIFIER on host $(host_classifier): the C ABI shim needs a native toolchain." >&2
      echo "Run staging on a matching host or set PDAL_FFM_ALLOW_FOREIGN_STAGE=true for inspection only." >&2
      exit 1
    fi
    echo "Skipping FFI shim build for foreign classifier $CLASSIFIER (PDAL_FFM_ALLOW_FOREIGN_STAGE=true)." >&2
    return
  fi

  if [[ "$OS_FAMILY" == "windows" ]]; then
    powershell.exe -NoProfile -ExecutionPolicy Bypass \
      -File "$(to_windows_path "$ROOT_DIR/tools/ffi/build-ffi.ps1")" \
      -Classifier "$CLASSIFIER" \
      -PdalRoot "$(to_windows_path "$main_payload")" \
      -StageDir "$(to_windows_path "$TARGET_DIR")"
  else
    "$ROOT_DIR/tools/ffi/build-ffi.sh" "$CLASSIFIER" \
      --pdal-root "$main_payload" \
      --stage-dir "$TARGET_DIR"
  fi
}

ffi_library_path() {
  case "$OS_FAMILY" in
    linux)
      echo "lib/libpdal_ffi.so"
      ;;
    osx)
      echo "lib/libpdal_ffi.dylib"
      ;;
    windows)
      echo "bin/pdal_ffi.dll"
      ;;
  esac
}

prune_runtime_payload() {
  local os_family="$1"

  rm -rf \
    "$TARGET_DIR/lib/cmake" \
    "$TARGET_DIR/lib/pkgconfig" \
    "$TARGET_DIR/share/doc" \
    "$TARGET_DIR/share/man" \
    "$TARGET_DIR/share/bash-completion"

  if [[ -d "$TARGET_DIR/share" ]]; then
    find "$TARGET_DIR/share" -mindepth 1 -maxdepth 1 \
      ! -name gdal \
      ! -name proj \
      -exec rm -rf {} +
  fi

  if [[ "$os_family" == "windows" ]]; then
    rm -rf "$TARGET_DIR/lib"
    if [[ -d "$TARGET_DIR/bin" ]]; then
      find "$TARGET_DIR/bin" -mindepth 1 -maxdepth 1 \
        -type d \
        -exec rm -rf {} +

      find "$TARGET_DIR/bin" -mindepth 1 -maxdepth 1 \
        -type f \
        ! -iname '*.dll' \
        -exec rm -f {} +

      find "$TARGET_DIR/bin" -mindepth 1 -maxdepth 1 \
        -type l \
        ! -iname '*.dll' \
        -exec rm -f {} +
    fi
  else
    rm -rf "$TARGET_DIR/bin"

    if [[ -d "$TARGET_DIR/lib" ]]; then
      find "$TARGET_DIR/lib" -type f \
        ! -name '*.so' \
        ! -name '*.so.*' \
        ! -name '*.dylib' \
        ! -name '*.dylib.*' \
        -exec rm -f {} +

      find "$TARGET_DIR/lib" -type l \
        ! -name '*.so' \
        ! -name '*.so.*' \
        ! -name '*.dylib' \
        ! -name '*.dylib.*' \
        -exec rm -f {} +
    fi
  fi

  find "$TARGET_DIR" -type d -empty -delete
}

validate_staged_payload() {
  local os_family="$1"
  local entry_library="$2"
  local preload_libraries="$3"
  local ffi_library="$4"

  print_windows_dll_diagnostics() {
    local missing_kind="$1"
    local expected_path="$2"

    echo "Expected runtime path from binaries.lock ($missing_kind): $expected_path" >&2
    if [[ -d "$TARGET_DIR/bin" ]]; then
      echo "Available staged DLLs under $TARGET_DIR/bin:" >&2
      find "$TARGET_DIR/bin" -maxdepth 2 -type f -iname '*.dll' -print \
        | sed "s#^$TARGET_DIR/##" \
        | sort >&2
    else
      echo "Staged runtime directory missing: $TARGET_DIR/bin" >&2
    fi

    if [[ "$missing_kind" == "entry_library" ]]; then
      echo "If names drifted upstream, update platform.windows-x86_64.entry_library in tools/natives/binaries.lock." >&2
    else
      echo "If names drifted upstream, update platform.windows-x86_64.preload_libraries in tools/natives/binaries.lock." >&2
    fi
  }

  local entry_path="$TARGET_DIR/$entry_library"
  if [[ ! -f "$entry_path" && ! -L "$entry_path" ]]; then
    if [[ "$os_family" == "windows" ]]; then
      print_windows_dll_diagnostics "entry_library" "$entry_library"
    fi
    echo "Missing entry library after staging: $entry_library" >&2
    exit 1
  fi

  if [[ -n "$preload_libraries" ]]; then
    IFS=',' read -r -a preload_items <<< "$preload_libraries"
    for preload in "${preload_items[@]}"; do
      preload="$(echo "$preload" | sed 's/^ *//;s/ *$//')"
      if [[ -z "$preload" ]]; then
        continue
      fi
      local preload_path="$TARGET_DIR/$preload"
      if [[ ! -f "$preload_path" && ! -L "$preload_path" ]]; then
        if [[ "$os_family" == "windows" ]]; then
          print_windows_dll_diagnostics "preload_libraries" "$preload"
        fi
        echo "Missing preload library after staging: $preload" >&2
        exit 1
      fi
    done
  fi

  if [[ ! -f "$TARGET_DIR/$ffi_library" ]]; then
    echo "Missing C ABI shim after staging: $ffi_library" >&2
    exit 1
  fi

  if [[ ! -d "$TARGET_DIR/share/gdal" ]]; then
    echo "Missing share/gdal in staged bundle for $CLASSIFIER" >&2
    exit 1
  fi
  if [[ ! -d "$TARGET_DIR/share/proj" ]]; then
    echo "Missing share/proj in staged bundle for $CLASSIFIER" >&2
    exit 1
  fi

  if [[ "$os_family" != "windows" ]]; then
    local has_libcurl=false
    if [[ -d "$TARGET_DIR/lib" ]] && find "$TARGET_DIR/lib" -maxdepth 1 -type f \
      \( -name 'libcurl.so*' -o -name 'libcurl*.dylib*' \) -print -quit | grep -q .; then
      has_libcurl=true
    fi

    if [[ "$has_libcurl" == "true" && ! -f "$TARGET_DIR/ssl/cacert.pem" ]]; then
      echo "Missing ssl/cacert.pem in staged Unix bundle for $CLASSIFIER with libcurl runtime" >&2
      exit 1
    fi
  fi

  if [[ "$os_family" != "windows" && -d "$TARGET_DIR/bin" ]]; then
    if find "$TARGET_DIR/bin" -mindepth 1 -print -quit | grep -q .; then
      echo "Non-empty bin directory is not allowed for $CLASSIFIER runtime bundle" >&2
      exit 1
    fi
  fi
}

stage_package() {
  local name="$1"
  local url="$2"
  local sha256="$3"
  local archive_type="$4"
  local strip_prefix="$5"
  local allow_empty_payload="${6:-false}"

  if [[ "$sha256" == REPLACE_WITH_SHA256 ]]; then
    echo "Refusing to continue: SHA256 placeholder found for $CLASSIFIER ($name)" >&2
    exit 1
  fi

  local normalized_url
  normalized_url="$(normalize_url "$url")"

  local suffix
  suffix="$(archive_suffix "$archive_type")"
  if [[ -z "$suffix" ]]; then
    echo "Unsupported archive type: $archive_type" >&2
    exit 1
  fi

  local url_path="${normalized_url%%\?*}"
  local url_basename="${url_path##*/}"
  local archive_file
  if [[ -n "$url_basename" && "$url_basename" != "$url_path" ]]; then
    archive_file="$TMP_DIR/$url_basename"
  else
    archive_file="$TMP_DIR/${name}${suffix}"
  fi

  case "$archive_file" in
    *"$suffix") ;;
    *) archive_file="${archive_file}${suffix}" ;;
  esac

  local extract_dir="$TMP_DIR/extracted-${name}"
  local actual_sha
  local need_download=true
  if [[ -f "$archive_file" ]]; then
    actual_sha="$(sha256_file "$archive_file")"
    if [[ "$actual_sha" == "$sha256" ]]; then
      echo "Using cached archive $archive_file"
      need_download=false
    else
      echo "Cached archive has wrong SHA, re-downloading: $archive_file"
      rm -f "$archive_file"
    fi
  fi

  if [[ "$need_download" == "true" ]]; then
    echo "Downloading $normalized_url"
    curl --retry 5 --retry-delay 2 --retry-all-errors -fL "$normalized_url" -o "$archive_file"
  fi

  actual_sha="$(sha256_file "$archive_file")"
  if [[ "$actual_sha" != "$sha256" ]]; then
    echo "SHA256 mismatch for $CLASSIFIER ($name)" >&2
    echo "expected: $sha256" >&2
    echo "actual:   $actual_sha" >&2
    exit 1
  fi

  extract_archive "$archive_file" "$archive_type" "$extract_dir"

  local payload_dir="$extract_dir"
  if [[ "$strip_prefix" != "." ]]; then
    payload_dir="$extract_dir/$strip_prefix"
  fi

  if [[ ! -d "$payload_dir" ]]; then
    echo "Payload directory not found: $payload_dir" >&2
    exit 1
  fi

  copy_sections "$payload_dir" "$name" "$allow_empty_payload" || true
}

URL="$(require_prop "platform.$CLASSIFIER.url")"
SHA256="$(require_prop "platform.$CLASSIFIER.sha256")"
ARCHIVE_TYPE="$(require_prop "platform.$CLASSIFIER.archive")"
STRIP_PREFIX="$(require_prop "platform.$CLASSIFIER.strip_prefix")"
ENTRY_LIBRARY="$(require_prop "platform.$CLASSIFIER.entry_library")"
PRELOAD_LIBRARIES="$(require_prop "platform.$CLASSIFIER.preload_libraries")"
PLUGIN_PATH="$(require_prop "platform.$CLASSIFIER.plugin_path")"
PDAL_VERSION="$(require_prop "pdal.version")"
OS_FAMILY="$(classifier_os)"
FFI_LIBRARY="$(ffi_library_path)"

mkdir -p "$TMP_DIR"
mkdir -p "$TARGET_DIR"

find "$TARGET_DIR" -mindepth 1 \
  ! -name manifest.json \
  ! -name README.txt \
  -exec rm -rf {} +

mkdir -p "$TARGET_DIR/lib" "$TARGET_DIR/bin" "$TARGET_DIR/share"

stage_package "main" "$URL" "$SHA256" "$ARCHIVE_TYPE" "$STRIP_PREFIX" "false"

extra_index=1
while true; do
  extra_url="$(read_prop "platform.$CLASSIFIER.extra_url_$extra_index")"
  if [[ -z "$extra_url" ]]; then
    break
  fi

  extra_sha="$(require_prop "platform.$CLASSIFIER.extra_sha256_$extra_index")"
  extra_archive="$(require_prop "platform.$CLASSIFIER.extra_archive_$extra_index")"
  extra_strip="$(require_prop "platform.$CLASSIFIER.extra_strip_prefix_$extra_index")"

  stage_package "extra-$extra_index" "$extra_url" "$extra_sha" "$extra_archive" "$extra_strip" "true"

  extra_index=$((extra_index + 1))
done

if [[ "$OS_FAMILY" == "windows" ]]; then
  normalize_windows_runtime_bins
fi

build_ffi_shim

# Rewrite conda build paths (for example libcurl's baked-in CA file) before the
# binaries are signed by the relocation step.
"$ROOT_DIR/tools/natives/patch-embedded-paths.sh" "$CLASSIFIER" "$TARGET_DIR"

prune_runtime_payload "$OS_FAMILY"
"$ROOT_DIR/tools/natives/relocate-runtime-deps.sh" "$CLASSIFIER" "$TARGET_DIR"

mkdir -p "$TARGET_DIR/share/gdal" "$TARGET_DIR/share/proj"

PRELOAD_JSON=""
if [[ -n "$PRELOAD_LIBRARIES" ]]; then
  IFS=',' read -r -a PRELOAD_ITEMS <<< "$PRELOAD_LIBRARIES"
  for item in "${PRELOAD_ITEMS[@]}"; do
    item_trimmed="$(echo "$item" | sed 's/^ *//;s/ *$//')"
    if [[ -z "$item_trimmed" ]]; then
      continue
    fi

    if [[ -n "$PRELOAD_JSON" ]]; then
      PRELOAD_JSON+=$',\n    '
    fi
    PRELOAD_JSON+="\"$item_trimmed\""
  done
fi

CA_BUNDLE_JSON=""
if [[ "$OS_FAMILY" != "windows" && -f "$TARGET_DIR/ssl/cacert.pem" ]]; then
  CA_BUNDLE_JSON=$',\n  "caBundlePath": "ssl/cacert.pem"'
fi

cat > "$TARGET_DIR/manifest.json" <<MANIFEST
{
  "bundleVersion": "$PDAL_VERSION",
  "ffiLibrary": "$FFI_LIBRARY",
  "entryLibrary": "$ENTRY_LIBRARY",
  "preloadLibraries": [
    $PRELOAD_JSON
  ],
  "gdalDataPath": "share/gdal",
  "projDataPath": "share/proj",
  "pluginPath": "$PLUGIN_PATH"$CA_BUNDLE_JSON
}
MANIFEST

validate_staged_payload "$OS_FAMILY" "$ENTRY_LIBRARY" "$PRELOAD_LIBRARIES" "$FFI_LIBRARY"

echo "Staged native bundle for $CLASSIFIER in $TARGET_DIR"
