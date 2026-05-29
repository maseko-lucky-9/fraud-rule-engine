"""JWT minting + caching against the engine's `/auth/token` endpoint.

Each *simulated account* gets its own JWT (distinct `sub` claim) so the
engine's 100 req/min-per-subject rate limit applies per-account, not per-run.
That gives us the full 100 accounts × 100 req/min = 10 000 req/min budget the
plan's default profile counts on.

Tokens are cached until `expiresInSeconds - LEEWAY_SECONDS` and refreshed in
the background. The engine's TTL is 3600s; LEEWAY of 60s avoids submitting
with a token that expires mid-request.
"""

from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass, field
from typing import Final

import httpx

from .models import TokenRequest, TokenResponse

LEEWAY_SECONDS: Final[int] = 60

# Env-var names (declared in the simulator profile, never literal secret values).
ENV_SERVICE_API_KEY: Final[str] = "SERVICE_API_KEY"


@dataclass(slots=True)
class CachedToken:
    """In-memory token entry."""

    access_token: str
    expires_at_monotonic: float


@dataclass(slots=True)
class JwtMinter:
    """Per-subject token cache.

    Not thread-safe across threads; safe under asyncio (the lock serialises
    refresh per subject so we don't issue duplicate refresh calls).
    """

    rest_base_url: str
    token_endpoint: str = "/auth/token"  # noqa: S105 — URL path, not a credential
    http_timeout_seconds: float = 10.0
    _cache: dict[str, CachedToken] = field(default_factory=dict)
    _locks: dict[str, asyncio.Lock] = field(default_factory=dict)

    async def get(self, subject: str, client: httpx.AsyncClient) -> str:
        """Return a valid access token for ``subject``, minting if needed."""
        lock = self._locks.setdefault(subject, asyncio.Lock())
        async with lock:
            now = asyncio.get_running_loop().time()
            cached = self._cache.get(subject)
            if cached is not None and cached.expires_at_monotonic > now:
                return cached.access_token

            response = await client.post(
                f"{self.rest_base_url.rstrip('/')}{self.token_endpoint}",
                json=TokenRequest(subject=subject).model_dump(),
                timeout=self.http_timeout_seconds,
            )
            response.raise_for_status()
            token = TokenResponse.model_validate(response.json())
            expires_at = now + max(0, token.expiresInSeconds - LEEWAY_SECONDS)
            self._cache[subject] = CachedToken(
                access_token=token.accessToken,
                expires_at_monotonic=expires_at,
            )
            return token.accessToken

    def invalidate(self, subject: str) -> None:
        """Drop a cached token (call on 401)."""
        self._cache.pop(subject, None)


def service_api_key_from_env() -> str | None:
    """Load `SERVICE_API_KEY` for admin-route probes.

    Returns ``None`` when unset so callers can branch (admin probes skip
    gracefully in environments without the engine secret).
    """
    return os.getenv(ENV_SERVICE_API_KEY)
