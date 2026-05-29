"""Pydantic validation tests — keep us honest about the engine contract."""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from uuid import uuid4

import pytest
from pydantic import ValidationError

from simulator.transport.models import (
    Channel,
    DecisionResponse,
    DecisionStatus,
    MatchedRule,
    TokenRequest,
    TokenResponse,
    Transaction,
)


def _valid_tx_payload() -> dict:
    return dict(
        txId=uuid4(),
        accountId="ACC-SMOKE-0001",
        amount=Decimal("25.50"),
        currency="ZAR",
        mcc="5411",
        channel=Channel.MOBILE,
        country="ZA",
        ipCountry="ZA",
        deviceId="dev-abc",
        merchantId="MERCH-OK-001",
        accountAgeDays=180,
        timestamp=datetime(2026, 5, 28, 12, 0, tzinfo=UTC),
    )


def test_transaction_round_trip() -> None:
    tx = Transaction(**_valid_tx_payload())
    payload = tx.model_dump(mode="json")
    assert payload["currency"] == "ZAR"
    assert payload["channel"] == "MOBILE"
    # Json dump must produce a string-encoded amount the engine's BigDecimal accepts.
    assert isinstance(payload["amount"], str)
    assert Decimal(payload["amount"]) == Decimal("25.50")
    # Round-trip back through validation.
    Transaction.model_validate(payload)


@pytest.mark.parametrize(
    ("field", "bad_value"),
    [
        ("currency", "zar"),       # lowercase → fails uppercase regex
        ("currency", "USDX"),      # too long
        ("mcc", "541"),            # too short
        ("mcc", "abcd"),           # not numeric
        ("country", "ZAF"),        # alpha-3 not allowed
        ("ipCountry", "z1"),       # numeric
        ("channel", "EFT"),        # not in enum
        ("accountAgeDays", -1),    # negative
        ("amount", Decimal("-1.00")),  # negative
    ],
)
def test_transaction_rejects_bad_input(field: str, bad_value: object) -> None:
    payload = _valid_tx_payload()
    payload[field] = bad_value
    with pytest.raises(ValidationError):
        Transaction(**payload)


def test_decision_response_round_trip() -> None:
    raw = {
        "decisionId": str(uuid4()),
        "txId": str(uuid4()),
        "status": "APPROVED",
        "score": 0.0,
        "ruleSetVersion": 1,
        "matchedRules": [],
        "evaluatedAt": "2026-05-28T12:00:00Z",
    }
    decision = DecisionResponse.model_validate(raw)
    assert decision.status is DecisionStatus.APPROVED
    assert decision.matchedRules == []


def test_decision_response_with_matched_rules() -> None:
    raw = {
        "decisionId": str(uuid4()),
        "txId": str(uuid4()),
        "status": "REVIEW",
        "score": 0.85,
        "ruleSetVersion": 1,
        "matchedRules": [
            {"ruleId": "HIGH_AMOUNT_NEW_ACCOUNT", "priority": 800, "reason": "amount > 10000"},
        ],
        "evaluatedAt": "2026-05-28T12:00:00Z",
    }
    decision = DecisionResponse.model_validate(raw)
    assert decision.status is DecisionStatus.REVIEW
    assert len(decision.matchedRules) == 1
    assert decision.matchedRules[0] == MatchedRule(
        ruleId="HIGH_AMOUNT_NEW_ACCOUNT", priority=800, reason="amount > 10000"
    )


def test_decision_score_bounds() -> None:
    base = {
        "decisionId": str(uuid4()),
        "txId": str(uuid4()),
        "status": "BLOCK",
        "ruleSetVersion": 1,
        "matchedRules": [],
        "evaluatedAt": "2026-05-28T12:00:00Z",
    }
    with pytest.raises(ValidationError):
        DecisionResponse.model_validate({**base, "score": 1.5})
    with pytest.raises(ValidationError):
        DecisionResponse.model_validate({**base, "score": -0.1})


def test_token_request_subject_required() -> None:
    with pytest.raises(ValidationError):
        TokenRequest(subject="")


def test_token_response_round_trip() -> None:
    tok = TokenResponse(accessToken="ey...", expiresInSeconds=3600)
    assert tok.model_dump() == {"accessToken": "ey...", "expiresInSeconds": 3600}
