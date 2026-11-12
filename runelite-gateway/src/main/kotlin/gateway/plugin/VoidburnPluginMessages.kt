package gateway.plugin

/**
 * Minimal JSON parser for the messages the Voidburn plugin sends to the gateway. Only
 * handles the attach message that Phase 2b receives; grows alongside the message set in
 * Phase 2c/d.
 *
 * Deliberately tiny — no library dep, no reflection, no untrusted content walked more
 * than once. Rejects anything that doesn't match the exact expected shape, so a
 * malformed or hostile payload can't trigger surprising behaviour.
 */
internal object VoidburnPluginMessages {
    /**
     * Shape: `{"type":"attach","username":"..."}`. Returns the username on a valid
     * message, or null when the payload is anything else. Extra fields are tolerated
     * (future-compatible); missing or non-string username, or any other type, is not.
     */
    fun parseAttach(json: String): String? {
        val type = extractStringField(json, "type") ?: return null
        if (type != "attach") return null
        val username = extractStringField(json, "username") ?: return null
        if (username.isEmpty() || username.length > MAX_USERNAME_LENGTH) return null
        return username
    }

    private const val MAX_USERNAME_LENGTH = 32

    /**
     * Pull the value of a top-level string field. Returns null when the field is missing,
     * not a string, or the JSON is malformed. Does not descend into nested objects.
     *
     * Not a full JSON parser; not a performance path (one attach per plugin connection).
     * Uses a conservative approach: find the field key, verify it's followed by a colon,
     * skip whitespace, verify an opening quote, consume string content with escape
     * handling, stop at the closing quote.
     */
    private fun extractStringField(json: String, field: String): String? {
        val key = "\"$field\""
        var index = 0
        while (true) {
            val keyAt = json.indexOf(key, index)
            if (keyAt < 0) return null
            // Must be a true key position — the character immediately before (skipping
            // whitespace) should be `{`, `,`, or the start of input.
            var preceding = keyAt - 1
            while (preceding >= 0 && json[preceding].isWhitespace()) preceding--
            if (preceding >= 0 && json[preceding] != '{' && json[preceding] != ',') {
                index = keyAt + key.length
                continue
            }
            // Advance past the key.
            var afterKey = keyAt + key.length
            // Skip whitespace, require a colon.
            while (afterKey < json.length && json[afterKey].isWhitespace()) afterKey++
            if (afterKey >= json.length || json[afterKey] != ':') return null
            afterKey++
            while (afterKey < json.length && json[afterKey].isWhitespace()) afterKey++
            if (afterKey >= json.length || json[afterKey] != '"') return null
            // Consume string content with escape handling.
            val sb = StringBuilder()
            var cursor = afterKey + 1
            while (cursor < json.length) {
                val c = json[cursor]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (cursor + 1 >= json.length) return null
                    val esc = json[cursor + 1]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (cursor + 5 >= json.length) return null
                            val hex = json.substring(cursor + 2, cursor + 6)
                            sb.append(hex.toInt(16).toChar())
                            cursor += 4
                        }
                        else -> return null
                    }
                    cursor += 2
                } else {
                    sb.append(c)
                    cursor++
                }
            }
            return null
        }
    }
}
