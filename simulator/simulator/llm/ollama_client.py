"""Thin async Ollama client wrapper.

Centralises:
- Base-URL + model configuration (loaded once at startup).
- Retries with simple back-off.
- A ``ollama_available`` probe so callers can branch into ``--no-llm`` mode
  without a connection error tank-bombing the whole run.

We deliberately use the upstream ``ollama.AsyncClient`` rather than re-doing
HTTP ourselves — it understands streaming and structured-output schemas.
"""

from __future__ import annotations

import asyncio
import logging
import os
from dataclasses import dataclass
from typing import Any

import httpx
from ollama import AsyncClient

LOG = logging.getLogger("simulator.llm.ollama")


@dataclass(slots=True, frozen=True)
class OllamaConfig:
    base_url: str = "http://localhost:11434"
    model: str = "llama3.1:8b"
    temperature: float = 0.0
    request_timeout_seconds: float = 30.0
    retries: int = 2


def is_no_llm_mode() -> bool:
    """Honour the ``SIM_NO_LLM`` env flag set in CI / `sim run --no-llm`."""
    return os.environ.get("SIM_NO_LLM", "").strip() in {"1", "true", "TRUE", "yes"}


async def ollama_available(config: OllamaConfig) -> bool:
    """Return True iff the Ollama daemon answers a ``/api/tags`` GET in time."""
    if is_no_llm_mode():
        return False
    url = f"{config.base_url.rstrip('/')}/api/tags"
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            response = await client.get(url)
            return response.status_code == 200
    except httpx.HTTPError as exc:
        LOG.debug("ollama probe failed: %r", exc)
        return False


class OllamaWrapper:
    """Async client with retry + structured-output helpers."""

    def __init__(self, config: OllamaConfig) -> None:
        self._config = config
        self._client = AsyncClient(host=config.base_url)

    @property
    def config(self) -> OllamaConfig:
        return self._config

    async def chat_structured(
        self,
        *,
        messages: list[dict[str, str]],
        schema: dict[str, Any] | type,
    ) -> str:
        """Call Ollama with a structured-output schema; return the raw JSON string.

        Caller is responsible for ``YourModel.model_validate_json(result)``.
        We avoid coupling this module to specific Pydantic types so each role
        owns its own schemas.
        """
        last_error: Exception | None = None
        for attempt in range(self._config.retries + 1):
            try:
                response = await self._client.chat(
                    model=self._config.model,
                    messages=messages,
                    format=schema if isinstance(schema, dict) else schema.model_json_schema(),
                    options={"temperature": self._config.temperature},
                )
                return response["message"]["content"]
            except Exception as exc:  # noqa: BLE001 — surface to caller after retries
                last_error = exc
                LOG.warning("ollama chat attempt %d failed: %r", attempt + 1, exc)
                await asyncio.sleep(min(2 ** attempt, 5))
        assert last_error is not None
        raise last_error
