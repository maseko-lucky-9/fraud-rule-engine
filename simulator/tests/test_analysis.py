"""Analysis pipeline tests — metrics, predicate validator, audit rendering."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import pytest

from simulator.analysis.auditor import (
    FindingDraft,
    build_audit_report,
)
from simulator.analysis.metrics import (
    PerRuleMetrics,
    _scenario_classification,
    compute_run_metrics,
)
from simulator.analysis.yaml_validator import (
    PredicateAllowlist,
)
from simulator.telemetry.sink import SinkRow, SqliteSink
from simulator.transport.models import DecisionResponse, DecisionStatus, MatchedRule
from simulator.transport.rest import SubmissionResult

# --------------------------------------------------------------------------- #
# Helpers — populate a sink DB with synthetic scenario results.
# --------------------------------------------------------------------------- #

def _result(*, status: DecisionStatus, matched: list[str], http_status: int = 202) -> SubmissionResult:
    tx_id = uuid4()
    decision = DecisionResponse(
        decisionId=uuid4(),
        txId=tx_id,
        status=status,
        score=0.0 if status is DecisionStatus.APPROVED else 0.85,
        ruleSetVersion=1,
        matchedRules=[MatchedRule(ruleId=r, priority=500) for r in matched],
        evaluatedAt=datetime(2026, 5, 28, 12, tzinfo=UTC),
    )
    now = datetime.now(tz=UTC)
    return SubmissionResult(
        submitted_at=now, received_at=now, http_status=http_status,
        decision=decision, idempotency_key=str(tx_id), subject="alice",
    )


async def _populate_sink(db_path: Path) -> None:
    sink = SqliteSink(db_path)
    await sink.start()

    # BLACKLISTED_MERCHANT — 1 positive caught, 1 negative correctly clean.
    await sink.record(SinkRow(
        run_id="r", persona_role="ADVERSARY_SCRIPTED",
        scenario_id="rule_coverage_blacklisted_merchant_positive", tx_index=0,
        account_id="ACC-RC-BL-1",
        result=_result(status=DecisionStatus.BLOCK, matched=["BLACKLISTED_MERCHANT"]),
    ))
    await sink.record(SinkRow(
        run_id="r", persona_role="ADVERSARY_SCRIPTED",
        scenario_id="rule_coverage_blacklisted_merchant_negative", tx_index=0,
        account_id="ACC-RC-BL-2",
        result=_result(status=DecisionStatus.APPROVED, matched=[]),
    ))

    # HIGH_AMOUNT_NEW_ACCOUNT — positive correctly detected, negative correctly clean.
    await sink.record(SinkRow(
        run_id="r", persona_role="ADVERSARY_SCRIPTED",
        scenario_id="rule_coverage_high_amount_new_account_positive", tx_index=0,
        account_id="ACC-RC-HA-1",
        result=_result(status=DecisionStatus.REVIEW, matched=["HIGH_AMOUNT_NEW_ACCOUNT"]),
    ))
    await sink.record(SinkRow(
        run_id="r", persona_role="ADVERSARY_SCRIPTED",
        scenario_id="rule_coverage_high_amount_new_account_negative", tx_index=0,
        account_id="ACC-RC-HA-3",
        result=_result(status=DecisionStatus.APPROVED, matched=[]),
    ))

    # Evasion: card_testing — engine misses (expected `miss`).
    for i in range(3):
        await sink.record(SinkRow(
            run_id="r", persona_role="ADVERSARY_SCRIPTED",
            scenario_id="evasion_card_testing", tx_index=i,
            account_id=f"ACC-EV-CT-{i:02d}",
            result=_result(status=DecisionStatus.APPROVED, matched=[]),
        ))

    await sink.close()


# --------------------------------------------------------------------------- #
# Scenario classification
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize(
    ("sid", "expected"),
    [
        ("rule_coverage_blacklisted_merchant_positive",
         ("BLACKLISTED_MERCHANT", "positive")),
        ("rule_coverage_high_amount_new_account_negative",
         ("HIGH_AMOUNT_NEW_ACCOUNT", "negative")),
        ("rule_coverage_high_amount_new_account_boundary",
         ("HIGH_AMOUNT_NEW_ACCOUNT", "boundary")),
        ("evasion_card_testing", None),
        ("", None),
    ],
)
def test_scenario_classification(sid: str, expected: tuple[str, str] | None) -> None:
    assert _scenario_classification(sid) == expected


# --------------------------------------------------------------------------- #
# Metrics
# --------------------------------------------------------------------------- #


@pytest.mark.asyncio
async def test_per_rule_metrics_on_synthetic_run(tmp_path: Path) -> None:
    db = tmp_path / "sink.db"
    await _populate_sink(db)

    metrics = compute_run_metrics(
        db_path=db, run_id="r",
        evasion_expectations={"evasion_card_testing": "miss"},
    )
    assert metrics.total_submissions == 7

    by_rule = {r.rule: r for r in metrics.per_rule}
    assert by_rule["BLACKLISTED_MERCHANT"].precision == 1.0
    assert by_rule["BLACKLISTED_MERCHANT"].recall == 1.0
    assert by_rule["HIGH_AMOUNT_NEW_ACCOUNT"].f1 == 1.0

    evasion = {o["scenario_id"]: o for o in metrics.evasion_outcomes}
    assert evasion["evasion_card_testing"]["expected"] == "miss"
    assert evasion["evasion_card_testing"]["actual"] == "miss"


def test_per_rule_metrics_division_by_zero_safe() -> None:
    m = PerRuleMetrics(rule="X", true_positives=0, false_positives=0,
                       false_negatives=0, true_negatives=0)
    assert m.precision == 0.0
    assert m.recall == 0.0
    assert m.f1 == 0.0


# --------------------------------------------------------------------------- #
# Predicate allowlist
# --------------------------------------------------------------------------- #

def test_allowlist_loads_from_default_path() -> None:
    allowlist = PredicateAllowlist.from_path()
    assert "amountAbove" in allowlist.predicates
    assert "all" in allowlist.combinators


def test_allowlist_accepts_drop_in_stub() -> None:
    allowlist = PredicateAllowlist.from_path()
    stub = """\
