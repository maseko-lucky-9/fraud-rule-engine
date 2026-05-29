# Simulator full-pipeline wiring + bug fixes

Branch: `fix/ratelimit-flaky-reenable` · Scope approved: **Full pipeline wiring** · Review: **manual** (no auto-ship)

## Root causes (confirmed 2026-05-29)
- **Bug 1 (deadlock):** unsupervised sink writer (`sink.py:84`) + bounded blocking `put()` + no deadline guard on `record()` (`honest.py:140`) + no run watchdog → any writer stall = permanent silent deadlock; no `logging.basicConfig` + stdout buffering = empty log.
- **Bug 2 (empty seed):** atomic `model_validate_json` (`scenario_generator.py:108`) — one `low>=high` item (6/15 from llama3.1:8b) fails the whole batch; `except ValidationError: return []` drops the 9 good specs.

## Workstreams

### WS-DEP — dependency
- [x] Add `pyarrow` to pyproject + `uv lock` (for raw_decisions.parquet)

### WS1 — seed fail-loud + lenient parse (Bug 2)
- [x] `scenario_generator.py`: per-item validation, keep valid, count+log rejects, raise on 0
- [x] `cli.py seed`: non-zero exit + reject reasons on 0 specs
- [x] tests: mixed batch keeps subset; exits non-zero only when truly empty (6 tests)

### WS0 — deadlock + observability (Bug 1)
- [x] `sink.py`: crash-proof writer (skip bad row), `_writer_failed` event, non-hanging `close()`
- [x] `honest.py`: record() respects writer-failed (handled at sink level — no persona change needed)
- [x] `runner.py`: `asyncio.timeout` watchdog + `try/finally` always-finalize
- [x] `cli.py`: `_configure_logging()` + line-buffered stdout + per-stage/periodic progress logs
- [x] tests: skip-bad-row no hang; fast-drop on writer-failed; close() can't hang; runner watchdog terminates

### WS2 — multi-phase run loop
- [x] new `scenarios/spec_bridge.py`: `spec_to_fraud_scenario(spec, *, seed, max_steps)`
- [x] `runner.py`: honest + scripted (`load_default_registry`) + LLM adversary (capped, `--no-llm` skip) concurrent, then security probes
- [x] rebalance tx budget so total ≈ total_tx_target (documented approximate)
- [x] tests: bridge determinism + cap; adversary scenario_ids land in sink (respx)

### WS3 — finalization
- [x] new `orchestrator/finalize.py`: raw_decisions.parquet, reports/run-latest symlink, k6 via `run_k6_load` (graceful skip)
- [x] `auditor.py`: fold security-probe outcomes + k6 summary into report (sections 6 + 7) + metrics.json
- [x] tests: parquet roundtrip; symlink created + idempotent; missing-db skip

### WS4 — verify
- [x] full pytest suite green (115 passed); ruff clean
- [x] ADR `docs/decisions/001-simulator-run-finalization.md`

## Review (completed 2026-05-29)

**Both bugs fixed and verified.**
- **Bug 2 (live):** `uv run sim seed` → 15 specs (was 0), exit 0, against real llama3.1:8b. Root cause
  was atomic batch validation + silent swallow, not structured-output shape (proven by repro).
- **Bug 1:** deadlock root cause was architectural (unsupervised writer + bounded blocking put + no
  deadline guard + no watchdog + no logging). Eliminated by crash-proof/supervised sink, run watchdog,
  always-`finally`-close, and real logging. Proven by sink unit tests + runner watchdog integration test.
- **Full pipeline wired:** honest + scripted + LLM adversaries + security probes; parquet, k6, run-latest,
  extended audit report.

**Files changed:** `pyproject.toml`, `simulator/llm/scenario_generator.py`, `simulator/cli.py`,
`simulator/telemetry/sink.py`, `simulator/orchestrator/runner.py`, `simulator/analysis/auditor.py`;
new `simulator/scenarios/spec_bridge.py`, `simulator/orchestrator/finalize.py`; new tests
`test_spec_bridge.py`, `test_finalize.py`, `test_runner.py` + additions to `test_llm.py`, `test_telemetry_sink.py`.

**Not done (needs infra, offered):** live full `sim run` end-to-end — requires postgres/redis/redpanda +
app jar. Honest-persona `mean_interarrival_seconds` (5 s) is still not profile-driven; out of scope.

**Caveats:** tx target is approximate; scripted-adversary subject is per-scenario (high-volume scenarios
may hit per-subject rate limits and retry — acceptable, surfaced via the security rate-limit probe).
