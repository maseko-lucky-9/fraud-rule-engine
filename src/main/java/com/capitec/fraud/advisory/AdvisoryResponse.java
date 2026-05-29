package com.capitec.fraud.advisory;

import java.util.List;

/**
 * Advisory commentary on a deterministic decision. NEVER the source of
 * truth — the {@code humanReviewRequired} flag is ALWAYS true when the
 * advisory wasn't generated cleanly (TIMED_OUT, UNAVAILABLE, MALFORMED)
 * and is true by default for any non-trivial decision, by policy.
 *
 * <p>Schema mirrors the prompt template's expected output JSON shape.
 */
public record AdvisoryResponse(
        AdvisoryStatus advisoryStatus,
        String summary,
        List<String> concerns,
        double confidence,
        boolean humanReviewRequired
) {

    public static AdvisoryResponse timedOut() {
        return new AdvisoryResponse(AdvisoryStatus.TIMED_OUT, null, List.of(), 0.0, true);
    }

    public static AdvisoryResponse unavailable() {
        return new AdvisoryResponse(AdvisoryStatus.UNAVAILABLE, null, List.of(), 0.0, true);
    }

    public static AdvisoryResponse malformed() {
        return new AdvisoryResponse(AdvisoryStatus.MALFORMED, null, List.of(), 0.0, true);
    }
}
