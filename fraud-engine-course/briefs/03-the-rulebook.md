# Module 3: The Rulebook — How the Engine Decides

> Course: "How a Fraud Engine Thinks". Accent: vermillion. Audience: non-technical. THIS IS THE HEART OF THE COURSE — give it the most care.

### Teaching Arc
- **Metaphor:** A **Smart Playlist** (like the ones in a music app). You build rules in a settings panel — "if genre is Jazz AND year is after 2000, add to this playlist" — picking conditions from a fixed dropdown menu. No coding. You hit *apply* and the playlist updates live. Map it: the fraud **rules** are the playlist rules (editable data), the **predicates** (`amountAbove`, `velocity`, `geoMismatch`) are the dropdown of available conditions, and **hot reload** is hitting *apply* without restarting the app. (No restaurant/kitchen.)
- **Opening hook:** "In Module 1 a rule called HIGH_AMOUNT_NEW_ACCOUNT decided to flag that R12,500 payment for review. Here is the surprise: that rule is not buried in code. It lives in a plain text file a fraud analyst can edit — and change without a single developer or redeploy."
- **Key insight:** **Rules are data, not code.** They live in a versioned YAML file. Each rule names reusable building-block tests (predicates) and an action. The engine walks the rules in priority order, keeps the strictest verdict and the highest score, can stop early on an obvious block, and the whole rulebook can be hot-swapped live — with a broken file safely rejected.
- **"Why should I care?":** This is how real systems let business experts change behavior safely without waiting on engineers — and it is one of the most valuable things you can ask an AI assistant to build: "don't hardcode these thresholds; make them configurable data I can change without redeploying." You will also learn to recognize when something *should* be data instead of code.

### Code Snippets (verbatim; HTML-escape `<` `>` `&` in HTML)

**File: src/main/resources/rules/rule-set-v1.yml (lines 33–46)** — a whole fraud rule, written as plain settings:
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
Plain English: A complete rule, no programming. IF amount > 10,000 rand AND account younger than 30 days → flag for human REVIEW, score 0.85, with a readable reason. An analyst can change 10000 to 8000 and reload — no developer needed.

**File: src/main/java/com/capitec/fraud/engine/predicates/AmountAbovePredicate.java (lines 22–28)** — one reusable test:
```java
    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        BigDecimal threshold = PredicateArgs.requireBigDecimal(args, "value");
        String currency = PredicateArgs.requireString(args, "currency");
        var tx = ctx.transaction();
        return currency.equals(tx.currency()) && tx.amount().compareTo(threshold) > 0;
    }
```
Plain English: One reusable fraud test. It reads two settings from the rule (`value` and `currency`), looks at the transaction, and returns true only if the money is in the matching currency AND bigger than the threshold. The *same code* serves a 10,000 rule and a 5,000 rule — the number comes from the YAML, not the code.

**File: src/main/java/com/capitec/fraud/engine/PredicateRegistry.java (lines 20–30)** — the name-to-code phone book:
```java
    public PredicateRegistry(List<Predicate> predicates) {
        Map<String, Predicate> map = new HashMap<>();
        for (Predicate p : predicates) {
            Predicate existing = map.putIfAbsent(p.id(), p);
            if (existing != null) {
                throw new IllegalStateException("Duplicate predicate id: " + p.id()
                        + " (" + existing.getClass().getName() + " vs " + p.getClass().getName() + ")");
            }
        }
        this.byId = Map.copyOf(map);
    }
```
Plain English: At startup the app collects every fraud test that exists and files each under its name in a lookup table. If two tests accidentally claim the same name (both call themselves `amountAbove`), it refuses to start and tells you exactly which two clashed — instead of silently picking one.

**File: src/main/java/com/capitec/fraud/engine/RuleEngine.java (lines 72–80)** — the evaluation loop (the beating heart):
```java
            for (Rule rule : snapshot.rules()) {
                if (evaluateCondition(rule.condition(), ctx)) {
                    Action action = rule.action();
                    matched.add(new RuleResult(rule.id(), rule.priority(), action.reason()));
                    maxScore = Math.max(maxScore, action.score());
                    status = stricter(status, action.flag());
                    if (rule.shortCircuit()) break;
                }
            }
```
Plain English: The engine goes through every rule one at a time, in order. If a rule's condition is true, it records the match, keeps the highest risk score so far, and upgrades to the strictest verdict so far. If the matched rule is marked `shortCircuit`, it stops immediately — the answer is already final.

**File: src/main/java/com/capitec/fraud/engine/RuleEngine.java (lines 100–116)** — combining tests with all / any (AND / OR):
```java
    private boolean evaluateCondition(Condition cond, PredicateContext ctx) {
        return switch (cond) {
            case Condition.Atomic atomic -> registry.require(atomic.predicateId()).test(ctx, atomic.args());
            case Condition.All all -> {
                for (Condition c : all.children()) {
                    if (!evaluateCondition(c, ctx)) yield false;
                }
                yield true;
            }
            case Condition.Any any -> {
                for (Condition c : any.children()) {
                    if (evaluateCondition(c, ctx)) yield true;
                }
                yield false;
            }
        };
    }
```
Plain English: A condition can be a single test, or a group joined by `all` (every test must pass — like AND) or `any` (at least one must pass — like OR). `all` stops at the first failure; `any` stops at the first success. Groups can nest, so analysts build rich logic in plain YAML.

