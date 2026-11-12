package gateway.plugin

/**
 * Minimal JSON parser for the messages the Voidburn plugin sends to the gateway. Only
 * handles the attach message that Phase 2b receives; grows alongside the message set in
 * Phase 2c/d.
 *
 * Deliberately tiny — no library dep, no reflection. Validates a complete JSON object
 * structurally: a hostile payload cannot smuggle a key through a nested object, trailing
 * garbage after the closing brace, or an unterminated string. Returns null on anything
 * that doesn't match the exact expected shape.
 */
internal object VoidburnPluginMessages {
    /**
     * Shape: `{"type":"attach","username":"..."}`. Returns the username on a valid
     * message, or null when the payload is anything else. Extra top-level string fields
     * are tolerated (future-compatible); non-string fields are skipped structurally.
     *
     * Hostile payloads that this rejects:
     *   - `{"type":"attach","username":"Alice"` — missing closing brace
     *   - `{"nested":{"type":"attach","username":"Alice"}}` — fields are nested
     *   - `{"type":"attach","username":"\uGGGG"}` — malformed unicode escape
     *   - `{"type":"attach","username":"Alice"}  trailing` — trailing garbage
     */
    fun parseAttach(json: String): String? {
        val obj = parseTopLevelObject(json) ?: return null
        if (obj["type"] != "attach") return null
        val username = obj["username"] ?: return null
        if (username.isEmpty() || username.length > MAX_USERNAME_LENGTH) return null
        return username
    }

    private const val MAX_USERNAME_LENGTH = 32

    /**
     * Parse one complete JSON object at the top level. Returns a map of its top-level
     * string-valued fields (non-string fields are present in the structure but skipped
     * in the result map). Returns null on any structural error: missing/extra braces,
     * malformed escapes, trailing non-whitespace, duplicate keys, truncated input.
     *
     * Keys of any depth below the top level are not visible to the caller.
     */
    private fun parseTopLevelObject(json: String): Map<String, String>? {
        val p = Parser(json)
        p.skipWhitespace()
        if (!p.consume('{')) return null
        val out = HashMap<String, String>()
        p.skipWhitespace()
        if (p.peek() == '}') {
            p.advance()
            p.skipWhitespace()
            return if (p.eof()) out else null
        }
        while (true) {
            p.skipWhitespace()
            val key = p.readString() ?: return null
            if (out.containsKey(key)) return null
            p.skipWhitespace()
            if (!p.consume(':')) return null
            p.skipWhitespace()
            val value = p.readValue() ?: return null
            if (value is StringValue) out[key] = value.content
            p.skipWhitespace()
            when (p.peek()) {
                ',' -> {
                    p.advance()
                    continue
                }
                '}' -> {
                    p.advance()
                    p.skipWhitespace()
                    return if (p.eof()) out else null
                }
                else -> return null
            }
        }
    }

    /** Opaque holder so parseTopLevelObject can distinguish strings from other values. */
    private class StringValue(val content: String)

    private class Parser(private val s: String) {
        private var i: Int = 0

        fun eof(): Boolean = i >= s.length
        fun peek(): Char = if (eof()) '\u0000' else s[i]
        fun advance() { i++ }

        fun consume(c: Char): Boolean {
            if (eof() || s[i] != c) return false
            i++
            return true
        }

        fun skipWhitespace() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else return
            }
        }

        /**
         * Read a JSON string at the current position; leaves the cursor just past the
         * closing quote. Returns null on any parse error (unterminated, bad escape, bad
         * unicode escape).
         */
        fun readString(): String? {
            if (!consume('"')) return null
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when (c) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        if (i + 1 >= s.length) return null
                        val esc = s[i + 1]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 >= s.length) return null
                                val hex = s.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i += 2
                    }
                    '\n', '\r' -> return null // raw control chars not allowed in strings
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return null
        }

        /**
         * Read any JSON value at the current position. Returns a StringValue for strings,
         * Unit for any other legal value (number, bool, null, object, array), or null on
         * parse error. Objects and arrays are walked structurally so the outer parser can
         * continue past them without treating their inner keys as top-level.
         */
        fun readValue(): Any? {
            if (eof()) return null
            return when (s[i]) {
                '"' -> readString()?.let { StringValue(it) }
                '{' -> if (skipObject()) Unit else null
                '[' -> if (skipArray()) Unit else null
                't' -> if (match("true")) Unit else null
                'f' -> if (match("false")) Unit else null
                'n' -> if (match("null")) Unit else null
                '-', in '0'..'9' -> if (skipNumber()) Unit else null
                else -> null
            }
        }

        private fun match(literal: String): Boolean {
            if (i + literal.length > s.length) return false
            for (k in literal.indices) if (s[i + k] != literal[k]) return false
            i += literal.length
            return true
        }

        private fun skipNumber(): Boolean {
            val start = i
            if (peek() == '-') i++
            if (eof()) return false
            if (peek() == '0') {
                i++
            } else if (peek() in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                return false
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || s[i] !in '0'..'9') return false
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') return false
                while (i < s.length && s[i] in '0'..'9') i++
            }
            return i > start
        }

        private fun skipObject(): Boolean {
            if (!consume('{')) return false
            skipWhitespace()
            if (peek() == '}') { i++; return true }
            while (true) {
                skipWhitespace()
                if (readString() == null) return false
                skipWhitespace()
                if (!consume(':')) return false
                skipWhitespace()
                if (readValue() == null) return false
                skipWhitespace()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return true }
                    else -> return false
                }
            }
        }

        private fun skipArray(): Boolean {
            if (!consume('[')) return false
            skipWhitespace()
            if (peek() == ']') { i++; return true }
            while (true) {
                skipWhitespace()
                if (readValue() == null) return false
                skipWhitespace()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return true }
                    else -> return false
                }
            }
        }
    }
}
