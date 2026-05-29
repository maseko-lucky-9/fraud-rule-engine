"""Rule-coverage scenarios — positive, negative, boundary per engine rule.

Each engine rule gets three scenarios:

- ``<rule>_positive``: payload that MUST trip the rule (we expect ``detected``).
- ``<rule>_negative``: payload that must NOT trip the rule (we expect
  ``miss``; matched_rules empty for *this rule*).
- ``<rule>_boundary``: payload one tick under/over the threshold (we expect
  the documented behaviour — ``miss`` for one-under, ``detected`` for
  exactly-at depending on the predicate).

Computed acceptance: per-rule recall on the positive scenarios must be >= 0.95.
"""

from __future__ import annotations

from collections.abc import Iterable
from decimal import Decimal

from . import FraudScenario, ScenarioStep
from ._helpers import base_time, benign_tx, hours

# -------- Engine rule fixtures --------------------------------------------- #
# Mirror the constants in src/main/resources/rules/rule-set-v1.yml.

_BLACKLISTED = "MERCH-DENY-001"
_OFF_HOURS_TS = base_time(hour_sast=3, minute=15)         # inside 02:00–05:00 SAST window
_DAYTIME_TS = base_time(hour_sast=12)
_HIGH_AMOUNT_NEW_ACCOUNT_AMT = Decimal("12500.00")        # > $10k
_NEW_DEVICE_HIGH_AMT = Decimal("16000.00")                # > $15k
_CROSS_BORDER_HIGH = Decimal("5500.00")                   # > $5k
_OFF_HOURS_HIGH = Decimal("8000.00")                      # > $7.5k


def build_rule_coverage_scenarios(*, seed: int) -> Iterable[FraudScenario]:
    """Yield one FraudScenario per (rule, classification) cell."""
    yield from (
        _blacklisted_merchant_positive(seed),
        _blacklisted_merchant_negative(seed),
        _velocity_burst_positive(seed),
        _velocity_burst_negative(seed),
        _high_amount_new_account_positive(seed),
        _high_amount_new_account_boundary(seed),
        _high_amount_new_account_negative(seed),
        _new_device_high_amount_positive(seed),
        _new_device_high_amount_negative(seed),
        _cross_border_high_value_positive(seed),
        _cross_border_high_value_negative(seed),
        _off_hours_large_tx_positive(seed),
        _off_hours_large_tx_negative(seed),
    )


# ----- BLACKLISTED_MERCHANT ----------------------------------------------- #

def _blacklisted_merchant_positive(seed: int) -> FraudScenario:
    sid = "rule_coverage_blacklisted_merchant_positive"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="BLACKLISTED_MERCHANT",
        expected_outcome="detected",
        description="Single tx to a blacklisted merchant must BLOCK with score 1.0.",
        steps=(
            ScenarioStep(
                tx=benign_tx(
                    scenario_id=sid, step=0, seed=seed, account_id="ACC-RC-BL-1",
                    amount=Decimal("100.00"), timestamp=_DAYTIME_TS,
                    merchant_id=_BLACKLISTED,
                ),
                expected_rules=("BLACKLISTED_MERCHANT",),
            ),
        ),
    )


def _blacklisted_merchant_negative(seed: int) -> FraudScenario:
    sid = "rule_coverage_blacklisted_merchant_negative"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="BLACKLISTED_MERCHANT",
        expected_outcome="miss",
        description="Non-blacklisted merchant must not trip the rule.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-BL-2",
                             amount=Decimal("100.00"), timestamp=_DAYTIME_TS,
                             merchant_id="MERCH-OK-PICKNPAY"),
            ),
        ),
    )


# ----- VELOCITY_BURST ----------------------------------------------------- #

def _velocity_burst_positive(seed: int) -> FraudScenario:
    sid = "rule_coverage_velocity_burst_positive"
    steps: list[ScenarioStep] = []
    # 6 txs from the same account, timestamp irrelevant (engine uses wall-clock window).
    for i in range(6):
        expected = ("VELOCITY_BURST",) if i >= 5 else ()
        steps.append(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=i, seed=seed,
                             account_id="ACC-RC-VEL-1",
                             amount=Decimal("75.00"), timestamp=_DAYTIME_TS),
                expected_rules=expected,
            )
        )
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="VELOCITY_BURST",
        expected_outcome="detected",
        description="6 txs from same account within 60s → 6th must trip VELOCITY_BURST.",
        steps=tuple(steps),
    )


def _velocity_burst_negative(seed: int) -> FraudScenario:
    sid = "rule_coverage_velocity_burst_negative"
    # 5 txs is below the >5 threshold.
    steps = tuple(
        ScenarioStep(
            tx=benign_tx(scenario_id=sid, step=i, seed=seed,
                         account_id="ACC-RC-VEL-2",
                         amount=Decimal("75.00"), timestamp=_DAYTIME_TS),
        )
        for i in range(5)
    )
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="VELOCITY_BURST",
        expected_outcome="miss",
        description="5 txs from same account — under the >5 count threshold.",
        steps=steps,
    )


# ----- HIGH_AMOUNT_NEW_ACCOUNT ------------------------------------------- #

