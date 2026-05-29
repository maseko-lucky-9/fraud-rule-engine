"""Materialise an LLM-generated scenario spec into a replayable FraudScenario.

:func:`generate_scenarios` (the Ollama seed expansion) yields
:class:`GeneratedScenarioSpec` objects — abstract shapes (account_count,
tx_per_account, amount band, cadence). The run loop needs concrete
:class:`Transaction` steps, so this is the missing converter the scenario
docstring promised ("the runner turns each spec into actual Transaction
objects").

The output is deterministic for a given ``(spec.id, seed)`` and volume-capped
so a single hallucinated 50×100 spec can't blow the run's tx budget.
"""

from __future__ import annotations

from datetime import timedelta
from decimal import Decimal

from simulator.llm.scenario_generator import GeneratedScenarioSpec
from simulator.scenarios import FraudScenario, ScenarioStep
from simulator.scenarios._helpers import base_time, benign_tx, stable_rng
from simulator.transport.models import Channel

DEFAULT_MAX_STEPS: int = 200


def spec_to_fraud_scenario(
    spec: GeneratedScenarioSpec,
    *,
    seed: int,
    max_steps: int = DEFAULT_MAX_STEPS,
) -> FraudScenario:
    """Turn one ``GeneratedScenarioSpec`` into a deterministic FraudScenario.

    Emits ``account_count × tx_per_account`` steps (capped at ``max_steps``),
    each a benign-shaped transaction with the spec's amount band and cadence.
    Amounts are drawn from a per-spec deterministic RNG so two runs with the
    same seed replay identically.
    """
    rng = stable_rng(seed, salt=spec.id)
    start = base_time()
    low = float(spec.amount_low_zar)
    high = float(spec.amount_high_zar)

    steps: list[ScenarioStep] = []
    step_index = 0
    for account_index in range(spec.account_count):
        if step_index >= max_steps:
            break
        account_id = f"llm-{spec.id}-{account_index:03d}"
        device_id = f"dev-{spec.id}-{account_index:03d}"
        for tx_index in range(spec.tx_per_account):
            if step_index >= max_steps:
                break
            amount = Decimal(f"{rng.uniform(low, high):.2f}")
            timestamp = start + timedelta(seconds=spec.cadence_seconds * tx_index)
            steps.append(
                ScenarioStep(
                    tx=benign_tx(
                        scenario_id=spec.id,
                        step=step_index,
                        seed=seed,
                        account_id=account_id,
                        amount=amount,
                        timestamp=timestamp,
                        device_id=device_id,
                        channel=Channel.WEB,
                    ),
                )
            )
            step_index += 1

    if not steps:
        # spec validation guarantees account_count>=1 and tx_per_account>=1,
        # so this only trips if max_steps <= 0 — guard so FraudScenario's
        # empty-steps invariant raises a clearer error here instead.
        raise ValueError(f"spec {spec.id} produced no steps (max_steps={max_steps})")

    return FraudScenario(
        id=spec.id,
        archetype=spec.archetype,
        targets_rule=None,
        expected_outcome=spec.expected_outcome,
        description=spec.description,
        steps=tuple(steps),
    )