id: TEST
condition:
  all:
    - amountAbove: { value: 100, currency: ZAR }
    - accountAgeBelow: { days: 30 }
action: { flag: REVIEW, score: 0.5, reason: "test" }
"""
    result = allowlist.validate(stub)
    assert result.drop_in_ready
    assert result.unknown_predicates == ()


def test_allowlist_flags_unknown_predicate() -> None:
    allowlist = PredicateAllowlist.from_path()
    stub = """\
id: TEST
condition:
  all:
    - amountTotalAccount: { windowHours: 24, value: 25000 }
    - amountAbove: { value: 8000, currency: ZAR }
action: { flag: REVIEW, score: 0.5, reason: "test" }
"""
    result = allowlist.validate(stub)
    assert not result.drop_in_ready
    assert "amountTotalAccount" in result.unknown_predicates


def test_allowlist_rejects_malformed_yaml() -> None:
    allowlist = PredicateAllowlist.from_path()
    result = allowlist.validate("not: yaml: structure:")
    assert not result.drop_in_ready
    assert result.parse_error is not None


# --------------------------------------------------------------------------- #
# Auditor — end-to-end with --no-llm fallback
# --------------------------------------------------------------------------- #


@pytest.mark.asyncio
async def test_audit_report_writes_markdown(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("SIM_NO_LLM", "1")  # use fallback narrative
    db = tmp_path / "sink.db"
    out = tmp_path / "report"
    await _populate_sink(db)

    report_path = await build_audit_report(
        db_path=db, run_id="r-test",
        output_dir=out, ollama_wrapper=None,
        evasion_expectations={"evasion_card_testing": "miss"},
    )

    markdown = report_path.read_text()
    assert report_path.is_file()
    assert "# Fraud-rule-engine — Pen-Test Audit" in markdown
    assert "Rule-by-rule precision / recall" in markdown
    assert "BLACKLISTED_MERCHANT" in markdown
    assert "evasion_card_testing" in markdown
    # Evasion miss → default finding stub surfaces.
    assert "Card-testing across accounts" in markdown

    # Metrics JSON sidecar is written.
    assert (out / "metrics.json").is_file()


@pytest.mark.asyncio
async def test_audit_report_handles_drop_in_finding(tmp_path: Path) -> None:
    """Custom finding using only known predicates renders as 'drop-in'."""
    db = tmp_path / "sink.db"
    await _populate_sink(db)

    drop_in = FindingDraft(
        severity="P2", scenario_id="custom",
        title="custom",
        proposed_yaml_stub=(
            "id: DROP_IN\n"
            "condition:\n  all:\n    - amountAbove: { value: 100, currency: ZAR }\n"
            "action: { flag: REVIEW, score: 0.5, reason: x }\n"
        ),
    )

    await build_audit_report(
        db_path=db, run_id="r-test",
        output_dir=tmp_path / "out",
        ollama_wrapper=None,
        findings_drafts=[drop_in],
        evasion_expectations={"evasion_card_testing": "miss"},
    )
    md = (tmp_path / "out" / "audit_report.md").read_text()
    assert "Draft YAML rule stub (drop-in)" in md


def test_async_loop_is_idempotent() -> None:
    # Sanity check that asyncio.run can be called twice without leftover state.
    asyncio.run(asyncio.sleep(0))
    asyncio.run(asyncio.sleep(0))
