"""Minimal psycopg wrapper for the ``sim reset --truncate`` command.

Reads ``scripts/sql/reset-decisions.sql`` and executes it against the
engine's Postgres. Credentials are loaded from the engine's ``.env`` via
``python-dotenv`` so we never re-state them in the simulator config.

Failure modes:
- ``.env`` missing → raises ``EnvFileMissing`` with the searched paths.
- PG unreachable → propagates psycopg's connection error.
- SQL script missing → raises ``FileNotFoundError``.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Final

import psycopg
from dotenv import dotenv_values

DEFAULT_RESET_SQL: Final[Path] = (
    Path(__file__).resolve().parent.parent / "scripts" / "sql" / "reset-decisions.sql"
)


class EnvFileMissing(FileNotFoundError):
    """Raised when no engine .env can be found to supply PG creds."""


@dataclass(slots=True, frozen=True)
class PostgresConfig:
    host: str
    port: int
    database: str
    user: str
    password: str

    @property
    def conninfo(self) -> str:
        return (
            f"host={self.host} port={self.port} dbname={self.database} "
            f"user={self.user} password={self.password}"
        )

    @classmethod
    def from_env_file(cls, env_path: Path) -> PostgresConfig:
        """Load a `.env` file (Spring Boot style) and return PG config."""
        if not env_path.is_file():
            raise EnvFileMissing(f"engine .env not found at {env_path}")

        env = dotenv_values(env_path)
        try:
            return cls(
                host=env.get("POSTGRES_HOST", "localhost") or "localhost",
                port=int(env.get("POSTGRES_PORT", "5432") or "5432"),
                database=env["POSTGRES_DB"],
                user=env["POSTGRES_USER"],
                password=env["POSTGRES_PASSWORD"],
            )
        except KeyError as missing:
            raise EnvFileMissing(
                f"engine .env at {env_path} is missing key: {missing!s}"
            ) from None


def reset_decisions(
    config: PostgresConfig,
    sql_path: Path | None = None,
) -> None:
    """Apply the reset SQL. Idempotent — TRUNCATEs are safe on empty tables."""
    sql_path = sql_path or DEFAULT_RESET_SQL
    sql = sql_path.read_text()

    with psycopg.connect(config.conninfo, autocommit=False) as conn:
        with conn.cursor() as cur:
            cur.execute(sql)
        conn.commit()
