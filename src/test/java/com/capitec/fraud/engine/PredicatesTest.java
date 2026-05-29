package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.engine.predicates.AccountAgeBelowPredicate;
import com.capitec.fraud.engine.predicates.AmountAbovePredicate;
import com.capitec.fraud.engine.predicates.CurrencyMismatchPredicate;
import com.capitec.fraud.engine.predicates.DeviceFingerprintNewPredicate;
import com.capitec.fraud.engine.predicates.GeoMismatchPredicate;
import com.capitec.fraud.engine.predicates.MerchantBlacklistPredicate;
import com.capitec.fraud.engine.predicates.TimeOfDayPredicate;
import com.capitec.fraud.engine.predicates.VelocityPredicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PredicatesTest {

    private static final Clock UTC = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
    private static final StateStore STATE = new InMemoryStateStore();

    private static PredicateContext ctx(Transaction tx) {
        return new PredicateContext(tx, STATE, UTC);
    }

    @Nested
    class AmountAbove {
        private final AmountAbovePredicate p = new AmountAbovePredicate();

        @Test
        void above_threshold_same_currency() {
            assertThat(p.test(ctx(tx(BigDecimal.valueOf(12000), "ZAR")),
                    Map.of("value", 10000, "currency", "ZAR"))).isTrue();
        }

        @Test
        void below_threshold_same_currency() {
            assertThat(p.test(ctx(tx(BigDecimal.valueOf(5000), "ZAR")),
                    Map.of("value", 10000, "currency", "ZAR"))).isFalse();
        }

        @Test
        void different_currency_never_matches() {
            assertThat(p.test(ctx(tx(BigDecimal.valueOf(999999), "USD")),
                    Map.of("value", 10000, "currency", "ZAR"))).isFalse();
        }
    }

    @Nested
    class AccountAgeBelow {
        private final AccountAgeBelowPredicate p = new AccountAgeBelowPredicate();

        @Test
        void young() {
            assertThat(p.test(ctx(txAge(10)), Map.of("days", 30))).isTrue();
        }

        @Test
        void old() {
            assertThat(p.test(ctx(txAge(365)), Map.of("days", 30))).isFalse();
        }
    }

    @Nested
    class CurrencyMismatch {
        private final CurrencyMismatchPredicate p = new CurrencyMismatchPredicate();

        @Test
        void mismatch() {
            assertThat(p.test(ctx(tx(BigDecimal.TEN, "USD")), Map.of("expected", "ZAR"))).isTrue();
        }

        @Test
        void match() {
            assertThat(p.test(ctx(tx(BigDecimal.TEN, "ZAR")), Map.of("expected", "ZAR"))).isFalse();
        }
    }

    @Nested
    class GeoMismatch {
        private final GeoMismatchPredicate p = new GeoMismatchPredicate();

        @Test
        void mismatch() {
            assertThat(p.test(ctx(txCountries("ZA", "NG")), Map.of())).isTrue();
        }

        @Test
        void match() {
            assertThat(p.test(ctx(txCountries("ZA", "ZA")), Map.of())).isFalse();
        }
    }

    @Nested
    class MerchantBlacklist {
        private final MerchantBlacklistPredicate p = new MerchantBlacklistPredicate();

        @Test
        void on_blacklist() {
            assertThat(p.test(ctx(txMerchant("BAD")),
                    Map.of("in", List.of("BAD", "WORSE")))).isTrue();
        }

        @Test
        void not_on_blacklist() {
            assertThat(p.test(ctx(txMerchant("OK")),
                    Map.of("in", List.of("BAD", "WORSE")))).isFalse();
        }

        @Test
        void null_merchant_id_never_matches() {
            assertThat(p.test(ctx(txMerchant(null)),
                    Map.of("in", List.of("BAD")))).isFalse();
        }
    }

    @Nested
    class TimeOfDay {
        private final TimeOfDayPredicate p = new TimeOfDayPredicate();

        @Test
        void inside_window_non_wrap() {
            // 01:00 UTC = 03:00 SAST → inside 02:00–05:00 SAST
            assertThat(p.test(
                    ctx(txAt(Instant.parse("2026-05-19T01:00:00Z"))),
                    Map.of("start", "02:00", "end", "05:00", "zone", "Africa/Johannesburg"))).isTrue();
        }

        @Test
        void outside_window_non_wrap() {
            // 14:00 UTC = 16:00 SAST → outside 02:00–05:00 SAST
            assertThat(p.test(
                    ctx(txAt(Instant.parse("2026-05-19T14:00:00Z"))),
                    Map.of("start", "02:00", "end", "05:00", "zone", "Africa/Johannesburg"))).isFalse();
        }

        @Test
        void inside_wrap_around_window() {
            // 22:30 SAST = 20:30 UTC → inside 22:00–05:00
            assertThat(p.test(
                    ctx(txAt(Instant.parse("2026-05-19T20:30:00Z"))),
                    Map.of("start", "22:00", "end", "05:00", "zone", "Africa/Johannesburg"))).isTrue();
        }
    }

    @Nested
    class Velocity {
        private final VelocityPredicate p = new VelocityPredicate();

        @Test
        void under_threshold_does_not_match() {
            StateStore fresh = new InMemoryStateStore();
            PredicateContext c = new PredicateContext(txAge(100), fresh, UTC);
            assertThat(p.test(c, Map.of("count", 5, "windowSeconds", 60))).isFalse();
        }

        @Test
        void over_threshold_matches_on_sixth_call() {
            StateStore fresh = new InMemoryStateStore();
            VelocityPredicate vp = new VelocityPredicate();
            boolean any = false;
            for (int i = 0; i < 6; i++) {
                Transaction t = txAtAccount(Instant.parse("2026-05-19T12:00:" + String.format("%02d", i) + "Z"), "ACC-V");
                any |= vp.test(new PredicateContext(t, fresh, UTC), Map.of("count", 5, "windowSeconds", 60));
            }
            assertThat(any).isTrue();
        }
    }

    @Nested
    class DeviceFingerprintNew {
        private final DeviceFingerprintNewPredicate p = new DeviceFingerprintNewPredicate();

        @Test
        void first_use_matches() {
            StateStore fresh = new InMemoryStateStore();
            assertThat(p.test(new PredicateContext(txDevice("dev-X", "ACC-N"), fresh, UTC), Map.of())).isTrue();
        }

        @Test
        void second_use_does_not_match() {
            StateStore fresh = new InMemoryStateStore();
            p.test(new PredicateContext(txDevice("dev-X", "ACC-N"), fresh, UTC), Map.of());
            assertThat(p.test(new PredicateContext(txDevice("dev-X", "ACC-N"), fresh, UTC), Map.of())).isFalse();
        }

        @Test
        void null_device_does_not_match() {
            StateStore fresh = new InMemoryStateStore();
            assertThat(p.test(new PredicateContext(txDevice(null, "ACC-N"), fresh, UTC), Map.of())).isFalse();
        }
    }

    @Nested
    class ArgsValidation {
        @Test
        void missing_arg_throws_with_clear_message() {
            AmountAbovePredicate p = new AmountAbovePredicate();
            assertThatThrownBy(() -> p.test(ctx(tx(BigDecimal.TEN, "ZAR")), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("value");
        }
    }

    // --- builders ---
    private static Transaction tx(BigDecimal amount, String currency) {
        return new Transaction(UUID.randomUUID(), "ACC-1", amount, currency, "5411",
                Transaction.Channel.WEB, "ZA", "ZA", "dev", null, 100, UTC.instant());
    }

    private static Transaction txAge(int age) {
        return new Transaction(UUID.randomUUID(), "ACC-1", BigDecimal.TEN, "ZAR", "5411",
                Transaction.Channel.WEB, "ZA", "ZA", "dev", null, age, UTC.instant());
    }

    private static Transaction txCountries(String country, String ipCountry) {
        return new Transaction(UUID.randomUUID(), "ACC-1", BigDecimal.TEN, "ZAR", "5411",
                Transaction.Channel.WEB, country, ipCountry, "dev", null, 100, UTC.instant());
    }

    private static Transaction txMerchant(String merchantId) {
        return new Transaction(UUID.randomUUID(), "ACC-1", BigDecimal.TEN, "ZAR", "5411",
                Transaction.Channel.WEB, "ZA", "ZA", "dev", merchantId, 100, UTC.instant());
    }

    private static Transaction txAt(Instant at) {
        return new Transaction(UUID.randomUUID(), "ACC-1", BigDecimal.TEN, "ZAR", "5411",
                Transaction.Channel.WEB, "ZA", "ZA", "dev", null, 100, at);
    }

    private static Transaction txDevice(String deviceId, String accountId) {
        return new Transaction(UUID.randomUUID(), accountId, BigDecimal.TEN, "ZAR", "5411",
                Transaction.Channel.WEB, "ZA", "ZA", deviceId, null, 100, UTC.instant());
    }

    private static Transaction txAtAccount(Instant at, String accountId) {
        return new Transaction(UUID.randomUUID(), accountId, BigDecimal.TEN, "ZAR", "5411",
                Transaction.Channel.WEB, "ZA", "ZA", "dev", null, 100, at);
    }
}
