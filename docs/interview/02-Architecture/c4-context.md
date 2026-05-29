# C4 — Context

Single system (`fraud-rule-engine`) with the actors and external systems it interacts with.

```
                  ┌────────────────────────┐
                  │  Ops Engineer          │
                  │  (administrator)       │
                  └──────────┬─────────────┘
                             │ X-Service-Api-Key
                             │ - hot-reload rules
                             │ - read audit log
                             │ - scrape Prometheus
                             ▼
┌───────────────┐      ┌────────────────────┐      ┌────────────────────┐
│ Card system / │ JWT  │  fraud-rule-engine │Kafka │ Downstream         │
│ merchant      │─────►│                    │─────►│ services           │
│ (transaction  │ POST │                    │ pub  │ (reconciliation,   │
│  source)      │ /api │                    │ tx.. │  case mgmt, ledger)│
└───────────────┘      └─────────┬──────────┘      └────────────────────┘
                                 │
                                 ▼ (storage)
                       ┌────────────────────┐
                       │ Postgres           │
                       │  + Redis           │
                       └────────────────────┘

                                 │  (optional)
                                 ▼
                       ┌────────────────────┐
                       │ Ollama (phi3:mini) │
                       │  human-reviewer    │
                       │  advisory only     │
                       └────────────────────┘
```

## Actors

| Actor | Identity | Authority |
|---|---|---|
| **Card system / merchant** | JWT (HS256 demo, RS256 production) bearing `ROLE_USER` | Submit transactions, read own decisions |
| **Ops Engineer** | Static service API key (`X-Service-Api-Key`) bearing `ROLE_SERVICE` | Hot-reload rules, read audit, scrape metrics |
| **Downstream services** | Kafka consumer group on `tx.decisions.v1` | Read-only consumer |
| **Human reviewer** | Internal tool reading the advisory + audit log | Workflow-driven; outside this scope |

## External systems

| System | Direction | Why it's external |
|---|---|---|
| Postgres 16 | bidirectional | The system of record for decisions + audit |
| Redis 7 | bidirectional | Idempotency cache + velocity state + rate-limit buckets |
| Redpanda (Kafka-compatible) | producer + consumer | Async ingest + decision egress |
| Ollama | producer only (outgoing HTTP) | Optional advisory; never affects decisions |
