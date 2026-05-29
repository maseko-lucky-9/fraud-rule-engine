"""Security-probe runner tests — every probe records an outcome."""

from __future__ import annotations

import httpx
import pytest
import respx

from simulator.scenarios.security_runner import (
    ProbeOutcome,
    SecurityProbeRunner,
    total_probe_count,
)


@pytest.mark.asyncio
async def test_runner_records_one_outcome_per_probe() -> None:
    """All 14 probes (4 + 10 fuzz) execute and produce an outcome row."""
    async with httpx.AsyncClient() as client, respx.mock(
        base_url="http://engine.test", assert_all_called=False
    ) as mock:
        # Auth-failure probes → 401
        mock.post("/api/v1/transactions",
                  headers__contains={"Authorization": "Bearer eyJhbGciOiJub25l"}).mock(
            return_value=httpx.Response(401, json={"error": "alg=none rejected"})
        )

        # Catch-all for valid bearer
        mock.post("/api/v1/transactions").mock(
            return_value=httpx.Response(400, json={"error": "validation"})
        )

        runner = SecurityProbeRunner(
            rest_base_url="http://engine.test",
            valid_bearer_token="valid-token",
            client=client,
        )
        outcomes = await runner.run_all()

    assert len(outcomes) == total_probe_count()
    assert all(isinstance(o, ProbeOutcome) for o in outcomes)


@pytest.mark.asyncio
async def test_probe_passes_when_actual_code_matches_expected() -> None:
    async with httpx.AsyncClient() as client, respx.mock(
        base_url="http://engine.test", assert_all_called=False
    ) as mock:
        # 401 satisfies the JWT alg=none probe
        mock.post("/api/v1/transactions").mock(
            return_value=httpx.Response(401, json={"error": "auth"})
        )

        runner = SecurityProbeRunner(
            rest_base_url="http://engine.test",
            valid_bearer_token="valid-token",
            client=client,
        )
        outcomes = await runner.run_all()

    jwt_probes = [o for o in outcomes if o.probe_id.startswith("security_jwt_")]
    assert len(jwt_probes) == 2
    assert all(o.passed for o in jwt_probes)


@pytest.mark.asyncio
async def test_probe_fails_when_actual_code_unexpected() -> None:
    async with httpx.AsyncClient() as client, respx.mock(
        base_url="http://engine.test", assert_all_called=False
    ) as mock:
        # 500 fails every probe (none expect 500).
        mock.post("/api/v1/transactions").mock(
            return_value=httpx.Response(500, json={"error": "boom"})
        )

        runner = SecurityProbeRunner(
            rest_base_url="http://engine.test",
            valid_bearer_token="valid-token",
            client=client,
        )
        outcomes = await runner.run_all()

    assert all(not o.passed for o in outcomes)
    assert all(o.actual_code == 500 for o in outcomes)


@pytest.mark.asyncio
async def test_rate_limit_probe_passes_on_first_observed_429() -> None:
    """Even if most burst responses are 202, one 429 suffices for pass."""
    call_count = {"n": 0}

    def _switch(_request: httpx.Request) -> httpx.Response:
        call_count["n"] += 1
        if call_count["n"] >= 100:
            return httpx.Response(429, headers={"Retry-After": "1"})
        return httpx.Response(202, json={"decisionId": "x", "txId": "y",
                                          "status": "APPROVED", "score": 0.0,
                                          "ruleSetVersion": 1, "matchedRules": [],
                                          "evaluatedAt": "2026-05-28T12:00:00Z"})

    async with httpx.AsyncClient() as client, respx.mock(
        base_url="http://engine.test", assert_all_called=False
    ) as mock:
        mock.post("/api/v1/transactions").mock(side_effect=_switch)

        runner = SecurityProbeRunner(
            rest_base_url="http://engine.test",
            valid_bearer_token="valid-token",
            client=client,
        )
        outcomes = await runner.run_all()

    rate_limit = next(o for o in outcomes if o.probe_id == "security_rate_limit")
    assert rate_limit.passed
    assert rate_limit.actual_code == 429
