package com.capitec.fraud.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyLengthValidationTest {

    @Test
    void rejectsNullKey() {
        assertThatThrownBy(() -> new ApiKeyAuthFilter(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(">= " + ApiKeyAuthFilter.MIN_API_KEY_LENGTH);
    }

    @Test
    void rejectsShortKey() {
        // 16 chars — the previous floor.
        String tooShort = "12345678abcdefgh";
        assertThatThrownBy(() -> new ApiKeyAuthFilter(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(">= " + ApiKeyAuthFilter.MIN_API_KEY_LENGTH);
    }

    @Test
    void rejectsKeyOneCharBelowFloor() {
        String oneShort = "x".repeat(ApiKeyAuthFilter.MIN_API_KEY_LENGTH - 1);
        assertThatThrownBy(() -> new ApiKeyAuthFilter(oneShort))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsKeyAtFloor() {
        String exactly = "x".repeat(ApiKeyAuthFilter.MIN_API_KEY_LENGTH);
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(exactly);
        assertThat(filter).isNotNull();
    }

    @Test
    void acceptsLongerKey() {
        String longer = "x".repeat(ApiKeyAuthFilter.MIN_API_KEY_LENGTH * 2);
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(longer);
        assertThat(filter).isNotNull();
    }

    @Test
    void minIsAtLeast32() {
        // Guard against accidental regression to the old 16-char floor.
        assertThat(ApiKeyAuthFilter.MIN_API_KEY_LENGTH).isGreaterThanOrEqualTo(32);
    }
}
