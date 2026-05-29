"""Run-loop orchestrator.

Drives the full pen-test run as a sequence of phases, all feeding one
:class:`SqliteSink`:

- **A. Honest** — benign Poisson traffic (the green-path baseline).
- **B. Scripted adversaries** — the deterministic evasion + rule-coverage
  scenarios from :func:`load_default_registry` (this is what makes the
  cross-border / blacklist / off-hours / new-device / structuring rules
  actually fire).
- **C. LLM adversaries** — Ollama-expanded scenario specs materialised into
  replayable transactions (skipped under ``--no-llm`` / when Ollama is down;
  a seed failure degrades the run, never aborts it).
- **D. Security probes** — JWT tampering, idempotency, rate-limit, fuzzing.

Two invariants protect against the original finalization deadlock (Bug 1):

1. The whole traffic phase is bounded by an ``asyncio.timeout`` watchdog, so
   the run always terminates even if a task wedges.
2. ``sink.close()`` runs in a ``finally``, so artifacts are always written —
   even on timeout, cancellation, or a phase exception.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

import httpx

from simulator.agents.personas.honest import HonestPersona
from simulator.agents.personas.scripted_adversary import ScriptedAdversaryPersona
from simulator.orchestrator.account_pool import build_account_pool
from simulator.scenarios import FraudScenario, load_default_registry
from simulator.scenarios.security_runner import ProbeOutcome, SecurityProbeRunner
from simulator.telemetry.sink import SqliteSink
from simulator.transport.auth import JwtMinter
from simulator.transport.models import Channel
from simulator.transport.rest import FraudEngineRestClient

LOG = logging.getLogger("simulator.runner")

_SEEDS_PATH = Path(__file__).resolve().parents[1] / "config" / "scenarios.seed.yaml"
_PROGRESS_INTERVAL_SECONDS = 15.0
_DEFAULT_WATCHDOG_GRACE_SECONDS = 120.0


@dataclass(slots=True, frozen=True)
class RunSummary:
    run_id: str
    accounts: int
    submissions_made: int
    sink_path: Path
    started_at: datetime
    finished_at: datetime
    honest_submissions: int = 0
    scripted_submissions: int = 0
    llm_submissions: int = 0
    llm_specs: int = 0
    security_outcomes: tuple[ProbeOutcome, ...] = field(default_factory=tuple)
    timed_out: bool = False

    @property
    def wall_clock_seconds(self) -> float:
        return (self.finished_at - self.started_at).total_seconds()


async def _safe_run(make_coro: Callable[[], Awaitable[int]], label: str) -> int:
    """Run one persona task, swallowing its own errors so a single failure
    can't cancel the whole TaskGroup. Cancellation still propagates."""
    try:
        return await make_coro()
    except asyncio.CancelledError:
        raise
    except Exception as exc:  # noqa: BLE001 — isolate per-task failures
        LOG.error("task %s failed: %r", label, exc)
        return 0


async def _gated(sem: asyncio.Semaphore, make_coro: Callable[[], Awaitable[int]], label: str) -> int:
    async with sem:
        return await _safe_run(make_coro, label)


async def _progress_logger(sink: SqliteSink) -> None:
    while True:
        await asyncio.sleep(_PROGRESS_INTERVAL_SECONDS)
        LOG.info(
            "progress: %d rows persisted, %d dropped (writer_failed=%s)",
            sink.row_count, sink.dropped, sink.writer_failed,
        )


