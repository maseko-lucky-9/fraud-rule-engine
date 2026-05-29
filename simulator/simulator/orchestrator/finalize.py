"""Post-run finalization helpers.

Turns a completed run directory into the full deliverable set:

- ``raw_decisions.parquet`` — the sink rows exported for downstream analysis.
- a ``reports/run-latest`` symlink pointing at the newest run.
- an optional k6 load-test summary (graceful no-op when docker is absent).

Every helper is defensive: a missing sink, absent docker, or an unsupported
filesystem (no symlink) logs and degrades rather than aborting finalization.
"""

from __future__ import annotations

import logging
import sqlite3
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Mapping

    from simulator.transport.k6_wrapper import K6Summary

LOG = logging.getLogger("simulator.finalize")


def export_decisions_parquet(*, sink_db: Path, out_path: Path) -> Path | None:
    """Export every sink row to parquet. Returns the path, or None if skipped."""
    import pandas as pd

    if not sink_db.is_file():
        LOG.warning("parquet export skipped: no sink db at %s", sink_db)
        return None
    with sqlite3.connect(sink_db) as conn:
        frame = pd.read_sql_query("SELECT * FROM submissions ORDER BY id", conn)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    frame.to_parquet(out_path, index=False)
    LOG.info("wrote %d decision rows to %s", len(frame), out_path)
    return out_path


def update_run_latest_symlink(*, reports_root: Path, run_dir: Path) -> None:
    """Point ``reports/run-latest`` at ``run_dir`` (best-effort)."""
    link = reports_root / "run-latest"
    try:
        if link.is_symlink() or link.exists():
            link.unlink()
        link.symlink_to(run_dir.resolve(), target_is_directory=True)
        LOG.info("run-latest -> %s", run_dir.name)
    except OSError as exc:
        LOG.warning("could not update run-latest symlink: %r", exc)


def _resolve_k6_script(raw_path: str) -> Path:
    script = Path(raw_path)
    if not script.is_absolute():
        # Relative to the simulator project dir (this package's parents[2]).
        script = (Path(__file__).resolve().parents[2] / script).resolve()
    return script


async def maybe_run_k6(*, profile: Mapping, rest_base_url: str) -> K6Summary | None:
    """Run the engine's k6 load script if enabled. None when disabled."""
    load = dict(profile.get("scenarios", {}).get("load", {}))
    if not load.get("enabled"):
        LOG.info("k6 load disabled in profile — skipping")
        return None

    import httpx

    from simulator.transport.auth import JwtMinter
    from simulator.transport.k6_wrapper import run_k6_load

    async with httpx.AsyncClient(timeout=10.0) as client:
        minter = JwtMinter(rest_base_url=rest_base_url)
        try:
            token = await minter.get("k6-load", client)
        except Exception as exc:  # noqa: BLE001
            LOG.warning("k6 skipped: could not mint token: %r", exc)
            return None

    summary = await run_k6_load(
        k6_script_path=_resolve_k6_script(load.get("k6_script_path", "../k6/load.js")),
        api_base_url=rest_base_url,
        api_token=token,
        target_rps=int(load.get("target_rps", 150)),
        duration_seconds=int(load.get("duration_seconds", 60)),
        docker_image=load.get("k6_image", "grafana/k6:latest"),
    )
    LOG.info(
        "k6 load: available=%s iterations=%d error_rate=%.3f",
        summary.available, summary.iterations, summary.error_rate,
    )
    return summary
