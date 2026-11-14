package com.voidburn.plugin;

/**
 * Builds the plugin → gateway click frame:
 * {@code {"type":"click","widgetId":<int>,"op":<int>}}.
 *
 * Same hand-built style as {@link AttachMessage} — no Gson dep, deterministic
 * key order for logs and tests, no stray fields. Optional {@code sub} and
 * {@code obj} are omitted; the gateway parser defaults both to NONE (0xFFFF),
 * which is the correct value for the root-of-widget clicks the MOTD panel
 * fires (no sub-component, no inventory item).
 *
 * {@code widgetId} is the full packed uid: the group id in the high 16 bits,
 * the component id in the low 16 bits. Numbers below {@link Integer#MIN_VALUE}
 * or above {@link Integer#MAX_VALUE} would overflow the gateway's asInt and
 * reject the frame; both parameters are Java ints, so the only way to land
 * outside that range here is a programmer error at the call site.
 */
final class ClickFrame {
    private ClickFrame() {}

    /**
     * Build a click frame. {@code widgetId} is a full packed uid; {@code op}
     * is the 1-based option index the server declared for this widget
     * (1..10). The gateway's parser enforces those ranges and refuses a
     * widget whose group is below the custom-interface cutoff (30000), so
     * callers that stick to the MOTD panel's own widgets (group 30020) are
     * always accepted.
     */
    static String build(int widgetId, int op) {
        StringBuilder sb = new StringBuilder(48);
        sb.append("{\"type\":\"click\",\"widgetId\":");
        sb.append(widgetId);
        sb.append(",\"op\":");
        sb.append(op);
        sb.append('}');
        return sb.toString();
    }
}
