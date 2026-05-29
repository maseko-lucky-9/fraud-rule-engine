package com.capitec.fraud.ingest;

/**
 * Topic names. Centralised so listeners and producers stay in lock-step.
 * The {@code v1} suffix is a deliberate contract version — schema breakage
 * forces a {@code v2} topic, not a backwards-incompatible {@code v1} change.
 */
public final class KafkaTopics {
    public static final String EVENTS_IN = "tx.events.v1";
    public static final String DECISIONS_OUT = "tx.decisions.v1";
    public static final String EVENTS_RETRY = "tx.events.retry";
    public static final String EVENTS_DLT = "tx.events.dlt";

    private KafkaTopics() {}
}
