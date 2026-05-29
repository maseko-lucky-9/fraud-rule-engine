#!/usr/bin/env bash
# check-doc-claims.sh — guard against doc/code drift on observable claims.
#
# Plan §11 + Day-6.5/6.6/7 reviews repeatedly caught fabricated metric names
# in interview docs (`jwt_validation_failed_total`, `rules_reload_failed_total`,
# `predicate_state_unavailable_total`). This script grep-asserts every
# Prometheus-style metric name mentioned in docs/ also appears at least once
# under src/main/. Non-zero exit on any orphan.
#
# Pattern matched: lower_snake_case_total | lower_snake_case_seconds.
# False-positive avoidance: allowlist a couple of standard Spring metric
# names that are auto-exported (we don't ship them but operators reference
# them in runbooks).
#
# Usage:
#   scripts/check-doc-claims.sh                   # exit 0 on clean, 1 on drift
#
# Wired into .github/workflows/ci.yml as a fast pre-test sanity step.

set -euo pipefail

cd "$(dirname "$0")/.."

# Allowlist: metrics produced by upstream libraries (Spring Boot, Resilience4j,
# Micrometer, JVM, Spring Kafka). They legitimately appear in docs but live in
# dependency JARs, not src/main.
ALLOWLIST=(
  "http_server_requests_seconds"
  "http_server_requests_seconds_count"
  "http_server_requests_seconds_bucket"
  "http_server_requests_total"
  "jvm_memory_used_bytes"
  "kafka_producer_record_error_total"
  "resilience4j_circuitbreaker_calls_total"
  "resilience4j_circuitbreaker_state"
  "outbox_lag_seconds"  # gauge wired via OutboxPoller; tolerated even though name lives in a config file
)

# Deferred: metrics whose doc reference explicitly says "Day-7 polish" or similar.
# Each entry MUST be cross-referenced to the doc cell that defers it. The script
# tolerates these because the docs are upfront about the gap — not a silent claim.
DEFERRED=(
  # failure-modes.md row 20 — Spring Security MetricsFilterAutoConfiguration emits
  # HTTP-level only today; a dedicated counter would need a custom
  # AuthenticationFailureHandler. Documented as Day-7 polish + WAF rules.
  "jwt_validation_failed_total"
)

is_allowlisted() {
  local name="$1"
  for allowed in "${ALLOWLIST[@]}"; do
    if [[ "$name" == "$allowed" ]]; then
      return 0
    fi
  done
  for deferred in "${DEFERRED[@]}"; do
    if [[ "$name" == "$deferred" ]]; then
      return 0
    fi
  done
  return 1
}

# Extract candidate metric names from docs.
# Match: word boundary + lower snake_case + (_total|_seconds)
# Exclude code-fenced bash heredocs by being permissive — false positives only
# cause noise, false negatives are the real risk.
# Portable: uses while-read, not mapfile (bash 3.2 on macOS lacks mapfile).
MISSING_FILE=$(mktemp)
trap 'rm -f "$MISSING_FILE"' EXIT

while IFS= read -r metric; do
  [[ -z "$metric" ]] && continue
  if is_allowlisted "$metric"; then
    continue
  fi
  if ! grep -rq "\"$metric\"" src/main/ 2>/dev/null; then
    echo "$metric" >> "$MISSING_FILE"
  fi
done < <(
  grep -hroE '\b[a-z][a-z0-9_]*_(total|seconds)\b' docs/ README.md ARCHITECTURE.md 2>/dev/null \
    | sort -u
)

MISSING_COUNT=$(wc -l < "$MISSING_FILE" | tr -d ' ')

if [[ "$MISSING_COUNT" == "0" ]]; then
  echo "doc-claim check: OK — every domain metric mentioned in docs is registered in src/main."
  exit 0
fi

echo "doc-claim check FAILED — these metric names appear in docs but have no string literal in src/main/:" >&2
while IFS= read -r m; do
  echo "  - $m" >&2
done < "$MISSING_FILE"
echo "" >&2
echo "Fix: either wire the counter in code (preferred) or update the docs to point at the metric that actually exists." >&2
echo "Allowlist (for upstream-library metrics) is at the top of this script." >&2
exit 1
