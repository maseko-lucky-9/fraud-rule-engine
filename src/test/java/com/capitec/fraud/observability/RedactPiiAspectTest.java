package com.capitec.fraud.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level proof of the redaction primitive used by
 * {@link RedactPiiAspect}. The aspect's AOP wiring (Spring proxy +
 * annotation discovery) is exercised by the boot integration suite;
 * this test pins the regex + masking behaviour independently so a
 * regression in either side surfaces at the unit layer.
 */
class RedactPiiAspectTest {

    @Test
    void singleAccountIdInStringIsMasked() {
        String redacted = RedactPiiAspect.redactInString("audit row for ACC-12345678 written");
        assertThat(redacted).isEqualTo("audit row for ACC-****5678 written");
    }

    @Test
    void multipleAccountIdsInSameStringAreAllMasked() {
        String redacted = RedactPiiAspect.redactInString(
                "rule misfire from ACC-12345678 -> ACC-87654321");
        assertThat(redacted)
                .contains("ACC-****5678")
                .contains("ACC-****4321")
                .doesNotContain("ACC-12345678")
                .doesNotContain("ACC-87654321");
    }

    @Test
    void stringWithoutAccountIdIsUntouched() {
        String input = "this string has no account id";
        assertThat(RedactPiiAspect.redactInString(input)).isEqualTo(input);
    }

    @Test
    void shortIdThatDoesNotMatchPatternIsNotMasked() {
        String input = "ACC-1 is too short to match the strict pattern";
        assertThat(RedactPiiAspect.redactInString(input)).isEqualTo(input);
    }

    @Test
    void accountIdAtStartAndEndOfStringHandled() {
        assertThat(RedactPiiAspect.redactInString("ACC-12345678 alone"))
                .startsWith("ACC-****5678");
        assertThat(RedactPiiAspect.redactInString("ending with ACC-12345678"))
                .endsWith("ACC-****5678");
    }
}
