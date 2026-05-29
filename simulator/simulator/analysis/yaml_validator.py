"""Validate LLM-emitted draft YAML rule stubs against the engine predicate allowlist.

If the LLM produces a stub referencing an unknown predicate, the audit
downgrades the finding from "draft YAML rule stub (drop-in)" to
"suggested mitigation (requires new predicate <name>)". Either way the
finding is surfaced — we never silently drop a candidate rule.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Final

import yaml

DEFAULT_ALLOWLIST_PATH: Final[Path] = (
    Path(__file__).resolve().parent.parent / "scripts" / "predicates.allowlist.json"
)


@dataclass(slots=True, frozen=True)
class YamlValidationResult:
    drop_in_ready: bool
    unknown_predicates: tuple[str, ...]
    parse_error: str | None = None


class PredicateAllowlist:
    """In-memory allowlist; loaded once at audit time."""

    def __init__(self, predicates: set[str], combinators: set[str]) -> None:
        self._predicates = predicates
        self._combinators = combinators

    @classmethod
    def from_path(cls, path: Path = DEFAULT_ALLOWLIST_PATH) -> PredicateAllowlist:
        data = json.loads(path.read_text())
        return cls(
            predicates=set(data.get("predicates", [])),
            combinators=set(data.get("combinators", [])),
        )

    @property
    def predicates(self) -> set[str]:
        return self._predicates

    @property
    def combinators(self) -> set[str]:
        return self._combinators

    def validate(self, yaml_text: str) -> YamlValidationResult:
        """Parse + scan the stub for predicate keys outside the allowlist."""
        try:
            data = yaml.safe_load(yaml_text)
        except yaml.YAMLError as exc:
            return YamlValidationResult(
                drop_in_ready=False, unknown_predicates=(), parse_error=str(exc),
            )
        if not isinstance(data, dict):
            return YamlValidationResult(
                drop_in_ready=False, unknown_predicates=(),
                parse_error="rule stub must be a YAML mapping",
            )

        condition = data.get("condition")
        if condition is None:
            return YamlValidationResult(
                drop_in_ready=False, unknown_predicates=(),
                parse_error="rule stub missing top-level 'condition'",
            )

        unknown: set[str] = set()
        self._scan_condition(condition, unknown)
        return YamlValidationResult(
            drop_in_ready=not unknown,
            unknown_predicates=tuple(sorted(unknown)),
        )

    def _scan_condition(self, node: object, unknown: set[str]) -> None:
        """Walk a condition subtree, collecting unknown predicate names.

        Each node is either:
        - a combinator dict: `{all: [...]}` or `{any: [...]}` — recurse
          into every list child
        - a predicate dict: `{<predicate_name>: <args>}` — the key is the
          predicate; the args are opaque (don't recurse)

        Any key that is neither a known predicate nor a known combinator
        is reported as unknown.
        """
        if isinstance(node, list):
            for item in node:
                self._scan_condition(item, unknown)
            return
        if not isinstance(node, dict):
            return
        for key, value in node.items():
            if key in self._combinators:
                self._scan_condition(value, unknown)
            elif key in self._predicates:
                # Known predicate — its value is opaque args, don't walk.
                continue
            else:
                unknown.add(key)
