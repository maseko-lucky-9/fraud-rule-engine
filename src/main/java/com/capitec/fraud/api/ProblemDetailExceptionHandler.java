package com.capitec.fraud.api;

import com.capitec.fraud.engine.RuleEvaluationTimeoutException;
import com.capitec.fraud.engine.RuleValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RFC 7807 Problem Details for every error response. Adds a
 * {@code correlationId} field from MDC so a client can quote it back when
 * reporting a problem. Validation errors include the offending field paths.
 */
@RestControllerAdvice
public class ProblemDetailExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);
    private static final URI TYPE_BAD_REQUEST = URI.create("https://fraud-engine.example/problems/bad-request");
    private static final URI TYPE_VALIDATION = URI.create("https://fraud-engine.example/problems/validation-failed");
    private static final URI TYPE_UNAUTHORIZED = URI.create("https://fraud-engine.example/problems/unauthorized");
    private static final URI TYPE_FORBIDDEN = URI.create("https://fraud-engine.example/problems/forbidden");
    private static final URI TYPE_RULE_VALIDATION = URI.create("https://fraud-engine.example/problems/rule-validation-failed");
    private static final URI TYPE_INTERNAL = URI.create("https://fraud-engine.example/problems/internal");
    private static final URI TYPE_RULE_EVAL_TIMEOUT = URI.create("https://fraud-engine.example/problems/rule-eval-timeout");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<java.util.Map<String, Object>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> java.util.Map.<String, Object>of(
                        "field", f.getField(),
                        "message", String.valueOf(f.getDefaultMessage())))
                .collect(Collectors.toList());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_VALIDATION);
        pd.setTitle("Validation failed");
        pd.setDetail("One or more fields failed validation.");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("errors", errors);
        addCorrelationId(pd);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleBadArg(IllegalArgumentException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(TYPE_BAD_REQUEST);
        pd.setTitle("Bad request");
        pd.setInstance(URI.create(req.getRequestURI()));
        addCorrelationId(pd);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(RuleValidationException.class)
    public ResponseEntity<ProblemDetail> handleRuleValidation(RuleValidationException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setType(TYPE_RULE_VALIDATION);
        pd.setTitle("Rule validation failed");
        pd.setDetail(ex.getMessage());
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("errors", ex.errors());
        addCorrelationId(pd);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
    }

    @ExceptionHandler({AuthenticationException.class, AuthenticationCredentialsNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleAuth(Exception ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required.");
        pd.setType(TYPE_UNAUTHORIZED);
        pd.setTitle("Unauthorized");
        pd.setInstance(URI.create(req.getRequestURI()));
        addCorrelationId(pd);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(AccessDeniedException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied.");
        pd.setType(TYPE_FORBIDDEN);
        pd.setTitle("Forbidden");
        pd.setInstance(URI.create(req.getRequestURI()));
        addCorrelationId(pd);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    @ExceptionHandler(RuleEvaluationTimeoutException.class)
    public ResponseEntity<ProblemDetail> handleEvalTimeout(RuleEvaluationTimeoutException ex,
                                                           HttpServletRequest req) {
        // 503 instead of silently APPROVing — a pathological predicate is a
        // correctness gap that should be visible to the caller. Retry-After=1
        // matches the engine cap order of magnitude.
        log.error("rule-eval timeout on {} cause={}", req.getRequestURI(), ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Rule evaluation exceeded the allowed time bound.");
        pd.setType(TYPE_RULE_EVAL_TIMEOUT);
        pd.setTitle("Rule evaluation timeout");
        pd.setInstance(URI.create(req.getRequestURI()));
        addCorrelationId(pd);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("unhandled exception on {} cause={}", req.getRequestURI(), ex.toString(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
        pd.setType(TYPE_INTERNAL);
        pd.setTitle("Internal Server Error");
        pd.setInstance(URI.create(req.getRequestURI()));
        addCorrelationId(pd);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }

    private static void addCorrelationId(ProblemDetail pd) {
        String cid = MDC.get("correlationId");
        if (cid != null) pd.setProperty("correlationId", cid);
    }
}
