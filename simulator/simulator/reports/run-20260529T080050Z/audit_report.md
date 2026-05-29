# Fraud-rule-engine — Pen-Test Audit · sim-20260529T080050Z

_Generated 2026-05-29T08:10:59.692657+00:00_

## 1. Executive summary

**Critical Gaps in Evasion Detection Expose System to High-Risk Transactions**

The recent pen-test audit revealed significant gaps in evasion detection, putting the system at risk of processing high-risk transactions. The most critical finding is that all six evasion scenarios were successfully skipped by attackers, indicating a lack of effective countermeasures. This suggests that the current rules and configurations are not sufficient to detect and prevent sophisticated attacks. Specifically, the evasion_card_testing scenario was expected to be missed but was actually skipped, highlighting a clear gap in the system's ability to identify and block suspicious card testing activity. Furthermore, the p99 latency of 24.4 ms indicates that the system is processing transactions at an acceptable speed, but this may not be sufficient to prevent high-risk transactions from being processed before they can be detected and blocked.

**Top findings:**
- Evasion_card_testing scenario was successfully skipped by attackers, indicating a lack of effective countermeasures
- All six evasion scenarios were successfully skipped, highlighting significant gaps in evasion detection
- Rate-limited transactions are not being properly reviewed, potentially allowing high-risk activity to go undetected
- The system's p99 latency is within acceptable limits, but may not be sufficient to prevent high-risk transactions from being processed
- The current rules and configurations are not sufficient to detect and prevent sophisticated attacks

## 2. Rule-by-rule precision / recall

| Rule | TP | FP | FN | TN | Precision | Recall | F1 |
|------|---:|---:|---:|---:|----------:|-------:|---:|

## 3. Status mix + performance

- Total submissions: **8479**
- APPROVED: 0.0 %  ·  REVIEW: 0.0 %  ·  BLOCK: 23.0 %  ·  failed: 0.0 %
- Latency p50/p95/p99: 14.9 / 21.1 / 24.4 ms
- HTTP 429 (rate-limited): 0 (0.00 %)

## 4. Ranked findings (P0 → P2)

_No ranked findings — every evasion scenario was detected or skipped._
## 5. Evasion scenario outcomes

| Scenario | Expected today | Actual |
|----------|----------------|--------|
| evasion_card_testing | miss | skipped |
| evasion_structuring | miss | skipped |
| evasion_ato_geo_leap | partial | skipped |
| evasion_synthetic_identity | miss | skipped |
| evasion_device_entropy | miss | skipped |
| evasion_geo_impossibility | miss | skipped |

## 6. Counter-arguments / known limitations

- New rules proposed above introduce predicates the engine doesn't ship today (amountTotalAccount, deviceVelocity, geoImpossibility); each carries an implementation + perf cost on the hot path.
- Per-rule precision / recall is computed *only* on the rule_coverage scenarios; production traffic mix differs. Treat the numbers as engineering regression signals, not customer-impact predictions.
- Geo-impossibility findings assume ground-truth country codes; spoofed ipCountry headers would suppress this signal.

## 7. Run metadata

- Run ID: `sim-20260529T080050Z`
- Generated at: 2026-05-29T08:10:59.692657+00:00
- Total submissions: 8479
- Evasion scenarios assessed: 6
- Ranked findings: 0
