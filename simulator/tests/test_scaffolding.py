"""Phase 1 smoke tests — verify the scaffolding is sane and parseable.

These tests run before any runtime code exists. They guard the two failure
modes that would otherwise surface only mid-run:

1. A typo in profile.default.yaml or scenarios.seed.yaml that breaks YAML parse.
2. A drift between the documented profile schema and what scenarios.seed.yaml
   actually contains.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

SIMULATOR_ROOT = Path(__file__).resolve().parent.parent
CONFIG_DIR = SIMULATOR_ROOT / "simulator" / "config"


def test_simulator_package_importable() -> None:
    import simulator

    assert simulator.__version__ == "0.1.0"


def test_profile_default_parses() -> None:
    profile_path = CONFIG_DIR / "profile.default.yaml"
    assert profile_path.is_file(), f"missing {profile_path}"

    data = yaml.safe_load(profile_path.read_text())
    assert data["profile_name"] == "default"

    # Engine endpoints — every field the transport layer will need.
    engine = data["engine"]
    for key in (
        "rest_base_url",
        "kafka_bootstrap",
        "jwt_token_endpoint",
        "transactions_endpoint",
        "decisions_endpoint",
        "admin_audit_endpoint",
        "prometheus_endpoint",
        "rate_limit_per_subject_per_min",
    ):
        assert key in engine, f"engine config missing: {key}"

    # Run parameters that lock the default profile to the plan's promise.
    run = data["run"]
    assert run["total_tx_target"] == 10_000
    assert run["duration_seconds"] == 600
    assert run["seed"] == 42
    assert run["transport_mix"]["rest"] + run["transport_mix"]["kafka"] == pytest.approx(1.0)

    # Account pool budget — must equal agents budget.
    accounts = data["accounts"]
    assert accounts["count"] == 100

    agents = data["agents"]
    total_agents = (
        agents["honest_count"]
        + agents["scripted_adversary_count"]
        + agents["llm_adversary_count"]
    )
    assert total_agents == accounts["count"], (
        "agent counts must sum to accounts.count "
        f"(got {total_agents}, expected {accounts['count']})"
    )

    # Load probe RPS cap — plan §5 requires <= rate-limit ceiling.
    rate_ceiling = (
        accounts["count"] * engine["rate_limit_per_subject_per_min"] / 60
    )
    target_rps = data["scenarios"]["load"]["target_rps"]
    assert target_rps <= rate_ceiling, (
        f"target_rps={target_rps} exceeds rate-limit ceiling={rate_ceiling:.0f}"
    )


def test_scenarios_seed_parses() -> None:
    seed_path = CONFIG_DIR / "scenarios.seed.yaml"
    assert seed_path.is_file(), f"missing {seed_path}"

    data = yaml.safe_load(seed_path.read_text())
    assert data["version"] == 1
    seeds = data["seeds"]
    assert isinstance(seeds, list)
    assert len(seeds) >= 5, "plan §3 calls for >= 5 seed scenarios"

    valid_outcomes = {"miss", "partial", "detected"}
    for seed in seeds:
        for key in ("id", "archetype", "targets_rule", "description", "expected_outcome"):
            assert key in seed, f"seed missing {key}: {seed.get('id', '<unnamed>')}"
        assert seed["expected_outcome"] in valid_outcomes, (
            f"seed {seed['id']} has invalid expected_outcome={seed['expected_outcome']}"
        )

    # IDs must be unique — sim seed uses them as filenames.
    ids = [s["id"] for s in seeds]
    assert len(ids) == len(set(ids)), f"duplicate seed ids: {ids}"
