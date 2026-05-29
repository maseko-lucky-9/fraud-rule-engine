package com.capitec.fraud.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose String/Object arguments may contain account-ids
 * that must be masked before reaching log appenders. The matching
 * {@link RedactPiiAspect} replaces every detected account-id in the
 * method's argument array with the {@link PiiRedactor}-redacted form for
 * the duration of the invocation.
 *
 * <p>Use sparingly: most of the engine already calls {@link PiiRedactor}
 * directly at log sites. The aspect exists for cases where a method
 * returns a value or throws an exception whose message contains a raw
 * account-id that would otherwise reach an error-handler logger.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RedactPii {
}
