# Module 2: Meet the Cast

> Course: "How a Fraud Engine Thinks". Accent: vermillion. Audience: non-technical. Reuse the role names introduced in Module 1.

### Teaching Arc
- **Metaphor:** A **company org chart, all under one roof**. Each package is a department: the api package is the Reception Desk, the engine is the Decision Committee, ingest is the single Assembly Line where all writing happens, persistence is the Filing Cabinet, publish is the Mail Room, audit is the Compliance Logbook (un-erasable), security is the guard at the door, ratelimit is the turnstile, observability is the control room. The whole point: they all sit *under one roof* — that single roof is what "modular monolith" means. (No restaurant/kitchen.)
- **Opening hook:** "In Module 1 your transaction was passed from hand to hand. Each pair of hands belongs to a different department. Let us walk the building and meet them — and answer one question a real engineer agonized over: why is this *one* program instead of a dozen?"
- **Key insight:** The system is **one deployable program split into clearly-walled departments** (a *modular monolith*), not many separate mini-services. A bank wants one thing to deploy, one audit trail, and one all-or-nothing transaction — so the walls are enforced inside one app, with the option to split later.
- **"Why should I care?":** Once you know which department does what, every later detail has a place to live. This is the map. When you tell an AI assistant "put this logic in X, not Y," you need this map to point correctly. It also teaches the difference between a monolith and microservices — a real architecture decision (ADR-0002) you will face.

### Code Snippets (verbatim; HTML-escape in HTML)

**File: src/main/java/com/capitec/fraud/FraudRuleEngineApplication.java (lines 6–13)** — one on-switch for the whole building:
```java
@SpringBootApplication
public class FraudRuleEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(FraudRuleEngineApplication.class, args);
	}

}
```
Plain English: This tiny file is the single "on" switch for the entire system. One method boots every department at once. That single starting point is exactly what makes this a *monolith* — one program, not many separate services.

