"""LLM-layer tests — Pydantic schema validation, fallback behaviour, no-LLM mode."""

from __future__ import annotations

import os
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from simulator.llm.audit_writer import (
    AuditNarrativeContext,
    ExecutiveSummary,
    write_executive_summary,
)
from simulator.llm.ollama_client import (
    OllamaConfig,
    OllamaWrapper,
    is_no_llm_mode,
)
from simulator.llm.scenario_generator import (
    GeneratedScenarioSpec,
    ScenarioGenerationError,
    ScenarioGenerationResult,
    generate_scenarios,
    load_seed_archetypes,
)
from simulator.llm.triage import TriageResult


def test_is_no_llm_mode_respects_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("SIM_NO_LLM", raising=False)
    assert is_no_llm_mode() is False

    monkeypatch.setenv("SIM_NO_LLM", "1")
    assert is_no_llm_mode() is True

    monkeypatch.setenv("SIM_NO_LLM", "yes")
    assert is_no_llm_mode() is True

    monkeypatch.setenv("SIM_NO_LLM", "0")
    assert is_no_llm_mode() is False


def test_ollama_config_defaults() -> None:
    config = OllamaConfig()
    assert config.base_url == "http://localhost:11434"
    assert config.model == "llama3.1:8b"
    assert config.temperature == 0.0


def test_load_seed_archetypes_from_default_config() -> None:
    seed_path = (
        Path(__file__).resolve().parent.parent / "simulator" / "config" / "scenarios.seed.yaml"
    )
    archetypes = load_seed_archetypes(seed_path)
    assert len(archetypes) >= 5
    assert {a["archetype"] for a in archetypes} >= {
        "card_testing", "structuring", "account_takeover",
    }


def test_generated_scenario_spec_rejects_invalid_amount_band() -> None:
    with pytest.raises(Exception):  # noqa: B017
        GeneratedScenarioSpec(
            id="x", archetype="card_testing", expected_outcome="miss",
            account_count=10, tx_per_account=5,
            amount_low_zar=100, amount_high_zar=50,  # inverted
            cadence_seconds=30, description="x",
        )


def test_scenario_generation_result_requires_at_least_one() -> None:
    with pytest.raises(Exception):  # noqa: B017
        ScenarioGenerationResult(scenarios=[])


def test_triage_result_rejects_unknown_label() -> None:
    with pytest.raises(Exception):  # noqa: B017
        TriageResult(label="bogus", confidence=0.9, rationale="x")  # type: ignore[arg-type]


def _ctx_with_evasion_misses() -> AuditNarrativeContext:
    return AuditNarrativeContext(
        run_id="r-test",
        total_submissions=10000,
        approved_pct=85.0,
        review_pct=10.0,
        block_pct=2.0,
        failed_pct=3.0,
        per_rule_precision_recall=[
            {"rule": "VELOCITY_BURST", "precision": 1.0, "recall": 1.0, "f1": 1.0},
            {"rule": "HIGH_AMOUNT_NEW_ACCOUNT", "precision": 1.0, "recall": 0.95, "f1": 0.975},
        ],
        evasion_outcomes=[
            {"scenario_id": "evasion_card_testing", "expected": "miss", "actual": "miss"},
            {"scenario_id": "evasion_structuring", "expected": "miss", "actual": "miss"},
        ],
        p99_latency_ms=180.0,
        rate_limited_pct=0.5,
    )


@pytest.mark.asyncio
async def test_audit_writer_fallback_when_no_llm() -> None:
    ctx = _ctx_with_evasion_misses()
    summary = await write_executive_summary(context=ctx, wrapper=None)
    assert isinstance(summary, ExecutiveSummary)
    assert "evasion patterns slipped" in summary.headline
    assert "engine misses evasion_card_testing" in summary.top_findings