async def _expand_llm_scenarios(
    *, seed: int, llm_count: int,
) -> list[FraudScenario]:
    """Phase C setup — expand seeds via Ollama and materialise to scenarios.

    Returns [] (logging loudly) on any LLM failure; never raises, so a flaky
    local Ollama degrades the run to honest+scripted rather than aborting it.
    """
    from simulator.llm.ollama_client import OllamaConfig, OllamaWrapper, ollama_available
    from simulator.llm.scenario_generator import (
        ScenarioGenerationError,
        generate_scenarios,
        load_seed_archetypes,
    )
    from simulator.scenarios.spec_bridge import spec_to_fraud_scenario

    config = OllamaConfig()
    if not await ollama_available(config):
        LOG.warning("phase C (LLM): Ollama unavailable — skipping LLM adversaries")
        return []
    try:
        archetypes = load_seed_archetypes(_SEEDS_PATH)
        specs = await generate_scenarios(archetypes, OllamaWrapper(config))
    except ScenarioGenerationError as exc:
        LOG.error("phase C (LLM): seed expansion failed, degrading: %s", exc)
        return []
    except Exception as exc:  # noqa: BLE001
        LOG.error("phase C (LLM): unexpected expansion error, degrading: %r", exc)
        return []

    if llm_count > 0:
        specs = specs[:llm_count]
    scenarios = [spec_to_fraud_scenario(s, seed=seed) for s in specs]
    LOG.info("phase C (LLM): %d scenarios materialised from %d specs", len(scenarios), len(specs))
    return scenarios


