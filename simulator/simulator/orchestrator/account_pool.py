"""Deterministic synthetic account pool.

Given a seed, ``build_account_pool`` always produces the same accounts in
the same order — that is the foundation of the determinism contract
(plan §7 / §9). All randomness routes through one ``random.Random`` instance
so adding a new bucket doesn't shift unrelated accounts.

Account age buckets (default profile):

- ``new``:     0 – 10  days   (HIGH_AMOUNT_NEW_ACCOUNT-eligible)
- ``young``:  11 – 30  days   (HIGH_AMOUNT_NEW_ACCOUNT-eligible)
- ``aged``:   31 – 365 days
- ``veteran``:    > 365 days

The pool distributes a fixed share of accounts whose ``ipCountry`` differs
from ``country`` (cross-border share) so CROSS_BORDER_HIGH_VALUE has a
natural positive class to fire on without us hand-constructing it.
"""

from __future__ import annotations

import random
from collections.abc import Mapping
from dataclasses import dataclass

from simulator.transport.models import Channel

# ISO-3166-1 alpha-2 codes used for cross-border simulation.
_CROSS_BORDER_COUNTRIES: tuple[str, ...] = ("US", "GB", "NG", "AE", "IN")


@dataclass(frozen=True, slots=True)
class SimulatedAccount:
    """A single simulated bank account; one persona drives each."""

    account_id: str
    account_age_days: int
    home_country: str
    ip_country: str
    primary_channel: Channel
    device_id: str

    @property
    def is_cross_border(self) -> bool:
        return self.home_country != self.ip_country


def _age_for_bucket(rng: random.Random, bucket: str) -> int:
    """Return a random age that lands inside the named bucket."""
    match bucket:
        case "new":
            return rng.randint(0, 10)
        case "young":
            return rng.randint(11, 30)
        case "aged":
            return rng.randint(31, 365)
        case "veteran":
            return rng.randint(366, 365 * 5)
    raise ValueError(f"unknown account-age bucket: {bucket}")


def build_account_pool(
    *,
    count: int,
    seed: int,
    age_buckets: Mapping[str, float],
    primary_country: str = "ZA",
    cross_border_share: float = 0.10,
    channels: list[Channel] | None = None,
) -> list[SimulatedAccount]:
    """Construct a deterministic pool of ``count`` simulated accounts.

    Args:
        count: total accounts (default profile: 100).
        seed: ``random.Random`` seed; same seed → identical pool every time.
        age_buckets: mapping of bucket name → share (must sum to ~1.0).
        primary_country: ISO-3166-1 alpha-2 for "home" customers.
        cross_border_share: fraction of accounts whose ``ipCountry`` differs.
        channels: allowed primary channels (defaults to all engine channels).

    Returns:
        A list of ``SimulatedAccount`` instances, ordered by ``account_id``
        for stability.
    """
    if count <= 0:
        raise ValueError("count must be positive")

    bucket_total = sum(age_buckets.values())
    if bucket_total <= 0:
        raise ValueError("age_buckets must have positive share")

    rng = random.Random(seed)  # noqa: S311 — synthetic test data, not crypto
    channels = channels or list(Channel)

    # Allocate ints per bucket via largest-remainder rounding so totals match `count`.
    allocations: dict[str, int] = {}
    fractional: list[tuple[float, str]] = []
    assigned = 0
    for name, share in age_buckets.items():
        ideal = (share / bucket_total) * count
        whole = int(ideal)
        allocations[name] = whole
        assigned += whole
        fractional.append((ideal - whole, name))
    # Distribute the residual across the largest-fractional buckets.
    fractional.sort(reverse=True)
    for _, name in fractional[: count - assigned]:
        allocations[name] = allocations.get(name, 0) + 1

    accounts: list[SimulatedAccount] = []
    serial = 0
    for bucket, n in allocations.items():
        for _ in range(n):
            serial += 1
            account_id = f"ACC-SIM-{serial:04d}"
            age = _age_for_bucket(rng, bucket)
            channel = rng.choice(channels)
            device_id = f"dev-{rng.randrange(1, 1_000_000):06d}"
            if rng.random() < cross_border_share:
                ip_country = rng.choice(_CROSS_BORDER_COUNTRIES)
            else:
                ip_country = primary_country
            accounts.append(
                SimulatedAccount(
                    account_id=account_id,
                    account_age_days=age,
                    home_country=primary_country,
                    ip_country=ip_country,
                    primary_channel=channel,
                    device_id=device_id,
                )
            )

    accounts.sort(key=lambda a: a.account_id)
    return accounts
