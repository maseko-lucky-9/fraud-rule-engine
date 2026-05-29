# Fraud-rule-engine — Pen-Test Audit · run-20260529T092118Z

_Generated 2026-05-29T09:38:46.591999+00:00_

## 1. Executive summary

**Internal Fraud Detection Pen-Test Audit: High-Risk Gaps Identified**

The pen-test audit revealed a significant gap in detecting evasion scenarios, particularly 'evasion_ato_geo_leap', where the system failed to detect partial attempts. This vulnerability could be exploited by attackers to bypass geo-location checks and conduct high-value transactions. The high precision and recall rates of rules such as BLACKLISTED_MERCHANT and CROSS_BORDER_HIGH_VALUE indicate that these rules are effective in detecting known threats, but the low recall rate of VELOCITY_BURST suggests that this rule may not be catching all instances of velocity-based attacks. Furthermore, the lack of submissions being blocked or failed indicates a potential issue with the system's ability to detect and prevent fraudulent activity.

**Top findings:**
- GAP: 'evasion_ato_geo_leap' evasion scenario not detected in partial attempts
- MITIGATION: Implement additional geo-location checks and anomaly detection for high-value transactions
- GAP: Low recall rate of VELOCITY_BURST rule, indicating potential missed velocity-based attacks
- MITIGATION: Review and refine the VELOCITY_BURST rule to improve its ability to detect velocity-based attacks
- GAP: Lack of submissions being blocked or failed, indicating potential issue with system's ability to prevent fraudulent activity

## 2. Rule-by-rule precision / recall

| Rule | TP | FP | FN | TN | Precision | Recall | F1 |
|------|---:|---:|---:|---:|----------:|-------:|---:|
| BLACKLISTED_MERCHANT | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |
| CROSS_BORDER_HIGH_VALUE | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |
| HIGH_AMOUNT_NEW_ACCOUNT | 1 | 0 | 0 | 2 | 1.00 | 1.00 | 1.00 |
| NEW_DEVICE_HIGH_AMOUNT | 1 | 0 | 0 | 2 | 1.00 | 1.00 | 1.00 |
| OFF_HOURS_LARGE_TX | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |
| VELOCITY_BURST | 1 | 0 | 5 | 5 | 1.00 | 0.17 | 0.29 |

## 3. Status mix + performance

- Total submissions: **10585**
- APPROVED: 0.0 %  ·  REVIEW: 0.3 %  ·  BLOCK: 21.3 %  ·  failed: 0.0 %
- Latency p50/p95/p99: 14.9 / 21.6 / 27.9 ms
- HTTP 429 (rate-limited): 10 (0.09 %)

## 4. Ranked findings (P0 → P2)

### P0 · ATO geo-leap with new device slips when amount < $5k
`scenario: evasion_ato_geo_leap`

**Suggested mitigation (requires new predicate: geoImpossibility)**
```yaml
id: GEO_LEAP_NEW_DEVICE
priority: 880
severity: HIGH
condition:
  all:
    - geoMismatch: {}
    - deviceFingerprintNew: {}
    - geoImpossibility: { hoursBetween: 6 }   # NEW predicate
action: { flag: REVIEW, score: 0.90, reason: "impossible-distance geo-leap with new device" }
```

### P0 · No time-distance rule — ZA→UK in 30 min is silently approved
`scenario: evasion_geo_impossibility`

**Suggested mitigation (requires new predicate: geoImpossibility)**
```yaml
id: GEO_IMPOSSIBLE
priority: 870
severity: HIGH
condition:
  all:
    - geoMismatch: {}
    - geoImpossibility: { hoursBetween: 4 }   # NEW predicate (ground-truth distance vs time)
action: { flag: BLOCK, score: 0.95, reason: "physically impossible geo distance vs elapsed time" }
```

### P0 · Sub-threshold structuring evades HIGH_AMOUNT_NEW_ACCOUNT
`scenario: evasion_structuring`

**Suggested mitigation (requires new predicate: amountTotalAccount)**
```yaml
id: STRUCTURING_24H_TOTAL
priority: 900
severity: HIGH
condition:
  all:
    - amountAbove: { value: 8000.0, currency: ZAR }
    - amountTotalAccount: { windowHours: 24, value: 25000.0 }   # NEW predicate
action: { flag: REVIEW, score: 0.85, reason: "structuring (24h aggregate over $25k)" }
```

### P1 · Card-testing across accounts evades VELOCITY_BURST
`scenario: evasion_card_testing`

**Draft YAML rule stub (drop-in)**
```yaml
id: CROSS_ACCOUNT_MICRO_BURST
priority: 850
severity: HIGH
condition:
  all:
    - amountAbove: { value: 0.0, currency: ZAR }   # any value
    - velocity: { count: 50, windowSeconds: 60 }   # NB: requires aggregating across accountId
action: { flag: REVIEW, score: 0.80, reason: "distributed micro-burst (suspected card-testing)" }
```

### P1 · Device-rotation farming under NEW_DEVICE_HIGH_AMOUNT threshold
`scenario: evasion_device_entropy`

**Suggested mitigation (requires new predicate: deviceVelocity)**
```yaml
id: DEVICE_ROTATION_ACCOUNT
priority: 820
severity: MEDIUM
condition:
  all:
    - deviceFingerprintNew: {}
    - deviceVelocity: { countPerHour: 3 }   # NEW predicate
action: { flag: REVIEW, score: 0.70, reason: "account using many fresh devices per hour" }
```

## 5. Evasion scenario outcomes

| Scenario | Expected today | Actual |
|----------|----------------|--------|
| evasion_card_testing | miss | miss |
| evasion_structuring | miss | miss |
| evasion_ato_geo_leap | partial | miss |
| evasion_synthetic_identity | miss | detected |
| evasion_device_entropy | miss | miss |
| evasion_geo_impossibility | miss | miss |

## 6. Counter-arguments / known limitations

- New rules proposed above introduce predicates the engine doesn't ship today (amountTotalAccount, deviceVelocity, geoImpossibility); each carries an implementation + perf cost on the hot path.
- Per-rule precision / recall is computed *only* on the rule_coverage scenarios; production traffic mix differs. Treat the numbers as engineering regression signals, not customer-impact predictions.
- Geo-impossibility findings assume ground-truth country codes; spoofed ipCountry headers would suppress this signal.

## 6. Security probe results

_No security probes were run for this report._

## 7. Load test (k6)

_Load test not run for this report._

## 8. Run metadata

- Run ID: `run-20260529T092118Z`
- Generated at: 2026-05-29T09:38:46.591999+00:00
- Total submissions: 10585
- Evasion scenarios assessed: 6
- Ranked findings: 5
