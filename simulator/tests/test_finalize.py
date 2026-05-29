"""Tests for post-run finalization: parquet export + run-latest symlink."""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest

from simulator.orchestrator.finalize import (
    export_decisions_parquet,
    update_run_latest_symlink,
)
from simulator.telemetry.sink import SinkRow, SqliteSink
from simulator.transport.rest import SubmissionResult


def _result() -> SubmissionResult:
    now = datetime.now(tz=UTC)
    return SubmissionResult(
        submitted_at=now, received_at=now, http_status=202,
        decision=None, idempotency_key="k", subject="s",
    )


@pytest.mark.asyncio
async def test_export_decisions_parquet_roundtrip(tmp_path: Path) -> None:
    sink = SqliteSink(tmp_path / "sink.db")
    await sink.start()
    await sink.record(SinkRow(
        run_id="r", persona_role="HONEST", scenario_id=None,
        tx_index=1, account_id="ACC-1", result=_result(),
    ))
    await sink.close()

    out = export_decisions_parquet(sink_db=tmp_path / "sink.db", out_path=tmp_path / "raw.parquet")
    assert out is not None and out.is_file()

    import pandas as pd

    frame = pd.read_parquet(out)
    assert len(frame) == 1
    assert frame.iloc[0]["account_id"] == "ACC-1"


def test_export_decisions_parquet_skips_missing_db(tmp_path: Path) -> None:
    assert export_decisions_parquet(
        sink_db=tmp_path / "nope.db", out_path=tmp_path / "x.parquet"
    ) is None


def test_update_run_latest_symlink(tmp_path: Path) -> None:
    reports = tmp_path / "reports"
    reports.mkdir()
    run_dir = reports / "run-1"
    run_dir.mkdir()

    update_run_latest_symlink(reports_root=reports, run_dir=run_dir)
    link = reports / "run-latest"
    assert link.is_symlink()
    assert link.resolve() == run_dir.resolve()

    # Idempotent: repointing to a new run replaces the old target.
    run_dir2 = reports / "run-2"
    run_dir2.mkdir()
    update_run_latest_symlink(reports_root=reports, run_dir=run_dir2)
    assert (reports / "run-latest").resolve() == run_dir2.resolve()