**File: src/main/java/com/capitec/fraud/domain/Transaction.java (lines 11–26)** — the core noun, kept clean:
```java
public record Transaction(
        UUID txId,
        String accountId,
        BigDecimal amount,
        String currency,
        String mcc,
        Channel channel,
        String country,
        String ipCountry,
        String deviceId,
        String merchantId,
        int accountAgeDays,
        Instant timestamp
) {
    public enum Channel { WEB, MOBILE, POS, ATM, API }
}
```
Plain English: A Transaction is just the facts about one card payment — id, account, amount/currency, where it happened (country vs the IP's country), device, merchant, account age, time. Plain data with nothing technical bolted on, so its business meaning stays obvious.

**File: src/main/java/com/capitec/fraud/domain/DecisionStatus.java (lines 3–7)** — the only three verdicts:
```java
public enum DecisionStatus {
    APPROVE,
    REVIEW,
    BLOCK
}
```
Plain English: Every transaction ends as exactly one of three verdicts — let it through (APPROVE), pause for a human (REVIEW), or stop it (BLOCK). When several rules fire, the strictest wins: BLOCK beats REVIEW beats APPROVE.

**File: src/main/java/com/capitec/fraud/domain/Decision.java (lines 12–28)** — the permanent record of *why*:
```java
public record Decision(
        UUID decisionId,
        UUID txId,
        String accountId,
        DecisionStatus status,
        double score,
        int ruleSetVersion,
        List<RuleResult> matchedRules,
        Instant evaluatedAt
) {
    public Decision {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0,1], got " + score);
        }
        matchedRules = List.copyOf(matchedRules);
    }
}
```
Plain English: A Decision is the verdict *and the full story behind it* — which transaction, the status, a 0-to-1 score, the rulebook version, and the exact list of rules that fired. It refuses to exist with a nonsense score, and it copies the matched-rules list so nobody can secretly rewrite the explanation later. This is the record a regulator relies on.

### Interactive Elements
- [x] **Visual file tree** — map the `src/main/java/com/capitec/fraud/` packages to one-line plain-English responsibilities: `api/` (Reception Desk), `domain/` (the shared vocabulary), `engine/` (Decision Committee), `ingest/` (Assembly Line — the only writer), `persistence/` (Filing Cabinet), `publish/` (Mail Room), `audit/` (Compliance Logbook), `advisory/` (the optional advisor), `security/` (door guard), `ratelimit/` (turnstile), `idempotency/` (duplicate-catcher), `observability/` (control room), `config/` + `query/` (plumbing & read helpers).
- [x] **Group chat animation (HERO + the course's required group chat)** — actors with distinct colors: Front Desk (api), The Brain (ingest), The Judge (engine), Filing Cabinet (persistence), Mail Room (publish), Logbook (audit). Script a short conversation as one transaction moves through:
  - Front Desk: "New transaction for ACC-NEW-12, 12,500 ZAR. Token checks out."
  - The Brain: "On it. Saving the raw event, then I will get a verdict."
  - The Judge: "Running the rules… HIGH_AMOUNT_NEW_ACCOUNT fired. Verdict: REVIEW, score 0.85."
  - The Brain: "Saving the verdict and the reasons — all in one go, or not at all."
  - Logbook: "Logged. This can never be erased."
  - Mail Room: "I will announce the decision to downstream services when it is safely committed."
- [x] **Drag-and-drop matching** — match each department (chip) to its responsibility (zone). e.g. "The only place that writes to the database" → ingest; "Keeps an un-erasable trail for regulators" → audit; "Decides allow/review/block" → engine; "Caps requests to 100/min" → ratelimit.
- [x] **Code↔English translation** — Transaction (the core noun) and Decision (the record of why). At least one.
- [x] **Side-by-side comparison** — "Modular Monolith vs Microservices" two columns: one app / one deploy / one all-or-nothing transaction / one audit trail vs many services / network calls between them / eventual consistency / harder to keep one clean audit trail. Cite ADR-0002.
- [x] **Quiz** — 3 questions, architecture/decision style:
  1. *Architecture:* You want to add a brand-new way to write data to the system. Based on the "single writer" rule, which department must that go through? (Answer: ingest — it is the only writer.)
  2. *Decision:* Why might a bank choose one program (monolith) over many services here? (Answer: one all-or-nothing transaction + one audit trail; correctness and compliance are simpler.)
  3. *Debugging:* A regulator asks "show me the unchangeable history of every decision." Which department do you point them to? (Answer: audit.)
- [x] **Callout (accent)** — "separation of concerns": splitting work into focused departments is one of the most important ideas in software — it is what lets you change one part without breaking the rest.

### Glossary Tooltips
package / module, monolith, microservices, modular monolith, deploy, enum, record, domain model, ADR (architecture decision record), eventual consistency, audit trail, PII, instance/replica, framework (Spring Boot).

### Reference Files to Read
- `references/interactive-elements.md` → "Group Chat Animation", "Visual File Tree", "Drag-and-Drop Matching", "Code ↔ English Translation Blocks", "Multiple-Choice Quizzes", "Callout Boxes", "Pattern/Feature Cards", "Icon-Label Rows"
- `references/content-philosophy.md` → all
- `references/gotchas.md` → all (note: chat windows need unique `id`; control buttons need `.chat-next-btn`/`.chat-all-btn`/`.chat-reset-btn`)

### Connections
- **Previous module:** "The Journey of a Transaction" — established the end-to-end path and the request/response shapes. This module names the departments that path ran through.
- **Next module:** "The Rulebook" — zooms into the Decision Committee (engine) and reveals its killer trick: the fraud rules are editable data, not code.
- **Tone/style notes:** This is the org-chart map module — lean heavily on the group chat, file tree, and drag-and-drop (visual, scannable). Keep prose minimal. Assign each department a consistent actor color (use `--color-actor-1..5`).
