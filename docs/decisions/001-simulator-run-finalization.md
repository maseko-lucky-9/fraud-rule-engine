# ADR 001 — Simulator run finalization, deadlock removal, and full-pipeline wiring

- **Status:** Accepted
- **Date:** 2026-05-29
- **Scope:** simulator/ only (the fraud-rule-engine pen-test harness). The engine is unchanged.

## Problem

Two simulator-side bugs blocked the harness from producing its audit deliverable:

1. **Run finalization deadlock.** `sim run` would submit ~8,479/10,000 transactions, then sit at
   `state=Ss`, 0% CPU, indefinitely — never writing `sink.db`, never running the audit, never
   logging anything. It had to be SIGTERM'd.
2. **Empty LLM seed.** `sim seed` logged "Ollama returned 0 concrete scenario specs", silently
   degrading runs to honest + velocity traffic only.

Investigation also found that much of the expected pipeline was **unimplemented, not broken**:
the k6/grafana load phase, `raw_decisions.parquet`, the `reports/run-latest` symlink, the
scripted/LLM adversary personas, and the evasion/rule-coverage/security scenario replay were all
defined but never wired into `sim run` (the runner shipped only the Phase-3 honest-persona core).

## Root causes (confirmed)

- **Deadlock = architectural, not a data bug.** `submit_transaction` never raises and
  `DecisionResponse.matchedRules` defaults to an empty list, so the sink writer cannot die from row
  data. The fragility was structural: the `SqliteSink` writer ran via a bare, **unsupervised**
  `asyncio.create_task`; producers wrote through a **bounded** `asyncio.Queue(maxsize=1024)` with a
  blocking `put()`; `HonestPersona.run` had **no deadline guard** on `record()` (the 600 s deadline
  only guarded `sleep`); and there was **no run-level watchdog**. Any writer stall therefore filled
  the queue and blocked every producer forever inside the `TaskGroup`, so `sink.close()` was never
  reached and no artifacts were finalized — silently, because there was **no `logging.basicConfig`**
  (every `LOG.info` was dropped) and stdout was block-buffered on a non-TTY.
- **Empty seed = atomic validation + silent swallow.** llama3.1:8b returns ~15 well-formed
  scenarios, but a few fixed-amount archetypes emit equal low/high amount bounds. The code validated
  the whole array atomically (`ScenarioGenerationResult.model_validate_json`), so one bad item failed
  the batch, and `except ValidationError: return []` discarded all of them — including the valid
  majority — with exit code 0.

## Decision

1. **Make the deadlock impossible by construction.**
   - The sink writer is crash-proof (a bad insert is skipped + counted, never fatal) and signals a
     `_writer_failed` event on catastrophic failure so producers fast-drop instead of blocking on a
     dead consumer. `close()` bounds its drain with a timeout and cancels a stalled writer.
   - The runner wraps the traffic phases in an `asyncio.timeout(duration + grace)` watchdog and runs
     `sink.close()` in a `finally`, so the run **always** terminates and finalizes artifacts — even
     on timeout, cancellation, or a phase exception.
   - `configure_logging()` (INFO to stderr, `force=True`) + line-buffered stdout + per-stage and
     periodic progress logs make runs observable.
2. **Fail loud on seed expansion.** Validate each scenario independently (keep the valid subset),
   log rejected items with reasons, and raise `ScenarioGenerationError` when zero survive — `sim seed`
   exits non-zero; `sim run` logs loudly and degrades to honest + scripted rather than aborting.
3. **Wire the full pipeline.** `sim run` now runs honest + scripted (evasion/rule-coverage) +
   LLM-adversary personas concurrently, then the security-probe suite; finalization exports
   `raw_decisions.parquet`, runs k6 (graceful no-op without docker), folds security + k6 results into
   the audit report, and updates the `reports/run-latest` symlink. A new
   `spec_to_fraud_scenario` bridge materialises LLM specs into replayable transactions.

## Consequences

- The honest baseline volume is sized to fill the remainder of `total_tx_target` after the
  deterministic scenario steps; the total is therefore **approximate**, not exactly 10,000.
- New dependency: **`pyarrow`** (required by pandas to write parquet).
- LLM adversary specs exercise amount/velocity variety; geo/device/cross-border coverage comes from
  the curated scripted scenarios, by design.
- Verified: full suite 115 passed, ruff clean; `sim seed` returns 15 specs against live
  llama3.1:8b (was 0). The Bug-1 fix is proven by unit tests (sink hardening) and an integration
  test (runner watchdog terminates a wedged run and still finalizes). A live full `sim run`
  additionally requires the engine stack (postgres/redis/redpanda + app jar) to be up.
