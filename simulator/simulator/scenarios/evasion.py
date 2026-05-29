"""Adversarial-evasion scenarios — the canonical gap-probes.

Each scenario probes a documented engine gap. The expected_outcome is what
the engine produces TODAY (per plan §5). If a future engine version closes
a gap, the corresponding scenario flips from ``miss`` → ``detected`` and
the audit highlights the improvement.
"""

from __future__ import annotations

from decimal import Decimal

from . import FraudScenario, ScenarioStep
from ._helpers import base_time, benign_tx, hours, minutes


def build_card_testing(*, seed: int) -> FraudScenario:
    """Distributed micro-tx across 20 accounts — evades per-account VELOCITY_BURST."""
    sid = "evasion_card_testing"
    base = base_time(hour_sast=14)
    steps: list[ScenarioStep] = []
    for acc_n in range(20):
        for tx_n in range(10):  # 10 per account × 20 accounts = 200 micro-tx
            steps.append(
                ScenarioStep(
                    tx=benign_tx(
                        scenario_id=sid, step=len(steps), seed=seed,
                        account_id=f"ACC-EV-CT-{acc_n:02d}",
                        amount=Decimal("0.99"),
                        timestamp=base + minutes(tx_n),
                        device_id=f"dev-CT-{acc_n:02d}",
                        merchant_id="MERCH-OK-WEB-001",
                    ),
                )
            )
    return FraudScenario(
        id=sid, archetype="card_testing", targets_rule="VELOCITY_BURST",
        expected_outcome="miss",
        description=(
            "20 accounts × 10 transactions of $0.99 each. VELOCITY_BURST is "
            "per-account (>5 in 60s); distribution across accounts evades it. "
            "Classic BIN-attack / card-validation pattern."
        ),
        steps=tuple(steps),
        citations=("https://www.chargeflow.io/chargebacks-101/card-testing",),
    )


