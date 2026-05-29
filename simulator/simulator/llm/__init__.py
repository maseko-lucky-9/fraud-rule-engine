"""LLM layer — Ollama-backed scenario generation, triage, and audit writing.

Per plan §2 the LLM has four roles in the harness:

- **Scenario generator** — expands hand-curated seeds into ~10 concrete
  variants apiece (this module ships).
- **Adversarial brain** — feeds engine responses back to the LLM to choose
  the next attack (deferred to a follow-up; the harness already runs
  scripted adversaries that produce useful audit content).
- **Decision triage** — post-run, classifies grey-zone scores into
  fraud-pattern buckets (this module ships).
- **Audit writer** — final markdown report narrative (this module ships).

Every role uses Pydantic structured outputs so LLM hallucination cannot
break the deterministic pandas/metrics path.
"""
