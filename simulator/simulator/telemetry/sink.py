"""Append-only SQLite sink for submission results.

One writer task pulls from an ``asyncio.Queue``; producer agents drop rows
in and never block on disk. SQLite is opened in WAL mode so the writer
doesn't lock readers if an analyst tails the file mid-run.

Schema is deliberately flat — one row per submitted transaction — so
``pandas.read_sql_query`` in :mod:`simulator.analysis` is trivial.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import sqlite3
from collections.abc import Iterable
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Final

from simulator.transport.rest import SubmissionResult

LOG = logging.getLogger("simulator.telemetry.sink")

SCHEMA_SQL: Final[str] = """
CREATE TABLE IF NOT EXISTS submissions (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id            TEXT NOT NULL,
    persona_role      TEXT NOT NULL,
    scenario_id       TEXT,
    tx_index          INTEGER,
    account_id        TEXT NOT NULL,
    subject           TEXT NOT NULL,
    submitted_at      TEXT NOT NULL,
    received_at       TEXT NOT NULL,
    latency_ms        REAL NOT NULL,
    http_status       INTEGER NOT NULL,
    decision_id       TEXT,
    status            TEXT,
    score             REAL,
    rule_set_version  INTEGER,
    matched_rules     TEXT,         -- JSON array of rule ids
    error             TEXT,
    idempotency_key   TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_submissions_status   ON submissions(status);
CREATE INDEX IF NOT EXISTS idx_submissions_account  ON submissions(account_id);
CREATE INDEX IF NOT EXISTS idx_submissions_scenario ON submissions(scenario_id);
"""


@dataclass(slots=True, frozen=True)
class SinkRow:
    """Producer-side row. Mapped onto the ``submissions`` table at write time."""

    run_id: str
    persona_role: str
    scenario_id: str | None
    tx_index: int | None
    account_id: str
    result: SubmissionResult


class SqliteSink:
    """Single-writer SQLite sink, queue-fed for concurrency safety."""

    def __init__(
        self,
        db_path: Path,
        queue_maxsize: int = 1024,
        close_timeout_seconds: float = 30.0,
    ) -> None:
        self._db_path = db_path
        self._queue: asyncio.Queue[SinkRow | None] = asyncio.Queue(maxsize=queue_maxsize)
        self._writer_task: asyncio.Task[None] | None = None
        self._row_count = 0
        self._dropped = 0
        self._close_timeout = close_timeout_seconds
        # Set if the writer loop dies fatally — producers then fast-drop
        # instead of blocking forever on a full queue (the Bug-1 deadlock).
        self._writer_failed = asyncio.Event()
        self._writer_error: BaseException | None = None

    @property
    def row_count(self) -> int:
        return self._row_count

    @property
    def dropped(self) -> int:
        """Rows that could not be persisted (bad insert, or writer dead)."""
        return self._dropped

    @property
    def writer_failed(self) -> bool:
        return self._writer_failed.is_set()

    async def start(self) -> None:
        """Open the DB, install schema, kick off the writer task."""
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(SCHEMA_SQL)
            conn.commit()
        self._writer_task = asyncio.create_task(self._writer_loop(), name="sink-writer")

    async def record(self, row: SinkRow) -> None:
        """Enqueue a row for the writer.

        If the writer has died fatally we fast-drop (counting it) instead of
        blocking forever on a full bounded queue — that blocking-put against a
        dead consumer is the original finalization deadlock.
        """
        if self._writer_failed.is_set():
            self._dropped += 1
            return
        await self._queue.put(row)

    async def close(self) -> None:
        """Drain the queue and stop the writer — never hang.

        Sends the sentinel, then bounds the join with ``close_timeout``; a
        stalled writer is cancelled rather than blocking the whole run.
        """
        task = self._writer_task
        self._writer_task = None
        if task is None:
            return

        if not task.done():
            try:
                self._queue.put_nowait(None)  # non-blocking sentinel
            except asyncio.QueueFull:
                LOG.warning("sink queue full at close; relying on drain timeout")

        try:
            await asyncio.wait_for(asyncio.shield(task), timeout=self._close_timeout)
        except TimeoutError:
            LOG.error(
                "sink writer did not drain within %.1fs (%d rows queued); cancelling",
                self._close_timeout,
                self._queue.qsize(),
            )
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        except Exception as exc:  # noqa: BLE001 — surface, don't re-hang
            LOG.error("sink writer exited with error: %r", exc)

        if self._dropped:
            LOG.warning("sink dropped %d row(s) this run", self._dropped)

    @contextmanager
    def _connect(self):
        conn = sqlite3.connect(self._db_path)
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA synchronous=NORMAL;")
        try:
            yield conn
        finally:
            conn.close()

    async def _writer_loop(self) -> None:
        # Open the DB inside the writer task — SQLite connections aren't
        # thread/loop-safe to share, but a single-writer loop is fine.
        #
        # A single bad row must NEVER kill the drain: an unsupervised writer
        # death used to fill the bounded queue and deadlock every producer
        # (Bug 1). We skip-and-count bad rows, and only flip _writer_failed on
        # a genuinely fatal (connection/commit) error so producers stop
        # blocking on a dead consumer.
        try:
            with self._connect() as conn:
                cursor = conn.cursor()
                try:
                    while True:
                        row = await self._queue.get()
                        if row is None:
                            conn.commit()
                            return
                        try:
                            self._insert(cursor, row)
                            self._row_count += 1
                        except Exception as exc:  # noqa: BLE001 — skip, never die
                            self._dropped += 1
                            LOG.error("sink dropped a row (insert failed): %r", exc)
                            continue
                        # Commit in small batches to keep WAL bounded.
                        if self._row_count % 128 == 0:
                            conn.commit()
                finally:
                    with contextlib.suppress(Exception):
                        conn.commit()
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 — fatal: signal producers
            self._writer_error = exc
            self._writer_failed.set()
            LOG.exception("sink writer loop failed fatally; producers will fast-drop")

    @staticmethod
    def _insert(cursor: sqlite3.Cursor, row: SinkRow) -> None:
        r = row.result
        decision = r.decision
        matched_rules: str | None = None
        decision_id: str | None = None
        status: str | None = None
        score: float | None = None
        rule_set_version: int | None = None
        if decision is not None:
            decision_id = str(decision.decisionId)
            status = decision.status.value
            score = decision.score
            rule_set_version = decision.ruleSetVersion
            matched_rules = json.dumps([m.ruleId for m in decision.matchedRules])

        cursor.execute(
            """
            INSERT INTO submissions (
                run_id, persona_role, scenario_id, tx_index, account_id, subject,
                submitted_at, received_at, latency_ms, http_status,
                decision_id, status, score, rule_set_version, matched_rules,
                error, idempotency_key
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """,
            (
                row.run_id,
                row.persona_role,
                row.scenario_id,
                row.tx_index,
                row.account_id,
                r.subject,
                r.submitted_at.isoformat(),
                r.received_at.isoformat(),
                r.latency_ms,
                r.http_status,
                decision_id,
                status,
                score,
                rule_set_version,
                matched_rules,
                r.error,
                r.idempotency_key,
            ),
        )


def fetch_all_rows(db_path: Path) -> Iterable[dict]:
    """Read every row from a sink DB. Convenience for tests + ad-hoc analysis."""
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        for row in conn.execute("SELECT * FROM submissions ORDER BY id"):
            yield dict(row)


def deterministic_run_hash(db_path: Path) -> str:
    """sha256 over the determinism-relevant projection of every submission.

    Excludes wall-clock and UUID columns per plan §9. Two runs with the
    same seed must produce the same hash modulo timing.
    """
    import hashlib

    digest = hashlib.sha256()
    for row in fetch_all_rows(db_path):
        projection = "|".join(
            str(x) for x in (
                row.get("persona_role"),
                row.get("scenario_id"),
                row.get("tx_index"),
                row.get("status"),
                row.get("score"),
                row.get("matched_rules") or "[]",
            )
        )
        digest.update(projection.encode())
        digest.update(b"\n")
    return digest.hexdigest()


_ = datetime  # re-export anchor for downstream typing imports
