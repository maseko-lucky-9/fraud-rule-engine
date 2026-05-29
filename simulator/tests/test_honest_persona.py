"""Honest-persona behavioural tests — every tx must stay sub-rule-threshold."""

from __future__ import annotations

import asyncio
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
import respx

from simulator.agents.personas.honest import HonestPersona
from simulator.orchestrator.account_pool import SimulatedAccount
from simulator.telemetry.sink import SqliteSink, fetch_all_rows
from simulator.transport.auth import JwtMinter
from simulator.transport.models import Channel
from simulator.transport.rest import FraudEngineRestClient


def _account() -> SimulatedAccount:
    return SimulatedAccount(
        account_id="ACC-HONEST-1",
        account_age_days=180,
        home_country="ZA",
        ip_country="ZA",
        primary_channel=Channel.MOBILE,
        device_id="dev-honest",
    )


def _approved(tx_id) -> dict:
    return {
        "decisionId": str(uuid4()),
        "txId": str(tx_id),
        "status": "APPROVED",
        "score": 0.0,
        "ruleSetVersion": 1,
        "matchedRules": [],
        "evaluatedAt": "2026-05-28T12:00:00Z",
    }


@pytest.mark.asyncio
async def test_honest_persona_submits_at_least_one_tx(tmp_path: Path) -> None:
    sink = SqliteSink(tmp_path / "sink.db")
    await sink.start()

    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok", "expiresInSeconds": 3600})
        )
        mock.post("/api/v1/transactions").mock(
            side_effect=lambda req: httpx.Response(202, json=_approved(uuid4()))
        )

        minter = JwtMinter(rest_base_url="http://engine.test")
        rest = FraudEngineRestClient(rest_base_url="http://engine.test", minter=minter, client=client)
        persona = HonestPersona(
            account=_account(), rest=rest, sink=sink,
            run_id="r-test", mean_interarrival_seconds=0.001, seed=42,
        )

        submitted = await persona.run(duration_seconds=0.5, max_tx=5)

    await sink.close()
    assert submitted >= 1
    assert submitted <= 5

    rows = list(fetch_all_rows(tmp_path / "sink.db"))
    assert len(rows) == submitted
    for row in rows:
        assert row["persona_role"] == "HONEST"
        assert row["account_id"] == "ACC-HONEST-1"
        assert row["status"] == "APPROVED"


@pytest.mark.asyncio
async def test_honest_persona_payload_stays_under_thresholds(tmp_path: Path) -> None:
    """Every submitted tx must:
    - amount well under all engine thresholds (max $300 ZAR)
    - country == ipCountry (no cross-border)
    - merchant NOT in MERCH-DENY-* blacklist
    - deviceId is the account's pinned device
    """
    captured: list[dict] = []
    sink = SqliteSink(tmp_path / "sink.db")
    await sink.start()

    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok", "expiresInSeconds": 3600})
        )

        def _capture(request: httpx.Request) -> httpx.Response:
            import json as _json
            captured.append(_json.loads(request.content))
            return httpx.Response(202, json=_approved(captured[-1]["txId"]))

        mock.post("/api/v1/transactions").mock(side_effect=_capture)

        minter = JwtMinter(rest_base_url="http://engine.test")
        rest = FraudEngineRestClient(rest_base_url="http://engine.test", minter=minter, client=client)
        persona = HonestPersona(
            account=_account(), rest=rest, sink=sink,
            run_id="r-test", mean_interarrival_seconds=0.001, seed=42,
        )
        await persona.run(duration_seconds=0.2, max_tx=20)

    await sink.close()

    assert len(captured) > 0
    for tx in captured:
        assert tx["country"] == tx["ipCountry"] == "ZA"
        assert not tx["merchantId"].startswith("MERCH-DENY-")
        assert tx["deviceId"] == "dev-honest"
        # Amount well under all rule thresholds ($5000 cross-border is the
        # lowest amount-bearing rule).
        assert Decimal(tx["amount"]) < Decimal("500")


@pytest.mark.asyncio
async def test_stop_event_short_circuits(tmp_path: Path) -> None:
    """A pre-set stop event makes ``run()`` exit before any submission.

    The respx mock context here uses ``assert_all_called=False`` because
    the persona should never reach the auth or tx endpoints.
    """
    sink = SqliteSink(tmp_path / "sink.db")
    await sink.start()

    async with httpx.AsyncClient() as client, respx.mock(
        base_url="http://engine.test", assert_all_called=False
    ) as mock:
        mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok", "expiresInSeconds": 3600})
        )
        mock.post("/api/v1/transactions").mock(
            side_effect=lambda req: httpx.Response(202, json=_approved(uuid4()))
        )

        minter = JwtMinter(rest_base_url="http://engine.test")
        rest = FraudEngineRestClient(rest_base_url="http://engine.test", minter=minter, client=client)
        persona = HonestPersona(
            account=_account(), rest=rest, sink=sink,
            run_id="r-test", mean_interarrival_seconds=0.001, seed=42,
        )

        stop_event = asyncio.Event()
        stop_event.set()  # immediately

        submitted = await persona.run(duration_seconds=10, stop_event=stop_event)

    await sink.close()
    assert submitted == 0
