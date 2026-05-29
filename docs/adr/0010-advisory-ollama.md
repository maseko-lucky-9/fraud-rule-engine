# ADR-0010: Ollama Advisory — Optional, Non-authoritative, Human-Gated

**Status:** Accepted · **Date:** 2026-05-19

## Context

A bank can't let an LLM decide whether a transaction is fraud. But the brief asks for an Ollama integration; the panel signals reward "AI-aware but rigorous". The submission must show that we know exactly where AI helps (analyst productivity) and exactly where it would cause harm (the decision itself).

## Decision

**Ollama is a commentary layer only.** The deterministic rule engine is the source of truth for every decision; the advisory is a string of structured prose attached after-the-fact for a human reviewer to read.

### Hard rules

1. **Never inline.** Advisory is a separate endpoint (`GET /api/v1/decisions/{id}/advisory`), called by clients AFTER they have the deterministic decision. The ingest hot path (`POST /api/v1/transactions`) does not touch Ollama, ever.
2. **Always human-gated.** Every `AdvisoryResponse` carries `humanReviewRequired=true`. Even on a healthy `OK` reply, the flag is true — by policy, not by accident. The shape of the field makes it impossible for a caller to interpret advisory as a green-light.
3. **Fail open to deterministic.** Ollama down → 503 with `Retry-After: 10`. Ollama times out (>2s) → `AdvisoryStatus.TIMED_OUT`. Ollama returns malformed JSON → `MALFORMED`. In every failure mode, the deterministic decision remains the binding answer.
4. **Strict 2s timeout.** No bank reviewer will wait. Resilience4j `advisory` circuit breaker opens after sustained failure, returning `UNAVAILABLE` without burning further upstream calls.
5. **PII never leaves the process.** The prompt template (`prompts/advisory-v1.md`) explicitly forbids account-id / customer-name / amount inclusion verbatim. Only the verdict, score, and matched-rule ids are sent.

### Model + footprint

- **Default model:** `phi3:mini` (≈ 2.3GB). Cheap, runs on a laptop, strict JSON output via `format=json` mode of Ollama's `/api/generate`.
- **Reproducible:** model pinned via `OLLAMA_MODEL` env var; documented in README quick-start.
- **Optional in compose:** `docker-compose.advisory.yml` is an overlay; the core stack runs without it.

### Implementation surface

| File | Role |
|---|---|
| `advisory/AdvisoryService.java` | Interface — implementations injected by Spring profile. |
| `advisory/NoopAdvisoryService.java` | Bound when `app.advisory.enabled=false`; returns `UNAVAILABLE`. |
| `advisory/OllamaAdvisoryService.java` | Bound when `=true`; calls Ollama HTTP API with 2s timeout + circuit breaker. |
| `api/AdvisoryController.java` | `GET /api/v1/decisions/{id}/advisory` → 200 on `OK`, 503 on `UNAVAILABLE`/`TIMED_OUT`. |
| `resources/prompts/advisory-v1.md` | The prompt template. Versioned with the code; bumps require ADR-0010-v2. |
| `docs/advisory-eval.md` | The hallucination eval rubric. |

### Hallucination defence in depth

- **Schema validation.** Ollama's JSON-mode + our strict parser. Anything that doesn't match the schema → `MALFORMED`. We observe `advisory_response_total{status="MALFORMED"}` and alert on it.
- **No-PII prompt contract.** The prompt explicitly says "never include account id / customer name / amount verbatim" — the eval suite asserts that for 20 fixtures.
- **No-overturn policy.** "NEVER recommend overturning the rule engine" is in the prompt. Eval suite asserts that advisory text doesn't contain "approve" or "block" keywords for `REVIEW` decisions.
- **`humanReviewRequired` invariant.** The flag is set in code, not by the model. It is true by default and is only ever cleared in a future iteration if a calibration study justifies it.

### Why not a different model

- **`mistral` (7B):** larger memory footprint, similar quality for this short-form task; we'd pay ~3× the RAM for marginal benefit.
- **Hosted (OpenAI / Anthropic):** would require external network egress + secrets management + spend tracking. Out of scope for a single-machine submission. Documented as the production migration path.

## Consequences

**Positive.**
- Reviewer sees the full surface: the demo runs without Ollama (`NoopAdvisoryService`) and with Ollama (`OllamaAdvisoryService`) by toggling a single env var. No conditional code paths leak into the deterministic engine.
- The "AI cannot decide" boundary is enforced at three points (separate endpoint, never inline; `humanReviewRequired` always true; deterministic decision pre-existing).
- Hallucination is observable (`advisory_response_total{status}` counters) and bounded (eval rubric).

**Negative.**
- Cold-start of Ollama on `compose up` is slow; pre-pull is required for a smooth demo.
- The single static prompt limits us to one task framing; richer evaluation (e.g. step-by-step reasoning) is out of scope. Documented as Day-7+ work.

## Forward path

- Replace `phi3:mini` with a fine-tuned internal model when one exists. Same interface.
- Add an `/admin/advisory/reload-prompt` to hot-swap the prompt template (audit-logged).
- Switch from one-shot prompting to a small-fixture few-shot pattern if precision needs to lift.
