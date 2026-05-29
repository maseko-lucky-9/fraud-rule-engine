"""Ollama-narrated audit report writer.

Pandas owns every metric in the report; this module asks the LLM only for
prose (executive summary, finding narratives, mitigation explanations). If
Ollama is unavailable, the writer falls back to template-only output —
the report stays sound, just less narrative.
"""

from __future__ import annotations

from dataclasses import dataclass

from pydantic import BaseModel, Field

from .ollama_client import OllamaWrapper, is_no_llm_mode


class ExecutiveSummary(BaseModel):
    """LLM-written narrative slot."""

    headline: str = Field(max_length=140)
    body: str = Field(max_length=2000)
    top_findings: list[str] = Field(default_factory=list, max_length=5)


@dataclass(slots=True, frozen=True)
class AuditNarrativeContext:
    """Compact JSON-summary the LLM sees — keeps the prompt small."""

    run_id: str
    total_submissions: int
    approved_pct: float
    review_pct: float
    block_pct: float
    failed_pct: float
    per_rule_precision_recall: list[dict]   # [{rule, precision, recall, f1}]
    evasion_outcomes: list[dict]            # [{scenario_id, expected, actual}]
    p99_latency_ms: float
    rate_limited_pct: float

    def to_prompt(self) -> str:
        return f"""\
Run ID: {self.run_id}
Total submissions: {self.total_submissions}
Status mix: APPROVED {self.approved_pct:.1f}% / REVIEW {self.review_pct:.1f}% \
/ BLOCK {self.block_pct:.1f}% / failed {self.failed_pct:.1f}%
p99 latency: {self.p99_latency_ms:.1f} ms
Rate-limited: {self.rate_limited_pct:.2f}%

Per-rule precision/recall:
{self._format_rule_table()}

Evasion scenarios:
{self._format_evasion()}
"""

    def _format_rule_table(self) -> str:
        lines = ["rule | precision | recall | f1"]
        for row in self.per_rule_precision_recall:
            lines.append(
                f"{row['rule']} | "
                f"{row.get('precision', 0):.2f} | "
                f"{row.get('recall', 0):.2f} | "
                f"{row.get('f1', 0):.2f}"
            )
        return "\n".join(lines)

    def _format_evasion(self) -> str:
        if not self.evasion_outcomes:
            return "(none)"
        return "\n".join(
            f"- {row['scenario_id']}: expected={row['expected']}, actual={row['actual']}"
            for row in self.evasion_outcomes
        )


_PROMPT = """\
You are writing the executive summary for an internal fraud-detection
pen-test audit. The numeric findings below are authoritative; do not
contradict them. Be concrete: name specific rules, scenarios, and gaps.

{context}

Write:
- `headline` (<=140 chars): one-line verdict.
- `body` (<=2000 chars): 3-5 paragraphs in plain English. Lead with the
  highest-impact gap. No tables — just prose.
- `top_findings` (<=5 entries): short bullets, each naming ONE concrete
  gap and proposed mitigation direction.

Output ONLY JSON matching the schema.
"""


async def write_executive_summary(
    *,
    context: AuditNarrativeContext,
    wrapper: OllamaWrapper | None,
) -> ExecutiveSummary:
    """Generate the executive-summary narrative. Falls back without LLM."""
    if wrapper is None or is_no_llm_mode():
        return _fallback_summary(context)

    raw = await wrapper.chat_structured(
        messages=[{"role": "user", "content": _PROMPT.format(context=context.to_prompt())}],
        schema=ExecutiveSummary,
    )
    try:
        return ExecutiveSummary.model_validate_json(raw)
    except Exception:  # noqa: BLE001
        return _fallback_summary(context)


def _fallback_summary(context: AuditNarrativeContext) -> ExecutiveSummary:
    """Deterministic stand-in when Ollama is unavailable."""
    misses = [
        row["scenario_id"] for row in context.evasion_outcomes
        if row.get("expected") == "miss" and row.get("actual") == "miss"
    ]
    headline = (
        f"Run {context.run_id}: {len(misses)} evasion patterns slipped through, "
        f"p99 {context.p99_latency_ms:.0f} ms."
    )
    body = (
        "This audit was generated without the narrative LLM. The numeric "
        "findings remain authoritative — see the per-rule precision/recall "
        "table and the ranked findings section below. "
        f"{len(misses)} evasion scenarios produced the expected `miss` "
        "outcome, confirming the engine still has the gaps the harness "
        "was designed to probe."
    )
    return ExecutiveSummary(
        headline=headline,
        body=body,
        top_findings=[f"engine misses {sid}" for sid in misses[:5]],
    )
