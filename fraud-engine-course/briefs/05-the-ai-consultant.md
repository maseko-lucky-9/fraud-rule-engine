# Module 5: The AI Consultant — and Why It Is Never Allowed to Decide

> Course: "How a Fraud Engine Thinks". Accent: vermillion. Audience: non-technical "vibe coders". THIS MODULE CARRIES THE COURSE'S MOST IMPORTANT LESSON about using AI responsibly.

### Teaching Arc
- **Metaphor:** A car's **built-in GPS co-pilot**. The GPS suggests a route, but it can never touch the steering wheel or the brakes — *you* always drive. Even when the GPS says confidently "turn here," the dashboard still shows "driver must confirm." And if the GPS loses signal, the car keeps driving exactly as before. Map it: the deterministic rule engine is the driver; the Ollama AI advisor is the GPS — advice only, never control. (No restaurant/kitchen.)
- **Opening hook:** "Everyone wants to bolt AI onto everything. So here is the most important question this whole codebase answers: how do you add an AI assistant to a system that decides whether to block someone's money — *without ever letting the AI make that call?*"
- **Key insight:** The deterministic rule engine is the **only source of truth**. The AI (a local Ollama model) only writes a short note *after* the decision, to help a *human* reviewer. It is **opt-in** (one setting flips it on/off), **non-blocking** (a 2-second timeout + a circuit breaker mean a slow or broken AI never delays or breaks a real decision), and **never authoritative** — every reply, even a perfect one, is hard-coded to say "a human must still review this."
- **"Why should I care?":** This is *the* lesson for steering AI: keep the AI as a helpful commentator, never the judge, in anything high-stakes. You will learn the exact patterns — an off-switch that swaps in a harmless stand-in, a timeout so the AI can never hang your app, and a safety flag decided by *policy* not by the model. When you build with AI, you will know to ask: "where is the human in the loop, and what happens when the model is wrong or down?"

### Code Snippets (verbatim; HTML-escape in HTML)

**File: src/main/java/com/capitec/fraud/advisory/AdvisoryService.java (lines 11–17)** — the optional-advisor contract:
```java
public interface AdvisoryService {

    AdvisoryResponse adviseOn(Decision decision);

    /** Indicates whether a real advisor (e.g. Ollama) is wired up. */
    default boolean enabled() { return false; }
}
```
Plain English: A promise (an interface) that any AI advisor must keep: one method, `adviseOn`, that takes a finished decision and returns commentary. The default answer to "are you a real advisor?" is "no." Because everything talks to this promise, the rest of the app never has to know whether AI is actually on.

**File: src/main/java/com/capitec/fraud/advisory/NoopAdvisoryService.java (lines 16–25)** — the do-nothing stand-in (the default):
```java
@Component
@ConditionalOnProperty(prefix = "app.advisory", name = "enabled", havingValue = "false", matchIfMissing = true)
@ConditionalOnMissingBean(value = AdvisoryService.class, ignored = NoopAdvisoryService.class)
public class NoopAdvisoryService implements AdvisoryService {

    @Override
    public AdvisoryResponse adviseOn(Decision decision) {
        return AdvisoryResponse.unavailable();
    }
}
```
Plain English: When the AI setting is false (or simply not set at all), the app plugs in this empty stand-in. Asked for advice, it just replies "unavailable." This is how the whole system runs fine with no AI installed: the off-switch swaps in a harmless placeholder instead of leaving a hole.

**File: src/main/java/com/capitec/fraud/advisory/OllamaAdvisoryService.java (lines 48–50)** — the real advisor, loaded only when switched on:
```java
@Component
@ConditionalOnProperty(prefix = "app.advisory", name = "enabled", havingValue = "true")
public class OllamaAdvisoryService implements AdvisoryService {
```
Plain English: The real AI advisor, loaded only when the setting is exactly "true." The two advisors are mutually exclusive: one setting flips which one the app uses, with no other code changing. This is the strategy pattern.

**File: src/main/java/com/capitec/fraud/advisory/OllamaAdvisoryService.java (lines 104–116)** — the call, with a graceful fallback:
```java
        try {
            String response = http.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response);
        } catch (RestClientException e) {
            log.warn("advisory: ollama call failed cause={}", e.toString());
            timeoutCounter.increment();
            return AdvisoryResponse.timedOut();
        }
```
Plain English: The app sends the question to the local AI and waits. If anything goes wrong — slow, broken, unreachable — it does NOT crash or block: it quietly notes the failure, ticks a counter, and returns a "timed out" answer. The decision the customer already received is never affected.

**File: src/main/java/com/capitec/fraud/advisory/OllamaAdvisoryService.java (lines 143–144)** — even a perfect reply still demands a human:
```java
            okCounter.increment();
            return new AdvisoryResponse(AdvisoryStatus.OK, summary, concerns, confidence, true);
```
Plain English: When the AI replies cleanly, the code builds a success response. The final value is `true` — the `humanReviewRequired` flag, hard-coded to true even on a perfect answer. The AI is never allowed to be a green light; a person must still look.

