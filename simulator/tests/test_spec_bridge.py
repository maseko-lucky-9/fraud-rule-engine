"""Tests for the LLM-spec → FraudScenario bridge."""

from __future__ import annotations

from decimal import Decimal

from simulator.llm.scenario_generator import GeneratedScenarioSpec
from simulator.scenarios.spec_bridge import spec_to_fraud_scenario


def _spec(**overrides: object) -> GeneratedScenarioSpec:
    base = dict(
        id="s1", archetype="card_testing", expected_outcome="miss",
        account_count=3, tx_per_account=4,
        amount_low_zar=1.0, amount_high_zar=10.0,
        cadence_seconds=5, description="synthetic",
    )
    base.update(overrides)
    return GeneratedScenarioSpec(**base)  # type: ignore[arg-type]


def test_spec_bridge_materialises_all_steps() -> None:
    scenario = spec_to_fraud_scenario(_spec(), seed=42)
    assert scenario.id == "s1"
    assert scenario.archetype == "card_testing"
    assert scenario.expected_outcome == "miss"
    assert len(scenario.steps) == 12  # 3 accounts × 4 tx
    assert all(Decimal("1") <= s.tx.amount <= Decimal("10") for s in scenario.steps)


def test_spec_bridge_caps_volume() -> None:
    big = _spec(id="big", account_count=50, tx_per_account=100,
               amount_low_zar=1.0, amount_high_zar=2.0, cadence_seconds=0)
    scenario = spec_to_fraud_scenario(big, seed=1, max_steps=20)
    assert len(scenario.steps) == 20


def test_spec_bridge_is_deterministic() -> None:
    a = spec_to_fraud_scenario(_spec(), seed=7)
    b = spec_to_fraud_scenario(_spec(), seed=7)
    assert [str(s.tx.txId) for s in a.steps] == [str(s.tx.txId) for s in b.steps]
    assert [s.tx.amount for s in a.steps] == [s.tx.amount for s in b.steps]
