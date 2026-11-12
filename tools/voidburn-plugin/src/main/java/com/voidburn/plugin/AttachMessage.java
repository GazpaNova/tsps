package com.voidburn.plugin;

/**
 * Builds the single JSON message the plugin sends once a WebSocket is open:
 * {@code {"type":"attach","username":"...","token":"..."}}.
 *
 * Hand-built rather than routed through Gson so the output matches exactly
 * what the gateway's VoidburnPluginMessages parser expects — key order
 * (irrelevant to that parser, but still deterministic for logs and tests),
 * no stray fields, strict JSON string escaping for every character below
 * U+0020 (the gateway rejects raw control characters in strings).
 *
 * This class is intentionally tiny and stateless so it can be exercised
 * directly in unit tests without starting a WebSocket or RuneLite.
 */
final class AttachMessage {
    private AttachMessage() {}

    /**
     * Build the attach frame for {@code username} and {@code token}. Both
     * strings are JSON-escaped; nothing is validated semantically here — the
     * gateway side will reject empty strings and oversize values.
     */
    static String build(String username, String token) {
        StringBuilder sb = new StringBuilder(64 + username.length() * 2 + token.length() * 2);
        sb.append("{\"type\":\"attach\",\"username\":");
        appendJsonString(sb, username);
        sb.append(",\"token\":");
        appendJsonString(sb, token);
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        // Gateway rejects raw control characters; escape them.
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }
}
