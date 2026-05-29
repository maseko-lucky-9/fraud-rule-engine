#!/usr/bin/env bash
# build-pdf.sh — generate the one-page architecture PDF for the submission.
#
# Plan §7 Day 7. Uses pandoc/latex Docker image; targets:
#   docs/interview/00-Overview/elevator-pitch.md  →  docs/ARCHITECTURE-SUMMARY.pdf
#
# Substitutes a few Unicode characters that the default LaTeX Computer Modern
# font cannot render (e.g. ↛ used in "api ↛ persistence"). The markdown source
# stays unchanged — substitutions happen on a temp copy.
#
# Apple Silicon: pandoc/latex publishes amd64 only — we pass --platform linux/amd64
# and Docker Desktop uses Rosetta. ~10 s overhead on first run, fine for a one-shot.

set -euo pipefail

cd "$(dirname "$0")/.."

INPUT="docs/interview/00-Overview/elevator-pitch.md"
OUTPUT="docs/ARCHITECTURE-SUMMARY.pdf"

if [[ ! -f "$INPUT" ]]; then
  echo "ERROR: missing input $INPUT" >&2
  exit 1
fi

TMP_MD=$(mktemp -t fraud-pdf.XXXXXX.md)
trap 'rm -f "$TMP_MD"' EXIT

# Character substitutions for the default LaTeX fonts.
# ↛ → 'does not depend on'  (used in "api ↛ persistence" ArchUnit rule prose)
sed 's/↛/does not depend on/g' "$INPUT" > "$TMP_MD"

PLATFORM_FLAG=""
if [[ "$(uname -m)" == "arm64" ]] || [[ "$(uname -m)" == "aarch64" ]]; then
  PLATFORM_FLAG="--platform linux/amd64"
fi

# shellcheck disable=SC2086
docker run --rm $PLATFORM_FLAG \
  -v "$PWD:/data" -v "$TMP_MD:/tmp/input.md:ro" -w /data \
  pandoc/latex:3.5 \
  /tmp/input.md \
  -o "$OUTPUT" \
  --pdf-engine=xelatex \
  -V geometry:margin=2cm \
  -V fontsize=11pt \
  --metadata title="Fraud Rule Engine — Architecture Summary" \
  --metadata subtitle="Architecture Summary" \
  --metadata date="2026-05-20"

if [[ ! -f "$OUTPUT" ]]; then
  echo "ERROR: pandoc completed but $OUTPUT missing" >&2
  exit 1
fi

SIZE_BYTES=$(stat -f%z "$OUTPUT" 2>/dev/null || stat -c%s "$OUTPUT")
SIZE_KB=$((SIZE_BYTES / 1024))
echo "Built: $OUTPUT (${SIZE_KB} KB)"