def _high_amount_new_account_positive(seed: int) -> FraudScenario:
    sid = "rule_coverage_high_amount_new_account_positive"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="HIGH_AMOUNT_NEW_ACCOUNT",
        expected_outcome="detected",
        description="$12.5k tx on a 7-day-old account must REVIEW.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-HA-1",
                             amount=_HIGH_AMOUNT_NEW_ACCOUNT_AMT,
                             timestamp=_DAYTIME_TS, account_age_days=7),
                expected_rules=("HIGH_AMOUNT_NEW_ACCOUNT",),
            ),
        ),
    )


def _high_amount_new_account_boundary(seed: int) -> FraudScenario:
    sid = "rule_coverage_high_amount_new_account_boundary"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="HIGH_AMOUNT_NEW_ACCOUNT",
        expected_outcome="miss",
        description="$10k exactly on 30-day-old account — both predicates strictly above.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-HA-2",
                             amount=Decimal("10000.00"),
                             timestamp=_DAYTIME_TS, account_age_days=30),
            ),
        ),
    )


def _high_amount_new_account_negative(seed: int) -> FraudScenario:
    sid = "rule_coverage_high_amount_new_account_negative"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="HIGH_AMOUNT_NEW_ACCOUNT",
        expected_outcome="miss",
        description="High amount on a veteran (180-day) account — age predicate misses.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-HA-3",
                             amount=_HIGH_AMOUNT_NEW_ACCOUNT_AMT,
                             timestamp=_DAYTIME_TS, account_age_days=180),
            ),
        ),
    )


# ----- NEW_DEVICE_HIGH_AMOUNT -------------------------------------------- #

def _new_device_high_amount_positive(seed: int) -> FraudScenario:
    sid = "rule_coverage_new_device_high_amount_positive"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="NEW_DEVICE_HIGH_AMOUNT",
        expected_outcome="detected",
        description="$16k from an unseen deviceId must REVIEW (NEW_DEVICE_HIGH_AMOUNT).",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-ND-1",
                             amount=_NEW_DEVICE_HIGH_AMT,
                             timestamp=_DAYTIME_TS,
                             device_id="dev-brand-new-001"),
                expected_rules=("NEW_DEVICE_HIGH_AMOUNT",),
            ),
        ),
    )


def _new_device_high_amount_negative(seed: int) -> FraudScenario:
    sid = "rule_coverage_new_device_high_amount_negative"
    # Two transactions — second from the same device (now seen) with high amount.
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="NEW_DEVICE_HIGH_AMOUNT",
        expected_outcome="miss",
        description="Device seen before — subsequent high-amount tx must not trip.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-ND-2",
                             amount=Decimal("100.00"),
                             timestamp=_DAYTIME_TS, device_id="dev-known-001"),
            ),
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=1, seed=seed,
                             account_id="ACC-RC-ND-2",
                             amount=_NEW_DEVICE_HIGH_AMT,
                             timestamp=_DAYTIME_TS + hours(1),
                             device_id="dev-known-001"),
            ),
        ),
    )


# ----- CROSS_BORDER_HIGH_VALUE ------------------------------------------- #

def _cross_border_high_value_positive(seed: int) -> FraudScenario:
    sid = "rule_coverage_cross_border_high_value_positive"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="CROSS_BORDER_HIGH_VALUE",
        expected_outcome="detected",
        description="$5.5k tx with country!=ipCountry must trip CROSS_BORDER_HIGH_VALUE.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-CB-1",
                             amount=_CROSS_BORDER_HIGH,
                             timestamp=_DAYTIME_TS,
                             country="ZA", ip_country="US"),
                expected_rules=("CROSS_BORDER_HIGH_VALUE",),
            ),
        ),
    )


def _cross_border_high_value_negative(seed: int) -> FraudScenario:
    sid = "rule_coverage_cross_border_high_value_negative"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="CROSS_BORDER_HIGH_VALUE",
        expected_outcome="miss",
        description="High amount but country == ipCountry — geo predicate misses.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-CB-2",
                             amount=_CROSS_BORDER_HIGH, timestamp=_DAYTIME_TS,
                             country="ZA", ip_country="ZA"),
            ),
        ),
    )


# ----- OFF_HOURS_LARGE_TX ------------------------------------------------ #

def _off_hours_large_tx_positive(seed: int) -> FraudScenario:
    sid = "rule_coverage_off_hours_large_tx_positive"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="OFF_HOURS_LARGE_TX",
        expected_outcome="detected",
        description="$8k tx at 03:15 SAST must trip OFF_HOURS_LARGE_TX.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-OH-1",
                             amount=_OFF_HOURS_HIGH, timestamp=_OFF_HOURS_TS),
                expected_rules=("OFF_HOURS_LARGE_TX",),
            ),
        ),
    )


def _off_hours_large_tx_negative(seed: int) -> FraudScenario:
    sid = "rule_coverage_off_hours_large_tx_negative"
    return FraudScenario(
        id=sid, archetype="rule_coverage", targets_rule="OFF_HOURS_LARGE_TX",
        expected_outcome="miss",
        description="High amount but at 12:00 SAST — outside the off-hours window.",
        steps=(
            ScenarioStep(
                tx=benign_tx(scenario_id=sid, step=0, seed=seed,
                             account_id="ACC-RC-OH-2",
                             amount=_OFF_HOURS_HIGH, timestamp=_DAYTIME_TS),
            ),
        ),
    )
