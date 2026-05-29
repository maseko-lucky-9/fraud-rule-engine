"""``sim`` CLI entry point.

Subcommands:
- ``sim reset``  — TRUNCATE engine PG tables for a clean run.
- ``sim seed``   — expand seed YAML into concrete scenarios via Ollama.
- ``sim run``    — execute a default-profile run end-to-end.
- ``sim audit``  — re-render the audit report for an existing run dir.
- ``sim replay`` — read a sink DB and reprint summary metrics.

This is a thin orchestrator — most logic lives in the orchestrator,
analysis, and transport modules. Keep flags few and obvious.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import sys
from datetime import UTC
from pathlib import Path

import typer
import yaml
from rich.console import Console

from simulator.analysis.auditor import build_audit_report
from simulator.analysis.metrics import compute_run_metrics
from simulator.telemetry.sink import deterministic_run_hash
from simulator.transport.pg_admin import EnvFileMissing, PostgresConfig, reset_decisions

app = typer.Typer(
    add_completion=False,
    no_args_is_help=True,
    help="Distributed pen-test harness for the fraud-rule-engine.",
)
console = Console()

DEFAULT_PROFILE = Path(__file__).resolve().parent / "config" / "profile.default.yaml"
DEFAULT_REPORTS_ROOT = Path(__file__).resolve().parent / "reports"
DEFAULT_ENGINE_ENV = (
    Path(__file__).resolve().parent.parent / ".env"
)

# Evasion scenarios whose expected current-engine outcome is a "miss" (gap) —
# shared by the standalone `audit` command and the run finalization path.
_EVASION_EXPECTATIONS: dict[str, str] = {
    "evasion_card_testing": "miss",
    "evasion_structuring": "miss",
    "evasion_ato_geo_leap": "partial",
    "evasion_synthetic_identity": "miss",
    "evasion_device_entropy": "miss",
    "evasion_geo_impossibility": "miss",
}


def _configure_logging() -> None:
    """Send INFO logs to stderr and line-buffer stdout.

    Without this, `LOG.info` progress is dropped entirely (no root handler)
    and `rich`/print output is block-buffered on a non-TTY — together the
    cause of the empty run log in Bug 1.
    """
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s | %(message)s",
        stream=sys.stderr,
        force=True,
    )
    with contextlib.suppress(Exception):
        sys.stdout.reconfigure(line_buffering=True)  # type: ignore[union-attr]
        sys.stderr.reconfigure(line_buffering=True)  # type: ignore[union-attr]


def _load_profile(path: Path) -> dict:
    if not path.is_file():
        console.print(f"[red]profile not found: {path}[/red]")
        raise typer.Exit(code=1)
    return yaml.safe_load(path.read_text())


# --------------------------------------------------------------------------- #
# reset
# --------------------------------------------------------------------------- #


@app.command()
def reset(
    truncate: bool = typer.Option(
        False, "--truncate", help="TRUNCATE the engine's decision tables for a fresh run.",
    ),
    env_file: Path = typer.Option(
        DEFAULT_ENGINE_ENV,
        "--env-file", help="Path to the engine's .env (sources PG credentials).",
    ),
) -> None:
    """Reset the engine's state for a clean simulation run."""
    if not truncate:
        console.print("[yellow]Nothing to do — pass --truncate to clear engine PG tables.[/yellow]")
        return

    try:
        config = PostgresConfig.from_env_file(env_file)
    except EnvFileMissing as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(code=1) from exc

    try:
        reset_decisions(config)
    except Exception as exc:  # noqa: BLE001
        console.print(f"[red]PG reset failed: {exc!r}[/red]")
        raise typer.Exit(code=1) from exc

    console.print("[green]Engine decision tables truncated.[/green]")


# --------------------------------------------------------------------------- #
# seed
# --------------------------------------------------------------------------- #


