"""Executor for ``SecurityProbe`` definitions.

The probes themselves live in :mod:`simulator.scenarios.security`; this
module hits the engine and records the outcome. The runner returns a list
of ``ProbeOutcome`` rows the audit writer turns into a "Security probe
results" section.
"""

from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass

import httpx

from .security import (
    JWT_EXPIRED_FAKE,
    JWT_NONE_TOKEN,
    SecurityProbe,
    all_probes,
    fuzz_body,
    idempotency_collision_probe,
    jwt_alg_none_probe,
    jwt_expired_probe,
    payload_fuzz_probes,
    rate_limit_probe,
)


@dataclass(slots=True, frozen=True)
class ProbeOutcome:
    probe_id: str
    targets: str
    expected_codes: tuple[int, ...]
    actual_code: int
    passed: bool
    note: str | None = None


class SecurityProbeRunner:
    """Submits each probe directly via httpx and asserts the response code."""

    def __init__(
        self,
        *,
        rest_base_url: str,
        valid_bearer_token: str,
        client: httpx.AsyncClient | None = None,
        transactions_path: str = "/api/v1/transactions",
        request_timeout_seconds: float = 5.0,
    ) -> None:
        self._base = rest_base_url.rstrip("/")
        self._bearer = valid_bearer_token
        self._tx_path = transactions_path
        self._timeout = request_timeout_seconds
        self._client = client or httpx.AsyncClient(timeout=request_timeout_seconds)
        self._owns_client = client is None

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def __aenter__(self) -> SecurityProbeRunner:
        return self

    async def __aexit__(self, *_: object) -> None:
        await self.aclose()

    async def run_all(self) -> list[ProbeOutcome]:
        outcomes: list[ProbeOutcome] = []
        outcomes.append(await self._run_jwt_alg_none())
        outcomes.append(await self._run_jwt_expired())
        outcomes.append(await self._run_idempotency_collision())
        outcomes.append(await self._run_rate_limit_burst())
        for probe in payload_fuzz_probes():
            variant = probe.id.removeprefix("security_payload_fuzz_")
            outcomes.append(await self._run_payload_fuzz(probe, variant))
        return outcomes

    # ------------------------------------------------------------------ #
    # Individual probes
    # ------------------------------------------------------------------ #

    async def _submit(
        self,
        *,
        headers: dict[str, str],
        body: dict,
    ) -> httpx.Response:
        try:
            return await self._client.post(
                f"{self._base}{self._tx_path}",
                headers={**headers, "Content-Type": "application/json"},
                json=body,
            )
        except httpx.HTTPError:
            # Synthesise a transport-failure response so the probe still records.
            return httpx.Response(0, request=httpx.Request("POST", self._tx_path))

    def _outcome(
        self,
        probe: SecurityProbe,
        response: httpx.Response,
        note: str | None = None,
    ) -> ProbeOutcome:
        return ProbeOutcome(
            probe_id=probe.id,
            targets=probe.targets,
            expected_codes=tuple(sorted(probe.pass_status_codes)),
            actual_code=response.status_code,
            passed=response.status_code in probe.pass_status_codes,
            note=note,
        )

    async def _run_jwt_alg_none(self) -> ProbeOutcome:
        probe = jwt_alg_none_probe()
        body = fuzz_body("currency_lowercase")  # any benign-shape body; auth fails first
        body["currency"] = "ZAR"  # restore validity so auth is the only failure mode
        response = await self._submit(
            headers={"Authorization": f"Bearer {JWT_NONE_TOKEN}",
                     "Idempotency-Key": f"probe-{uuid.uuid4()}"},
            body=body,
        )
        return self._outcome(probe, response)

    async def _run_jwt_expired(self) -> ProbeOutcome:
        probe = jwt_expired_probe()
        body = fuzz_body("currency_lowercase")
        body["currency"] = "ZAR"
        response = await self._submit(
            headers={"Authorization": f"Bearer {JWT_EXPIRED_FAKE}",
                     "Idempotency-Key": f"probe-{uuid.uuid4()}"},
            body=body,
        )
        return self._outcome(probe, response)

    async def _run_idempotency_collision(self) -> ProbeOutcome:
        probe = idempotency_collision_probe()
        key = f"probe-collide-{uuid.uuid4()}"
        first_body = fuzz_body("currency_lowercase")
        first_body["currency"] = "ZAR"
        second_body = {**first_body, "amount": "9999.00"}  # different body, same key

        # First request — establish the idempotency-cache entry.
        await self._submit(
            headers={"Authorization": f"Bearer {self._bearer}", "Idempotency-Key": key},
            body=first_body,
        )
        response = await self._submit(
            headers={"Authorization": f"Bearer {self._bearer}", "Idempotency-Key": key},
            body=second_body,
        )
        return self._outcome(probe, response)

    async def _run_rate_limit_burst(self) -> ProbeOutcome:
        probe = rate_limit_probe()
        body = fuzz_body("currency_lowercase")
        body["currency"] = "ZAR"

        # Fire 120 requests as fast as possible — well above 100/min.
        async def one() -> httpx.Response:
            return await self._submit(
                headers={"Authorization": f"Bearer {self._bearer}",
                         "Idempotency-Key": f"probe-burst-{uuid.uuid4()}"},
                body=body,
            )

        responses = await asyncio.gather(*[one() for _ in range(120)], return_exceptions=True)
        codes = [r.status_code for r in responses if isinstance(r, httpx.Response)]
        # Pass iff at least one 429 in the burst.
        last = max(codes, default=0, key=lambda c: 1 if c == 429 else 0)
        synthetic = httpx.Response(429 if 429 in codes else (last or 0),
                                   request=httpx.Request("POST", self._tx_path))
        return self._outcome(
            probe, synthetic,
            note=f"observed codes: {sorted(set(codes))}",
        )

    async def _run_payload_fuzz(self, probe: SecurityProbe, variant: str) -> ProbeOutcome:
        body = fuzz_body(variant)
        response = await self._submit(
            headers={"Authorization": f"Bearer {self._bearer}",
                     "Idempotency-Key": f"probe-fuzz-{uuid.uuid4()}"},
            body=body,
        )
        return self._outcome(probe, response, note=f"variant={variant}")


def total_probe_count() -> int:
    """Sanity check used by the audit writer."""
    return len(all_probes())
