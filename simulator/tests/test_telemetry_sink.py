"""SQLite sink tests — schema, write path, deterministic-hash projection."""

from __future__ import annotations

import asyncio
import sqlite3
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import pytest

from simulator.telemetry.sink import (
    SinkRow,
    SqliteSink,
    deterministic_run_hash,
    fetch_all_rows,
)
from simulator.transport.models import DecisionResponse, DecisionStatus, MatchedRule
from simulator.transport.rest import SubmissionResult


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
        submitted_at=now,
        received_at=now,
        http_status=http_status,
        decision=decision,
        idempotency_key=str(tx_id),
        subject="alice",
    )


@pytest.mark.asyncio
async def test_sink_round_trips_a_row(tmp_path: Path) -> None:
    sink = SqliteSink(tmp_path / "sink.db")
    await sink.start()

    await sink.record(
        SinkRow(
            run_id="r1",
            persona_role="HONEST",
            scenario_id=None,
            tx_index=1,
            account_id="ACC-1",
            result=_result(status=DecisionStatus.APPROVED, matched=[]),
        )
    )

    await sink.close()

    rows = list(fetch_all_rows(tmp_path / "sink.db"))
    assert len(rows) == 1
    row = rows[0]
    assert row["account_id"] == "ACC-1"
    assert row["status"] == "APPROVED"
    assert row["matched_rules"] == "[]"
    assert row["http_status"] == 202


@pytest.mark.asyncio
async def test_sink_persists_multiple_rows_in_order(tmp_path: Path) -> None:
    sink = SqliteSink(tmp_path / "sink.db")
    await sink.start()

    for i in range(10):
        await sink.record(
            SinkRow(
                run_id="r1",
                persona_role="HONEST",
                scenario_id=None,
                tx_index=i + 1,
                account_id=f"ACC-{i}",
                result=_result(status=DecisionStatus.APPROVED, matched=[]),
            )
        )

    await sink.close()
    rows = list(fetch_all_rows(tmp_path / "sink.db"))
    assert [r["tx_index"] for r in rows] == list(range(1, 11))


@pytest.mark.asyncio
async def test_sink_records_failed_submission(tmp_path: Path) -> None:
    """500-class results still produce a sink row (per FraudEngineRestClient contract)."""
    sink = SqliteSink(tmp_path / "sink.db")
    await sink.start()

    now = datetime.now(tz=UTC)
    failed = SubmissionResult(
        submitted_at=now, received_at=now,
        http_status=500, decision=None,
        idempotency_key="key-1", subject="alice",
        error="unexpected-status-500",
    )
    await sink.record(
        SinkRow(
            run_id="r1", persona_role="HONEST",
            scenario_id=None, tx_index=1, account_id="ACC-1",
            result=failed,
        )
    )

    await sink.close()
    rows = list(fetch_all_rows(tmp_path / "sink.db"))
    assert rows[0]["http_status"] == 500
    assert rows[0]["status"] is None
    assert rows[0]["error"] == "unexpected-status-500"


@pytest.mark.asyncio
async def test_deterministic_hash_stable_across_runs(tmp_path: Path) -> None:
    """Two sinks with identical row content must produce identical hashes."""
    for n in (1, 2):
        sink = SqliteSink(tmp_path / f"sink-{n}.db")
        await sink.start()
        await sink.record(
            SinkRow(
                run_id=f"r{n}", persona_role="HONEST",
                scenario_id="card_testing_micro", tx_index=1,
                account_id="ACC-1",
                # Force identical projection: same status, same score, same
                # matched_rules. Timestamps differ but they're excluded.
                result=SubmissionResult(
                    submitted_at=datetime.now(tz=UTC),
                    received_at=datetime.now(tz=UTC),
                    http_status=202,
                    decision=DecisionResponse(
                        decisionId=uuid4(), txId=uuid4(),
                        status=DecisionStatus.REVIEW, score=0.75,
                        ruleSetVersion=1,
                        matchedRules=[MatchedRule(ruleId="HIGH_AMOUNT_NEW_ACCOUNT", priority=800)],
                        evaluatedAt=datetime.now(tz=UTC),
                    ),
                    idempotency_key=f"k-{n}",
                    subject="alice",
                ),
            )
        )
        await sink.close()

    hash1 = deterministic_run_hash(tmp_path / "sink-1.db")
    hash2 = deterministic_run_hash(tmp_path / "sink-2.db")
    assert hash1 == hash2


# --------------------------------------------------------------------------- #
# Bug 1 — writer must never deadlock the run
# --------------------------------------------------------------------------- #


def _row(account_id: str) -> SinkRow:
    return SinkRow(
        run_id="r1", persona_role="HONEST", scenario_id=None,
        tx_index=1, account_id=account_id,
        result=_result(status=DecisionStatus.APPROVED, matched=[]),
    )


@pytest.mark.asyncio
async def test_sink_skips_bad_row_without_dying(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A failing insert must be skipped+counted, not kill the writer.

    Previously a single insert exception killed the unsupervised writer, the
    bounded queue filled, and every producer blocked on put() forever.
    """
    real_insert = SqliteSink._insert

    def flaky(cursor: sqlite3.Cursor, row: SinkRow) -> None:
        if row.account_id == "BAD":
            raise sqlite3.OperationalError("simulated insert failure")
        real_insert(cursor, row)

    monkeypatch.setattr(SqliteSink, "_insert", staticmethod(flaky))

    sink = SqliteSink(tmp_path / "sink.db")
    await sink.start()
    for acc in ("GOOD-1", "BAD", "GOOD-2"):
        await asyncio.wait_for(sink.record(_row(acc)), timeout=1.0)
    await asyncio.wait_for(sink.close(), timeout=5.0)

    rows = list(fetch_all_rows(tmp_path / "sink.db"))
    assert {r["account_id"] for r in rows} == {"GOOD-1", "GOOD-2"}
    assert sink.dropped == 1
    assert sink.row_count == 2


@pytest.mark.asyncio
async def test_sink_record_fastdrops_when_writer_failed(tmp_path: Path) -> None:
    """When the writer has died fatally, record() returns instead of blocking."""
    sink = SqliteSink(tmp_path / "sink.db", queue_maxsize=1)
    await sink.start()
    sink._writer_failed.set()  # simulate a fatal writer death

    # Would block forever on a full queue against a dead consumer pre-fix.
    await asyncio.wait_for(sink.record(_row("X")), timeout=0.5)
    await asyncio.wait_for(sink.record(_row("Y")), timeout=0.5)
    assert sink.dropped == 2
    await asyncio.wait_for(sink.close(), timeout=5.0)


@pytest.mark.asyncio
async def test_sink_close_does_not_hang_on_stalled_writer(tmp_path: Path) -> None:
    """close() must bound its wait and cancel a stalled writer."""
    sink = SqliteSink(tmp_path / "sink.db", queue_maxsize=2, close_timeout_seconds=0.3)

    async def _never_drain() -> None:
        await asyncio.sleep(3600)

    sink._writer_task = asyncio.create_task(_never_drain())
    sink._queue.put_nowait(_row("A"))
    sink._queue.put_nowait(_row("B"))  # queue now full → sentinel can't enqueue

    loop = asyncio.get_running_loop()
    started = loop.time()
    await asyncio.wait_for(sink.close(), timeout=3.0)
    assert loop.time() - started < 2.0  # returned via timeout+cancel, not a hang
