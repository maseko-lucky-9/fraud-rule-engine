# 03 — Architecture Decision Records

Single source of truth lives at `../../adr/`. Symlinks are avoided so the submission ZIP works on Windows extractors.

| # | Decision | Link |
|---|---|---|
| 0001 | Overall architecture | [../../adr/0001-architecture-overview.md](../../adr/0001-architecture-overview.md) |
| 0002 | Modular monolith | [../../adr/0002-modular-monolith.md](../../adr/0002-modular-monolith.md) |
| 0003 | Redpanda (Kafka-compatible) for async transport | [../../adr/0003-redpanda.md](../../adr/0003-redpanda.md) |
| 0004 | Postgres + Flyway as datastore | [../../adr/0004-postgres.md](../../adr/0004-postgres.md) |
| 0005 | YAML rules + typed Java predicates | [../../adr/0005-rule-representation-yaml.md](../../adr/0005-rule-representation-yaml.md) |
| 0006 | Reliability + resilience patterns | [../../adr/0006-reliability-resilience.md](../../adr/0006-reliability-resilience.md) |
| 0007 | Security — JWT + service API key | [../../adr/0007-security-jwt-api-key.md](../../adr/0007-security-jwt-api-key.md) |
| 0008 | Storage schema + retention | [../../adr/0008-storage-schema.md](../../adr/0008-storage-schema.md) |
| 0009 | API design — contract-first + RFC 7807 | [../../adr/0009-api-design.md](../../adr/0009-api-design.md) |
| 0010 | Ollama advisory — non-authoritative + human-gated | [../../adr/0010-advisory-ollama.md](../../adr/0010-advisory-ollama.md) |
| 0011 | Spring Boot 4 + JDK 21 (plan deviation log) | [../../adr/0011-spring-boot-4-and-jdk-21.md](../../adr/0011-spring-boot-4-and-jdk-21.md) |

All 11 ADRs are committed to the submission. Each includes Context / Decision / Consequences / Forward path; the security and advisory ADRs include explicit footgun sections so the reviewer can verify Day-4.5 + Day-5.5 fixes in git history.
