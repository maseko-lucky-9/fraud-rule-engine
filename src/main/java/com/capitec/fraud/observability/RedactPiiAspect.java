package com.capitec.fraud.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AspectJ around-advice that masks Capitec account-id strings in the
 * arguments of any {@link RedactPii}-annotated method, AND in the
 * message of any RuntimeException the method throws.
 *
 * <p>Detection is by regex: anything matching {@link #ACCOUNT_ID_PATTERN}
 * is rewritten through {@link PiiRedactor#redactAccountId(String)} which
 * keeps the first 4 and last 4 chars so triage is still possible without
 * exposing the full id. Non-String args are passed through untouched.
 *
 * <p>This is a defense-in-depth measure — most log sites already redact
 * explicitly. The aspect catches the cases where a raw id leaks into an
 * exception message that an error handler later logs unredacted.
 */
@Aspect
@Component
public class RedactPiiAspect {

    private static final Logger log = LoggerFactory.getLogger(RedactPiiAspect.class);

    /**
     * Capitec-style account-id: ACC- prefix + at least 4 alphanumerics.
     * Stricter than necessary so a stray "ACC-1" in a test fixture
     * doesn't trip the aspect needlessly.
     */
    static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("ACC-[A-Za-z0-9]{4,}");

    @Around("@annotation(com.capitec.fraud.observability.RedactPii)")
    public Object redact(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        Object[] redacted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            redacted[i] = (args[i] instanceof String s) ? redactInString(s) : args[i];
        }
        try {
            return pjp.proceed(redacted);
        } catch (RuntimeException ex) {
            String msg = ex.getMessage();
            if (msg != null && ACCOUNT_ID_PATTERN.matcher(msg).find()) {
                String maskedMsg = redactInString(msg);
                log.debug("RedactPiiAspect rewrote account-id in exception message of {}",
                        pjp.getSignature().toShortString());
                throw new RuntimeException(maskedMsg, ex);
            }
            throw ex;
        }
    }

    static String redactInString(String s) {
        Matcher m = ACCOUNT_ID_PATTERN.matcher(s);
        if (!m.find()) return s;
        StringBuilder out = new StringBuilder();
        m.reset();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(PiiRedactor.redactAccountId(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }
}
