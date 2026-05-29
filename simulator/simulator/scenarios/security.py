"""Security probes — JWT tamper, idempotency collision, rate limit, payload fuzz.

Run in a 10s pre-flight sub-phase so failures are reported separately from
fraud-detection metrics. Each probe returns a small, well-defined set of
expected HTTP status codes — the audit asserts pass/fail per vector.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from decimal import Decimal
from typing import Final

from ._helpers import base_time, benign_tx

# Deliberately-forged probe payloads, not real credentials. The engine is
# expected to reject both with 401/403. Per-file `ruff` ignores in
# pyproject.toml suppress S105/E501 for this file.

JWT_NONE_TOKEN: Final[str] = (
    "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0."
    "eyJzdWIiOiJhbGljZSIsInJvbGVzIjpbIlJPTEVfU0VSVklDRSJdfQ."
)
JWT_EXPIRED_FAKE: Final[str] = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
    "eyJzdWIiOiJhbGljZSIsImV4cCI6MTU3NzgzNjgwMH0."
    "TGhpcyBpcyBub3QgYSByZWFsIHNpZ25hdHVyZQ"
)


@dataclass(slots=True, frozen=True)
class SecurityProbe:
    """One probe call — name, builder, and the codes that count as ``pass``."""

    id: str
    description: str
    targets: str  # what aspect of security the probe exercises
    pass_status_codes: frozenset[int]


def jwt_alg_none_probe() -> SecurityProbe:
    return SecurityProbe(
        id="security_jwt_alg_none",
        description="Bearer alg=none token must be rejected (401 / 403).",
        targets="auth",
        pass_status_codes=frozenset({401, 403}),
    )


def jwt_expired_probe() -> SecurityProbe:
    return SecurityProbe(
        id="security_jwt_expired",
        description="Bearer token signed by a different secret must be rejected.",
        targets="auth",
        pass_status_codes=frozenset({401, 403}),
    )


def idempotency_collision_probe() -> SecurityProbe:
    return SecurityProbe(
        id="security_idempotency_collision",
        description=(
            "Same Idempotency-Key with a different body must return 409 Conflict."
        ),
        targets="idempotency",
        pass_status_codes=frozenset({409}),
    )


def rate_limit_probe() -> SecurityProbe:
    return SecurityProbe(
        id="security_rate_limit",
        description=(
            "Sustained burst beyond 100 req/min/subject must yield 429 + Retry-After."
        ),
        targets="rate_limit",
        pass_status_codes=frozenset({429}),
    )


def payload_fuzz_probes() -> tuple[SecurityProbe, ...]:
    """Each malformed-payload variant the engine should reject with 400."""
    return tuple(
        SecurityProbe(
            id=f"security_payload_fuzz_{vector}",
            description=f"Malformed payload ({vector}) must be rejected with 400.",
            targets="payload_validation",
            pass_status_codes=frozenset({400}),
        )
        for vector in (
            "currency_lowercase",
            "currency_alpha3",
            "mcc_too_short",
            "mcc_alpha",
            "channel_invalid",
            "country_alpha3",
            "amount_negative",
            "amount_nan",
            "account_age_negative",
            "missing_required_field",
        )
    )


def all_probes() -> tuple[SecurityProbe, ...]:
    return (
        jwt_alg_none_probe(),
        jwt_expired_probe(),
        idempotency_collision_probe(),
        rate_limit_probe(),
        *payload_fuzz_probes(),
    )


# Fuzz body builders — used by the security runner, not by the registry above. # noqa: E501

def fuzz_body(variant: str) -> dict:
    """Build a malformed JSON body for a given variant."""
    sid = "security_payload_fuzz"
    valid = benign_tx(
        scenario_id=sid, step=0, seed=0,
        account_id="ACC-FUZZ-1", amount=Decimal("100.00"),
        timestamp=base_time(hour_sast=12),
    ).model_dump(mode="json")

    mutations: dict[str, Callable[[dict], dict]] = {
        "currency_lowercase": lambda b: {**b, "currency": "zar"},
        "currency_alpha3":    lambda b: {**b, "currency": "USDX"},
        "mcc_too_short":      lambda b: {**b, "mcc": "541"},
        "mcc_alpha":          lambda b: {**b, "mcc": "abcd"},
        "channel_invalid":    lambda b: {**b, "channel": "EFT"},
        "country_alpha3":     lambda b: {**b, "country": "ZAF"},
        "amount_negative":    lambda b: {**b, "amount": "-1.00"},
        "amount_nan":         lambda b: {**b, "amount": "NaN"},
        "account_age_negative": lambda b: {**b, "accountAgeDays": -1},
        "missing_required_field": lambda b: {k: v for k, v in b.items() if k != "currency"},
    }
    try:
        return mutations[variant](valid)
    except KeyError as exc:
        raise ValueError(f"unknown fuzz variant: {variant}") from exc
