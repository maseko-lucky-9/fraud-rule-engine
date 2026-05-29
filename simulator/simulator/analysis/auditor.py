"""Audit pipeline: metrics → narrative → markdown render → write to disk.

This is the deliverable: ``reports/run-<ts>/audit_report.md``.
"""

from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING

from simulator.llm.audit_writer import (
    AuditNarrativeContext,
    ExecutiveSummary,
    write_executive_summary,
)
from simulator.llm.ollama_client import OllamaWrapper

from .metrics import RunMetrics, compute_run_metrics
from .yaml_validator import PredicateAllowlist, YamlValidationResult

if TYPE_CHECKING:
    from collections.abc import Sequence

    from simulator.scenarios.security_runner import ProbeOutcome
    from simulator.transport.k6_wrapper import K6Summary

LOG = logging.getLogger("simulator.analysis.auditor")


@dataclass(slots=True, frozen=True)
class FindingDraft:
    """One ranked finding the LLM is invited to flesh out into prose."""

    severity: str  # P0 / P1 / P2
    scenario_id: str
    title: str
    proposed_yaml_stub: str


@dataclass(slots=True, frozen=True)
class AuditReport:
    """Whole-report payload — handed to the Jinja template."""

    run_id: str
    generated_at: datetime
    metrics: RunMetrics
    findings: list[FindingDraft]
    yaml_validation: dict[str, YamlValidationResult]
    executive: ExecutiveSummary
    security_outcomes: tuple[ProbeOutcome, ...] = field(default_factory=tuple)
    k6_summary: K6Summary | None = None

    def to_markdown(self) -> str:
        return _render_markdown(self)


async def build_audit_report(
    *,
    db_path: Path,
    run_id: str,
    output_dir: Path,
    ollama_wrapper: OllamaWrapper | None,
    findings_drafts: list[FindingDraft] | None = None,
    evasion_expectations: dict[str, str] | None = None,
    allowlist: PredicateAllowlist | None = None,
    security_outcomes: Sequence[ProbeOutcome] | None = None,
    k6_summary: K6Summary | None = None,
) -> Path:
    """Build the report and write ``audit_report.md`` under ``output_dir``."""
    # One-shot filesystem ops at the start/end of the audit phase — the
    # event loop is idle here so the sync-call is harmless.
    output_dir.mkdir(parents=True, exist_ok=True)  # noqa: ASYNC240

    metrics = compute_run_metrics(
        db_path=db_path, run_id=run_id,
        evasion_expectations=evasion_expectations,
    )
    drafts = findings_drafts or _default_findings_from_evasion(metrics)
    allowlist = allowlist or PredicateAllowlist.from_path()

    validations: dict[str, YamlValidationResult] = {
        d.scenario_id: allowlist.validate(d.proposed_yaml_stub) for d in drafts
    }

    narrative_ctx = AuditNarrativeContext(
        run_id=run_id,
        total_submissions=metrics.total_submissions,
        approved_pct=metrics.approved_pct,
        review_pct=metrics.review_pct,
        block_pct=metrics.block_pct,
        failed_pct=metrics.failed_pct,
        per_rule_precision_recall=[
            {
                "rule": r.rule, "precision": r.precision,
                "recall": r.recall, "f1": r.f1,
            }
            for r in metrics.per_rule
        ],
        evasion_outcomes=metrics.evasion_outcomes,
        p99_latency_ms=metrics.latency_ms.get("p99", 0.0),
        rate_limited_pct=100.0 * metrics.http_status_counts.get(429, 0)
                          / max(1, metrics.total_submissions),
    )
    executive = await write_executive_summary(
        context=narrative_ctx, wrapper=ollama_wrapper,
    )

    report = AuditReport(
        run_id=run_id,
        generated_at=datetime.now(tz=UTC),
        metrics=metrics,
        findings=drafts,
        yaml_validation=validations,
        executive=executive,
        security_outcomes=tuple(security_outcomes or ()),
        k6_summary=k6_summary,
    )

    report_path = output_dir / "audit_report.md"
    report_path.write_text(report.to_markdown())

    # Also drop a machine-readable JSON next to the markdown for replay.
    json_path = output_dir / "metrics.json"
    json_path.write_text(json.dumps({
        "run_id": run_id,
        "metrics": _metrics_to_dict(metrics),
        "executive": executive.model_dump(),
        "security_outcomes": [asdict(o) for o in report.security_outcomes],
        "k6_summary": asdict(k6_summary) if k6_summary is not None else None,
    }, indent=2))

    return report_path


# --------------------------------------------------------------------------- #
# Default findings — the evasion scenarios with expected `miss` are the
# obvious P0 / P1 candidates. The runner can override by passing custom
# drafts to build_audit_report().
# --------------------------------------------------------------------------- #


