"""Run-loop orchestrator tests — finalization + the Bug-1 watchdog guarantee.

The engine is mocked with respx; SIM_NO_LLM skips Ollama. The point is to
prove the multi-phase run (a) actually fires scripted adversaries into the
sink and (b) ALWAYS terminates and finalizes — even when a persona wedges.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
import respx

from simulator.agents.personas.honest import HonestPersona
from simulator.orchestrator.runner import run_simulation
from simulator.telemetry.sink import fetch_all_rows

_BASE = "http://engine.test"


def _profile(**run_overrides: object) -> dict:
    run = {
        "seed": 42,
        "duration_seconds": 0.3,
        "total_tx_target": 50,
        "watchdog_grace_seconds": 10,
    }
    run.update(run_overrides)
    return {
        "accounts": {
            "count": 4,
            "account_age_buckets": {"new": 0.25, "young": 0.25, "aged": 0.25, "veteran": 0.25},
            "primary_country": "ZA",
            "cross_border_share": 0.1,
            "channels": ["WEB", "MOBILE"],
        },
        "agents": {"honest_count": 2, "llm_adversary_count": 0, "llm_concurrency_cap": 8},
        "run": run,
    }


def _mock_engine(mock: respx.MockRouter) -> None:
    mock.post("/auth/token").mock(
        return_value=httpx.Response(200, json={"accessToken": "tok", "expiresInSeconds": 3600})
    )
    mock.post("/api/v1/transactions").mock(
        return_value=httpx.Response(202, json={
            "decisionId": str(uuid4()),
            "txId": str(uuid4()),
            "status": "APPROVED",
            "score": 0.0,
            "ruleSetVersion": 1,
            "matchedRules": [],
            "evaluatedAt": "2026-05-28T12:00:00Z",
        })
    )


@pytest.mark.asyncio
async def test_run_simulation_finalizes_and_fires_scripted(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("SIM_NO_LLM", "1")
    sink_path = tmp_path / "sink.db"

    with respx.mock(base_url=_BASE) as mock:
        _mock_engine(mock)
        summary = await asyncio.wait_for(
            run_simulation(rest_base_url=_BASE, profile=_profile(), sink_path=sink_path),
            timeout=30,
        )

    assert summary.timed_out is False
    assert summary.submissions_made > 0
    assert summary.scripted_submissions > 0  # the evasion/rule-coverage scenarios fired
    assert sink_path.is_file()

    rows = list(fetch_all_rows(sink_path))
    assert len(rows) > 0
    roles = {r["persona_role"] for r in rows}
    assert "ADVERSARY_SCRIPTED" in roles
    # Scripted scenario ids (evasion_* / rule_coverage_*) landed in the sink.
    assert any((r["scenario_id"] or "").startswith(("evasion_", "rule_coverage_")) for r in rows)


@pytest.mark.asyncio
async def test_run_simulation_watchdog_terminates_on_hang(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A wedged honest persona must NOT hang the run — the watchdog finalizes."""
    monkeypatch.setenv("SIM_NO_LLM", "1")

    async def _hang(self: HonestPersona, *, duration_seconds: float,
                    max_tx: int | None = None, stop_event: object = None) -> int:
        await asyncio.sleep(3600)
        return 0

    monkeypatch.setattr(HonestPersona, "run", _hang)
    sink_path = tmp_path / "sink.db"

    with respx.mock(base_url=_BASE) as mock:
        _mock_engine(mock)
        summary = await asyncio.wait_for(
            run_simulation(
                rest_base_url=_BASE,
                profile=_profile(duration_seconds=0.2, watchdog_grace_seconds=0.5,
                                 total_tx_target=10),
                sink_path=sink_path,
            ),
            timeout=15,
        )

    assert summary.timed_out is True
    assert sink_path.is_file()  # artifacts finalized despite the hang
