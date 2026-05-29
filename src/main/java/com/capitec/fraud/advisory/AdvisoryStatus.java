package com.capitec.fraud.advisory;

/**
 * Outcome of an advisory call. The deterministic decision is unaffected by
 * any of these — they only annotate the decision for a human reviewer.
 */
public enum AdvisoryStatus {
    /** Ollama returned a parseable response. */
    OK,
    /** Ollama hit the 2s timeout. Advisory unavailable for this decision. */
    TIMED_OUT,
    /** Ollama down or circuit open. Advisory unavailable. */
    UNAVAILABLE,
    /** Ollama responded but the JSON failed our strict parser / schema. */
    MALFORMED
}
