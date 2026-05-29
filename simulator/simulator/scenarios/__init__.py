"""Pen-test scenario library.

A scenario is a deterministic sequence of transactions plus an
``expected_outcome`` (``detected`` / ``partial`` / ``miss``). Walking the
scenario through the engine and comparing the actual decisions to the
expectation is what produces the audit's per-rule precision/recall numbers
and gap-discovery findings.

Scenarios live in three subpackages:

- :mod:`simulator.scenarios.rule_coverage` — positive + negative + boundary
  per existing engine rule. Acceptance: per-rule recall >= 0.95.
- :mod:`simulator.scenarios.evasion` — probes known engine gaps
  (card-testing, structuring, ATO geo-leap, synthetic identity, device
  entropy, geo-impossibility).
- :mod:`simulator.scenarios.security` — JWT tampering, idempotency
  collisions, rate-limit probes, payload fuzzing.

Every scenario emits :class:`ScenarioStep` instances, each carrying a
ready-to-submit :class:`Transaction` and the rule(s) it should (or should
not) trip.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Literal, Protocol

from simulator.transport.models import Transaction

ExpectedOutcome = Literal["detected", "partial", "miss"]


@dataclass(slots=True, frozen=True)
class ScenarioStep:
    """One transaction within a scenario plus its expected disposition."""

    tx: Transaction
    expected_rules: tuple[str, ...] = ()
    note: str | None = None


@dataclass(slots=True, frozen=True)
class FraudScenario:
    """A named, reproducible sequence of transactions."""

    id: str
    archetype: str
    targets_rule: str | None
    expected_outcome: ExpectedOutcome
    description: str
    steps: tuple[ScenarioStep, ...]
    citations: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:  # noqa: D401
        if not self.steps:
            raise ValueError(f"scenario {self.id} has no steps")


class ScenarioBuilder(Protocol):
    """Each scenario module exposes a builder of this shape."""

    def __call__(self, *, seed: int) -> FraudScenario: ...


@dataclass(slots=True)
class ScenarioRegistry:
    """In-memory catalogue keyed by scenario id."""

    _scenarios: dict[str, FraudScenario] = field(default_factory=dict)

    def register(self, scenario: FraudScenario) -> None:
        if scenario.id in self._scenarios:
            raise ValueError(f"duplicate scenario id: {scenario.id}")
        self._scenarios[scenario.id] = scenario

    def get(self, scenario_id: str) -> FraudScenario:
        return self._scenarios[scenario_id]

    def all(self) -> Iterable[FraudScenario]:
        return self._scenarios.values()

    @property
    def count(self) -> int:
        return len(self._scenarios)


def load_default_registry(*, seed: int) -> ScenarioRegistry:
    """Eagerly load every shipped scenario into a fresh registry."""
    from simulator.scenarios.evasion import (
        build_ato_geo_leap,
        build_card_testing,
        build_device_entropy,
        build_geo_impossibility,
        build_structuring,
        build_synthetic_identity,
    )
    from simulator.scenarios.rule_coverage import build_rule_coverage_scenarios

    registry = ScenarioRegistry()
    for s in build_rule_coverage_scenarios(seed=seed):
        registry.register(s)
    for builder in (
        build_card_testing,
        build_structuring,
        build_ato_geo_leap,
        build_synthetic_identity,
        build_device_entropy,
        build_geo_impossibility,
    ):
        registry.register(builder(seed=seed))
    return registry