**File: src/main/java/com/capitec/fraud/api/AdminController.java (lines 56–74)** — the hot-reload button:
```java
    public ResponseEntity<?> reload() {
        try {
            RuleSet rs = loader.load(resourceLoader.getResource(rulesPath));
            int oldVersion = engine.active() == null ? -1 : engine.active().version();
            engine.install(rs);
            log.info("Reload OK: {} → {} (rules: {})", oldVersion, rs.version(), rs.rules().size());
            return ResponseEntity.ok(Map.of(
                    "previousVersion", oldVersion,
                    "currentVersion", rs.version(),
                    "ruleCount", rs.rules().size()
            ));
        } catch (RuleValidationException e) {
            reloadFailedCounter.increment();
            log.error("Reload REJECTED: {}", e.getMessage());
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
            pd.setProperty("errors", e.errors());
            pd.setProperty("activeVersion", engine.active() == null ? null : engine.active().version());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
        }
    }
```
Plain English: An operator hits this web button; the app re-reads the YAML and, if valid, swaps it in as the live rulebook while running — reporting the old and new version numbers. If the new file is broken, it rejects it with a 422 error and a list of problems, and the old working rulebook stays in charge. Fraud detection is never accidentally switched off by a typo.

### Interactive Elements
- [x] **Code↔English translations (at least 3 — this is the heart)** — the YAML rule, the AmountAbove predicate, and the evaluation loop are the must-haves. Optionally the all/any condition.
- [x] **Data flow / message flow animation** — the evaluation walk: actors `Transaction` → `Rule Engine` walking a stack of `Rules` (BLACKLISTED_MERCHANT pri 1000 → VELOCITY_BURST pri 900 → HIGH_AMOUNT_NEW_ACCOUNT pri 800 …) → for each rule it asks the `Predicate Registry` for the named test → keeps strictest verdict → a shortCircuit match stops the walk. Show short-circuit visually (a BLOCK at priority 900 halts the rest).
- [x] **Pattern/feature cards** — "Rules as data, not code", "Predicate registry (a vocabulary of tests)", "Atomic hot-swap (apply live, no restart)", "Validate before install (a broken file is rejected, the old one keeps running)".
- [x] **Callout (accent, "Key Insight")** — rules-as-data: "When you separate the *rules* from the *machinery*, the people who understand fraud can change the rules, and the people who build software can improve the machinery — independently. Ask your AI assistant for this whenever thresholds might change."
- [x] **Callout (info)** — short-circuit + priority: most-serious checks run first; an obvious BLOCK stops the rest. The final verdict is the *strictest* matched, and the score is the *highest* matched (not a sum).
- [x] **Quiz** — 4 questions, scenario/decision style:
  1. *Scenario:* Fraud team wants the high-amount threshold lowered from 10,000 to 8,000 today. What is the safe path — and who is needed? (Answer: edit the YAML value, hot-reload; no developer/redeploy.)
  2. *Debugging:* Someone reloads a rulebook with a typo. What happens to live fraud detection? (Answer: the reload is rejected with a 422, the previous good rulebook stays active — detection is never silently disabled.)
  3. *Tracing:* Two rules both match — one says REVIEW (score 0.7), one says BLOCK (score 0.95). What is the final verdict and score? (Answer: BLOCK, 0.95 — strictest verdict, highest score.)
  4. *Decision:* You are steering an AI to build a feature with thresholds that marketing will tweak weekly. Code constant or config data? Why? (Answer: config data — so non-engineers change it without a redeploy; exactly the rules-as-data lesson.)

### Glossary Tooltips
YAML, rule / rule set, predicate, condition, threshold, priority, short-circuit, hot reload, validation / schema, AND / OR (all / any), interface, registry / lookup table, redeploy, version (of a config), 422 Unprocessable Entity, atomic / atomic swap.

### Reference Files to Read
- `references/interactive-elements.md` → "Code ↔ English Translation Blocks", "Message Flow / Data Flow Animation", "Pattern/Feature Cards", "Callout Boxes", "Multiple-Choice Quizzes", "Scenario Quiz", "Glossary Tooltips"
- `references/content-philosophy.md` → all
- `references/gotchas.md` → all (data-steps single-quote warning; never modify code snippets)

### Connections
- **Previous module:** "Meet the Cast" — introduced the Decision Committee (engine). This module opens it up.
- **Next module:** "Never Lose a Decision" — once the engine has decided, how do we make sure the decision is never made twice and never lost? (idempotency, fast counting, the outbox). Tease: the VELOCITY_BURST rule needs to *count* fast — Module 4 shows how.
- **Tone/style notes:** Most code-dense module — but keep each screen to one idea and one translation block. The rules-as-data callout is the single biggest takeaway of the course; make it memorable. Vermillion accent.
