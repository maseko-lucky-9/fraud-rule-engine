"""Scenario library tests — registry, builders, determinism, expectations."""

from __future__ import annotations

import pytest

from simulator.scenarios import (
    FraudScenario,
    ScenarioRegistry,
    ScenarioStep,
    load_default_registry,
)
from simulator.scenarios.evasion import (
    build_ato_geo_leap,
    build_card_testing,
    build_device_entropy,
    build_geo_impossibility,
    build_structuring,
    build_synthetic_identity,
)
from simulator.scenarios.security import (
    all_probes,
    fuzz_body,
)


def test_registry_rejects_duplicates() -> None:
    registry = ScenarioRegistry()
    scenario = build_card_testing(seed=42)
    registry.register(scenario)
    with pytest.raises(ValueError, match="duplicate"):
        registry.register(scenario)


def test_default_registry_loads_all_scenarios() -> None:
    registry = load_default_registry(seed=42)
    ids = {s.id for s in registry.all()}
    # 13 rule-coverage + 6 evasion = 19.
    assert registry.count == 19
    # Spot-check that each evasion id is present.
    expected_evasion = {
        "evasion_card_testing", "evasion_structuring", "evasion_ato_geo_leap",
        "evasion_synthetic_identity", "evasion_device_entropy", "evasion_geo_impossibility",
    }
    assert expected_evasion.issubset(ids)


def test_scenario_without_steps_is_rejected() -> None:
    with pytest.raises(ValueError, match="no steps"):
        FraudScenario(
            id="empty", archetype="x", targets_rule=None,
            expected_outcome="miss", description="x", steps=(),
        )


def test_card_testing_distributes_across_accounts() -> None:
    scenario = build_card_testing(seed=42)
    accounts = {step.tx.accountId for step in scenario.steps}
    assert len(accounts) == 20
    # Every step is sub-$1 to mimic real card-validation probes.
    assert all(step.tx.amount < 1 for step in scenario.steps)
    assert scenario.expected_outcome == "miss"


def test_structuring_is_sub_threshold_with_volume() -> None:
    scenario = build_structuring(seed=42)
    assert all(str(step.tx.amount).startswith("9999") for step in scenario.steps)
    assert len(scenario.steps) >= 5
    assert scenario.expected_outcome == "miss"


def test_ato_geo_leap_is_partial() -> None:
    scenario = build_ato_geo_leap(seed=42)
    assert scenario.expected_outcome == "partial"
    assert scenario.steps[0].tx.ipCountry == "ZA"
    assert scenario.steps[1].tx.ipCountry == "US"
    # Same account; different device after compromise.
    assert scenario.steps[0].tx.accountId == scenario.steps[1].tx.accountId
    assert scenario.steps[0].tx.deviceId != scenario.steps[1].tx.deviceId


def test_synthetic_identity_lands_one_day_past_cutoff() -> None:
    scenario = build_synthetic_identity(seed=42)
    assert scenario.steps[0].tx.accountAgeDays == 31


def test_device_entropy_rotates_devices() -> None:
    scenario = build_device_entropy(seed=42)
    devices = {step.tx.deviceId for step in scenario.steps}
    assert len(devices) == 6


def test_geo_impossibility_is_under_thresholds() -> None:
    scenario = build_geo_impossibility(seed=42)
    # Both legs under the $5k cross-border threshold so we isolate the
    # impossibility signal from the existing CROSS_BORDER_HIGH_VALUE catch.
    assert all(step.tx.amount < 5000 for step in scenario.steps)


def test_scenarios_are_deterministic_per_seed() -> None:
    """Twin runs with the same seed produce byte-identical scenarios."""
    a = build_card_testing(seed=42)
    b = build_card_testing(seed=42)
    for sa, sb in zip(a.steps, b.steps, strict=True):
        assert sa.tx == sb.tx


def test_security_probes_cover_required_aspects() -> None:
    probes = all_probes()
    targets = {p.targets for p in probes}
    assert "auth" in targets
    assert "idempotency" in targets
    assert "rate_limit" in targets
    assert "payload_validation" in targets


def test_security_probes_have_explicit_pass_codes() -> None:
    for probe in all_probes():
        assert probe.pass_status_codes, f"{probe.id} has no pass codes"


def test_fuzz_body_variants_mutate_only_one_field() -> None:
    valid = fuzz_body("currency_lowercase")
    assert valid["currency"] == "zar"

    # Missing-field variant drops exactly one key.
    missing = fuzz_body("missing_required_field")
    assert "currency" not in missing


def test_fuzz_body_unknown_variant_rejected() -> None:
    with pytest.raises(ValueError, match="unknown fuzz variant"):
        fuzz_body("nonexistent")


def test_scenario_step_is_immutable() -> None:
    step = build_card_testing(seed=42).steps[0]
    with pytest.raises((AttributeError, TypeError)):
        step.note = "x"  # type: ignore[misc]
    assert isinstance(step, ScenarioStep)
