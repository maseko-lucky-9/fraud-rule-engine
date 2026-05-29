# ADR-0003: Redpanda for the Async Transport

**Status:** Accepted · **Date:** 2026-05-19

## Context

The async ingest path needs partitioned ordering (per `accountId`), replay (for rule backtesting), and durable storage. Apache Kafka is the conventional choice; Redpanda offers the same wire protocol and Spring Kafka client compatibility but ships as a single binary.

## Decision

**Redpanda v25.1.1** in compose. Spring Kafka 4.x client. Topics: `tx.events.v1`, `tx.decisions.v1`, `tx.events.retry`, `tx.events.dlt`. Partitioned by `accountId` (key) to preserve ordering per account.

## Rationale

| Concern | Apache Kafka | Redpanda |
|---|---|---|
| Container count | 2 (ZK or KRaft controller + broker) | 1 |
| `docker compose up` time | ~60–90s | ~10–15s |
| Spring Kafka compatibility | Yes | Yes (same client) |
| Replay for backtesting | Yes | Yes |
| Production-ready? | Probable | Possible (Vectorized has banking customers) |

## Consequences

- Plan deviation from "Apache Kafka" wording in the brief inspiration article. Documented in this ADR. Migration to Apache Kafka is a config change (no code change) because we use the standard Kafka client.
- Reviewer machines with very constrained resources can still run the stack.

## Migration path to Apache Kafka

1. Swap the compose service: `redpanda` → `bitnami/kafka` (KRaft mode) or `confluentinc/cp-kafka` (KRaft).
2. Update `KAFKA_BOOTSTRAP` advertised listeners in env.
3. No application code change. Tests via Testcontainers `KafkaContainer` still work.
