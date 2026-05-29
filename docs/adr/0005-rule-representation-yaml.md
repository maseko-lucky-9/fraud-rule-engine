# ADR-0005: Rules as Versioned YAML + Typed Java Predicates

**Status:** Accepted · **Date:** 2026-05-19

## Context

Fraud rules change often (regulator updates, new fraud vectors, threshold tuning) and must be auditable. The shape that scales is:

- **Definition.** Declarative — operators read it without compiling.
- **Execution.** Type-safe — bugs caught at compile time, performance is in-process.
- **Audit.** Versioned per change, with the version stamped on every decision.

The candidate options were (a) all Java strategy classes, (b) embedded DSL like Drools, (c) YAML config + typed Java predicates.

## Decision

**YAML config + typed Java predicates.**

- A rule is a YAML record with `id`, `priority`, `severity`, `shortCircuit`, `condition`, `action`.
- The `condition` is a small tree: `{predicate: id, args: {...}}` atomic, plus `all: [...]` (AND) and `any: [...]` (OR) composites. Sealed `Condition` interface enforces these three shapes.
- Each predicate is a Spring `@Component implements Predicate` keyed by `id()`. The `PredicateRegistry` rejects duplicate ids at startup.
- The YAML is validated against a JSON Schema (draft 2020-12) before being mapped to the domain. On validation failure: keep the old `RuleSet` active, return 422 to the operator, emit `rules_reload_failed_total` (Day 4).
- Hot reload: `POST /admin/rules/reload` swaps `AtomicReference<RuleSet>` atomically. Old set serves in-flight evaluations.
- `Decision.ruleSetVersion` is the active version at evaluation time — persisted, so every audit trace is reproducible.

## Why not the alternatives

| Concern | Java classes only | Drools DSL | **YAML + predicates (chosen)** |
|---|---|---|---|
| Operator can read a rule | No (Java source) | Partial (.drl) | Yes |
| Type safety on predicate args | Yes | Weak (string MVEL) | Yes (via PredicateArgs helpers + JSON Schema) |
| Compilation footprint | Recompile per change | Knowledge base reload | None — YAML reload |
| Audit (version per change) | Per-tag | Possible | First-class (version field, persisted) |
| Learning curve for reviewer | Low | High | Low |
| Performance overhead | None | RETE engine | Tree walk in pure Java — sub-millisecond |

Drools brings a RETE engine and a Java-MVEL hybrid syntax that takes more lines of explanation than the engine itself. For a take-home where the reviewer must build a mental model in 15 minutes, that overhead is not earned.

## Consequences

**Positive.**
- Rule changes ship without code review of business logic — operator reviews YAML, dev reviews predicate code.
- JSON Schema catches typos and shape errors before any decision is wrong.
- Versioning is structural, not by-convention.

**Negative.**
- Adding a new *predicate* still requires a Java class + redeploy. That's the deliberate boundary: composition is config; the building blocks are code.
- The condition AST is intentionally simple (only `all` / `any`). NOT-conditions, arithmetic comparisons, and nested groups beyond AND/OR are out of scope; complex logic should become its own predicate.

## Forward path

- Day 3: Redis-backed `StateStore` replaces in-memory for `velocity` / `deviceFingerprintNew`.
- Day 4: `/admin/rules/reload` gated by service API key + audit log entry.
- Day 5: Mutation tests (Pitest) on the engine package to lift coverage quality above the 95% line count target.
