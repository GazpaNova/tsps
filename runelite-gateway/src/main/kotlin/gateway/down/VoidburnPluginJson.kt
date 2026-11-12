package gateway.down

/**
 * JSON encoder for messages the gateway forwards to the Voidburn RuneLite plugin over its
 * side channel. Kept small and standalone so tests exercise it without the full Interfaces
 * harness (VarpMirror, InterfaceTree, StockMarket, cache). The encoder handles only the
 * value shapes the gateway actually produces: null, Boolean, Int, Long, String, and List
 * of those. Anything else throws so an unsupported shape is caught in tests, not in
 * production.
 */
internal object VoidburnPluginJson {
    const val DISCRIMINATOR_KEY = "type"

    /**
     * Encode a message. The discriminator is written as `"type":"<type>"` and the fields
     * follow. A field named "type" would overwrite the discriminator at the parser
     * (lenient parsers keep the last value, strict parsers reject the duplicate) so the
     * encoder requires callers to rename any payload field that collides — v1 shipped the
     * bug the Codex Phase 2a review caught, where widget_open_sub's numeric interface
     * `type` field overwrote the "widget_open_sub" discriminator.
     *
     * All string content — the discriminator value, field names, and string values —
     * goes through the same escape path so quotes/backslashes/control chars in any
     * position produce valid JSON. v1 escaped only values; the encoder's contract now
     * covers field names and discriminator too, even though current callers use fixed
     * safe names.
     */
    fun encode(type: String, fields: Map<String, Any?>): String {
        require(!fields.containsKey(DISCRIMINATOR_KEY)) {
            "field name '$DISCRIMINATOR_KEY' is reserved as the event discriminator; rename the payload field"
        }
        val sb = StringBuilder()
        sb.append('{')
        appendString(sb, DISCRIMINATOR_KEY)
        sb.append(':')
        appendString(sb, type)
        for ((key, value) in fields) {
            sb.append(',')
            appendString(sb, key)
            sb.append(':')
            appendValue(sb, value)
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value)
            is Long -> sb.append(value)
            is String -> appendString(sb, value)
            is List<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    appendValue(sb, item)
                    first = false
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value type: ${value::class.simpleName}")
        }
    }

    private fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
