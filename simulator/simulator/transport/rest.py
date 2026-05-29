"""Async REST client for the fraud-rule-engine.

Responsibilities:
- Mint and refresh per-subject JWTs (via :class:`JwtMinter`).
- Submit transactions to ``POST /api/v1/transactions`` with ``Idempotency-Key``.
- Honour ``429 Too Many Requests`` ``Retry-After`` headers.
- Surface every submitted tx + parsed response back to the caller for sink
  persistence (no internal caching of decisions).
"""

from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Final

import httpx

from .auth import JwtMinter, service_api_key_from_env
from .models import DecisionResponse, Transaction

DEFAULT_REQUEST_TIMEOUT: Final[float] = 10.0
MAX_RETRY_ATTEMPTS: Final[int] = 3


@dataclass(slots=True, frozen=True)
class SubmissionResult:
    """One row's worth of sink data."""

    submitted_at: datetime
    received_at: datetime
    http_status: int
    decision: DecisionResponse | None
    idempotency_key: str
    subject: str
    error: str | None = None

    @property
    def latency_ms(self) -> float:
        return (self.received_at - self.submitted_at).total_seconds() * 1000.0


class FraudEngineRestClient:
    """Thin wrapper around ``httpx.AsyncClient`` scoped to one engine instance."""

    def __init__(
        self,
        rest_base_url: str,
        minter: JwtMinter,
        transactions_endpoint: str = "/api/v1/transactions",
        admin_audit_endpoint: str = "/admin/audit",
        request_timeout_seconds: float = DEFAULT_REQUEST_TIMEOUT,
        max_retry_attempts: int = MAX_RETRY_ATTEMPTS,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base = rest_base_url.rstrip("/")
        self._minter = minter
        self._tx_path = transactions_endpoint
        self._audit_path = admin_audit_endpoint
        self._timeout = request_timeout_seconds
        self._max_retry = max_retry_attempts
        self._client = client or httpx.AsyncClient(timeout=request_timeout_seconds)
        self._owns_client = client is None

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def __aenter__(self) -> FraudEngineRestClient:
        return self

    async def __aexit__(self, *_: object) -> None:
        await self.aclose()

    # ------------------------------------------------------------------ #
    # Transaction submission
    # ------------------------------------------------------------------ #

    async def submit_transaction(
        self,
        tx: Transaction,
        subject: str,
        idempotency_key: str | None = None,
    ) -> SubmissionResult:
        """Submit one transaction. Returns the sink row, never raises on 4xx/5xx."""
        idem = idempotency_key or f"sim-{uuid.uuid4()}"
        attempts = 0
        last_error: str | None = None
        last_status = 0

        while attempts < self._max_retry:
            attempts += 1
            try:
                access_token = await self._minter.get(subject, self._client)
            except httpx.HTTPError as exc:
                last_error = f"auth-error: {exc!r}"
                break

            submitted_at = datetime.now(tz=UTC)
            try:
                response = await self._client.post(
                    f"{self._base}{self._tx_path}",
                    headers={
                        "Authorization": f"Bearer {access_token}",
                        "Idempotency-Key": idem,
                        "Content-Type": "application/json",
                    },
                    json=tx.model_dump(mode="json"),
                    timeout=self._timeout,
                )
            except httpx.HTTPError as exc:
                last_error = f"transport-error: {exc!r}"
                last_status = 0
                received_at = datetime.now(tz=UTC)
                # Retry on transport errors with simple back-off.
                await asyncio.sleep(min(2 ** (attempts - 1), 5))
                continue

            received_at = datetime.now(tz=UTC)
            last_status = response.status_code

            if response.status_code == 401:
                # JWT may have rolled over; force-refresh and retry once.
                self._minter.invalidate(subject)
                last_error = "401-refreshing-jwt"
                continue

            if response.status_code == 429:
                # Honour Retry-After (seconds, integer or HTTP-date).
                retry_after = response.headers.get("Retry-After", "1")
                try:
                    wait_seconds = float(retry_after)
                except ValueError:
                    wait_seconds = 1.0
                await asyncio.sleep(min(wait_seconds, 10.0))
                last_error = "429-rate-limited"
                continue

            # Terminal cases — return whatever we got, parse on 2xx.
            decision: DecisionResponse | None = None
            if 200 <= response.status_code < 300:
                try:
                    decision = DecisionResponse.model_validate(response.json())
                except Exception as exc:  # noqa: BLE001 — sink the parse error
                    last_error = f"decision-parse-error: {exc!r}"
            elif response.status_code in (400, 409):
                # Expected-business errors (payload_fuzz, idempotency_collide).
                last_error = response.text[:200]
            else:
                last_error = f"unexpected-status-{response.status_code}: {response.text[:200]}"

            return SubmissionResult(
                submitted_at=submitted_at,
                received_at=received_at,
                http_status=response.status_code,
                decision=decision,
                idempotency_key=idem,
                subject=subject,
                error=last_error,
            )

        # Exhausted retries — synthesise a result row so the sink stays append-only.
        now = datetime.now(tz=UTC)
        return SubmissionResult(
            submitted_at=now,
            received_at=now,
            http_status=last_status,
            decision=None,
            idempotency_key=idem,
            subject=subject,
            error=last_error or "retry-exhausted",
        )

    # ------------------------------------------------------------------ #
    # Admin probes
    # ------------------------------------------------------------------ #

    async def fetch_audit_log(self, page: int = 0, size: int = 50) -> httpx.Response:
        """Hit ``/admin/audit`` via ``X-Service-Api-Key`` header.

        Returns the raw httpx.Response so callers can decide how to parse.
        Skipped (returns 401) when SERVICE_API_KEY env var is unset.
        """
        api_key = service_api_key_from_env()
        if api_key is None:
            # Synthesise a 401-equivalent without hitting the server.
            request = httpx.Request("GET", f"{self._base}{self._audit_path}")
            return httpx.Response(status_code=401, request=request,
                                  content=b'{"error": "SERVICE_API_KEY not set"}')

        return await self._client.get(
            f"{self._base}{self._audit_path}",
            params={"page": page, "size": size},
            headers={"X-Service-Api-Key": api_key},
            timeout=self._timeout,
        )
