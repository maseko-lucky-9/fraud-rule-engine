#!/usr/bin/env bash
# package.sh — build the submission ZIP.
#
# Plan §7 Day 7 + §15 #8. Excludes build artefacts, IDE files, secrets,
# Docker volumes, evidence captures (local only), and tool artefacts.
# Target size budget: ≤ 50 MB.
#
# Usage:
#   scripts/package.sh [version-tag]
#     version-tag — optional; defaults to current git describe (v1.0.0-submission).
#
# Output:
#   dist/fraud-rule-engine-<version>.zip
#   dist/fraud-rule-engine-<version>.sha256

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
VERSION="${1:-$(git describe --tags --always 2>/dev/null || echo "untagged-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)")}"

OUT_DIR="$ROOT/dist"
ARCHIVE_NAME="fraud-rule-engine-${VERSION}.zip"
ARCHIVE_PATH="$OUT_DIR/$ARCHIVE_NAME"

mkdir -p "$OUT_DIR"
rm -f "$ARCHIVE_PATH" "$ARCHIVE_PATH.sha256"

echo "Packaging $ARCHIVE_NAME …"

# Exclusion list — matches plan §7 Day 7. .git/ stays in the ZIP so the
# reviewer can read the 7-day commit history (each day's commit message is
# itself part of the submission narrative).
zip -r "$ARCHIVE_PATH" . \
  -x 'target/*' \
  -x '*/target/*' \
  -x 'dist/*' \
  -x 'postgres_data/*' \
  -x 'redis_data/*' \
  -x 'redpanda_data/*' \
  -x '.idea/*' \
  -x '.vscode/*' \
  -x '.DS_Store' \
  -x '**/.DS_Store' \
  -x '*.log' \
  -x '.env' \
  -x 'evidence/*' \
  -x 'agentdb.rvf*' \
  -x 'ruvector.db' \
  -x 'graphify-out/*' \
  -x '*.iml' \
  -x '*.iws' \
  -x '*.ipr' \
  > /dev/null

SIZE_BYTES=$(stat -f%z "$ARCHIVE_PATH" 2>/dev/null || stat -c%s "$ARCHIVE_PATH")
SIZE_MB=$((SIZE_BYTES / 1024 / 1024))

# Generate checksum (macOS uses shasum; Linux uses sha256sum).
if command -v shasum >/dev/null 2>&1; then
  (cd "$OUT_DIR" && shasum -a 256 "$ARCHIVE_NAME" > "$ARCHIVE_NAME.sha256")
else
  (cd "$OUT_DIR" && sha256sum "$ARCHIVE_NAME" > "$ARCHIVE_NAME.sha256")
fi

echo ""
echo "Built: $ARCHIVE_PATH"
echo "Size:  ${SIZE_MB} MB ($SIZE_BYTES bytes)"
echo "Hash:  $(cat "$ARCHIVE_PATH.sha256")"
echo ""

# Enforce size budget per plan §15 #8.
MAX_MB=50
if [[ $SIZE_MB -gt $MAX_MB ]]; then
  echo "ERROR: archive ${SIZE_MB} MB exceeds budget of ${MAX_MB} MB. Investigate before shipping." >&2
  exit 1
fi

echo "OK — under ${MAX_MB} MB budget."
