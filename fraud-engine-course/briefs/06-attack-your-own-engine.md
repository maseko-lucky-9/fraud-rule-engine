# Module 6: Attack Your Own Engine

> Course: "How a Fraud Engine Thinks". Accent: vermillion. Audience: non-technical. THIS IS THE FINALE — it closes the loop back to Module 3.

### Teaching Arc
- **Metaphor:** A **vaccine / immune-system lab**. You deliberately inject weakened versions of known attacks (the evasion scenarios) into the body (the engine), watch which ones the immune system fails to neutralize, and from each failure design a new antibody (a draft YAML rule) that gets added to the body's defenses. Controlled exposure is what makes the system *stronger*. (No restaurant/kitchen.)
- **Opening hook:** "You built a fraud engine. Here is the uncomfortable question every real team must ask: *what does it miss?* This module is a second program — written in Python — whose entire job is to attack your engine with real fraud tricks and write up every hole it finds."
- **Key insight:** A Python harness spawns a crowd of fake accounts — mostly **honest** customers (the clean baseline), plus **scripted adversaries** running known fraud tricks, plus **LLM-invented** adversaries — all hitting the *live* engine over its real network interface. Then an **auditor** scores what was caught vs missed and writes a report that even **drafts new YAML rules** to plug the gaps — validated against the predicates the engine actually understands. The gaps it finds become the next rules that get **hot-reloaded** back into the engine (Module 3). The system gets smarter *by being attacked*.
- **"Why should I care?":** This builds the most valuable instinct a builder can have: assume your thing has holes, and go find them on purpose. You will see how "a miss" can be a *passing* test (it documents a known gap), how to keep numbers trustworthy (compute them deterministically, let the AI write only the prose), and how a feedback loop turns weaknesses into improvements — exactly the mindset for escaping AI bug-loops and shipping reliable software.

### Code Snippets (verbatim Python; HTML-escape `<` `>` `&` in HTML)

**File: simulator/simulator/scenarios/evasion.py (lines 17–35)** — card-testing: 200 tiny charges that dodge the speed rule:
```python
def build_card_testing(*, seed: int) -> FraudScenario:
    """Distributed micro-tx across 20 accounts — evades per-account VELOCITY_BURST."""
    sid = "evasion_card_testing"
    base = base_time(hour_sast=14)
    steps: list[ScenarioStep] = []
    for acc_n in range(20):
        for tx_n in range(10):  # 10 per account × 20 accounts = 200 micro-tx
            steps.append(
                ScenarioStep(
                    tx=benign_tx(
                        scenario_id=sid, step=len(steps), seed=seed,
                        account_id=f"ACC-EV-CT-{acc_n:02d}",
                        amount=Decimal("0.99"),
                        timestamp=base + minutes(tx_n),
                        device_id=f"dev-CT-{acc_n:02d}",
                        merchant_id="MERCH-OK-WEB-001",
                    ),
                )
            )
```
Plain English: A criminal with a batch of stolen card numbers wants to know which still work, so they run a flood of tiny $0.99 charges. The engine's speed alarm only counts how fast ONE account spends, so this attack spreads 200 charges across 20 different accounts — each account looks calm. This code builds exactly that crowd to prove the alarm never goes off.

**File: simulator/simulator/scenarios/evasion.py (lines 49–76)** — structuring: $9,999 just under the $10k cutoff:
```python
def build_structuring(*, seed: int) -> FraudScenario:
    """Single account: $9,999 every 90 min — slips under HIGH_AMOUNT cutoff."""
    sid = "evasion_structuring"
    base = base_time(hour_sast=10)
    steps = tuple(
        ScenarioStep(
            tx=benign_tx(
                scenario_id=sid, step=i, seed=seed,
                account_id="ACC-EV-STR-1",
                amount=Decimal("9999.00"),
                timestamp=base + hours(i * 90 // 60) + minutes(i * 90 % 60),
                account_age_days=15,  # younger account intensifies the miss
                merchant_id="MERCH-OK-001",
            ),
        )
        for i in range(8)
    )
    return FraudScenario(
        id=sid, archetype="structuring", targets_rule="HIGH_AMOUNT_NEW_ACCOUNT",
        expected_outcome="miss",
        description=(
            "$9,999 transactions every 90 minutes on a 15-day-old account. "
            "HIGH_AMOUNT_NEW_ACCOUNT triggers at >$10,000 — each transaction "
            "is sub-threshold. AML structuring/smurfing pattern."
        ),
```
Plain English: The big-amount rule only fires above $10,000, so the attacker sends $9,999 over and over — always one dollar under the line. Each payment looks innocent; together they move a fortune. This is the classic money-laundering trick called "structuring," and the scenario is explicitly tagged `expected_outcome="miss"` because today's engine has no way to add up many payments over time.

