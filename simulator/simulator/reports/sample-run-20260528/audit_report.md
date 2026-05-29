# Fraud-rule-engine — Pen-Test Audit · sample-run-20260528

_Generated 2026-05-28T14:23:10.959909+00:00_

## 1. Executive summary

**Run sample-run-20260528: 5 evasion patterns slipped through, p99 0 ms.**

This audit was generated without the narrative LLM. The numeric findings remain authoritative — see the per-rule precision/recall table and the ranked findings section below. 5 evasion scenarios produced the expected `miss` outcome, confirming the engine still has the gaps the harness was designed to probe.

**Top findings:**
- engine misses evasion_card_testing
- engine misses evasion_structuring
- engine misses evasion_synthetic_identity
- engine misses evasion_device_entropy
- engine misses evasion_geo_impossibility

## 2. Rule-by-rule precision / recall

| Rule | TP | FP | FN | TN | Precision | Recall | F1 |
|------|---:|---:|---:|---:|----------:|-------:|---:|
| BLACKLISTED_MERCHANT | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |
| CROSS_BORDER_HIGH_VALUE | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |
| HIGH_AMOUNT_NEW_ACCOUNT | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |
| NEW_DEVICE_HIGH_AMOUNT | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |
| OFF_HOURS_LARGE_TX | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |
| VELOCITY_BURST | 1 | 0 | 0 | 1 | 1.00 | 1.00 | 1.00 |

## 3. Status mix + performance

- Total submissions: **79**
- APPROVED: 91.1 %  ·  REVIEW: 8.9 %  ·  BLOCK: 0.0 %  ·  failed: 0.0 %
- Latency p50/p95/p99: 0.0 / 0.0 / 0.0 ms
- HTTP 429 (rate-limited): 0 (0.00 %)

## 4. Ranked findings (P0 → P2)

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

### P1 · One-day-past-cutoff bypasses HIGH_AMOUNT_NEW_ACCOUNT
`scenario: evasion_synthetic_identity`

**Draft YAML rule stub (drop-in)**
```yaml
id: HIGH_AMOUNT_YOUNG_ACCOUNT_60D
priority: 810
severity: MEDIUM
condition:
  all:
    - amountAbove: { value: 40000.0, currency: ZAR }
    - accountAgeBelow: { days: 60 }
action: { flag: REVIEW, score: 0.75, reason: "high amount on 30-60d account (synthetic risk band)" }
```

## 5. Evasion scenario outcomes

| Scenario | Expected today | Actual |
|----------|----------------|--------|
| evasion_card_testing | miss | miss |
| evasion_structuring | miss | miss |
| evasion_ato_geo_leap | partial | detected |
| evasion_synthetic_identity | miss | miss |
| evasion_device_entropy | miss | miss |
| evasion_geo_impossibility | miss | miss |

## 6. Counter-arguments / known limitations

- New rules proposed above introduce predicates the engine doesn't ship today (amountTotalAccount, deviceVelocity, geoImpossibility); each carries an implementation + perf cost on the hot path.
- Per-rule precision / recall is computed *only* on the rule_coverage scenarios; production traffic mix differs. Treat the numbers as engineering regression signals, not customer-impact predictions.
- Geo-impossibility findings assume ground-truth country codes; spoofed ipCountry headers would suppress this signal.

## 7. Run metadata

- Run ID: `sample-run-20260528`
- Generated at: 2026-05-28T14:23:10.959909+00:00
- Total submissions: 79
- Evasion scenarios assessed: 6
- Ranked findings: 5
