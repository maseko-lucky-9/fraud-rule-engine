"""Wrap the engine's existing ``k6/load.js`` via a docker container.

We deliberately do not re-implement k6's RPS shape in Python — the engine
already ships and exercises a tuned k6 script (plan §3 reused-asset list).
This wrapper:

1. Spawns the ``grafana/k6`` container on the host network so it can hit
   the engine on ``localhost:8090``.
2. Captures ``--summary-export=summary.json`` and parses it into a
   :class:`K6Summary`.
3. Returns the summary for the sink + audit pipelines to merge.

The container is a one-shot — k6 exits on completion and the wrapper
joins. If docker is missing or the engine is unreachable, the wrapper
returns ``K6Summary.unavailable()`` so the rest of the audit still runs.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import shutil
import tempfile
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(slots=True, frozen=True)
class K6Summary:
    """Subset of the k6 summary.json we care about for the audit."""

    available: bool
    duration_seconds: float
    iterations: int
    failed_iterations: int
    http_req_p50_ms: float
    http_req_p95_ms: float
    http_req_p99_ms: float
    error_rate: float
    raw: dict = field(default_factory=dict)

    @classmethod
    def unavailable(cls, reason: str) -> K6Summary:
        return cls(
            available=False, duration_seconds=0.0, iterations=0,
            failed_iterations=0, http_req_p50_ms=0.0, http_req_p95_ms=0.0,
            http_req_p99_ms=0.0, error_rate=0.0, raw={"error": reason},
        )


def _parse_summary(payload: dict) -> K6Summary:
    metrics = payload.get("metrics", {})
    http_req = metrics.get("http_req_duration", {}).get("values", {})
    iterations = metrics.get("iterations", {}).get("values", {})
    checks = metrics.get("checks", {}).get("values", {})

    return K6Summary(
        available=True,
        duration_seconds=float(payload.get("state", {}).get("testRunDurationMs", 0)) / 1000.0,
        iterations=int(iterations.get("count", 0)),
        failed_iterations=int(metrics.get("http_req_failed", {}).get("values", {}).get("fails", 0)),
        http_req_p50_ms=float(http_req.get("med", 0.0)),
        http_req_p95_ms=float(http_req.get("p(95)", 0.0)),
        http_req_p99_ms=float(http_req.get("p(99)", 0.0)),
        error_rate=float(checks.get("rate", 0.0)) if checks else 0.0,
        raw=payload,
    )


async def run_k6_load(
    *,
    k6_script_path: Path,
    api_base_url: str,
    api_token: str,
    target_rps: int = 150,
    duration_seconds: int = 60,
    docker_image: str = "grafana/k6:latest",
    docker_binary: str = "docker",
) -> K6Summary:
    """Spawn k6 in a docker container and capture its summary.

    Note: this function uses :func:`asyncio.create_subprocess_exec`, which
    invokes ``docker`` directly without a shell — there is no command
    injection surface even though the function name hints at it. All
    arguments are bound to a fixed argv tuple.
    """
    if shutil.which(docker_binary) is None:
        return K6Summary.unavailable(f"{docker_binary} not on PATH")

    # One-shot stat is fine here — this runs once at the start of the
    # load phase and the event loop has nothing else inflight.
    if not k6_script_path.is_file():  # noqa: ASYNC240
        return K6Summary.unavailable(f"k6 script missing: {k6_script_path}")

    with tempfile.TemporaryDirectory() as tmp_dir:
        summary_host = Path(tmp_dir) / "summary.json"
        argv = (
            docker_binary, "run", "--rm",
            "--network=host",
            "-v", f"{k6_script_path.parent.resolve()}:/scripts:ro",
            "-v", f"{tmp_dir}:/out",
            "-e", f"API_BASE={api_base_url}",
            "-e", f"API_TOKEN={api_token}",
            "-e", f"TARGET_RPS={target_rps}",
            "-e", f"DURATION={duration_seconds}s",
            docker_image,
            "run", "--summary-export=/out/summary.json",
            f"/scripts/{k6_script_path.name}",
        )
        # k6 streams run progress + the end-of-run summary to stdout at
        # volume (easily > the ~64 KB OS pipe buffer at 150 rps). We never
        # read that stdout — the machine-readable result is
        # --summary-export'd to the /out volume — so piping it without
        # draining would fill the buffer, block k6's write(), and hang
        # process.wait() forever (the original finalize-phase deadlock).
        # Discard stdout; keep stderr (small, useful for diagnostics) and
        # drain it via communicate(), never the bare wait() the asyncio
        # docs warn against with live pipes.
        process = await asyncio.create_subprocess_exec(
            *argv,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            _, stderr = await asyncio.wait_for(
                process.communicate(), timeout=duration_seconds + 120
            )
        except TimeoutError:
            process.kill()
            with contextlib.suppress(Exception):
                await process.communicate()  # reap; never re-hang on the pipe
            return K6Summary.unavailable("k6 container exceeded duration + 120 s")

        if process.returncode != 0:
            tail = (stderr or b"").decode("utf-8", "replace").strip()[-300:]
            return K6Summary.unavailable(
                f"k6 exited {process.returncode}: {tail or '(no stderr)'}"
            )

        if not summary_host.is_file():
            return K6Summary.unavailable("k6 produced no summary.json")

        try:
            return _parse_summary(json.loads(summary_host.read_text()))
        except (json.JSONDecodeError, KeyError, TypeError) as exc:
            return K6Summary.unavailable(f"summary parse error: {exc!r}")