**File: simulator/simulator/agents/personas/honest.py (lines 90–105)** — the honest customer, engineered to stay under every threshold:
```python
    def _build_tx(self) -> Transaction:
        return Transaction(
            txId=uuid4(),
            accountId=self.account.account_id,
            amount=self._next_amount(),
            currency="ZAR",
            mcc=self._rng.choice(_BENIGN_MCCS),
            channel=self.account.primary_channel if isinstance(self.account.primary_channel, Channel)
            else Channel(self.account.primary_channel),
            country=self.account.home_country,
            ipCountry=self.account.home_country,  # honest persona never crosses borders
            deviceId=self.account.device_id,
            merchantId=self._rng.choice(_BENIGN_MERCHANTS),
            accountAgeDays=self.account.account_age_days,
            timestamp=self._next_timestamp(),
        )
```
Plain English: One payment for a well-behaved customer — small random amount, home country for both location fields (never cross-border), usual device, trusted shop. Everything is tuned to fall under every alarm. If the engine ever flags one of these, you know something is broken — they are the clean baseline the attacks are measured against.

**File: simulator/simulator/analysis/metrics.py (lines 116–130)** — the scoreboard: catches and misses per rule:
```python
    for _, row in df.iterrows():
        classification = _scenario_classification(row.get("scenario_id") or "")
        if classification is None:
            continue
        rule, kind = classification
        matched = rule in row["matched_rules_list"]
        bucket = rows.setdefault(rule, {"tp": 0, "fp": 0, "fn": 0, "tn": 0})
        if kind == "positive" and matched:
            bucket["tp"] += 1
        elif kind == "positive" and not matched:
            bucket["fn"] += 1
        elif kind in {"negative", "boundary"} and not matched:
            bucket["tn"] += 1
        elif kind in {"negative", "boundary"} and matched:
            bucket["fp"] += 1
```
Plain English: The scoreboard. For every test the code knows what the engine *should* have done. Should-catch and did → "true positive." Should-catch but didn't → "false negative" (a dangerous miss). Clean one wrongly flagged → "false positive." These four tallies per rule become precision and recall — the engine's report card.

**File: simulator/simulator/analysis/auditor.py (lines 235–249)** — the auditor turns a miss into a proposed new rule:
```python
def _default_findings_from_evasion(metrics: RunMetrics) -> list[FindingDraft]:
    drafts: list[FindingDraft] = []
    for outcome in metrics.evasion_outcomes:
        sid = outcome["scenario_id"]
        if outcome.get("actual") != "miss":
            continue
        if sid not in _DEFAULT_STUBS:
            continue
        severity, title, stub = _DEFAULT_STUBS[sid]
        drafts.append(FindingDraft(
            severity=severity, scenario_id=sid, title=title,
            proposed_yaml_stub=stub,
        ))
    drafts.sort(key=lambda d: (d.severity, d.scenario_id))
    return drafts
```
Plain English: The loop closing. The auditor looks at every attack, and whenever the engine actually MISSED one, it pulls a matching draft rule off the shelf and adds it to the report as a ranked finding (P0 most urgent down to P2). A hole found by attacking turns directly into a proposed new YAML rule — the same kind the engine hot-reloads in Module 3.