_DEFAULT_STUBS: dict[str, tuple[str, str, str]] = {
    "evasion_card_testing": (
        "P1",
        "Card-testing across accounts evades VELOCITY_BURST",
        """\
id: CROSS_ACCOUNT_MICRO_BURST
priority: 850
severity: HIGH
condition:
  all:
    - amountAbove: { value: 0.0, currency: ZAR }   # any value
    - velocity: { count: 50, windowSeconds: 60 }   # NB: requires aggregating across accountId
action: { flag: REVIEW, score: 0.80, reason: "distributed micro-burst (suspected card-testing)" }
""",
    ),
    "evasion_structuring": (
        "P0",
        "Sub-threshold structuring evades HIGH_AMOUNT_NEW_ACCOUNT",
        """\
id: STRUCTURING_24H_TOTAL
priority: 900
severity: HIGH
condition:
  all:
    - amountAbove: { value: 8000.0, currency: ZAR }
    - amountTotalAccount: { windowHours: 24, value: 25000.0 }   # NEW predicate
action: { flag: REVIEW, score: 0.85, reason: "structuring (24h aggregate over $25k)" }
""",
    ),
    "evasion_ato_geo_leap": (
        "P0",
        "ATO geo-leap with new device slips when amount < $5k",
        """\
id: GEO_LEAP_NEW_DEVICE
priority: 880
severity: HIGH
condition:
  all:
    - geoMismatch: {}
    - deviceFingerprintNew: {}
    - geoImpossibility: { hoursBetween: 6 }   # NEW predicate
action: { flag: REVIEW, score: 0.90, reason: "impossible-distance geo-leap with new device" }
""",
    ),
    "evasion_synthetic_identity": (
        "P1",
        "One-day-past-cutoff bypasses HIGH_AMOUNT_NEW_ACCOUNT",
        """\
id: HIGH_AMOUNT_YOUNG_ACCOUNT_60D
priority: 810
severity: MEDIUM
condition:
  all:
    - amountAbove: { value: 40000.0, currency: ZAR }
    - accountAgeBelow: { days: 60 }
action: { flag: REVIEW, score: 0.75, reason: "high amount on 30-60d account (synthetic risk band)" }
""",
    ),
    "evasion_device_entropy": (
        "P1",
        "Device-rotation farming under NEW_DEVICE_HIGH_AMOUNT threshold",
        """\
id: DEVICE_ROTATION_ACCOUNT
priority: 820
severity: MEDIUM
condition:
  all:
    - deviceFingerprintNew: {}
    - deviceVelocity: { countPerHour: 3 }   # NEW predicate
action: { flag: REVIEW, score: 0.70, reason: "account using many fresh devices per hour" }
""",
    ),
    "evasion_geo_impossibility": (
        "P0",
        "No time-distance rule — ZA→UK in 30 min is silently approved",
        """\
id: GEO_IMPOSSIBLE
priority: 870
severity: HIGH
condition:
  all:
    - geoMismatch: {}
    - geoImpossibility: { hoursBetween: 4 }   # NEW predicate (ground-truth distance vs time)
action: { flag: BLOCK, score: 0.95, reason: "physically impossible geo distance vs elapsed time" }
""",
    ),
}


def _default_findings_from_evasion(metrics: RunMetrics) -> list[FindingDraft]:
    drafts: list[FindingDraft] = []
    for outcome in metrics.evasion_outcomes:
        sid = outcome["scenario_id"]
        if outcome.get("actual") != "miss":
            continue
        if sid not in _DEFAULT_STUBS:
            continue
        severity, title, stub = _DEFAULT_STUBS[sid]
        drafts.append(FindingDraft(
            severity=severity, scenario_id=sid, title=title,
            proposed_yaml_stub=stub,
        ))
    drafts.sort(key=lambda d: (d.severity, d.scenario_id))
    return drafts


# --------------------------------------------------------------------------- #
# Markdown rendering — we use a hand-written template here rather than a
# Jinja file because the structure is small and editor-friendly. Switching
# to Jinja later is mechanical.
# --------------------------------------------------------------------------- #


