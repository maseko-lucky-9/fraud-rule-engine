# Module 4: Never Lose a Decision

> Course: "How a Fraud Engine Thinks". Accent: vermillion. Audience: non-technical.

### Teaching Arc
- **Metaphor:** A meticulous **bank back office that never loses a slip**. Three sub-ideas, three small grounded images:
  - **Idempotency → a coat-check counter.** You hand over a coat and get a numbered ticket. Show the same ticket twice and you get the *same* coat back — never a second coat, never someone else's coat (the ticket is tied to you).
  - **Velocity counting → a nightclub hand-stamp that fades after the window.** The counter scrubs off stamps older than 60 seconds and counts the rest in one quick glance — deciding if a card has "come and gone" too many times.
  - **The transactional outbox → a certified-mail outbound shelf.** Instead of risking the message not being heard, the clerk spikes a paper slip on a shelf *in the same motion* as recording the decision; a runner later carries every slip to the mailroom and files the hopeless ones in a "dead letter" drawer. (No restaurant/kitchen.)
- **Opening hook:** "The engine has decided. Easy part over. Now the hard part nobody sees: making sure that decision is never made twice, never silently lost, and that a card-tester firing six payments in one second is actually *counted* in time."
- **Key insight:** Three classic reliability problems, each solved deliberately: (1) **idempotency** so a retried transaction is not processed twice; (2) **fast, correct counting** under concurrency using Redis + a tiny atomic Lua script; (3) the **transactional outbox** to escape the *dual-write problem* — you cannot reliably write to a database AND publish to a message bus in one step, so you write the decision and a "to-send" note in ONE database transaction, then a background poller delivers it, with a dead-letter table for the hopeless.
- **"Why should I care?":** This is exactly what stops a bank from charging you twice, losing your fraud alert, or letting a card-tester drain an account in seconds. The dual-write problem is one of the most common traps in systems that "save something AND send a message" — knowing the outbox pattern lets you recognize the trap and ask an AI for the right fix instead of the buggy two-step.

### Code Snippets (verbatim; HTML-escape in HTML)

**File: src/main/java/com/capitec/fraud/idempotency/IdempotencyService.java (lines 79–97)** — the key is tied to the user; the body is fingerprinted:
```java
    public static String hashBody(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static byte[] toBytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String redisKey(String subject, String key) {
        return "idem:" + (subject == null ? "anon" : subject) + ":" + key;
    }
```
Plain English: Two safety tricks. `hashBody` turns the request into a short unique fingerprint (SHA-256) so the engine can tell if a reused ticket actually carries the same content. `redisKey` glues the user's identity onto the ticket so one customer can never reuse another customer's Idempotency-Key to read or hijack their result.

**File: src/main/java/com/capitec/fraud/engine/InMemoryStateStore.java (lines 37–55)** — the simple single-machine counter:
```java
    @Override
    public int recordAndCountWithin(String accountId, String txId, Instant at, Duration window) {
        long now = at.toEpochMilli();
        long floor = now - window.toMillis();
        Deque<Long> deque = velocityByAccount.computeIfAbsent(accountId, k -> new ArrayDeque<>());
        synchronized (deque) {
            // Evict entries older than or equal to floor.
            Iterator<Long> it = deque.iterator();
            while (it.hasNext()) {
                if (it.next() <= floor) {
                    it.remove();
                } else {
                    break; // deque is ordered by arrival ≈ timestamp asc
                }
            }
            deque.addLast(now);
            return deque.size();
        }
```
Plain English: The single-machine way to count recent transactions for an account. Keep a time-ordered list of timestamps, drop anything older than the window (e.g. older than 60s), add the current one, return how many remain. The `synchronized` lock stops two requests for the same account from tripping over each other.

**File: src/main/resources/redis/velocity.lua (lines 20–35)** — the whole atomic counter, run inside Redis:
```lua
local key      = KEYS[1]
local now_ms   = tonumber(ARGV[1])
local floor_ms = tonumber(ARGV[2])
local member   = ARGV[3]
local ttl_sec  = tonumber(ARGV[4])

-- Evict entries strictly older than the window floor.
redis.call('ZREMRANGEBYSCORE', key, '-inf', floor_ms - 1)

-- Add the new entry. Score = now_ms, member = txId.
redis.call('ZADD', key, now_ms, member)

-- Refresh the per-account TTL so the key dies if the account goes silent.
redis.call('EXPIRE', key, ttl_sec)

return tonumber(redis.call('ZCARD', key))
```
Plain English: This script runs *inside* Redis and does four things without interruption: remove events older than the window, add the new event (stamped with its time), refresh the auto-delete timer, and return the new count. Because Redis runs the whole script start-to-finish before any other command touches that account, two transactions in the same millisecond can never miscount each other.

