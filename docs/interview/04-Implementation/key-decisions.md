# Key implementation decisions

The "why didn't you just …" answers, by topic.

## Why a separate `query` package?

ArchUnit rule: `api ↛ persistence`. Controllers can't import `DecisionRepository` directly. Two options:
1. Relax the rule.
2. Add a thin read-service layer.

Picked (2). `DecisionQueryService` and `AuditQueryService` are 30-line `@Transactional(readOnly=true)` services that translate JPA entities to DTO records. Entities never leak past the service boundary. Cost: 2 small files. Benefit: schema changes don't accidentally reshape the wire format.

## Why `@PostConstruct` for rule loading, not `ApplicationReadyEvent`?

Caught in Day-2.5 self-review. `ApplicationReadyEvent` fires AFTER the web port is listening. A bad YAML at startup would log an error but the app would come up "ready" — every subsequent ingest would 500 with `IllegalStateException("No RuleSet installed")`. `@PostConstruct` surfaces the load failure as a `BeanInitializationException`, the context aborts, the process exits non-zero, the orchestrator restarts/alerts. Fail-fast, not fail-silent.

## Why `Optional<EngineMetrics>` in the RuleEngine constructor?

`RuleEngine` is unit-testable without spinning up Spring. Tests construct it directly with `new RuleEngine(...)` and don't want a `MeterRegistry`. `Optional<EngineMetrics>` lets Spring inject when present and stays empty in tests. Convenience overload (`new RuleEngine(registry, state, clock)`) caused a "no default constructor" Spring error because two constructors were ambiguous — removed and updated all tests to pass `Optional.empty()` explicitly (Day 4 fix).

## Why constant-time compare on the API key?

A regular `String.equals` short-circuits at the first non-matching byte. The total time taken correlates with how many leading characters match. Under load and with enough requests, an attacker could learn the key one byte at a time. Banking-grade hygiene: constant-time XOR-accumulate, return at the end.

## Why is the `OutboxPoller` query a native `FOR UPDATE SKIP LOCKED`?

JPA's `@Lock(PESSIMISTIC_WRITE)` doesn't emit `SKIP LOCKED` — replicas would serialise on the same row, defeating the parallelism. Caught this in Day-3.5 review when running multiple replicas would have caused duplicate publishes. Native query is the right surgical scope.

## Why a sealed `Condition` AST?

Three shapes (Atomic / All / Any), enforced by `sealed permits`. The engine's `switch (cond)` is exhaustive — adding a new shape is a compile error until the engine handles it. Cheaper than a visitor for an AST this small.

## Why is the rule sort a `priority.reversed().thenComparing(id)`?

Without the `id` tiebreaker, two rules with equal priority sort in arbitrary order. A `shortCircuit:true` rule could fire OR yield to a sibling depending on JVM internals. Same input → different decision across restarts is a banking red flag. Caught in Day-2.5 review.

## Why is the audit listener `@Async` + `REQUIRES_NEW`?

Latency. The business transaction holds row locks; an audit write inside it would extend lock duration. `@Async` releases the business path immediately; `REQUIRES_NEW` ensures the audit write is its own committable unit. The downside (audit-write failure with business already committed) is mitigated by the `audit_write_failed_total` metric (Day 5) — and durably resolved by a retry queue in Day-7 polish.

## Why Bucket4j+Redis not Spring Cloud Gateway?

We don't need a gateway. Bucket4j-with-Redis is one filter, one bucket per JWT subject, all replicas share the counter. Spring Cloud Gateway introduces a whole reactive runtime for one feature.

## Why no separate management port?

The plan §13 risk table flagged this — production wants `management.server.port` so probes aren't internet-reachable. Deferred to Day-7 polish because the demo runs everything on localhost anyway. The Prometheus scrape endpoint is gated by the same API key as `/admin/**`, so it's not unauthenticated — just on the same listener.

## Why HashMap → LinkedHashMap in `buildDecisionPayload`?

Reproducible JSON field order in published events. With `HashMap`, two identical decisions could serialise to different byte strings (depending on JVM hash seeding), making diff-based forensics painful. Caught in Day-3.5 review; aligns with `toTransactionEntity` which already used `LinkedHashMap`.