**File: simulator/simulator/analysis/yaml_validator.py (lines 99–107)** — only propose rules the engine can actually run:
```python
        for key, value in node.items():
            if key in self._combinators:
                self._scan_condition(value, unknown)
            elif key in self._predicates:
                # Known predicate — its value is opaque args, don't walk.
                continue
            else:
                unknown.add(key)
```
Plain English: Before a draft rule is presented as "drop-in ready," the validator walks every condition in it. A building block the engine recognizes (an amount or country check) is fine; something invented ("add up 24 hours of spending," which the engine can't do yet) is flagged as unknown. Rules using unknown blocks are honestly downgraded to "suggested mitigation (requires new predicate)" so the report never ships a rule that would crash the engine.

### Interactive Elements
- [x] **Code↔English translations (at least 3)** — card-testing or structuring scenario, the honest baseline, the metrics scoreboard, and the auditor-proposes-a-rule snippet. Pick at least 3.
- [x] **Data flow animation (HERO — the full feedback loop, the course finale)** — actors: `Account Pool` (100 fake accounts) → `Personas` (honest + scripted + LLM adversaries) → `Live Engine` (real REST) → `Results DB` → `Metrics (pandas)` → `Auditor` → `Draft YAML Rule` → arrow looping BACK to `Engine hot-reload` (Module 3). The loop-back arrow is the emotional payoff — make it visually close the circle.
- [x] **Pattern/feature cards** — "Closing the loop (gap → draft rule → hot reload)", "Numbers from pandas, prose from the AI", "Validate-then-downgrade (never ship a broken rule)", "Seed-driven determinism (same seed → same crowd)".
- [x] **Callout (accent, "Key Insight")** — "A 'miss' here is a *passing* test. It documents a real, known gap honestly — so the team can decide to close it, not pretend it isn't there. Naming your weaknesses is how you fix them."
- [x] **Callout (info)** — the numbers/prose split: every authoritative number is computed deterministically; the AI only writes narrative and never sees the raw rows, so it cannot invent or contradict statistics. (Ties back to Module 5's "AI advises, never decides.")
- [x] **Quiz** — 4 questions, scenario/decision style:
  1. *Scenario:* The card-testing attack runs 200 charges but VELOCITY_BURST never fires. Why does it slip through? (Answer: VELOCITY_BURST counts speed *per account*; spreading across 20 accounts keeps each one calm.)
  2. *Conceptual:* A scenario is tagged `expected_outcome="miss"` and the test passes. Is the simulator broken? (Answer: no — a "miss" is a deliberately documented known gap; a passing miss means reality matches the known weakness.)
  3. *Debugging (off-by-one):* HIGH_AMOUNT_NEW_ACCOUNT uses `accountAgeBelow: 30`. A $50,000 charge on a 31-day-old account sails through. Why? (Answer: 31 is not below 30 — the synthetic-identity evasion exploits this exact boundary.)
  4. *Decision:* Your auditor proposes a rule that needs a "sum 24 hours of spend" check the engine doesn't have. Should the report present it as drop-in ready? (Answer: no — downgrade it honestly to "requires a new predicate"; shipping it would crash the engine.)
- [x] **Closing screen** — a short "you now understand how a fraud engine thinks" wrap-up that recaps the six modules as one sentence each, and restates the two biggest lessons: rules-as-data (Module 3) and AI-advises-never-decides (Module 5).

### Glossary Tooltips
red team / penetration test, simulator / harness, persona / agent, baseline, evasion, card-testing, structuring / smurfing (AML), account takeover (ATO), false positive / false negative, precision / recall, pandas / DataFrame, seed / deterministic, off-by-one, feedback loop, predicate allowlist, SQLite, async / concurrency.

### Reference Files to Read
- `references/interactive-elements.md` → "Code ↔ English Translation Blocks", "Message Flow / Data Flow Animation", "Pattern/Feature Cards", "Callout Boxes", "Multiple-Choice Quizzes", "Scenario Quiz", "Glossary Tooltips"
- `references/content-philosophy.md` → all
- `references/gotchas.md` → all (data-steps single-quote warning; this module's flow loops back, so the final step should reference Module 3)

### Connections
- **Previous module:** "The AI Consultant" — taught AI-as-advisor; here the simulator uses a local AI again *non-authoritatively* (to invent attacks and write prose), reinforcing the same boundary.
- **Next module:** none — this is the finale. End by closing the loop visually (gaps → new rules → hot reload back into Module 3) and recapping the whole course.
- **Tone/style notes:** Triumphant, "you made it" energy. The feedback-loop flow animation is the grand finale; the loop-back arrow to Module 3 is the single most important visual. Vermillion accent.