def _render_markdown(report: AuditReport) -> str:
    lines: list[str] = []
    add = lines.append
    metrics = report.metrics
    executive = report.executive

    add(f"# Fraud-rule-engine — Pen-Test Audit · {report.run_id}")
    add("")
    add(f"_Generated {report.generated_at.isoformat()}_")
    add("")

    add("## 1. Executive summary")
    add("")
    add(f"**{executive.headline}**")
    add("")
    add(executive.body)
    if executive.top_findings:
        add("")
        add("**Top findings:**")
        for f in executive.top_findings:
            add(f"- {f}")
    add("")

    add("## 2. Rule-by-rule precision / recall")
    add("")
    add("| Rule | TP | FP | FN | TN | Precision | Recall | F1 |")
    add("|------|---:|---:|---:|---:|----------:|-------:|---:|")
    for r in metrics.per_rule:
        add(f"| {r.rule} | {r.true_positives} | {r.false_positives} | "
            f"{r.false_negatives} | {r.true_negatives} | "
            f"{r.precision:.2f} | {r.recall:.2f} | {r.f1:.2f} |")
    add("")

    add("## 3. Status mix + performance")
    add("")
    add(f"- Total submissions: **{metrics.total_submissions}**")
    add(f"- APPROVED: {metrics.approved_pct:.1f} %  ·  REVIEW: {metrics.review_pct:.1f} %  "
        f"·  BLOCK: {metrics.block_pct:.1f} %  ·  failed: {metrics.failed_pct:.1f} %")
    add(f"- Latency p50/p95/p99: {metrics.latency_ms['p50']:.1f} / "
        f"{metrics.latency_ms['p95']:.1f} / {metrics.latency_ms['p99']:.1f} ms")
    rate_limited = metrics.http_status_counts.get(429, 0)
    add(f"- HTTP 429 (rate-limited): {rate_limited} "
        f"({100.0 * rate_limited / max(1, metrics.total_submissions):.2f} %)")
    add("")

    add("## 4. Ranked findings (P0 → P2)")
    add("")
    if not report.findings:
        add("_No ranked findings — every evasion scenario was detected or skipped._")
    else:
        for finding in report.findings:
            validation = report.yaml_validation.get(finding.scenario_id)
            tag = (
                "**Draft YAML rule stub (drop-in)**"
                if validation and validation.drop_in_ready
                else f"**Suggested mitigation (requires new predicate: "
                     f"{', '.join(validation.unknown_predicates) if validation else 'unknown'})**"
            )
            add(f"### {finding.severity} · {finding.title}")
            add(f"`scenario: {finding.scenario_id}`")
            add("")
            add(tag)
            add("```yaml")
            add(finding.proposed_yaml_stub.rstrip())
            add("```")
            add("")

    add("## 5. Evasion scenario outcomes")
    add("")
    add("| Scenario | Expected today | Actual |")
    add("|----------|----------------|--------|")
    for outcome in metrics.evasion_outcomes:
        add(f"| {outcome['scenario_id']} | {outcome['expected']} | {outcome['actual']} |")
    add("")

    add("## 6. Counter-arguments / known limitations")
    add("")
    add(
        "- New rules proposed above introduce predicates the engine doesn't ship today "
        "(amountTotalAccount, deviceVelocity, geoImpossibility); each carries an "
        "implementation + perf cost on the hot path."
    )
    add(
        "- Per-rule precision / recall is computed *only* on the rule_coverage "
        "scenarios; production traffic mix differs. Treat the numbers as engineering "
        "regression signals, not customer-impact predictions."
    )
    add(
        "- Geo-impossibility findings assume ground-truth country codes; spoofed "
        "ipCountry headers would suppress this signal."
    )
    add("")

    add("## 6. Security probe results")
    add("")
    if not report.security_outcomes:
        add("_No security probes were run for this report._")
    else:
        passed = sum(1 for o in report.security_outcomes if o.passed)
        add(f"**{passed}/{len(report.security_outcomes)} probes passed.**")
        add("")
        add("| Probe | Targets | Expected | Actual | Result |")
        add("|-------|---------|---------:|-------:|--------|")
        for o in report.security_outcomes:
            expected = ", ".join(str(c) for c in o.expected_codes)
            add(f"| {o.probe_id} | {o.targets} | {expected} | {o.actual_code} | "
                f"{'PASS' if o.passed else 'FAIL'} |")
    add("")

    add("## 7. Load test (k6)")
    add("")
    k6 = report.k6_summary
    if k6 is None:
        add("_Load test not run for this report._")
    elif not k6.available:
        reason = k6.raw.get("error", "unknown") if isinstance(k6.raw, dict) else "unknown"
        add(f"_k6 unavailable: {reason}._")
    else:
        add(f"- Iterations: **{k6.iterations}** "
            f"({k6.failed_iterations} failed, error rate {k6.error_rate:.2%})")
        add(f"- http_req_duration p50/p95/p99: {k6.http_req_p50_ms:.1f} / "
            f"{k6.http_req_p95_ms:.1f} / {k6.http_req_p99_ms:.1f} ms")
        add(f"- Duration: {k6.duration_seconds:.1f} s")
    add("")

    add("## 8. Run metadata")
    add("")
    add(f"- Run ID: `{report.run_id}`")
    add(f"- Generated at: {report.generated_at.isoformat()}")
    add(f"- Total submissions: {metrics.total_submissions}")
    add(f"- Evasion scenarios assessed: {len(metrics.evasion_outcomes)}")
    add(f"- Ranked findings: {len(report.findings)}")
    add("")

    return "\n".join(lines)


def _metrics_to_dict(m: RunMetrics) -> dict:
    out = asdict(m)
    out["per_rule"] = [
        {**asdict(r), "precision": r.precision, "recall": r.recall, "f1": r.f1}
        for r in m.per_rule
    ]
    return out