def build_structuring(*, seed: int) -> FraudScenario:
    """Single account: $9,999 every 90 min — slips under HIGH_AMOUNT cutoff."""
    sid = "evasion_structuring"
    base = base_time(hour_sast=10)
    steps = tuple(
        ScenarioStep(
            tx=benign_tx(
                scenario_id=sid, step=i, seed=seed,
                account_id="ACC-EV-STR-1",
                amount=Decimal("9999.00"),
                timestamp=base + hours(i * 90 // 60) + minutes(i * 90 % 60),
                account_age_days=15,  # younger account intensifies the miss
                merchant_id="MERCH-OK-001",
            ),
        )
        for i in range(8)
    )
    return FraudScenario(
        id=sid, archetype="structuring", targets_rule="HIGH_AMOUNT_NEW_ACCOUNT",
        expected_outcome="miss",
        description=(
            "$9,999 transactions every 90 minutes on a 15-day-old account. "
            "HIGH_AMOUNT_NEW_ACCOUNT triggers at >$10,000 — each transaction "
            "is sub-threshold. AML structuring/smurfing pattern."
        ),
        steps=steps,
        citations=("https://www.fraud.net/glossary/smurfing-structuring",),
    )


def build_ato_geo_leap(*, seed: int) -> FraudScenario:
    """ZA→US in 30 minutes, new device. CROSS_BORDER alone fires only on amount > $5k."""
    sid = "evasion_ato_geo_leap"
    base = base_time(hour_sast=14)
    return FraudScenario(
        id=sid, archetype="account_takeover", targets_rule="CROSS_BORDER_HIGH_VALUE",
        expected_outcome="partial",
        description=(
            "Same accountId: ZA tx at t=0, then US tx 30 min later with a new "
            "device. Engine has no time-distance impossibility rule; only "
            "CROSS_BORDER_HIGH_VALUE catches the geo shift, and only if "
            "amount > $5k. With $4k amount this slips silently."
        ),
        steps=(
            ScenarioStep(
                tx=benign_tx(
                    scenario_id=sid, step=0, seed=seed,
                    account_id="ACC-EV-ATO-1",
                    amount=Decimal("100.00"), timestamp=base,
                    country="ZA", ip_country="ZA", device_id="dev-known-1",
                ),
            ),
            ScenarioStep(
                tx=benign_tx(
                    scenario_id=sid, step=1, seed=seed,
                    account_id="ACC-EV-ATO-1",
                    amount=Decimal("4000.00"),
                    timestamp=base + minutes(30),
                    country="ZA", ip_country="US",   # post-compromise
                    device_id="dev-new-after-ato",
                    merchant_id="MERCH-US-ECOM-001",
                ),
            ),
        ),
    )


def build_synthetic_identity(*, seed: int) -> FraudScenario:
    """accountAgeDays=31 → one-off-by-one bypass of the 30-day cutoff."""
    sid = "evasion_synthetic_identity"
    return FraudScenario(
        id=sid, archetype="synthetic_identity", targets_rule="HIGH_AMOUNT_NEW_ACCOUNT",
        expected_outcome="miss",
        description=(
            "$50,000 tx on a 31-day-old account. HIGH_AMOUNT_NEW_ACCOUNT "
            "predicate is `accountAgeBelow: 30` (strict). Day 31 evades."
        ),
        steps=(
            ScenarioStep(
                tx=benign_tx(
                    scenario_id=sid, step=0, seed=seed,
                    account_id="ACC-EV-SYN-1",
                    amount=Decimal("50000.00"),
                    timestamp=base_time(hour_sast=12),
                    account_age_days=31,
                ),
            ),
        ),
    )


def build_device_entropy(*, seed: int) -> FraudScenario:
    """6 fresh deviceIds in 1 hour, all sub-$15k → no per-account device-velocity rule fires."""
    sid = "evasion_device_entropy"
    base = base_time(hour_sast=13)
    steps = tuple(
        ScenarioStep(
            tx=benign_tx(
                scenario_id=sid, step=i, seed=seed,
                account_id="ACC-EV-DEV-1",
                amount=Decimal("14999.00"),
                timestamp=base + minutes(i * 10),
                device_id=f"dev-rotating-{i:02d}",
            ),
        )
        for i in range(6)
    )
    return FraudScenario(
        id=sid, archetype="device_entropy", targets_rule="NEW_DEVICE_HIGH_AMOUNT",
        expected_outcome="miss",
        description=(
            "Single account uses 6 distinct deviceIds within 1 hour, each tx "
            "$14,999 (just below NEW_DEVICE_HIGH_AMOUNT's $15k cutoff). "
            "Bot-driven account-farming signature."
        ),
        steps=steps,
    )


def build_geo_impossibility(*, seed: int) -> FraudScenario:
    """ZA→UK 30 min apart — physically impossible. No rule today; we expect miss."""
    sid = "evasion_geo_impossibility"
    base = base_time(hour_sast=15)
    return FraudScenario(
        id=sid, archetype="geo_impossibility", targets_rule=None,
        expected_outcome="miss",
        description=(
            "Same account in ZA at t=0, in UK 30 min later. No commercial "
            "flight or even time-zone shift explains the leap. Engine has no "
            "time-vs-distance impossibility rule; both tx will go through."
        ),
        steps=(
            ScenarioStep(
                tx=benign_tx(
                    scenario_id=sid, step=0, seed=seed,
                    account_id="ACC-EV-GEO-1",
                    amount=Decimal("200.00"), timestamp=base,
                    country="ZA", ip_country="ZA",
                ),
            ),
            ScenarioStep(
                tx=benign_tx(
                    scenario_id=sid, step=1, seed=seed,
                    account_id="ACC-EV-GEO-1",
                    amount=Decimal("250.00"),
                    timestamp=base + minutes(30),
                    country="ZA", ip_country="GB",
                ),
            ),
        ),
    )
