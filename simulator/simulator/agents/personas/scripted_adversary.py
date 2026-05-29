"""Scripted-adversary persona — walks a pre-built FraudScenario step-by-step."""

from __future__ import annotations

from dataclasses import dataclass

from simulator.scenarios import FraudScenario
from simulator.telemetry.sink import SinkRow, SqliteSink
from simulator.transport.rest import FraudEngineRestClient


@dataclass
class ScriptedAdversaryPersona:
    """Replays a deterministic FraudScenario through the REST transport.

    Unlike :class:`HonestPersona`, every transaction in the sequence is
    fully specified up-front. The persona's job is just to submit them in
    order and write the result rows to the sink.
    """

    scenario: FraudScenario
    rest: FraudEngineRestClient
    sink: SqliteSink
    run_id: str
    subject: str
    role_label: str = "ADVERSARY_SCRIPTED"

    async def run(self) -> int:
        for index, step in enumerate(self.scenario.steps):
            result = await self.rest.submit_transaction(
                step.tx,
                subject=self.subject,
                idempotency_key=f"{self.run_id}-{self.scenario.id}-{index}",
            )
            await self.sink.record(
                SinkRow(
                    run_id=self.run_id,
                    persona_role=self.role_label,
                    scenario_id=self.scenario.id,
                    tx_index=index,
                    account_id=step.tx.accountId,
                    result=result,
                )
            )
        return len(self.scenario.steps)
