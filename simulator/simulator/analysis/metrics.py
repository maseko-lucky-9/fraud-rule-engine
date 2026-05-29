"""Deterministic, pandas-driven metric computation over the sink DB.

Everything the audit reports as a number originates here — the LLM never
sees raw rows, only the summarised metrics object. That keeps the
authoritative numbers reproducible across runs.
"""

from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd


@dataclass(slots=True, frozen=True)
class PerRuleMetrics:
    rule: str
    true_positives: int
    false_positives: int
    false_negatives: int
    true_negatives: int

    @property
    def precision(self) -> float:
        denom = self.true_positives + self.false_positives
        return self.true_positives / denom if denom else 0.0

    @property
    def recall(self) -> float:
        denom = self.true_positives + self.false_negatives
        return self.true_positives / denom if denom else 0.0

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return 2 * p * r / (p + r) if (p + r) else 0.0


@dataclass(slots=True, frozen=True)
class RunMetrics:
    run_id: str
    total_submissions: int
    status_counts: dict[str, int]
    http_status_counts: dict[int, int]
    latency_ms: dict[str, float]              # {"p50": ..., "p95": ..., "p99": ...}
    per_rule: list[PerRuleMetrics]
    evasion_outcomes: list[dict] = field(default_factory=list)

    @property
    def approved_pct(self) -> float:
        return self._pct(self.status_counts.get("APPROVED", 0))

    @property
    def review_pct(self) -> float:
        return self._pct(self.status_counts.get("REVIEW", 0))

    @property
    def block_pct(self) -> float:
        return self._pct(self.status_counts.get("BLOCK", 0))

    @property
    def failed_pct(self) -> float:
        decisioned = sum(self.status_counts.values())
        return 100.0 * (self.total_submissions - decisioned) / max(1, self.total_submissions)

    def _pct(self, n: int) -> float:
        return 100.0 * n / max(1, sum(self.status_counts.values()))


# --------------------------------------------------------------------------- #
# SQL loader
# --------------------------------------------------------------------------- #

def load_submissions(db_path: Path) -> pd.DataFrame:
    with sqlite3.connect(db_path) as conn:
        df = pd.read_sql_query("SELECT * FROM submissions ORDER BY id", conn)
    if df.empty:
        return df
    # Normalize nullable columns so downstream string ops don't trip on NaN.
    df["scenario_id"] = df["scenario_id"].fillna("")
    df["status"] = df["status"].fillna("")
    df["matched_rules_list"] = df["matched_rules"].fillna("[]").map(json.loads)
    return df


# --------------------------------------------------------------------------- #
# Metric computation
# --------------------------------------------------------------------------- #

# Each rule_coverage scenario id carries its target rule + classification.
_RULE_COVERAGE_PREFIX = "rule_coverage_"


def _scenario_classification(scenario_id: str) -> tuple[str, str] | None:
    """Map a rule_coverage scenario_id to (rule, classification)."""
    if not scenario_id or not scenario_id.startswith(_RULE_COVERAGE_PREFIX):
        return None
    suffix = scenario_id.removeprefix(_RULE_COVERAGE_PREFIX)
    # The classification suffix is always one of these three.
    for tail in ("_positive", "_negative", "_boundary"):
        if suffix.endswith(tail):
            rule = suffix.removesuffix(tail).upper()
            return rule, tail.lstrip("_")
    return None


def compute_per_rule_metrics(df: pd.DataFrame) -> list[PerRuleMetrics]:
    """Compute TP/FP/FN/TN for each engine rule from rule_coverage scenarios."""
    if df.empty:
        return []

    rows: dict[str, dict[str, int]] = {}
    for _, row in df.iterrows():
        classification = _scenario_classification(row.get("scenario_id") or "")
        if classification is None:
            continue
        rule, kind = classification
        matched = rule in row["matched_rules_list"]
        bucket = rows.setdefault(rule, {"tp": 0, "fp": 0, "fn": 0, "tn": 0})
        if kind == "positive" and matched:
            bucket["tp"] += 1
        elif kind == "positive" and not matched:
            bucket["fn"] += 1
        elif kind in {"negative", "boundary"} and not matched:
            bucket["tn"] += 1
        elif kind in {"negative", "boundary"} and matched:
            bucket["fp"] += 1

    return [
        PerRuleMetrics(
            rule=rule,
            true_positives=counts["tp"],
            false_positives=counts["fp"],
            false_negatives=counts["fn"],
            true_negatives=counts["tn"],
        )
        for rule, counts in sorted(rows.items())
    ]


def compute_evasion_outcomes(df: pd.DataFrame, expectations: dict[str, str]) -> list[dict]:
    """For each evasion scenario, compare expected vs actual outcome."""
    out: list[dict] = []
    for scenario_id, expected in expectations.items():
        scenario_df = df[df["scenario_id"] == scenario_id]
        if scenario_df.empty:
            actual = "skipped"
        elif scenario_df["matched_rules_list"].map(bool).any():
            actual = "detected"
        else:
            actual = "miss"
        out.append({
            "scenario_id": scenario_id,
            "expected": expected,
            "actual": actual,
        })
    return out


def compute_run_metrics(
    db_path: Path,
    *,
    run_id: str,
    evasion_expectations: dict[str, str] | None = None,
) -> RunMetrics:
    df = load_submissions(db_path)
    if df.empty:
        return RunMetrics(
            run_id=run_id, total_submissions=0,
            status_counts={}, http_status_counts={},
            latency_ms={"p50": 0.0, "p95": 0.0, "p99": 0.0},
            per_rule=[], evasion_outcomes=[],
        )

    status_counts = df["status"].dropna().value_counts().to_dict()
    http_status_counts = df["http_status"].value_counts().to_dict()
    latency = df["latency_ms"].dropna()
    latency_ms = {
        "p50": float(latency.quantile(0.50) if not latency.empty else 0.0),
        "p95": float(latency.quantile(0.95) if not latency.empty else 0.0),
        "p99": float(latency.quantile(0.99) if not latency.empty else 0.0),
    }

    return RunMetrics(
        run_id=run_id,
        total_submissions=len(df),
        status_counts={str(k): int(v) for k, v in status_counts.items()},
        http_status_counts={int(k): int(v) for k, v in http_status_counts.items()},
        latency_ms=latency_ms,
        per_rule=compute_per_rule_metrics(df),
        evasion_outcomes=compute_evasion_outcomes(df, evasion_expectations or {}),
    )
