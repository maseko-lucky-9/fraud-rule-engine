# fraud-rule-engine simulator

Distributed multi-agent simulation and pen-test harness for the
[`fraud-rule-engine`](../README.md). Spawns parallel persona agents (a mix of
honest, scripted-adversary, and Ollama-driven adversary) that drive the engine
via its real REST and Kafka surface, then produces a written audit naming
detection gaps with predicate-validated draft YAML rule stubs.

It is the missing **red-team complement** to the engine's existing green-path
JUnit + k6 suite.

## Quickstart

Prereqs: Docker (engine stack + k6 + Ollama containers), [`uv`](https://docs.astral.sh/uv/),
[`ollama`](https://ollama.com/) CLI, `gh`, `jq`, ~16 GB RAM headroom.

```bash
# 1. Start the engine (Postgres + Redis + Redpanda + the Spring Boot app)
cd ..
docker compose up -d

# 2. Start Ollama and pull the model (one-time, ~5 GB download)
cd simulator
docker compose -f docker-compose.sim.yml up -d ollama
make pull-llm

# 3. Install deps and run a default 10-minute simulation
uv sync
uv run sim reset --truncate                # clean engine PG between runs
uv run sim seed                            # Ollama expands seed YAML into ~50 scripts
uv run sim run --profile config/profile.default.yaml
uv run sim audit reports/run-latest/
```

CI / no-Ollama runs (deterministic core only):

```bash
uv run sim run --no-llm --profile config/profile.default.yaml
```

## Default profile

- **100 deterministic accounts** with a controlled distribution of
  `accountAgeDays`, countries, channels, devices
- **~10 000 transactions** in ~10 minutes wall-clock
- **Transport mix**: 80 % REST, 20 % Kafka
- **150 RPS** load probe (under the 100-accounts × 100-req/min rate-limit ceiling)
- **5 LLM-driven adversaries** (cap concurrency 10 to keep Ollama off the critical path)

## Scenario taxonomy

| Folder | What | Acceptance |
|--------|------|------------|
| `scenarios/rule_coverage/` | Positive + negative + boundary per existing rule | Per-rule recall ≥ 0.95 |
| `scenarios/evasion/` | Card-testing, structuring, ATO geo-leap, synthetic identity, device entropy, geo-impossibility | Each probes a known engine gap |
| `scenarios/security/` | JWT tampering, idempotency collision, rate-limit probe, payload fuzz | Per-vector pass/fail |
| `scenarios/load/` | Wraps the engine's `k6/load.js` at 150 RPS | p99 within 2× baseline |

## Audit report

`reports/run-<ts>/audit_report.md` contains:

1. Executive summary (Ollama-written, ≤ 250 words)
2. Rule-by-rule precision / recall table (pandas — deterministic)
3. Score distribution histogram (matplotlib PNG)
4. Ranked findings (P0/P1/P2) with reproduction recipe + draft YAML rule stub
5. Performance section (p50/p95/p99, 429 rate, Kafka outbox lag)
6. Security probe results
7. Counter-arguments / known limitations
8. Run metadata (engine SHA, sim SHA, model, seed, config hash)

Every LLM-emitted draft rule is validated against the engine's predicate
allowlist (`scripts/predicates.allowlist.json`). Rules referencing an unknown
predicate are downgraded to *"suggested mitigation (requires new predicate)"*
so the report never ships broken stubs.

## Determinism

Two runs with the same seed produce identical
`sha256(persona_role || scenario_id || tx_index || status || score || sorted(matched_rule_ids))`
over the SQLite sink. Timestamps and UUIDs are explicitly excluded from the
hash.

## Layout

```
simulator/
├── cli.py                  # typer entry: seed, run, audit, replay, reset
├── orchestrator/           # runner, account_pool, auth
├── agents/                 # persona state machine + honest / scripted / llm personas
├── transport/              # rest (httpx), kafka (aiokafka), k6_wrapper, pg_admin, models
├── scenarios/              # rule_coverage, evasion, security, load
├── llm/                    # ollama_client, scenario_generator, adversarial_brain, triage, audit_writer
├── telemetry/              # SQLite sink, Prometheus scraper
├── analysis/               # metrics + auditor
├── reports/templates/      # audit_report.md.j2
├── scripts/                # reset SQL, predicates allowlist
├── config/                 # profile.default.yaml, scenarios.seed.yaml
└── tests/                  # pytest, --no-llm safe
```

## Plan

The full design lives at
`~/.claude/plans/create-a-simulation-application-distributed-moore.md`.
An ADR will land under `../docs/decisions/` once the simulator stabilises.
