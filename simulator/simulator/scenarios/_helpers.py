"""Shared helpers for scenario builders — keeps each scenario file tight."""

from __future__ import annotations

import random
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID, uuid5

from simulator.transport.models import Channel, Transaction

# Stable namespace so txId values within a scenario are deterministic for a
# given (scenario_id, step_index, seed). UUID5 over a namespace.
_NS = UUID("00000000-0000-0000-0000-cafebabef00d")


def deterministic_tx_id(scenario_id: str, step_index: int, seed: int) -> UUID:
    return uuid5(_NS, f"{scenario_id}|{step_index}|{seed}")


def base_time(*, hour_sast: int = 12, minute: int = 0) -> datetime:
    """Anchor timestamp for scenarios. SAST is UTC+2 so we subtract two."""
    return datetime(2026, 5, 28, (hour_sast - 2) % 24, minute, tzinfo=UTC)


def benign_tx(
    *,
    scenario_id: str,
    step: int,
    seed: int,
    account_id: str,
    amount: Decimal,
    timestamp: datetime,
    country: str = "ZA",
    ip_country: str | None = None,
    device_id: str = "dev-1",
    merchant_id: str = "MERCH-OK-001",
    account_age_days: int = 180,
    channel: Channel = Channel.MOBILE,
    currency: str = "ZAR",
    mcc: str = "5411",
) -> Transaction:
    """Build a deterministic Transaction for a scenario step."""
    return Transaction(
        txId=deterministic_tx_id(scenario_id, step, seed),
        accountId=account_id,
        amount=amount,
        currency=currency,
        mcc=mcc,
        channel=channel,
        country=country,
        ipCountry=ip_country or country,
        deviceId=device_id,
        merchantId=merchant_id,
        accountAgeDays=account_age_days,
        timestamp=timestamp,
    )


def stable_rng(seed: int, salt: str) -> random.Random:
    """RNG seeded jointly with the run seed and a scenario-local salt."""
    return random.Random(  # noqa: S311 — synthetic test data
        seed ^ hash(salt) & 0xFFFFFFFFFFFFFFFF
    )


def minutes(n: int) -> timedelta:
    return timedelta(minutes=n)


def hours(n: int) -> timedelta:
    return timedelta(hours=n)