**File: src/main/java/com/capitec/fraud/publish/OutboxPoller.java (lines 103–129)** — the drain loop:
```java
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    @Transactional
    public void drain() {
        List<OutboxEntity> batch = outboxRepo.findPendingForUpdate(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("outbox: draining {} pending rows (lock held until commit)", batch.size());

        // Dispatch every send in parallel so a single slow row can't stall the batch.
        Map<OutboxEntity, CompletableFuture<?>> inflight = new HashMap<>(batch.size());
        Map<OutboxEntity, String> lastErrors = new HashMap<>();
        for (OutboxEntity row : batch) {
            CompletableFuture<?> future = kafkaTemplate
                    .send(KafkaTopics.DECISIONS_OUT, row.getAggregateId().toString(), row.getPayload())
                    .toCompletableFuture();
            inflight.put(row, future);
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(inflight.values().toArray(new CompletableFuture[0]));
        try {
            all.get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("outbox: batch publish wait failed — settling per-row. cause={}", ex.toString());
        }
```
Plain English: Every half-second this background job wakes up, grabs a batch of unsent "to-publish" notes from the database, and fires them all at the message bus at once (in parallel, so one slow message can't hold up the rest). It waits up to a time limit; if the broker is slow or down, the wait gives up cleanly and it decides what to do with each note individually.

**File: src/main/java/com/capitec/fraud/publish/OutboxPoller.java (lines 135–154)** — settle each note: done, retry, or dead-letter:
```java
        for (Map.Entry<OutboxEntity, CompletableFuture<?>> e : inflight.entrySet()) {
            OutboxEntity row = e.getKey();
            CompletableFuture<?> f = e.getValue();
            if (f.isDone() && !f.isCompletedExceptionally()) {
                row.markProcessed();
                succeeded.add(row.getId());
            } else {
                row.incrementRetry();
                String why = describeFailure(f);
                lastErrors.put(row, why);
                f.cancel(true);
                if (row.getRetryCount() >= maxRetries) {
                    routeToDlt(row, why);
                    routedToDlt++;
                } else {
                    log.debug("outbox: row {} stays pending (attempt {})",
                            row.getId(), row.getRetryCount());
                }
            }
        }
```
Plain English: After the send attempt, it inspects each note one by one. Delivered → stamp it "processed" so it is never sent again. Failed → add one to its retry count and leave it for next time — unless it has failed too many times (default 10), in which case move it to a dead-letter table so it stops clogging the live queue forever.

### Interactive Elements
- [x] **Code↔English translations (at least 3)** — the idempotency key/fingerprint, the velocity.lua script, and the outbox drain loop are the must-haves.
- [x] **Data flow animation (HERO — the outbox)** — actors: `Ingest` → writes `Decision` + `Outbox Note` into the `Database` (one transaction, highlight "both or neither") → `Outbox Poller` (every 500ms) claims unsent notes → publishes to `Kafka` → marks notes done OR, after too many failures, moves them to the `Dead-Letter Drawer`. Emphasize the single-transaction step and the "crash here is safe" idea.
- [x] **"Spot the Bug" challenge** — use the real `Set` vs `Deque` velocity gotcha. Show a velocity counter that stores timestamps in a `Set<Long>` and ask the learner to find the flaw: two transactions in the same millisecond collapse into one, so a burst slips past VELOCITY_BURST. (The real code deliberately uses a `Deque` to avoid exactly this. Reveal explains it.)
- [x] **Pattern/feature cards** — "Idempotency (the do-not-repeat ticket)", "Transactional outbox (solves the dual-write problem)", "Atomic counting via a Redis script", "Dead-letter drawer (for hopeless messages)".
- [x] **Callout (accent, "Key Insight")** — the dual-write problem: "Saving to a database AND sending a message are two separate actions — one can succeed while the other fails, losing or duplicating events. The fix: make the *intent to send* part of the same save, and deliver it afterward."
- [x] **Quiz** — 4 questions, debugging/decision style:
  1. *Debugging:* A customer was charged twice after their app retried on a flaky connection. Which safeguard was missing or bypassed? (Answer: idempotency — the do-not-repeat ticket.)
  2. *Scenario:* The message broker is down for 30 seconds during a decision. Is the "decision announced" message lost? Why not? (Answer: no — the outbox note is saved in the same DB transaction; the poller delivers it once the broker is back. At-least-once delivery.)
  3. *Tracing:* A card-tester fires 6 tiny payments in one second on one account. Why does VELOCITY_BURST catch it, and why does the counter need to be atomic? (Answer: the window counter sees all 6; atomic so concurrent writes do not miscount.)
  4. *Decision:* You are steering an AI to build a feature that "saves an order and emails the customer." It writes a save then a send. What trap is this, and what do you ask for instead? (Answer: the dual-write trap — ask for an outbox so the email intent is saved with the order and sent reliably afterward.)

### Glossary Tooltips
idempotency, Redis, in-memory vs shared cache, Lua / script, atomic, race condition / concurrency, window (time window), Kafka / message broker / topic, message bus, transactional outbox, dual-write problem, dead-letter (DLT), retry, at-least-once delivery, SHA-256 / hash / fingerprint, poller / scheduled job, circuit breaker, fail-open.

### Reference Files to Read
- `references/interactive-elements.md` → "Code ↔ English Translation Blocks", "Message Flow / Data Flow Animation", "Spot the Bug Challenge", "Pattern/Feature Cards", "Callout Boxes", "Multiple-Choice Quizzes", "Glossary Tooltips"
- `references/content-philosophy.md` → all
- `references/gotchas.md` → all (data-steps single-quote warning)

### Connections
- **Previous module:** "The Rulebook" — the VELOCITY_BURST rule needs fast counting; this module shows how that counting actually works, plus how the resulting decision is stored and announced without loss.
- **Next module:** "The AI Consultant" — now that decisions are made and stored reliably, an optional AI can comment on them — but it is never allowed to decide.
- **Tone/style notes:** Three sub-concepts; give each its own screen and its own small image (coat-check / hand-stamp / certified-mail). The outbox flow animation is the centerpiece. Vermillion accent.