**File: src/main/java/com/capitec/fraud/advisory/AdvisoryResponse.java (lines 21–31)** — every failure shape still says "a human must check":
```java
    public static AdvisoryResponse timedOut() {
        return new AdvisoryResponse(AdvisoryStatus.TIMED_OUT, null, List.of(), 0.0, true);
    }

    public static AdvisoryResponse unavailable() {
        return new AdvisoryResponse(AdvisoryStatus.UNAVAILABLE, null, List.of(), 0.0, true);
    }

    public static AdvisoryResponse malformed() {
        return new AdvisoryResponse(AdvisoryStatus.MALFORMED, null, List.of(), 0.0, true);
    }
```
Plain English: The three ready-made failure replies — timed out, unavailable, and malformed (the AI sent gibberish). Every one carries empty content, zero confidence, and `true` for "a human must review." No matter how the AI fails, the answer is always "don't trust me, get a person."

**File: src/main/resources/prompts/advisory-v1.md (lines 1–4)** — the boundary, stated to the AI itself:
```text
System: You are a fraud-analyst assistant. You DO NOT make decisions.
The deterministic rule engine has already
produced the verdict — you produce a short structured commentary to help
a human reviewer.
```
Plain English: The literal opening instructions handed to the AI: you are a helper, you do NOT make decisions, the verdict is already final, your only job is a short note for a human. The boundary is told to the AI directly — not just enforced in code.

### Interactive Elements
- [x] **Code↔English translations (at least 3)** — the interface, the Noop-vs-Ollama conditional pair, the timeout fallback, and the always-true `humanReviewRequired` flag. Pick at least 3.
- [x] **Side-by-side comparison** — "AI switched OFF vs ON": OFF → NoopAdvisoryService, replies UNAVAILABLE, app runs perfectly with no model installed. ON → OllamaAdvisoryService, calls a local model with a 2s fuse, returns commentary — but still humanReviewRequired = true. One env-var is the only difference.
- [x] **Group chat animation** — actors: Reviewer Tool, Advisory Desk (controller), AI Model (Ollama), and a "Policy Stamp." Script:
  - Reviewer Tool: "I already have decision #X. Any commentary?"
  - Advisory Desk: "Let me ask the AI — but first I strip out the customer name and amount."
  - AI Model: "Looks like a high-amount-on-young-account pattern; medium confidence."
  - Policy Stamp: "Noted. Stamping this: HUMAN REVIEW STILL REQUIRED."
  - Advisory Desk: "Here is the note. The verdict itself does not change."
  - (Optional second run) AI Model: "…(no response, model is down)…" → Advisory Desk: "Timed out. Returning UNAVAILABLE. The decision stands untouched."
- [x] **Pattern/feature cards** — "Strategy pattern (one switch swaps the advisor)", "Null Object (a safe do-nothing stand-in)", "Graceful degradation (timeout + circuit breaker)", "Safety flag set by policy, not by the model".
- [x] **Callout (accent, "The most important lesson")** — the decision boundary: "In a high-stakes system, the AI advises; it never decides. Make that boundary structural — an off-switch, a timeout, and a human-review flag the model can never set — not just a hopeful instruction in a prompt."
- [x] **Quiz** — 4 questions, decision/scenario style:
  1. *Decision:* A stakeholder says "the AI is 95% accurate — let it auto-approve the easy ones to save reviewer time." Based on this design, what is the principled answer? (Answer: no — the AI is advisory only; a human must review. 95% still means 1 in 20 wrong on money decisions, and the boundary is structural.)
  2. *Scenario:* The Ollama model freezes and takes 30 seconds to respond. What does the customer experience for their actual transaction? (Answer: nothing — the decision was already made by the engine; advisory is a separate, time-limited call.)
  3. *Debugging:* You deploy with the AI setting absent entirely. Does the app crash, or run? (Answer: it runs — the do-nothing stand-in is the default via matchIfMissing.)
  4. *Decision:* You are steering an AI to add "AI-assisted approvals" to a payments app. What three guardrails would you insist on first? (Answer pattern: opt-in switch, a timeout so it cannot hang the request, and a human-in-the-loop flag the model cannot override.)

### Glossary Tooltips
AI model / LLM, Ollama, local model, deterministic vs probabilistic, interface / contract, strategy pattern, null object, env var / feature flag / setting, timeout, circuit breaker, graceful degradation, hallucination, prompt / system prompt, human-in-the-loop, PII redaction, 503 Service Unavailable, source of truth.

### Reference Files to Read
- `references/interactive-elements.md` → "Code ↔ English Translation Blocks", "Group Chat Animation", "Pattern/Feature Cards", "Callout Boxes", "Multiple-Choice Quizzes", "Scenario Quiz", "Glossary Tooltips"
- `references/content-philosophy.md` → all
- `references/gotchas.md` → all (chat window needs unique id + the three chat control button classes)

### Connections
- **Previous module:** "Never Lose a Decision" — decisions are now made and stored reliably; this module adds optional commentary on top.
- **Next module:** "Attack Your Own Engine" — the red-team simulator that hammers the engine to find what it misses (and even uses a local AI to *invent* new attacks, again non-authoritatively).
- **Tone/style notes:** This module is the philosophical heart for the vibe-coder audience. The GPS metaphor should recur. Make the "advises, never decides" callout the emotional peak. Vermillion accent.
