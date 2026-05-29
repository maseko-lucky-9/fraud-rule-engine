"""JwtMinter unit tests — caching, refresh, per-subject isolation."""

from __future__ import annotations

import asyncio

import httpx
import pytest
import respx

from simulator.transport.auth import JwtMinter


@pytest.fixture
def minter() -> JwtMinter:
    return JwtMinter(rest_base_url="http://engine.test")


@pytest.mark.asyncio
async def test_first_request_mints_token(minter: JwtMinter) -> None:
    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        route = mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok-alice", "expiresInSeconds": 3600})
        )

        token = await minter.get("alice", client)

        assert token == "tok-alice"
        assert route.called


@pytest.mark.asyncio
async def test_cached_token_reused_within_ttl(minter: JwtMinter) -> None:
    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        route = mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok-alice", "expiresInSeconds": 3600})
        )

        first = await minter.get("alice", client)
        second = await minter.get("alice", client)

        assert first == second == "tok-alice"
        # Only one HTTP call — second was served from cache.
        assert route.call_count == 1


@pytest.mark.asyncio
async def test_per_subject_isolation(minter: JwtMinter) -> None:
    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        mock.post("/auth/token", json__subject="alice").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok-alice", "expiresInSeconds": 3600})
        )
        mock.post("/auth/token", json__subject="bob").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok-bob", "expiresInSeconds": 3600})
        )

        alice = await minter.get("alice", client)
        bob = await minter.get("bob", client)

        assert alice == "tok-alice"
        assert bob == "tok-bob"
        assert alice != bob


@pytest.mark.asyncio
async def test_invalidate_drops_cache(minter: JwtMinter) -> None:
    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        route = mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok-alice", "expiresInSeconds": 3600})
        )

        await minter.get("alice", client)
        minter.invalidate("alice")
        await minter.get("alice", client)

        assert route.call_count == 2


@pytest.mark.asyncio
async def test_concurrent_requests_serialise_refresh(minter: JwtMinter) -> None:
    """Two concurrent get() calls for the same subject must mint only once."""
    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        route = mock.post("/auth/token").mock(
            return_value=httpx.Response(200, json={"accessToken": "tok-alice", "expiresInSeconds": 3600})
        )

        results = await asyncio.gather(
            minter.get("alice", client),
            minter.get("alice", client),
            minter.get("alice", client),
        )

        assert results == ["tok-alice"] * 3
        assert route.call_count == 1


@pytest.mark.asyncio
async def test_token_endpoint_5xx_propagates(minter: JwtMinter) -> None:
    async with httpx.AsyncClient() as client, respx.mock(base_url="http://engine.test") as mock:
        mock.post("/auth/token").mock(return_value=httpx.Response(500, text="boom"))

        with pytest.raises(httpx.HTTPStatusError):
            await minter.get("alice", client)