async def run_simulation(
    *,
    rest_base_url: str,
    profile: Mapping,
    sink_path: Path,
    run_id: str | None = None,
) -> RunSummary:
    """Execute the full multi-phase simulation against the engine."""
    run_id = run_id or f"sim-{datetime.now(tz=UTC):%Y%m%dT%H%M%SZ}"
    started_at = datetime.now(tz=UTC)

    accounts_cfg = profile["accounts"]
    agents_cfg = profile["agents"]
    run_cfg = profile["run"]

    seed = int(run_cfg["seed"])
    duration = float(run_cfg["duration_seconds"])
    grace = float(run_cfg.get("watchdog_grace_seconds", _DEFAULT_WATCHDOG_GRACE_SECONDS))
    target_tx = int(run_cfg.get("total_tx_target", 0))
    cap = int(agents_cfg.get("llm_concurrency_cap", 10))
    honest_count = int(agents_cfg.get("honest_count", int(accounts_cfg["count"])))
    llm_count = int(agents_cfg.get("llm_adversary_count", 0))

    from simulator.llm.ollama_client import is_no_llm_mode
    no_llm = is_no_llm_mode()

    accounts = build_account_pool(
        count=int(accounts_cfg["count"]),
        seed=seed,
        age_buckets=accounts_cfg["account_age_buckets"],
        primary_country=accounts_cfg.get("primary_country", "ZA"),
        cross_border_share=float(accounts_cfg.get("cross_border_share", 0.10)),
        channels=[Channel(c) for c in accounts_cfg.get("channels", [c.value for c in Channel])],
    )

    # Curated scenarios are deterministic and known up-front; size the honest
    # baseline to fill the remainder of the (approximate) tx target.
    registry = load_default_registry(seed=seed)
    scripted_total = sum(len(s.steps) for s in registry.all())
    honest_accounts = accounts[:honest_count]
    if target_tx:
        honest_budget = max(honest_count, target_tx - scripted_total)
        max_per_persona = max(1, honest_budget // max(1, honest_count))
    else:
        max_per_persona = None

    LOG.info(
        "run %s: %d accounts, %d honest personas (max_tx=%s), %d scripted scenarios "
        "(%d steps), target≈%d, duration=%.0fs (+%.0fs grace), no_llm=%s",
        run_id, len(accounts), len(honest_accounts), max_per_persona,
        registry.count, scripted_total, target_tx, duration, grace, no_llm,
    )

    sink = SqliteSink(sink_path)
    await sink.start()

    honest_subs = scripted_subs = llm_subs = 0
    llm_scenarios: list[FraudScenario] = []
    security_outcomes: list[ProbeOutcome] = []
    timed_out = False
    minter = JwtMinter(rest_base_url=rest_base_url)
    progress_task = asyncio.create_task(_progress_logger(sink), name="progress-logger")

    try:
        async with httpx.AsyncClient(timeout=10.0) as http_client:
            rest = FraudEngineRestClient(
                rest_base_url=rest_base_url, minter=minter, client=http_client,
            )
            try:
                async with asyncio.timeout(duration + grace):
                    if not no_llm:
                        llm_scenarios = await _expand_llm_scenarios(seed=seed, llm_count=llm_count)

                    sem = asyncio.Semaphore(cap)
                    honest_tasks: list[asyncio.Task[int]] = []
                    scripted_tasks: list[asyncio.Task[int]] = []
                    llm_tasks: list[asyncio.Task[int]] = []

                    LOG.info("phase: traffic start (honest + scripted + llm, concurrent)")
                    async with asyncio.TaskGroup() as tg:
                        for acc in honest_accounts:
                            persona = HonestPersona(
                                account=acc, rest=rest, sink=sink, run_id=run_id, seed=seed,
                            )
                            honest_tasks.append(tg.create_task(
                                _safe_run(
                                    lambda p=persona: p.run(
                                        duration_seconds=duration, max_tx=max_per_persona,
                                    ),
                                    f"honest-{acc.account_id}",
                                ),
                                name=f"honest-{acc.account_id}",
                            ))
                        for scenario in registry.all():
                            persona = ScriptedAdversaryPersona(
                                scenario=scenario, rest=rest, sink=sink, run_id=run_id,
                                subject=f"adv-{scenario.id}", role_label="ADVERSARY_SCRIPTED",
                            )
                            scripted_tasks.append(tg.create_task(
                                _gated(sem, persona.run, f"scripted-{scenario.id}"),
                                name=f"scripted-{scenario.id}",
                            ))
                        for scenario in llm_scenarios:
                            persona = ScriptedAdversaryPersona(
                                scenario=scenario, rest=rest, sink=sink, run_id=run_id,
                                subject=f"llm-{scenario.id}", role_label="ADVERSARY_LLM",
                            )
                            llm_tasks.append(tg.create_task(
                                _gated(sem, persona.run, f"llm-{scenario.id}"),
                                name=f"llm-{scenario.id}",
                            ))

                    honest_subs = sum(t.result() for t in honest_tasks)
                    scripted_subs = sum(t.result() for t in scripted_tasks)
                    llm_subs = sum(t.result() for t in llm_tasks)
                    LOG.info(
                        "phase: traffic done — honest=%d scripted=%d llm=%d",
                        honest_subs, scripted_subs, llm_subs,
                    )

                    security_outcomes = await _run_security_phase(
                        rest_base_url=rest_base_url, minter=minter, http_client=http_client,
                    )
            except TimeoutError:
                timed_out = True
                LOG.error(
                    "run watchdog fired after %.0fs — finalizing partial run", duration + grace,
                )
    finally:
        progress_task.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await progress_task
        await sink.close()

    finished_at = datetime.now(tz=UTC)
    submissions = honest_subs + scripted_subs + llm_subs
    LOG.info(
        "run %s complete: %d submissions persisted=%d (honest=%d scripted=%d llm=%d specs=%d) "
        "security_probes=%d timed_out=%s in %.1fs",
        run_id, submissions, sink.row_count, honest_subs, scripted_subs, llm_subs,
        len(llm_scenarios), len(security_outcomes), timed_out,
        (finished_at - started_at).total_seconds(),
    )

    return RunSummary(
        run_id=run_id,
        accounts=len(accounts),
        submissions_made=submissions,
        sink_path=sink_path,
        started_at=started_at,
        finished_at=finished_at,
        honest_submissions=honest_subs,
        scripted_submissions=scripted_subs,
        llm_submissions=llm_subs,
        llm_specs=len(llm_scenarios),
        security_outcomes=tuple(security_outcomes),
        timed_out=timed_out,
    )


async def _run_security_phase(
    *, rest_base_url: str, minter: JwtMinter, http_client: httpx.AsyncClient,
) -> list[ProbeOutcome]:
    """Phase D — run the security probe suite. Never raises."""
    try:
        token = await minter.get("security-probe", http_client)
        async with SecurityProbeRunner(
            rest_base_url=rest_base_url, valid_bearer_token=token,
        ) as runner:
            outcomes = await runner.run_all()
        passed = sum(1 for o in outcomes if o.passed)
        LOG.info("phase: security done — %d/%d probes passed", passed, len(outcomes))
        return outcomes
    except asyncio.CancelledError:
        raise
    except Exception as exc:  # noqa: BLE001
        LOG.error("phase: security failed: %r", exc)
        return []