@app.command()
def seed(
    seeds_path: Path = typer.Option(
        Path(__file__).resolve().parent / "config" / "scenarios.seed.yaml",
        "--seeds", help="Path to scenarios.seed.yaml.",
    ),
    no_llm: bool = typer.Option(
        False, "--no-llm",
        help="Skip Ollama expansion; print the seed catalogue and exit.",
    ),
) -> None:
    """Expand the seed archetype catalogue (Ollama, or print on --no-llm)."""
    _configure_logging()
    from simulator.llm.ollama_client import OllamaConfig, OllamaWrapper, is_no_llm_mode
    from simulator.llm.scenario_generator import (
        ScenarioGenerationError,
        generate_scenarios,
        load_seed_archetypes,
    )

    archetypes = load_seed_archetypes(seeds_path)
    console.print(f"Loaded {len(archetypes)} seed archetypes from {seeds_path}.")

    if no_llm or is_no_llm_mode():
        for arc in archetypes:
            console.print(f"- {arc['id']} ({arc['archetype']}) → {arc['expected_outcome']}")
        return

    wrapper = OllamaWrapper(OllamaConfig())
    try:
        expanded = asyncio.run(generate_scenarios(archetypes, wrapper))
    except ScenarioGenerationError as exc:
        console.print(f"[red]seed expansion failed: {exc}[/red]")
        raise typer.Exit(code=1) from exc

    console.print(f"[green]Ollama returned {len(expanded)} concrete scenario specs.[/green]")
    for spec in expanded[:10]:
        console.print(f"  · {spec.id} ({spec.archetype}, expected {spec.expected_outcome})")


# --------------------------------------------------------------------------- #
# run (Phase 9 ships the deterministic-core path; full multi-persona run is
# already wired through orchestrator/runner.py and reused here).
# --------------------------------------------------------------------------- #


@app.command(name="run")
def run_simulation(
    profile: Path = typer.Option(
        DEFAULT_PROFILE, "--profile", help="Profile YAML to load.",
    ),
    no_llm: bool = typer.Option(
        False, "--no-llm", help="Skip every Ollama call (CI-safe).",
    ),
    seed: int | None = typer.Option(
        None, "--seed", help="Override profile.run.seed.",
    ),
    output_dir: Path | None = typer.Option(
        None, "--output", help="Directory for sink + report (auto-named if omitted).",
    ),
) -> None:
    """Execute the simulation end-to-end against the engine.

    Honours the profile's run.duration_seconds and accounts.count. Spawns
    the orchestrator's TaskGroup of honest personas plus the scripted-
    adversary scenarios already wired through analysis.
    """
    _configure_logging()
    from simulator.orchestrator.runner import run_simulation as _run

    profile_data = _load_profile(profile)
    if seed is not None:
        profile_data["run"]["seed"] = int(seed)

    output_dir = output_dir or DEFAULT_REPORTS_ROOT / f"run-{_timestamp_slug()}"
    output_dir.mkdir(parents=True, exist_ok=True)
    sink_path = output_dir / profile_data["output"]["sink_db_filename"]

    if no_llm:
        import os
        os.environ["SIM_NO_LLM"] = "1"

    rest_base = profile_data["engine"]["rest_base_url"]
    summary = asyncio.run(_run(rest_base_url=rest_base, profile=profile_data, sink_path=sink_path))
    timeout_note = " [yellow](watchdog fired — partial run)[/yellow]" if summary.timed_out else ""
    console.print(
        f"[green]run {summary.run_id} complete[/green]{timeout_note} · "
        f"{summary.submissions_made} submissions "
        f"(honest={summary.honest_submissions} scripted={summary.scripted_submissions} "
        f"llm={summary.llm_submissions}) over {summary.wall_clock_seconds:.1f} s · "
        f"sink: {summary.sink_path}"
    )

    # Finalize: parquet export + k6 load + audit (with security/k6) + run-latest symlink.
    asyncio.run(_finalize_run(output_dir, summary=summary, profile=profile_data,
                              rest_base_url=rest_base, no_llm=no_llm))


# --------------------------------------------------------------------------- #
# audit / replay
# --------------------------------------------------------------------------- #


