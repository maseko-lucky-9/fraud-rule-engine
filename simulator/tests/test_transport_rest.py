"""End-to-end mock smoke test for the REST client.

Mirrors Phase 2's verification step: hard-coded ACC-SMOKE-0001 transaction,
mocked engine returns APPROVED, sink row carries the parsed decision.
"""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from uuid import uuid4

import httpx
import pytest
import respx

from simulator.transport.auth import JwtMinter
from simulator.transport.models import Channel, DecisionStatus, Transaction
from simulator.transport.rest import FraudEngineRestClient


def _smoke_tx() -> Transaction:
    """ACC-SMOKE-0001 — well-aged account, domestic, low amount: expects APPROVED."""
    return Transaction(
        txId=uuid4(),
        accountId="ACC-SMOKE-0001",
        amount=Decimal("25.50"),
        currency="ZAR",
        mcc="5411",
        channel=Channel.MOBILE,
        country="ZA",
        ipCountry="ZA",
        deviceId="dev-smoke",
        merchantId="MERCH-OK-001",
        accountAgeDays=180,
        timestamp=datetime(2026, 5, 28, 12, 0, tzinfo=UTC),
    )


def _approved_response(tx_id) -> dict:
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
async def test_smoke_submission_returns_approved() -> None:
    minter = JwtMinter(rest_base_url="http://engine.test")
    tx = _smoke_tx()

    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok", "expiresInSeconds": 3600})
        )
        mock.post("/api/v1/transactions").mock(
            return_value=httpx.Response(202, json=_approved_response(tx.txId))
        )

        rest = FraudEngineRestClient(
            rest_base_url="http://engine.test", minter=minter, client=client
        )
        result = await rest.submit_transaction(tx, subject="smoke")

    assert result.http_status == 202
    assert result.decision is not None
    assert result.decision.status is DecisionStatus.APPROVED
    assert result.error is None


@pytest.mark.asyncio
async def test_429_then_success_is_retried() -> None:
    minter = JwtMinter(rest_base_url="http://engine.test")
    tx = _smoke_tx()

    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok", "expiresInSeconds": 3600})
        )
        tx_route = mock.post("/api/v1/transactions").mock(
            side_effect=[
                httpx.Response(429, headers={"Retry-After": "0"}),
                httpx.Response(202, json=_approved_response(tx.txId)),
            ]
        )

        rest = FraudEngineRestClient(
            rest_base_url="http://engine.test", minter=minter, client=client
        )
        result = await rest.submit_transaction(tx, subject="smoke")

    assert tx_route.call_count == 2
    assert result.http_status == 202
    assert result.decision is not None
    assert result.decision.status is DecisionStatus.APPROVED


@pytest.mark.asyncio
async def test_401_invalidates_jwt_and_retries() -> None:
    minter = JwtMinter(rest_base_url="http://engine.test")
    tx = _smoke_tx()

    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        token_route = mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok", "expiresInSeconds": 3600})
        )
        tx_route = mock.post("/api/v1/transactions").mock(
            side_effect=[
                httpx.Response(401),
                httpx.Response(202, json=_approved_response(tx.txId)),
            ]
        )

        rest = FraudEngineRestClient(
            rest_base_url="http://engine.test", minter=minter, client=client
        )
        result = await rest.submit_transaction(tx, subject="smoke")

    # Token was re-minted after the 401.
    assert token_route.call_count == 2
    assert tx_route.call_count == 2
    assert result.http_status == 202


@pytest.mark.asyncio
async def test_unexpected_500_is_recorded_not_raised() -> None:
    minter = JwtMinter(rest_base_url="http://engine.test")
    tx = _smoke_tx()

    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok", "expiresInSeconds": 3600})
        )
        mock.post("/api/v1/transactions").mock(
            return_value=httpx.Response(500, text="boom")
        )

        rest = FraudEngineRestClient(
            rest_base_url="http://engine.test", minter=minter, client=client
        )
        result = await rest.submit_transaction(tx, subject="smoke")

    assert result.http_status == 500
    assert result.decision is None
    assert result.error is not None
    assert "unexpected-status-500" in result.error


@pytest.mark.asyncio
async def test_admin_audit_skips_without_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("SERVICE_API_KEY", raising=False)
    minter = JwtMinter(rest_base_url="http://engine.test")

    async with httpx.AsyncClient() as client:
        rest = FraudEngineRestClient(
            rest_base_url="http://engine.test", minter=minter, client=client
        )
        response = await rest.fetch_audit_log()

    assert response.status_code == 401


@pytest.mark.asyncio
async def test_admin_audit_sends_api_key_header(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SERVICE_API_KEY", "svc-key-test")
    minter = JwtMinter(rest_base_url="http://engine.test")

    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        audit_route = mock.get("/admin/audit").mock(
            return_value=httpx.Response(200, json={"page": 0, "items": []})
        )

        rest = FraudEngineRestClient(
            rest_base_url="http://engine.test", minter=minter, client=client
        )
        await rest.fetch_audit_log()

    assert audit_route.called
    sent_request = audit_route.calls.last.request
    assert sent_request.headers.get("X-Service-Api-Key") == "svc-key-test"
