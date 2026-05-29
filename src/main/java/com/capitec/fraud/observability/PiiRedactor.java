package com.capitec.fraud.observability;

/**
 * Account-id redaction for log lines and JSON payload hashes. POPIA + bank
 * audit rules: account identifiers must not appear in plain text in logs.
 * Convention here: keep first 4 + last 4 chars, mask the middle.
 *
 * <pre>
 *   redact("ACC-12345678")  → "ACC-****5678"
 *   redact("ABC")           → "***" (too short to keep edges)
 *   redact(null)            → "[null]"
 * </pre>
 */
public final class PiiRedactor {

    private PiiRedactor() {}

    public static String redactAccountId(String raw) {
        if (raw == null) return "[null]";
        int len = raw.length();
        if (len < 9) return "*".repeat(Math.max(3, len));
        return raw.substring(0, 4) + "****" + raw.substring(len - 4);
    }
}
