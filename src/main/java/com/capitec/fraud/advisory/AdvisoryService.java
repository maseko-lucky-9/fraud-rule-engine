package com.capitec.fraud.advisory;

import com.capitec.fraud.domain.Decision;

/**
 * Generates advisory commentary for a decision. NEVER authoritative. The
 * implementation is plugged in by Spring at startup; if the {@code advisory}
 * profile is inactive a no-op stub is bound so call sites don't have to
 * guard.
 */
public interface AdvisoryService {

    AdvisoryResponse adviseOn(Decision decision);

    /** Indicates whether a real advisor (e.g. Ollama) is wired up. */
    default boolean enabled() { return false; }
}
