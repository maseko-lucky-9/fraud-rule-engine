.PHONY: help sync pull-llm sim-run sim-reset sim-seed sim-audit test lint format clean

SIM          := uv run sim
OLLAMA_MODEL := llama3.1:8b
PROFILE      := config/profile.default.yaml

help:
	@echo "fraud-rule-engine simulator — make targets"
	@echo ""
	@echo "  sync       — uv sync (install runtime + dev deps)"
	@echo "  pull-llm   — ollama pull $(OLLAMA_MODEL) (~5 GB)"
	@echo "  sim-reset  — TRUNCATE engine PG decision tables for a clean run"
	@echo "  sim-seed   — Ollama expands seed YAML into scenario scripts"
	@echo "  sim-run    — run the default profile end-to-end"
	@echo "  sim-audit  — render the audit report for the latest run"
	@echo "  test       — pytest -q (no integration, no Ollama by default)"
	@echo "  lint       — ruff check ."
	@echo "  format     — ruff format ."
	@echo "  clean      — remove caches and per-run output dirs"

sync:
	uv sync

pull-llm:
	ollama pull $(OLLAMA_MODEL)

sim-reset:
	$(SIM) reset --truncate

sim-seed:
	$(SIM) seed

sim-run:
	$(SIM) run --profile $(PROFILE)

sim-audit:
	$(SIM) audit reports/run-latest/

test:
	uv run pytest -q --no-header

lint:
	uv run ruff check .

format:
	uv run ruff format .

clean:
	rm -rf .pytest_cache .ruff_cache __pycache__ */__pycache__ */*/__pycache__ \
	       reports/run-* reports/run-latest 2>/dev/null || true
