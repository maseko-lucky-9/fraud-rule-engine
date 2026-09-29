# Module 1: The Journey of a Transaction

> Course: "How a Fraud Engine Thinks". Accent color: vermillion (fraud-alert red). Audience: non-technical "vibe coders". Actor naming convention across the course: friendly role names (Front Desk, The Judge, etc.) mapped to real class names.

### Teaching Arc
- **Metaphor:** An **airport security lane**. You show your boarding pass (the JWT login token) and your bag gets a baggage tag (the Idempotency-Key so the same bag is never screened twice). Your bag (the transaction) is X-rayed against a checklist of red flags (the rules), and you get a green / amber / red light — ALLOW / REVIEW / BLOCK — with a note explaining why. (Do NOT use restaurant/kitchen anywhere in the course.)
- **Opening hook:** "Imagine someone taps a card for **R12,500** on an account that is only **12 days old**. In the few milliseconds before the bank says *'hold on — let a human look at this,'* here is the exact journey that payment takes." (This is the real README demo: it trips the HIGH_AMOUNT_NEW_ACCOUNT rule → REVIEW.)
- **Key insight:** A transaction goes IN as a small bundle of JSON facts and comes back OUT as a **verdict** (ALLOW / REVIEW / BLOCK) with a risk score and a plain-English reason. The whole rest of the course is a zoom-in on one of the stops along this path.
- **"Why should I care?":** This is the "what actually happens when you tap pay" story. Knowing the request→response shape and where each step lives is what lets you (a) read an API, (b) debug "my call returns something weird," and (c) tell an AI assistant *exactly* where in the pipeline a change belongs.

### Code Snippets (pre-extracted — use verbatim; HTML-escape `<` `>` `&` when placing in `<pre><code>`)

**File: src/main/java/com/capitec/fraud/api/TransactionRequest.java (lines 17–31)** — the exact shape every incoming transaction must match:
```java
public record TransactionRequest(
        @NotNull UUID txId,
        @NotBlank String accountId,
        @NotNull @DecimalMin(value = "0.0", inclusive = true)
        @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be ISO-4217 uppercase, e.g. ZAR") String currency,
        @NotBlank @Pattern(regexp = "[0-9]{4}", message = "mcc must be 4 numeric digits") String mcc,
        @NotBlank @Pattern(regexp = "WEB|MOBILE|POS|ATM|API") String channel,
        @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "country must be ISO-3166-1 alpha-2 uppercase") String country,
        @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "ipCountry must be ISO-3166-1 alpha-2 uppercase") String ipCountry,
        String deviceId,
        String merchantId,
        @NotNull @Min(0) Integer accountAgeDays,
        @NotNull Instant timestamp
) {
```
Plain English: The precise template every transaction must match. The `@`-labels are guardrails: amount can't be negative, currency must be three capitals like ZAR, channel must be one of WEB/MOBILE/POS/ATM/API, account age can't be below zero. Break any rule and the request is rejected *before* any fraud check runs.

**File: src/main/java/com/capitec/fraud/api/TransactionController.java (lines 76–82)** — the entry point:
```java
        Decision decision = ingestService.ingest(toDomain(request), IngestService.CONSUMER_REST);
        DecisionResponse response = toResponse(decision);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotency.store(subject, idempotencyKey, bodyHash, response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
```
Plain English: Once checks pass, the controller hands the transaction to IngestService, which returns a Decision (the verdict). It files the result under the do-not-repeat ticket if one was sent, then replies with HTTP 202 ACCEPTED and the verdict.

**File: src/main/java/com/capitec/fraud/api/TransactionController.java (lines 60–73)** — replay protection:
```java
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var cached = idempotency.lookup(subject, idempotencyKey);
            if (cached.isPresent()) {
                if (!cached.get().bodyHash().equals(bodyHash)) {
                    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
                    pd.setType(URI.create("https://fraud-engine.example/problems/idempotency-conflict"));
                    pd.setTitle("Idempotency-Key reused with different body");
                    pd.setDetail("The same Idempotency-Key was previously used with a different payload.");
                    pd.setProperty("idempotencyKey", idempotencyKey);
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
                }
                // Same key + same body → return cached response verbatim.
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(cached.get().response());
```
Plain English: Before doing any real work, it checks whether this ticket was used before. Same ticket + same transaction → return the saved answer (no double-processing). Same ticket + a *different* transaction → that's suspicious bookkeeping, so refuse with a 409 Conflict instead of guessing.

**File: src/main/java/com/capitec/fraud/api/DecisionResponse.java (lines 7–17)** — the verdict you get back:
```java
public record DecisionResponse(
        UUID decisionId,
        UUID txId,
        String status,
        double score,
        int ruleSetVersion,
        List<MatchedRule> matchedRules,
        Instant evaluatedAt
) {
    public record MatchedRule(String ruleId, int priority, String reason) {}
}
```
Plain English: Exactly what the client gets back — a unique decisionId, which transaction it answers, the status (ALLOW/REVIEW/BLOCK), a 0-to-1 risk score, which rulebook version was used, the list of rules that fired (each with name, priority, and a human-readable reason), and when it was evaluated.

