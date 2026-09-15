#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK_FILE="$ROOT_DIR/tools/natives/binaries.lock"
TMP_DIR="$ROOT_DIR/tmp"
CLASSIFIERS="linux-x86_64 linux-aarch64 osx-x86_64 osx-aarch64 windows-x86_64"

mkdir -p "$TMP_DIR"

if grep -q 'REPLACE_WITH_SHA256' "$LOCK_FILE"; then
  echo "binaries.lock still contains REPLACE_WITH_SHA256 placeholders" >&2
  exit 1
fi

for classifier in $CLASSIFIERS; do
  indices_file="$(mktemp "$TMP_DIR/lock-verify-${classifier}.XXXXXX")"

  awk -F= -v prefix="platform.${classifier}.extra_" '
    index($1, prefix) == 1 {
      key = substr($1, length(prefix) + 1)
      split(key, parts, "_")
      idx = parts[length(parts)]
      if (idx ~ /^[0-9]+$/) {
        print idx
      }
    }
  ' "$LOCK_FILE" | sort -n -u > "$indices_file"

  if [[ ! -s "$indices_file" ]]; then
    rm -f "$indices_file"
    continue
  fi

  expected=1
  while IFS= read -r idx; do
    if [[ "$idx" -ne "$expected" ]]; then
      echo "Gap in extra_* indices for $classifier: expected $expected but found $idx" >&2
      rm -f "$indices_file"
      exit 1
    fi

    for key_type in url sha256 archive strip_prefix; do
      if ! grep -q "^platform.${classifier}.extra_${key_type}_${idx}=" "$LOCK_FILE"; then
        echo "Missing extra_${key_type}_${idx} for $classifier" >&2
        rm -f "$indices_file"
        exit 1
      fi
    done

    expected=$((expected + 1))
  done < "$indices_file"

  rm -f "$indices_file"
done

if [[ "${PDAL_FFM_VERIFY_RESOLVER:-false}" == "true" ]]; then
  "$ROOT_DIR/tools/natives/refresh-lock-closure.sh" --check
fi

echo "binaries.lock verification passed."
