# ADR-0002: Modular Monolith over Microservices

**Status:** Accepted · **Date:** 2026-05-19

## Context

Fraud detection systems are sometimes scaffolded as microservices (ingest, rule, scoring, decision, advisory). For a take-home evaluation we must weigh complexity against signal.

## Decision

**Modular monolith.** Reject microservices for this submission.

## Rationale

| Concern | Monolith | Microservices |
|---|---|---|
| Reviewer time-to-running | ~3 min (`docker compose up`) | 10–15 min (multiple services, network) |
| Distributed tracing required | No (in-process) | Yes (OTel + collector mandatory) |
| Transactional integrity | One `@Transactional` boundary | Outbox + saga; harder to reason about |
| Demonstrates banking judgement | Yes (banks prefer boring) | Mixed (cargo-culting risk) |
| Future split feasibility | Preserved via ArchUnit + outbox | N/A |

## Consequences

- Boundaries must be enforced by tests, not the network.
- Future migration to microservices is documented as future work, not closed off.
