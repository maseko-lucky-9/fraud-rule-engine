# Test pyramid

Where each test class sits in the pyramid + why.

```
          ┌──────────────────────┐
          │  e2e / live smoke   │   ← README §2 curl walkthrough
          └──────────────────────┘
         ┌──────────────────────────┐
         │ integration (testcont.)  │   ← 4 tests · Postgres + Redpanda + Redis
         └──────────────────────────┘
        ┌───────────────────────────────┐
        │ architecture (ArchUnit)       │   ← 3 rules · pinned boundaries
        └───────────────────────────────┘
       ┌───────────────────────────────────┐
       │ contract / property               │   ← 12 golden cases + race test
       └───────────────────────────────────┘
      ┌─────────────────────────────────────────┐
      │ unit (engine, predicates, advisory)     │   ← 55 tests · in-memory, fast
      └─────────────────────────────────────────┘
```

## Counts (Day 7 — surefire reports)

| Layer | Tests | Class |
|---|---|---|
| Unit | 6 | `RuleEngineTest` |
| Unit | 19 | `PredicatesTest` (8 nested classes) |
| Unit | 17 | `EdgeCasesTest` |
| Unit | 4 | `RuleLoaderTest` |
| Unit | 3 | `AdvisoryServiceTest` |
| Unit | 6 | `RedisStateStoreTest` |
| Race | 1 | `HotReloadRaceTest` (50 concurrent × 50 reloads) |
| Property (golden) | 12 | `GoldenCasesTest` (parameterised) |
| Architecture | 3 | `ArchitectureTest` (ArchUnit) |
| Integration | 4 | `IngestServiceIntegrationTest` (Testcontainers) |
| Spring context | 1 | `FraudRuleEngineApplicationTests` |
| **Total** | **76** | |

## Coverage gate

- **Jacoco** runs on `verify`. Engine package gate: **≥ 95 % line coverage**.
- Current: **98.1 %** (260/265).
- Configured in `pom.xml` under `jacoco-maven-plugin` → `check-engine-coverage` execution.

## Mutation testing

- **Pitest** wired in `pom.xml` with 70 % mutation threshold on `engine`, `engine.predicates`, `domain` packages.
- Run manually: `./mvnw org.pitest:pitest-maven:mutationCoverage`.
- Day-7 polish promotes Pitest into the `verify` phase if the run is fast enough on CI.

## What's deliberately NOT here

| Missing layer | Why deferred |
|---|---|
| Contract tests against `openapi.yaml` | Day-7 polish — springdoc generates the spec, comparison vs baseline is a CI step |
| WireMock-based unit tests for `OllamaAdvisoryService` | Day-7 polish — live smoke covers the boundary |
| Load test (k6) in CI | `k6/load.js` exists, but running it in CI requires a sustained env; documented for ops-side cadence |
| Chaos tests (Toxiproxy) | Day-7+ polish — quarterly cadence |
| Security scan (OWASP ZAP) | Day-7 polish — would run against a deployed stack in a pipeline step |

## Why a race test for hot reload?

The plan §13 risk table flagged "Rule hot-reload race condition → wrong decision under load" as Low likelihood / High impact. `HotReloadRaceTest` runs 50 concurrent evaluators × 100 evaluations each, while another thread swaps the rule set 50 times. The assertion: every `Decision.ruleSetVersion` is either `v_old` or `v_new`, never null or mixed. Catches the invariant breach immediately if the AtomicReference contract is ever broken.
