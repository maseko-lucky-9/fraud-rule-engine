"""K6 wrapper tests — parsing, missing-tool fallback, and pipe-drain safety."""

from __future__ import annotations

import asyncio
import sys
import textwrap
from pathlib import Path

import pytest

from simulator.transport.k6_wrapper import K6Summary, _parse_summary, run_k6_load


def test_k6_summary_unavailable_carries_reason() -> None:
    summary = K6Summary.unavailable("docker missing")
    assert summary.available is False
    assert summary.raw == {"error": "docker missing"}


def test_parse_summary_extracts_percentiles() -> None:
    payload = {
        "state": {"testRunDurationMs": 60500},
        "metrics": {
            "http_req_duration": {"values": {"med": 25.0, "p(95)": 180.0, "p(99)": 350.0}},
            "iterations": {"values": {"count": 9000}},
            "http_req_failed": {"values": {"fails": 12}},
            "checks": {"values": {"rate": 0.99}},
        },
    }
    summary = _parse_summary(payload)
    assert summary.available
    assert summary.duration_seconds == 60.5
    assert summary.iterations == 9000
    assert summary.failed_iterations == 12
    assert summary.http_req_p50_ms == 25.0
    assert summary.http_req_p95_ms == 180.0
    assert summary.http_req_p99_ms == 350.0
    assert summary.error_rate == 0.99


def test_parse_summary_tolerates_missing_metrics() -> None:
    summary = _parse_summary({})
    assert summary.available
    assert summary.iterations == 0
    assert summary.http_req_p99_ms == 0.0


@pytest.mark.asyncio
async def test_run_k6_returns_unavailable_when_script_missing(tmp_path: Path) -> None:
    missing = tmp_path / "does-not-exist.js"
    summary = await run_k6_load(
        k6_script_path=missing,
        api_base_url="http://localhost:8090",
        api_token="tok",
        docker_binary="docker",
    )
    # Docker may or may not be on PATH in CI; either way the call must
    # land on an `unavailable` summary without raising.
    assert summary.available is False
    assert summary.raw["error"]


@pytest.mark.asyncio
async def test_run_k6_returns_unavailable_when_docker_missing(tmp_path: Path) -> None:
    script = tmp_path / "load.js"
    script.write_text("// placeholder")
    summary = await run_k6_load(
        k6_script_path=script,
        api_base_url="http://localhost:8090",
        api_token="tok",
        docker_binary="docker-does-not-exist-12345",
    )
    assert summary.available is False
    assert "not on PATH" in summary.raw["error"]


def _write_fake_docker(path: Path, body: str) -> Path:
    """Write an executable fake `docker` that runs `body` (a Python snippet)."""
    path.write_text(f"#!{sys.executable}\n" + textwrap.dedent(body))
    path.chmod(0o755)
    return path


@pytest.mark.asyncio
async def test_run_k6_does_not_hang_on_high_volume_stdout(tmp_path: Path) -> None:
    """Regression for the finalize-phase deadlock.

    A fake container floods stdout with far more than one OS pipe buffer
    (~64 KB) and writes summary.json to the /out bind-mount. The old code
    PIPE'd stdout and joined via ``process.wait()``, which blocked forever
    once the unread pipe filled. With stdout discarded + ``communicate()``
    draining, this completes promptly. The outer ``wait_for`` is the
    regression tripwire: if the hang returns, it raises instead of blocking.
    """
    script = tmp_path / "load.js"
    script.write_text("// placeholder — fake docker ignores it")

    fake_docker = _write_fake_docker(
        tmp_path / "docker",
        """
        import sys, os, json
        # Flood stdout well past the pipe buffer to trigger the old hang.
        sys.stdout.write("X" * (2 * 1024 * 1024))
        sys.stdout.flush()
        # Recover the host out-dir from the -v "<host>:/out" argument.
        out_host = None
        for i, a in enumerate(sys.argv):
            if a == "-v" and i + 1 < len(sys.argv):
                host, _, container = sys.argv[i + 1].partition(":")
                if container.startswith("/out"):
                    out_host = host
        if out_host:
            with open(os.path.join(out_host, "summary.json"), "w") as fh:
                json.dump({"metrics": {"iterations": {"values": {"count": 7}}}}, fh)
        sys.exit(0)
        """,
    )

    summary = await asyncio.wait_for(
        run_k6_load(
            k6_script_path=script,
            api_base_url="http://localhost:8090",
            api_token="tok",
            duration_seconds=1,
            docker_binary=str(fake_docker),
        ),
        timeout=20,
    )
    assert summary.available is True
    assert summary.iterations == 7


@pytest.mark.asyncio
async def test_run_k6_unavailable_on_nonzero_exit(tmp_path: Path) -> None:
    """A non-zero container exit surfaces as unavailable, carrying stderr."""
    script = tmp_path / "load.js"
    script.write_text("// placeholder")

    fake_docker = _write_fake_docker(
        tmp_path / "docker",
        """
        import sys
        sys.stderr.write("boom: engine unreachable")
        sys.exit(3)
        """,
    )

    summary = await asyncio.wait_for(
        run_k6_load(
            k6_script_path=script,
            api_base_url="http://localhost:8090",
            api_token="tok",
            duration_seconds=1,
            docker_binary=str(fake_docker),
        ),
        timeout=20,
    )
    assert summary.available is False
    assert "exited 3" in summary.raw["error"]
    assert "boom" in summary.raw["error"]
