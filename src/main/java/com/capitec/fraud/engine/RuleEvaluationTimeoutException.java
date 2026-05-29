package com.capitec.fraud.engine;

/**
 * Thrown by {@link BoundedRuleEngineEvaluator} when
 * {@link RuleEngine#evaluate} exceeds {@code app.engine.eval-timeout-ms}.
 * Mapped to HTTP 503 by {@code ProblemDetailExceptionHandler} so a
 * pathological predicate surfaces as a retriable-by-caller error rather
 * than silently approving the transaction.
 */
public class RuleEvaluationTimeoutException extends RuntimeException {
    public RuleEvaluationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
