# C4 — Container

Inside `fraud-rule-engine` (single deployable, Spring Boot 4 app).

```
┌──────────────────────── fraud-engine-api ────────────────────────┐
│                                                                  │
│   ┌──────────────┐    ┌────────────────┐    ┌─────────────────┐  │
│   │ REST adapter │    │ Kafka adapter  │    │ Admin / Actuator│  │
│   │ (POST /tx)   │    │ (consumer)     │    │ (Prometheus etc)│  │
│   └──────┬───────┘    └────────┬───────┘    └─────────────────┘  │
│          │                     │                                 │
│          └────────┐  ┌─────────┘                                 │
│                   ▼  ▼                                           │
│           ┌─────────────────────────────────┐                    │
│           │   IngestService (single writer) │                    │
│           │   @Transactional                │                    │
│           └────────────┬────────────────────┘                    │
│                        │                                         │
│           ┌────────────┼────────────────┐                        │
│           ▼            ▼                ▼                        │
│   ┌──────────────┐ ┌───────────┐  ┌──────────────────┐           │
│   │ RuleEngine   │ │ Persistence│  │ AuditEventListener│           │
│   │ (AtomicRef   │ │ (JPA)     │  │ @Async REQUIRES_NEW│           │
│   │  RuleSet)    │ │           │  │                  │           │
│   └──────────────┘ └─────┬─────┘  └──────────────────┘           │
│                          │                                       │
│                          ▼                                       │
│                  ┌────────────────┐                              │
│                  │ OutboxPoller   │                              │
│                  │ @Scheduled     │                              │
│                  │ SELECT … FOR   │                              │
│                  │ UPDATE         │                              │
│                  │ SKIP LOCKED    │                              │
│                  └────────┬───────┘                              │
│                           │                                      │
│                           ▼ Kafka publish                        │
└──────────────────────────────────────────────────────────────────┘
                                                  ┌──────────────┐
                                                  │ AdvisoryCtrl │
                                                  │ (read-only,  │
                                                  │ optional)    │
                                                  └──────┬───────┘
                                                         │
                                                         ▼
                                                    Ollama (HTTP)
```

## Containers (each is a deployable unit)

| Container | Technology | Purpose | Multi-replica safe? |
|---|---|---|---|
| `api` | Spring Boot 4 / Java 21 | All REST + Kafka consumer + scheduled poller | ✅ — outbox uses SKIP-LOCKED |
| `postgres` | Postgres 16 | Decisions + audit + outbox + rule versions | Single primary in demo; HA cluster in production |
| `redis` | Redis 7 | Idempotency + velocity + rate limit | Replica-set in production |
| `redpanda` | Kafka-compatible broker | `tx.events.v1` (input), `tx.decisions.v1` (output), `tx.events.dlt` | Single-node demo; 3-broker cluster in production |
| `ollama` (optional) | Ollama / `phi3:mini` | Advisory commentary | Per-region instance; stateless |

## Inter-container traffic

| Channel | Protocol | Auth |
|---|---|---|
| Client → api (REST) | HTTPS | JWT Bearer |
| Ops → api (admin) | HTTPS | X-Service-Api-Key |
| api ↔ postgres | TCP 5432 | Username/password (env-only) |
| api ↔ redis | TCP 6379 | None (network-isolated) |
| api ↔ redpanda | TCP 9092 | None in demo; mTLS in production |
| api → ollama | HTTP 11434 | None (loopback only) |

## Internal modules (not separate containers, but ArchUnit-enforced boundaries)

`domain`, `engine`, `ingest`, `persistence`, `publish`, `advisory`, `api`, `audit`, `idempotency`, `ratelimit`, `security`, `observability`, `query`, `config` — see [c4-component.md](c4-component.md) for the dependency graph.
