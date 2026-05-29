"""Determinism + distribution tests for the account pool."""

from __future__ import annotations

from collections import Counter

import pytest

from simulator.orchestrator.account_pool import (
    SimulatedAccount,
    build_account_pool,
)
from simulator.transport.models import Channel

DEFAULT_BUCKETS = {"new": 0.25, "young": 0.20, "aged": 0.35, "veteran": 0.20}


def test_pool_is_deterministic_per_seed() -> None:
    pool_a = build_account_pool(count=100, seed=42, age_buckets=DEFAULT_BUCKETS)
    pool_b = build_account_pool(count=100, seed=42, age_buckets=DEFAULT_BUCKETS)
    assert pool_a == pool_b


def test_different_seeds_produce_different_pools() -> None:
    pool_a = build_account_pool(count=100, seed=42, age_buckets=DEFAULT_BUCKETS)
    pool_b = build_account_pool(count=100, seed=43, age_buckets=DEFAULT_BUCKETS)
    # At least one account must differ; identical pools would be a determinism bug.
    assert pool_a != pool_b


def test_pool_size_matches_count() -> None:
    pool = build_account_pool(count=100, seed=42, age_buckets=DEFAULT_BUCKETS)
    assert len(pool) == 100


def test_account_ids_unique_and_stable_order() -> None:
    pool = build_account_pool(count=100, seed=42, age_buckets=DEFAULT_BUCKETS)
    ids = [a.account_id for a in pool]
    assert len(set(ids)) == 100
    assert ids == sorted(ids)  # post-sort stability per plan §9


def test_age_bucket_distribution() -> None:
    pool = build_account_pool(count=200, seed=42, age_buckets=DEFAULT_BUCKETS)
    buckets = Counter()
    for acc in pool:
        if acc.account_age_days <= 10:
            buckets["new"] += 1
        elif acc.account_age_days <= 30:
            buckets["young"] += 1
        elif acc.account_age_days <= 365:
            buckets["aged"] += 1
        else:
            buckets["veteran"] += 1

    # Largest-remainder rounding gives an exact match for nice totals.
    assert buckets["new"] == 50
    assert buckets["young"] == 40
    assert buckets["aged"] == 70
    assert buckets["veteran"] == 40


def test_cross_border_share_within_tolerance() -> None:
    pool = build_account_pool(count=1000, seed=42, age_buckets=DEFAULT_BUCKETS,
                              cross_border_share=0.10)
    cross_border = sum(1 for a in pool if a.is_cross_border)
    # 10 % of 1000 = 100; allow ±3 sigma binomial slack.
    assert 70 <= cross_border <= 130


def test_channels_distribute() -> None:
    pool = build_account_pool(count=500, seed=42, age_buckets=DEFAULT_BUCKETS,
                              channels=[Channel.WEB, Channel.MOBILE, Channel.POS])
    channels = {a.primary_channel for a in pool}
    # All three should be observed in 500 draws (probability of missing one
    # is < 1e-50 for a uniform 3-way choice).
    assert channels == {Channel.WEB, Channel.MOBILE, Channel.POS}


def test_invalid_count_rejected() -> None:
    with pytest.raises(ValueError, match="count"):
        build_account_pool(count=0, seed=42, age_buckets=DEFAULT_BUCKETS)


def test_invalid_buckets_rejected() -> None:
    with pytest.raises(ValueError, match="age_buckets"):
        build_account_pool(count=100, seed=42, age_buckets={"new": 0})


def test_simulated_account_is_immutable() -> None:
    acc = SimulatedAccount(
        account_id="ACC-X", account_age_days=10,
        home_country="ZA", ip_country="ZA",
        primary_channel=Channel.MOBILE, device_id="dev-1",
    )
    with pytest.raises((AttributeError, TypeError)):
        acc.account_id = "ACC-Y"  # type: ignore[misc]