@app.command()
def audit(
    run_dir: Path = typer.Argument(..., help="Per-run directory (must contain sink.db)."),
    no_llm: bool = typer.Option(
        False, "--no-llm", help="Skip Ollama narration; use the deterministic fallback.",
    ),
) -> None:
    """Re-render the audit report for an existing run directory."""
    _configure_logging()
    asyncio.run(_audit_dir(run_dir, run_id=run_dir.name, no_llm=no_llm))


@app.command()
def replay(
    sink_db: Path = typer.Argument(..., help="Path to a sink.db file."),
) -> None:
    """Print summary metrics + deterministic-run-hash from a sink DB."""
    metrics = compute_run_metrics(db_path=sink_db, run_id=sink_db.parent.name)
    console.print(f"Total submissions: {metrics.total_submissions}")
    console.print(f"Status counts:     {metrics.status_counts}")
    console.print(f"Latency p99:       {metrics.latency_ms['p99']:.1f} ms")
    console.print(f"Deterministic hash: {deterministic_run_hash(sink_db)}")


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #


def _timestamp_slug() -> str:
    from datetime import datetime
    return datetime.now(tz=UTC).strftime("%Y%m%dT%H%M%SZ")


async def _audit_dir(run_dir: Path, *, run_id: str, no_llm: bool) -> None:
    sink = run_dir / "sink.db"
    if not sink.is_file():
        console.print(f"[red]no sink.db in {run_dir}[/red]")
        sys.exit(1)

    from simulator.llm.ollama_client import OllamaConfig, OllamaWrapper, ollama_available

    wrapper: OllamaWrapper | None = None
    if not no_llm:
        config = OllamaConfig()
        if await ollama_available(config):
            wrapper = OllamaWrapper(config)

    report_path = await build_audit_report(
        db_path=sink, run_id=run_id, output_dir=run_dir,
        ollama_wrapper=wrapper, evasion_expectations=_EVASION_EXPECTATIONS,
    )
    console.print(f"[green]audit written: {report_path}[/green]")


async def _finalize_run(
    output_dir: Path,
    *,
    summary: object,
    profile: dict,
    rest_base_url: str,
    no_llm: bool,
) -> None:
    """Post-run: parquet export, k6 load, audit (with security/k6), run-latest."""
    from simulator.llm.ollama_client import OllamaConfig, OllamaWrapper, ollama_available
    from simulator.orchestrator.finalize import (
        export_decisions_parquet,
        maybe_run_k6,
        update_run_latest_symlink,
    )

    output_cfg = profile["output"]
    sink = output_dir / output_cfg["sink_db_filename"]
    parquet_name = output_cfg.get("decisions_parquet_filename", "raw_decisions.parquet")
    with contextlib.suppress(Exception):
        export_decisions_parquet(sink_db=sink, out_path=output_dir / parquet_name)

    k6_summary = None
    try:
        k6_summary = await maybe_run_k6(profile=profile, rest_base_url=rest_base_url)
    except Exception as exc:  # noqa: BLE001
        console.print(f"[yellow]k6 load skipped: {exc!r}[/yellow]")

    if not sink.is_file():
        console.print(f"[red]no sink.db in {output_dir}; skipping audit[/red]")
    else:
        wrapper: OllamaWrapper | None = None
        if not no_llm:
            config = OllamaConfig()
            if await ollama_available(config):
                wrapper = OllamaWrapper(config)
        report_path = await build_audit_report(
            db_path=sink,
            run_id=getattr(summary, "run_id", output_dir.name),
            output_dir=output_dir,
            ollama_wrapper=wrapper,
            evasion_expectations=_EVASION_EXPECTATIONS,
            security_outcomes=getattr(summary, "security_outcomes", ()),
            k6_summary=k6_summary,
        )
        console.print(f"[green]audit written: {report_path}[/green]")

    update_run_latest_symlink(reports_root=output_dir.parent, run_dir=output_dir)


if __name__ == "__main__":
    app()
