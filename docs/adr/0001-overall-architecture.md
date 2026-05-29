# ADR-0001: Modular Monolith with Async-First Ingest

**Status:** Accepted · **Date:** 2026-05-19

## Context

The brief calls for a fraud rule engine that demonstrates the seven NFR pillars without overshooting into microservices theater. A reviewer must be able to clone, run, and walk the architecture in 15–20 minutes.

## Decision

A single Spring Boot 4 service, organised as a **modular monolith**:

- Module boundaries enforced by ArchUnit (`api`, `domain`, `engine`, `ingest`, `enrichment`, `persistence`, `publish`, `advisory`, `security`, `observability`).
- Async-first ingest via Redpanda topics (Kafka-compatible API), with a sync REST façade for low-latency clients.
- Decision pipeline runs in a single `@Transactional` boundary inside one JVM. No distributed transaction.

## Consequences

**Positive.**
- One deployable artefact, one repository, one CI pipeline. Banks prefer this.
- Module boundaries prepare for future extraction without paying microservices tax now.
- Async core ensures we can replay events for backtesting rule changes.

**Negative.**
- Single point of horizontal scaling is the whole API; we cannot scale just the engine.
- Migrating a module out later requires breaking the in-process transaction boundary.

**Mitigations.**
- ArchUnit rules prevent accidental coupling.
- Outbox pattern + idempotent consumers make future split feasible.
