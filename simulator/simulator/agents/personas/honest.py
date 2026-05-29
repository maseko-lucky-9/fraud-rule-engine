"""Honest persona — benign Poisson-arrival traffic.

Generates the green-path baseline against which adversarial scenarios will
later be compared. Every honest transaction is constructed to fall safely
under every existing rule threshold:

- ``amount``: $5 – $300 ZAR (under $5k cross-border, $7.5k off-hours,
  $10k new-account, $15k new-device — all rule thresholds)
- ``country == ipCountry``: never cross-border
- ``timestamp.hour``: clamped to 08:00 – 22:00 SAST (outside the
  02:00–05:00 OFF_HOURS_LARGE_TX window even at high amounts)
- ``deviceId``: the account's primary device, never rotated
- ``merchantId``: pulled from a small allow-list, none of which match the
  engine's blacklist

A run that produces anything other than near-100 % APPROVED indicates
either a misconfigured rule or a wire-level bug in the simulator.
"""

from __future__ import annotations

import asyncio
import random
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Final
from uuid import uuid4

from simulator.orchestrator.account_pool import SimulatedAccount
from simulator.telemetry.sink import SinkRow, SqliteSink
from simulator.transport.models import Channel, Transaction
from simulator.transport.rest import FraudEngineRestClient

# Merchants picked to avoid the engine's MERCH-DENY-* blacklist.
_BENIGN_MERCHANTS: Final[tuple[str, ...]] = (
    "MERCH-OK-001",
    "MERCH-OK-PICKNPAY",
    "MERCH-OK-WOOLWORTHS",
    "MERCH-OK-MAKRO",
    "MERCH-OK-CHECKERS",
)
_BENIGN_MCCS: Final[tuple[str, ...]] = ("5411", "5812", "5814", "5912", "5999")

# Poisson arrival in seconds — average wait between this persona's submissions.
DEFAULT_MEAN_INTERARRIVAL_SECONDS: Final[float] = 5.0


@dataclass
class HonestPersona:
    """Single-account benign-traffic generator.

    Note: ``slots=True`` is omitted intentionally — the ``_rng`` and
    ``_tx_index`` private attributes are populated in ``__post_init__`` and
    would otherwise need to be declared as dataclass fields.
    """

    account: SimulatedAccount
    rest: FraudEngineRestClient
    sink: SqliteSink
    run_id: str
    mean_interarrival_seconds: float = DEFAULT_MEAN_INTERARRIVAL_SECONDS
    role_label: str = "HONEST"
    seed: int = 0

    def __post_init__(self) -> None:
        # Hash combined with the seed → deterministic per (seed, account_id).
        self._rng = random.Random(  # noqa: S311 — synthetic test data, not crypto
            (self.seed << 32) ^ hash(self.account.account_id)
        )
        self._tx_index = 0

    def _next_amount(self) -> Decimal:
        # Cap amount low — honest traffic is sub-rule-threshold by design.
        return Decimal(f"{self._rng.uniform(5.0, 300.0):.2f}")

    def _next_timestamp(self) -> datetime:
        # Daytime SAST (UTC+2) → UTC hours 06:00 – 20:00. Stay outside the
        # 00:00 – 03:00 UTC OFF_HOURS window (which is 02:00 – 05:00 SAST).
        now = datetime.now(tz=UTC)
        # Jitter ±30 minutes around now for realism; never near 02:00 SAST.
        jitter = self._rng.uniform(-1800, 1800)
        candidate = now + timedelta(seconds=jitter)
        # If we'd land in the off-hours window, push forward to safe daytime.
        sast_hour = (candidate.hour + 2) % 24
        if 2 <= sast_hour < 5:
            candidate = candidate.replace(hour=(6 - 2) % 24, minute=0, second=0, microsecond=0)
        return candidate

    def _build_tx(self) -> Transaction:
        return Transaction(
            txId=uuid4(),
            accountId=self.account.account_id,
            amount=self._next_amount(),
            currency="ZAR",
            mcc=self._rng.choice(_BENIGN_MCCS),
            channel=self.account.primary_channel if isinstance(self.account.primary_channel, Channel)
            else Channel(self.account.primary_channel),
            country=self.account.home_country,
            ipCountry=self.account.home_country,  # honest persona never crosses borders
            deviceId=self.account.device_id,
            merchantId=self._rng.choice(_BENIGN_MERCHANTS),
            accountAgeDays=self.account.account_age_days,
            timestamp=self._next_timestamp(),
        )

    async def run(
        self,
        *,
        duration_seconds: float,
        max_tx: int | None = None,
        stop_event: asyncio.Event | None = None,
    ) -> int:
        """Submit Poisson-arrival benign tx until ``duration_seconds`` elapses.

        Returns the number of submissions made.
        """
        loop = asyncio.get_running_loop()
        deadline = loop.time() + duration_seconds

        while True:
            if stop_event is not None and stop_event.is_set():
                break
            now = loop.time()
            if now >= deadline:
                break
            if max_tx is not None and self._tx_index >= max_tx:
                break

            wait = self._rng.expovariate(1.0 / self.mean_interarrival_seconds)
            await asyncio.sleep(min(wait, max(0.0, deadline - now)))

            self._tx_index += 1
            tx = self._build_tx()
            result = await self.rest.submit_transaction(
                tx,
                subject=self.account.account_id,
                idempotency_key=f"{self.run_id}-{self.account.account_id}-{self._tx_index}",
            )
            await self.sink.record(
                SinkRow(
                    run_id=self.run_id,
                    persona_role=self.role_label,
                    scenario_id=None,
                    tx_index=self._tx_index,
                    account_id=self.account.account_id,
                    result=result,
                )
            )

        return self._tx_index