**File: src/main/resources/rules/rule-set-v1.yml (lines 33–46)** — the rule that fires in the demo (preview only; full treatment is Module 3):
```yaml
    - id: HIGH_AMOUNT_NEW_ACCOUNT
      priority: 800
      severity: HIGH
      shortCircuit: false
      condition:
        all:
          - predicate: amountAbove
            args: { value: 10000, currency: "ZAR" }
          - predicate: accountAgeBelow
            args: { days: 30 }
      action:
        flag: REVIEW
        score: 0.85
        reason: "High amount on a young account."
```
Plain English: IF amount > 10,000 ZAR AND account younger than 30 days → flag for human REVIEW, score 0.85. The demo (12,500 ZAR on a 12-day-old account) trips exactly these two conditions.

### Interactive Elements
- [x] **Code↔English translation** — use TransactionRequest (the request shape), DecisionResponse (the verdict shape), and the TransactionController entry point (lines 76–82). At least 2 translation blocks.
- [x] **Data flow animation (HERO of this module)** — actors: `Client App` → `Front Desk (TransactionController)` → `Bouncer (auth + validation)` → `Memory (Idempotency)` → `The Brain (IngestService)` → `The Judge (Rule Engine)` → `Filing Cabinet (Database)` → back to `Client App`. Steps (keep labels apostrophe-free — single quotes break `data-steps` JSON parsing):
  1. Client asks Front Desk for a login pass at /auth/token, gets a JWT token
  2. Client POSTs the transaction JSON to /api/v1/transactions with the token + an Idempotency-Key
  3. Bouncer checks the token is valid and the JSON matches the template; bad input is rejected here
  4. Memory checks the Idempotency-Key: seen-before returns the saved answer; new key continues
  5. The Brain saves the raw event, then asks The Judge for a verdict
  6. The Judge runs the rules; HIGH_AMOUNT_NEW_ACCOUNT fires, verdict REVIEW, score 0.85
  7. The Brain saves the verdict plus the list of fired rules, all in one all-or-nothing write
  8. Front Desk replies HTTP 202 ACCEPTED with the verdict JSON
- [x] **Pattern/feature cards** — three cards: "Idempotency-Key (do-not-charge-twice ticket)", "One all-or-nothing write", "Consistent error reports (RFC 7807 Problem Details with a correlation id)".
- [x] **Quiz** — 3 questions, scenario/debugging style:
  1. *Scenario:* A client retries the exact same transaction (same Idempotency-Key, same body) after a timeout. What happens? (Answer: it returns the original saved verdict — no second processing. Teaches idempotency.)
  2. *Debugging:* A teammate says "the API returned 202 but I expected 200 — is it broken?" (Answer: 202 ACCEPTED is the intended success status here; not a bug. Teaches reading status codes — see gotcha.)
  3. *Tracing:* A submitted transaction never shows up in GET /api/v1/decisions. Which stop on the journey would you check first? (Answer: was it rejected at validation/auth before it ever reached the engine — a 400/401 means it never became a decision.)
- [x] **Callout (accent, "Key Insight")** — the idempotency aha: "A retry should never cost you twice. Real systems hand out a one-time ticket so a repeated request returns the *same* answer instead of doing the work again."
- [ ] Group chat — not here (M2 owns the first group chat).

### Glossary Tooltips (first use, aggressive)
JWT / token, JSON, API, endpoint, POST vs GET, payload, request/response, HTTP status code, 202 ACCEPTED, 409 Conflict, idempotency / Idempotency-Key, validation, hash, record (Java), transaction (DB sense vs payment sense — clarify both), UUID.

### Reference Files to Read
- `references/interactive-elements.md` → "Code ↔ English Translation Blocks", "Message Flow / Data Flow Animation", "Pattern/Feature Cards", "Multiple-Choice Quizzes", "Callout Boxes", "Glossary Tooltips"
- `references/content-philosophy.md` → all (content rules)
- `references/gotchas.md` → all (checklist; especially the `data-steps` single-quote warning and tooltip rules)

### Connections
- **Previous module:** none — this is the opening. Start by saying in one or two plain sentences what the whole app *is* (a real-time brain that scores bank card transactions and says allow/review/block with a reason), then dive into the journey.
- **Next module:** "Meet the Cast" — names every department this transaction just passed through (Front Desk, The Judge, The Brain, Filing Cabinet, Mail Room) and explains why it is all one program (a modular monolith).
- **Tone/style notes:** Establish the friendly role-name → real-class-name mapping here; later modules reuse the same role names. Vermillion accent. This module is mostly the journey + the in/out shapes — keep it concrete and grounded in the demo transaction.
