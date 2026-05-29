"""Determinism tests — same seed → same scenarios → same sink hash.

Per plan §9: two runs with the same seed must produce identical
``sha256(persona_role || scenario_id || tx_index || status || score ||
sorted(matched_rule_ids))`` aggregate hashes over the SQLite sink. UUIDs
and wall-clock timestamps are explicitly excluded.
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import pytest

from simulator.scenarios.evasion import (
    build_card_testing,
    build_geo_impossibility,
    build_structuring,
)
from simulator.scenarios.rule_coverage import build_rule_coverage_scenarios
from simulator.telemetry.sink import (
    SinkRow,
    SqliteSink,
    deterministic_run_hash,
)
from simulator.transport.models import DecisionResponse, DecisionStatus, MatchedRule
from simulator.transport.rest import SubmissionResult


def _row(scenario_id: str, tx_index: int, status: DecisionStatus, matched: list[str]) -> SinkRow:
    return SinkRow(
        run_id="r", persona_role="ADVERSARY_SCRIPTED",
        scenario_id=scenario_id, tx_index=tx_index,
        account_id="ACC",
        result=SubmissionResult(
            submitted_at=datetime.now(tz=UTC),
            received_at=datetime.now(tz=UTC),
            http_status=202,
            decision=DecisionResponse(
                decisionId=uuid4(), txId=uuid4(),
                status=status, score=0.0 if status is DecisionStatus.APPROVED else 0.85,
                ruleSetVersion=1,
                matchedRules=[MatchedRule(ruleId=r, priority=500) for r in matched],
                evaluatedAt=datetime.now(tz=UTC),
            ),
            idempotency_key=str(uuid4()), subject="alice",
        ),
    )


@pytest.mark.asyncio
async def test_same_seed_produces_identical_sink_hash(tmp_path: Path) -> None:
    """Two synthetic runs with the same rows (up to wall-clock noise) hash equal."""
    rows = [
        _row("evasion_card_testing", 0, DecisionStatus.APPROVED, []),
        _row("evasion_card_testing", 1, DecisionStatus.APPROVED, []),
        _row("rule_coverage_blacklisted_merchant_positive", 0,
             DecisionStatus.BLOCK, ["BLACKLISTED_MERCHANT"]),
        _row("rule_coverage_high_amount_new_account_positive", 0,
             DecisionStatus.REVIEW, ["HIGH_AMOUNT_NEW_ACCOUNT"]),
    ]

    hashes: list[str] = []
    for run in range(2):
        db = tmp_path / f"sink-{run}.db"
        sink = SqliteSink(db)
        await sink.start()
        for row in rows:
            await sink.record(row)
        await sink.close()
        hashes.append(deterministic_run_hash(db))

    assert hashes[0] == hashes[1]


def test_scenario_deterministic_across_repeated_builds() -> None:
    """build_card_testing/structuring/geo_impossibility are pure for fixed seed."""
    a = build_card_testing(seed=42)
    b = build_card_testing(seed=42)
    assert a == b

    a = build_structuring(seed=42)
    b = build_structuring(seed=42)
    assert a == b

    a = build_geo_impossibility(seed=42)
    b = build_geo_impossibility(seed=42)
    assert a == b


def test_rule_coverage_scenarios_deterministic() -> None:
    a = list(build_rule_coverage_scenarios(seed=42))
    b = list(build_rule_coverage_scenarios(seed=42))
    assert len(a) == len(b)
    for sa, sb in zip(a, b, strict=True):
        assert sa.id == sb.id
        # Steps must be byte-for-byte equal — including txId UUIDs (uuid5-derived).
        for step_a, step_b in zip(sa.steps, sb.steps, strict=True):
            assert step_a.tx == step_b.tx
