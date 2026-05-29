"""Expand hand-curated seed archetypes into concrete scenario specs via Ollama.

Input: ``simulator/config/scenarios.seed.yaml`` (the 5 seed archetypes).
Output: a list of ``GeneratedScenarioSpec`` describing concrete account
counts, amount distributions, and timing patterns the runner replays.

This is **scenario design**, not direct transaction emission. The runner
turns each spec into actual Transaction objects, so an Ollama hallucination
can be filtered (we validate every spec against a Pydantic schema).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, Field, ValidationError, model_validator

from .ollama_client import OllamaWrapper

LOG = logging.getLogger("simulator.llm.scenario_generator")


class ScenarioGenerationError(RuntimeError):
    """Raised when seed expansion produces zero usable specs.

    Surfacing this (instead of silently returning ``[]``) lets ``sim seed``
    exit non-zero and ``sim run`` log a loud warning before degrading.
    """

# --------------------------------------------------------------------------- #
# Pydantic schemas (LLM structured-output contract)
# --------------------------------------------------------------------------- #


class GeneratedScenarioSpec(BaseModel):
    """One concrete scenario spec the runner can materialise."""

    id: str
    archetype: Literal[
        "card_testing",
        "structuring",
        "account_takeover",
        "synthetic_identity",
        "device_entropy",
        "geo_impossibility",
    ]
    expected_outcome: Literal["detected", "partial", "miss"]
    account_count: int = Field(ge=1, le=50)
    tx_per_account: int = Field(ge=1, le=100)
    amount_low_zar: float = Field(ge=0)
    amount_high_zar: float = Field(ge=0)
    cadence_seconds: int = Field(ge=0)
    description: str

    @model_validator(mode="after")
    def _amount_band_ordered(self) -> GeneratedScenarioSpec:
        if self.amount_low_zar >= self.amount_high_zar:
            raise ValueError("amount_low_zar must be < amount_high_zar")
        return self


class ScenarioGenerationResult(BaseModel):
    """Top-level Ollama response — a list of specs the runner will replay."""

    scenarios: list[GeneratedScenarioSpec] = Field(default_factory=list, min_length=1)


# --------------------------------------------------------------------------- #
# Seed loading
# --------------------------------------------------------------------------- #


def load_seed_archetypes(seed_path: Path) -> list[dict]:
    data = yaml.safe_load(seed_path.read_text())
    return list(data.get("seeds", []))


# --------------------------------------------------------------------------- #
# Generation
# --------------------------------------------------------------------------- #


_PROMPT_TEMPLATE = """\
You are a fraud-detection pen-test scenario designer. Given the seed
archetypes below, expand each into 3 concrete variants. Each variant
specifies a deterministic replay shape (account_count, tx_per_account,
amount range in ZAR, cadence_seconds between transactions).

Hard constraints:
- account_count between 1 and 50
- tx_per_account between 1 and 100
- amount_low_zar < amount_high_zar
- cadence_seconds >= 0
- amount_low_zar MUST be strictly less than amount_high_zar. For a
  fixed-amount scenario (e.g. "exactly $50,000"), use a small band around
  the target instead of equal bounds (e.g. amount_low_zar: 49000,
  amount_high_zar: 51000).
- expected_outcome MUST match the archetype's documented current-engine
  behaviour: card_testing/structuring/synthetic_identity/device_entropy/
  geo_impossibility → miss; account_takeover → partial

Seed archetypes:
{seeds_yaml}

Return ONLY a JSON object matching the schema. Do not add prose.
"""


def _loads_embedded_json(raw: str) -> object:
    """Parse the widest JSON object/array embedded in ``raw``.

    Tolerates prose or markdown code fences wrapped around the JSON by
    scanning for the outermost ``{...}`` or ``[...]`` span.
    """
    for open_ch, close_ch in (("{", "}"), ("[", "]")):
        start = raw.find(open_ch)
        end = raw.rfind(close_ch)
        if start != -1 and end > start:
            try:
                return json.loads(raw[start : end + 1])
            except json.JSONDecodeError:
                continue
    raise ScenarioGenerationError(
        f"Ollama returned non-JSON content; first 200 chars: {raw[:200]!r}"
    )


def _extract_scenarios_payload(raw: str) -> list[dict]:
    """Pull the ``scenarios`` array out of the raw LLM JSON.

    Tolerant of (a) a top-level bare array, (b) the documented
    ``{"scenarios": [...]}`` object, and (c) prose/fences around the JSON.
    Raises :class:`ScenarioGenerationError` only when no array can be found.
    """
    text = raw.strip()
    try:
        data: object = json.loads(text)
    except json.JSONDecodeError:
        data = _loads_embedded_json(raw)

    if isinstance(data, list):
        return data
    if isinstance(data, dict) and isinstance(data.get("scenarios"), list):
        return data["scenarios"]
    raise ScenarioGenerationError(
        f"Ollama JSON had no 'scenarios' array (top-level type={type(data).__name__})."
    )


async def generate_scenarios(
    seed_archetypes: list[dict],
    wrapper: OllamaWrapper,
) -> list[GeneratedScenarioSpec]:
    """Expand seeds into concrete specs.

    Validates each scenario **independently** so one malformed item (e.g. an
    inverted amount band, which llama3.1:8b emits for fixed-amount archetypes)
    no longer discards the whole batch. Raises :class:`ScenarioGenerationError`
    when zero valid specs survive — never silently returns an empty list.
    """
    prompt = _PROMPT_TEMPLATE.format(seeds_yaml=yaml.safe_dump(seed_archetypes))
    raw = await wrapper.chat_structured(
        messages=[{"role": "user", "content": prompt}],
        schema=ScenarioGenerationResult,
    )

    items = _extract_scenarios_payload(raw)
    specs: list[GeneratedScenarioSpec] = []
    rejected: list[str] = []
    for index, item in enumerate(items):
        try:
            specs.append(GeneratedScenarioSpec.model_validate(item))
        except ValidationError as exc:
            sid = item.get("id", "?") if isinstance(item, dict) else "?"
            reason = "; ".join(e.get("msg", "?") for e in exc.errors()[:2])
            rejected.append(f"#{index} ({sid}): {reason}")

    if rejected:
        LOG.warning(
            "seed expansion dropped %d/%d invalid specs: %s",
            len(rejected),
            len(items),
            " | ".join(rejected[:10]),
        )

    if not specs:
        raise ScenarioGenerationError(
            f"Ollama returned {len(items)} scenario(s) but 0 were valid. "
            f"Rejections: {' | '.join(rejected[:10]) or 'no parseable items'}"
        )
    return specs