@pytest.mark.asyncio
async def test_audit_writer_fallback_when_env_flag(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SIM_NO_LLM", "1")
    fake_wrapper = OllamaWrapper(OllamaConfig())
    ctx = _ctx_with_evasion_misses()
    summary = await write_executive_summary(context=ctx, wrapper=fake_wrapper)
    assert isinstance(summary, ExecutiveSummary)
    # Falls back without hitting Ollama at all.
    assert "without the narrative LLM" in summary.body


@pytest.mark.asyncio
async def test_audit_writer_uses_ollama_when_available() -> None:
    """When wrapper is present and not in no-llm mode, structured chat is called."""
    os.environ.pop("SIM_NO_LLM", None)
    wrapper = OllamaWrapper(OllamaConfig())
    fake_response = ExecutiveSummary(
        headline="Fake LLM summary", body="Generated narrative body.",
        top_findings=["finding-1"],
    ).model_dump_json()
    wrapper.chat_structured = AsyncMock(return_value=fake_response)  # type: ignore[method-assign]

    summary = await write_executive_summary(context=_ctx_with_evasion_misses(), wrapper=wrapper)
    assert summary.headline == "Fake LLM summary"


def test_narrative_context_format_includes_metrics() -> None:
    ctx = _ctx_with_evasion_misses()
    rendered = ctx.to_prompt()
    assert "Total submissions: 10000" in rendered
    assert "VELOCITY_BURST" in rendered
    assert "evasion_card_testing" in rendered


# --------------------------------------------------------------------------- #
# generate_scenarios — per-item lenient parsing + fail-loud (Bug 2)
# --------------------------------------------------------------------------- #


def _spec_dict(sid: str, *, low: float, high: float) -> dict:
    return {
        "id": sid,
        "archetype": "card_testing",
        "expected_outcome": "miss",
        "account_count": 20,
        "tx_per_account": 50,
        "amount_low_zar": low,
        "amount_high_zar": high,
        "cadence_seconds": 60,
        "description": "synthetic test scenario",
    }


def _wrapper_returning(raw: str) -> OllamaWrapper:
    import json as _json

    wrapper = OllamaWrapper(OllamaConfig())
    payload = raw if isinstance(raw, str) else _json.dumps(raw)
    wrapper.chat_structured = AsyncMock(return_value=payload)  # type: ignore[method-assign]
    return wrapper


@pytest.mark.asyncio
async def test_generate_scenarios_keeps_valid_subset_when_some_invalid() -> None:
    """One inverted band (low>=high) must NOT discard the valid specs.

    This is the exact llama3.1:8b shape that previously yielded 0 specs.
    """
    import json as _json

    payload = _json.dumps({
        "scenarios": [
            _spec_dict("good_1", low=0.99, high=4.99),
            _spec_dict("good_2", low=1000, high=5000),
            _spec_dict("bad_equal", low=50000, high=50000),   # validator rejects
            _spec_dict("bad_inverted", low=900, high=100),     # validator rejects
            _spec_dict("good_3", low=10, high=300),
        ]
    })
    specs = await generate_scenarios([{"id": "x"}], _wrapper_returning(payload))
    assert [s.id for s in specs] == ["good_1", "good_2", "good_3"]


@pytest.mark.asyncio
async def test_generate_scenarios_raises_when_all_invalid() -> None:
    import json as _json

    payload = _json.dumps({
        "scenarios": [
            _spec_dict("bad_1", low=5, high=5),
            _spec_dict("bad_2", low=900, high=100),
        ]
    })
    with pytest.raises(ScenarioGenerationError):
        await generate_scenarios([{"id": "x"}], _wrapper_returning(payload))


@pytest.mark.asyncio
async def test_generate_scenarios_raises_on_empty_array() -> None:
    with pytest.raises(ScenarioGenerationError):
        await generate_scenarios([{"id": "x"}], _wrapper_returning('{"scenarios": []}'))


@pytest.mark.asyncio
async def test_generate_scenarios_tolerates_bare_array() -> None:
    import json as _json

    payload = _json.dumps([_spec_dict("only", low=1, high=2)])
    specs = await generate_scenarios([{"id": "x"}], _wrapper_returning(payload))
    assert [s.id for s in specs] == ["only"]


@pytest.mark.asyncio
async def test_generate_scenarios_tolerates_fenced_json() -> None:
    import json as _json

    inner = _json.dumps({"scenarios": [_spec_dict("fenced", low=1, high=2)]})
    fenced = "Here you go:\n" + chr(96) * 3 + "json\n" + inner + "\n" + chr(96) * 3
    specs = await generate_scenarios([{"id": "x"}], _wrapper_returning(fenced))
    assert [s.id for s in specs] == ["fenced"]


@pytest.mark.asyncio
async def test_generate_scenarios_raises_on_non_json() -> None:
    with pytest.raises(ScenarioGenerationError):
        await generate_scenarios([{"id": "x"}], _wrapper_returning("I cannot do that."))
