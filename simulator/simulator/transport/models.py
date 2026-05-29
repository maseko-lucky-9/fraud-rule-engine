"""Pydantic mirrors of the engine's Java records.

Mirror targets under ``src/main/java/com/capitec/fraud/`` (kept in sync deliberately):

- ``ingest/TransactionEvent.java``  — Kafka wire format
- ``api/TransactionRequest.java``   — REST request
- ``api/DecisionResponse.java``     — REST + Kafka decision shape

Validation rules match the Java bean-validation annotations exactly so a
payload that fails locally would also fail server-side — and vice-versa.
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_serializer, field_validator


class Channel(StrEnum):
    """Mirrors `TransactionRequest.channel` regex `WEB|MOBILE|POS|ATM|API`."""

    WEB = "WEB"
    MOBILE = "MOBILE"
    POS = "POS"
    ATM = "ATM"
    API = "API"


class DecisionStatus(StrEnum):
    """Mirrors the engine `DecisionStatus` enum (`APPROVED|REVIEW|BLOCK`)."""

    APPROVED = "APPROVED"
    REVIEW = "REVIEW"
    BLOCK = "BLOCK"


# --------------------------------------------------------------------------- #
# Common annotated types
# --------------------------------------------------------------------------- #

CurrencyStr = Annotated[
    str,
    Field(min_length=3, max_length=3, pattern=r"^[A-Z]{3}$",
          description="ISO-4217 uppercase, e.g. ZAR"),
]
CountryStr = Annotated[
    str,
    Field(min_length=2, max_length=2, pattern=r"^[A-Z]{2}$",
          description="ISO-3166-1 alpha-2 uppercase, e.g. ZA"),
]
MccStr = Annotated[
    str,
    Field(min_length=4, max_length=4, pattern=r"^[0-9]{4}$",
          description="4 numeric digits"),
]
AccountAgeDays = Annotated[int, Field(ge=0)]
# 15 integer digits + 4 fractional digits per Java @Digits constraint.
AmountDecimal = Annotated[Decimal, Field(ge=Decimal(0), max_digits=19, decimal_places=4)]


# --------------------------------------------------------------------------- #
# Transaction
# --------------------------------------------------------------------------- #

class Transaction(BaseModel):
    """REST + Kafka payload. Same shape; Jackson accepts both."""

    model_config = ConfigDict(
        populate_by_name=True,
        ser_json_timedelta="iso8601",
    )

    txId: UUID
    accountId: str = Field(min_length=1, max_length=128)
    amount: AmountDecimal
    currency: CurrencyStr
    mcc: MccStr
    channel: Channel
    country: CountryStr
    ipCountry: CountryStr
    deviceId: str | None = None
    merchantId: str | None = None
    accountAgeDays: AccountAgeDays
    timestamp: datetime

    @field_validator("amount")
    @classmethod
    def _amount_quantize(cls, v: Decimal) -> Decimal:
        # Server stores 4 fractional digits; quantize so equality compares clean.
        return v.quantize(Decimal("0.0001"))

    @field_serializer("amount")
    def _amount_as_string(self, v: Decimal) -> str:
        # Jackson on the server side parses BigDecimal from either string or number;
        # we send a plain string to avoid float-round-trip surprises.
        return format(v, "f")


# --------------------------------------------------------------------------- #
# Decision
# --------------------------------------------------------------------------- #

class MatchedRule(BaseModel):
    """Single entry in DecisionResponse.matchedRules[]."""

    ruleId: str
    priority: int
    reason: str | None = None


class DecisionResponse(BaseModel):
    """Server response from `POST /api/v1/transactions` (HTTP 202 Accepted)."""

    decisionId: UUID
    txId: UUID
    status: DecisionStatus
    score: float = Field(ge=0.0, le=1.0)
    ruleSetVersion: int
    matchedRules: list[MatchedRule] = Field(default_factory=list)
    evaluatedAt: datetime


# --------------------------------------------------------------------------- #
# Auth
# --------------------------------------------------------------------------- #

class TokenRequest(BaseModel):
    """`POST /auth/token` body."""

    subject: str = Field(min_length=1, max_length=128)


class TokenResponse(BaseModel):
    """`POST /auth/token` response."""

    accessToken: str
    expiresInSeconds: int = Field(gt=0)
