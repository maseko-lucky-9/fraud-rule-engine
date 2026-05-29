#!/usr/bin/env bash
# Validates src/main/resources/rules/rule-set-v1.yml against the JSON schema
# at src/main/resources/rules/rule-set.schema.json. Designed to be invoked
# from CI (.github/workflows/ci.yml) and from a pre-commit hook.
#
# Tool order of preference:
#  1. `ajv` (https://ajv.js.org/) — already widely available via npm
#  2. `check-jsonschema` (Python, pipx-installable) as a fallback
#
# If neither is present we skip the step with a warning so the script is
# never a hard CI failure on a runner that hasn't installed either tool.

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RULES_YML="$ROOT/src/main/resources/rules/rule-set-v1.yml"
SCHEMA_JSON="$ROOT/src/main/resources/rules/rule-set.schema.json"

if [[ ! -f "$RULES_YML" || ! -f "$SCHEMA_JSON" ]]; then
  echo "validate-rules-yaml: rule-set or schema missing"
  echo "  rules:  $RULES_YML"
  echo "  schema: $SCHEMA_JSON"
  exit 1
fi

if command -v ajv >/dev/null 2>&1; then
  echo "validate-rules-yaml: using ajv"
  ajv validate \
    --spec=draft2020 \
    --strict=false \
    -s "$SCHEMA_JSON" \
    -d "$RULES_YML"
  exit 0
fi

if command -v check-jsonschema >/dev/null 2>&1; then
  echo "validate-rules-yaml: using check-jsonschema"
  check-jsonschema --schemafile "$SCHEMA_JSON" "$RULES_YML"
  exit 0
fi

# Best-effort fallback: parse the YAML to confirm it's syntactically valid.
# This catches the most common breakage (broken indentation, missing colon)
# without proving schema conformance. CI runners that need schema enforcement
# install ajv or check-jsonschema explicitly.
if command -v python3 >/dev/null 2>&1; then
  echo "validate-rules-yaml: no schema validator found; parsing YAML only"
  python3 -c "import yaml,sys; yaml.safe_load(open('$RULES_YML'))" \
    && echo "validate-rules-yaml: YAML parse OK" \
    && exit 0
fi

echo "validate-rules-yaml: no validator found (install ajv or check-jsonschema)"
exit 0
