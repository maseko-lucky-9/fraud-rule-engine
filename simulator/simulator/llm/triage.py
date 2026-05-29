"""Post-run grey-zone decision triage.

For decisions with ``score`` in ``[0.4, 0.6]`` (configurable), ask Ollama to
classify the transaction into a fraud-pattern bucket. Used by the audit
report to flag scenarios that *almost* tripped a rule.

Triage NEVER overrides the pandas metrics — it only annotates ambiguous
rows in the audit's narrative section. If Ollama is unavailable, triage
returns ``unclassified`` for everything (the report degrades gracefully).
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

from .ollama_client import OllamaWrapper

TriageLabel = Literal[
    "likely_benign",
    "borderline_card_testing",
    "borderline_structuring",
    "borderline_ato",
    "borderline_device_anomaly",
    "borderline_geo_anomaly",
    "unclassified",
]


class TriageResult(BaseModel):
    label: TriageLabel
    confidence: float = Field(ge=0.0, le=1.0)
    rationale: str = Field(max_length=500)


_PROMPT = """\
You are a fraud-detection analyst. Classify this transaction's risk pattern
based on the engine's decision and the surrounding metadata.

Transaction:
  amount: {amount} {currency}
  country: {country}    ipCountry: {ip_country}
  device: {device_id}   merchant: {merchant_id}
  account age: {account_age_days} days   channel: {channel}
  timestamp: {timestamp}

Engine response:
  status: {status}
  score:  {score:.3f}
  matched rules: {matched_rules}

Classify into ONE of:
- likely_benign
- borderline_card_testing
- borderline_structuring
- borderline_ato
- borderline_device_anomaly
- borderline_geo_anomaly
- unclassified

Be specific in `rationale`. Output only JSON matching the schema.
"""


async def triage_decision(
    *,
    tx_payload: dict,
    decision_payload: dict,
    wrapper: OllamaWrapper,
) -> TriageResult:
    raw = await wrapper.chat_structured(
        messages=[{
            "role": "user",
            "content": _PROMPT.format(
                amount=tx_payload.get("amount"),
                currency=tx_payload.get("currency"),
                country=tx_payload.get("country"),
                ip_country=tx_payload.get("ipCountry"),
                device_id=tx_payload.get("deviceId"),
                merchant_id=tx_payload.get("merchantId"),
                account_age_days=tx_payload.get("accountAgeDays"),
                channel=tx_payload.get("channel"),
                timestamp=tx_payload.get("timestamp"),
                status=decision_payload.get("status"),
                score=float(decision_payload.get("score", 0.0)),
                matched_rules=decision_payload.get("matched_rules", []),
            ),
        }],
        schema=TriageResult,
    )
    try:
        return TriageResult.model_validate_json(raw)
    except Exception:  # noqa: BLE001 — never let LLM break the report path
        return TriageResult(
            label="unclassified",
            confidence=0.0,
            rationale="triage LLM response failed to validate",
        )
