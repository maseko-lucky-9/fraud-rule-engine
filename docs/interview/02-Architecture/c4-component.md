# C4 — Component

Package graph inside the `api` container. ArchUnit pins three rules:
1. `api ↛ persistence` (read services in `query` mediate).
2. `domain ↛ frameworks` (Spring/JPA/Kafka/Jackson all forbidden).
3. `advisory` only callable from `api`, `engine`, `config`, `advisory` itself.

```
              ┌─────────────┐
              │     api     │
              └──────┬──────┘
                     │ depends-on
       ┌─────────────┼──────────────┬──────────────┬──────────────┐
       ▼             ▼              ▼              ▼              ▼
  ┌─────────┐  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
  │ ingest  │  │  query   │   │ security │   │advisory  │   │idempotency│
  └────┬────┘  └─────┬────┘   └──────────┘   └────┬─────┘   └────┬─────┘
       │             │                            │              │
       │             │                            ▼              │
       ▼             ▼                       ┌──────────┐        ▼
  ┌─────────┐  ┌──────────┐                  │   Ollama │   ┌──────┐
  │persist..│  │persist.. │                  │  HTTP    │   │ Redis│
  └────┬────┘  └──────────┘                  └──────────┘   └──────┘
       │
       │  ┌──────────┐
       ├─►│ engine   │  ← ArchUnit-enforced: domain has zero framework deps
       │  │  + pred. │
       │  └────┬─────┘
       │       │
       ▼       ▼
  ┌─────────┐ ┌────────┐
  │ publish │ │ domain │  (pure POJOs)
  │ outbox  │ │ POJOs  │
  └────┬────┘ └────────┘
       │
       ▼
   Redpanda

  ┌─────────┐
  │ audit   │  (Spring @EventListener — invoked from ingest)
  └────┬────┘
       │
       ▼
   Postgres
```

## Package roles

| Package | Role | Owned by |
|---|---|---|
| `domain` | Pure POJOs (Transaction, Decision, Rule, RuleSet, Condition, ...) | The model itself; survives stack migration unchanged |
| `engine` | RuleEngine + RuleLoader + PredicateRegistry + StateStore | The deterministic core |
| `engine.predicates` | 8 typed predicates | Plug-in extension point |
| `ingest` | IngestService + TransactionKafkaListener | Single writer |
| `persistence` | JPA entities + repositories | Database-shape, never leaks out |
| `publish` | OutboxPoller | Eventual consistency between DB and Kafka |
| `advisory` | Ollama adapter + Noop fallback | LLM bounded zone |
| `api` | REST controllers + DTOs + RFC 7807 handler | Network surface |
| `audit` | AuditEvent + listener + AuditQueryService + AuditController | Compliance |
| `idempotency` | Redis-backed cache | Replay safety |
| `ratelimit` | Bucket4j filter | Backpressure |
| `security` | SecurityConfig + JWT + API key filters + AuthController | Authz boundary |
| `observability` | EngineMetrics + CorrelationIdFilter + PiiRedactor | Day-2 surface |
| `query` | Read services (DecisionQueryService) | Preserves `api ↛ persistence` rule |
| `config` | Spring config + AsyncConfig + ClockConfig + OpenApiConfig | Wiring |

## Key invariants

1. **Single writer.** Only `IngestService.ingestNew` writes the `decisions` / `decision_rules` / `outbox` / `processed_events` tables. Easier to reason about transactional correctness.
2. **Predicates are stateless.** All state lives in `StateStore` (Redis or in-memory). Predicates can be unit-tested without standing up Spring.
3. **Audit is fire-and-forget at the call site but durable at the listener.** `@Async REQUIRES_NEW` means audit-write failures don't affect business semantics — but they ARE observable via `audit_write_failed_total`.
