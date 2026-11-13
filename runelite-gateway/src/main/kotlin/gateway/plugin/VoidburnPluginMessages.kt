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
     * Shape: `{"type":"attach","username":"...","token":"..."}`. Both username and
     * token are required strings. Returns the parsed pair when the payload matches,
     * or null on anything else. Extra top-level string fields are tolerated
     * (future-compatible); non-string fields are skipped structurally.
     *
     * The token is validated semantically by the registry against the session's own
     * mint; this parser only enforces the shape (non-empty, under the length cap).
     *
     * Hostile payloads that this rejects:
     *   - `{"type":"attach","username":"A"` — missing closing brace
     *   - `{"nested":{"type":"attach","username":"A","token":"T"}}` — fields are nested
     *   - `{"type":"attach","username":"A","token":"T\uGGGG"}` — malformed unicode escape
     *   - `{"type":"attach","username":"A","token":"T"}  trailing` — trailing garbage
     *   - Missing token or empty token
     */
    data class Attach(val username: String, val token: String)

    /**
     * Plugin → gateway widget click. {@code widgetId} is the full packed uid
     * (group in the high 16 bits, component in the low 16 bits). {@code sub}
     * and {@code obj} default to NONE (0xFFFF) when the plugin omits them —
     * matches tsps's own sentinel for "no sub-component" / "no item". {@code op}
     * is the 1-based option index the server configured for this widget.
     */
    data class Click(val widgetId: Int, val sub: Int, val obj: Int, val op: Int)

    fun parseAttach(json: String): Attach? {
        val obj = parseTopLevelObject(json) ?: return null
        if (asString(obj["type"]) != "attach") return null
        val username = asString(obj["username"]) ?: return null
        if (username.isEmpty() || username.length > MAX_USERNAME_LENGTH) return null
        val token = asString(obj["token"]) ?: return null
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) return null
        return Attach(username, token)
    }

    /**
     * Shape: `{"type":"click","widgetId":<int>,"sub":<int>,"obj":<int>,"op":<int>}`.
     * Required: type, widgetId, op. Optional: sub / obj (default 0xFFFF). Returns
     * null on anything else, including out-of-range sub/obj/op, a non-number
     * for a numeric field, or a present-but-wrong-typed optional field
     * (e.g. `"sub":null` — a hostile shape distinct from "sub omitted").
     *
     * The gateway enforces authorization separately (widgetId must be in a custom
     * group >= 30000, decided inside GatewaySession); the parser only enforces shape.
     */
    fun parseClick(json: String): Click? {
        val obj = parseTopLevelObject(json) ?: return null
        if (asString(obj["type"]) != "click") return null
        val widgetId = asInt(obj["widgetId"]) ?: return null
        val op = asInt(obj["op"]) ?: return null
        // Optional-with-default: an absent key defaults to NONE; a present key
        // with a wrong-typed value (null / bool / string / object / array)
        // rejects. containsKey separates "absent" from "present but not a
        // captured number."
        val sub = if (obj.containsKey("sub")) asInt(obj["sub"]) ?: return null else NONE
        val objId = if (obj.containsKey("obj")) asInt(obj["obj"]) ?: return null else NONE
        if (sub !in 0..0xFFFF || objId !in 0..0xFFFF || op !in 1..MAX_OP) return null
        return Click(widgetId, sub, objId, op)
    }

    private fun asString(value: Any?): String? = value as? String

    /**
     * Coerce a parser value into an Int. Accepts only a {@link NumberValue}
     * whose captured JSON number parses as a Long within Int range. A JSON
     * fractional ({@code 1.5}), scientific ({@code 1e2}), or over/underflow
     * number is rejected here, not at the parser level — so an attach with an
     * unknown {@code "future":1.5} top-level field still parses, while a
     * click that genuinely wants an int in widgetId still rejects on a
     * float.
     */
    private fun asInt(value: Any?): Int? {
        val nv = value as? NumberValue ?: return null
        val l = nv.text.toLongOrNull() ?: return null
        if (l < Int.MIN_VALUE || l > Int.MAX_VALUE) return null
        return l.toInt()
    }

    /** NONE sentinel for sub/obj ("no sub-component" / "no item"); matches gateway/up/Upstream.kt. */
    private const val NONE = 0xFFFF

    /** Upper bound on the 1-based option index a widget may expose. OSRS uses 1..10. */
    private const val MAX_OP = 10

    private const val MAX_USERNAME_LENGTH = 32

    /**
     * Upper bound on token length the parser will accept. Phase 2c-1 mints a 32-byte
     * random token encoded as base64 (≈44 chars) with dashes for readability (≈52
     * chars); 128 gives generous headroom for a future HMAC-style replacement without
     * changing the shape contract.
     */
    private const val MAX_TOKEN_LENGTH = 128

    /**
     * Maximum structural nesting depth the parser will walk. Attach payloads have no
     * legitimate reason to nest beyond this, and the limit bounds stack consumption when
     * a hostile client sends thousands of nested arrays/objects. Picked to be well below
     * the default JVM thread stack ceiling.
     */
    private const val MAX_NESTING_DEPTH = 16

    /**
     * Parse one complete JSON object at the top level. Returns a map of its top-level
     * string-valued fields (non-string fields are present in the structure but skipped
     * in the result map). Returns null on any structural error: missing/extra braces,
     * malformed escapes, trailing non-whitespace, duplicate keys, truncated input.
     *
     * Keys of any depth below the top level are not visible to the caller.
     */
    /**
     * Returns a map of top-level fields. Values are {@link String} for string
     * literals, {@link NumberValue} (carrying the source text) for numbers, or
     * null for bool/null/object/array — the key is still present in the map so
     * callers can distinguish "field absent" (containsKey false) from "field
     * present but not a captured type" (containsKey true, value null). Nested
     * composites are walked for structural strictness; their content is
     * discarded.
     *
     * Duplicate keys reject regardless of value type. A JSON number that
     * doesn't parse as any legal shape rejects via the lower-level readNumber.
     * Numeric parsing to int happens at the consumer (see asInt) so a
     * parseAttach doesn't reject when an unknown extra field carries a float.
     */
    private fun parseTopLevelObject(json: String): Map<String, Any?>? {
        val p = Parser(json)
        p.skipWhitespace()
        if (!p.consume('{')) return null
        val out = HashMap<String, Any?>()
        // Track EVERY key we've seen at this level, independent of whether we kept its
        // value. Without this, a non-string value for a duplicate key — `"type":null` —
        // would never land in `out`, and a later `"type":"attach"` would slip past the
        // duplicate check.
        val seenKeys = HashSet<String>()
        p.skipWhitespace()
        if (p.peek() == '}') {
            p.advance()
            p.skipWhitespace()
            return if (p.eof()) out else null
        }
        while (true) {
            p.skipWhitespace()
            val key = p.readString() ?: return null
            if (!seenKeys.add(key)) return null
            p.skipWhitespace()
            if (!p.consume(':')) return null
            p.skipWhitespace()
            val value = p.readValue() ?: return null
            when (value) {
                is StringValue -> out[key] = value.content
                is NumberValue -> out[key] = value
                // bool/null/object/array: mark present (null) but not captured, so
                // containsKey still returns true and a consumer that requires a
                // string/number value sees the mismatch.
                else -> out[key] = null
            }
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

    /** Opaque holders so parseTopLevelObject can distinguish typed values. */
    private class StringValue(val content: String)
    /** Captured JSON number source text; consumers call toLongOrNull / toIntOrNull. */
    private class NumberValue(val text: String)

    private class Parser(private val s: String) {
        private var i: Int = 0
        /** Current structural nesting depth; incremented when entering an object or array. */
        private var depth: Int = 0

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

        private fun isHex(c: Char): Boolean =
            c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

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
                                val h0 = s[i + 2]
                                val h1 = s[i + 3]
                                val h2 = s[i + 4]
                                val h3 = s[i + 5]
                                // Strict: every escape digit must be hex. toIntOrNull
                                // alone would accept a leading sign (\u+041 → 0x41).
                                if (!isHex(h0) || !isHex(h1) || !isHex(h2) || !isHex(h3)) return null
                                val code = ((h0.digitToInt(16) shl 12) or
                                        (h1.digitToInt(16) shl 8) or
                                        (h2.digitToInt(16) shl 4) or
                                        h3.digitToInt(16))
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i += 2
                    }
                    else -> {
                        // JSON forbids EVERY unescaped character below U+0020 in a
                        // string. v2 only rejected LF/CR; a literal tab, vertical tab,
                        // form feed, or any other control character went through.
                        if (c < ' ') return null
                        sb.append(c)
                        i++
                    }
                }
            }
            return null
        }

        /**
         * Read any JSON value at the current position. Returns a StringValue for
         * strings, a NumberValue (with the source text, not a parsed Long — leave
         * range checks to the caller) for numbers, Unit for bool/null/object/array,
         * or null on parse error. Objects and arrays are walked structurally so
         * the outer parser can continue past them without treating their inner
         * keys as top-level.
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
                '-', in '0'..'9' -> readNumber()?.let { NumberValue(it) }
                else -> null
            }
        }

        /**
         * Validate a number at the current position and return its source text.
         * Grammar is the same strict JSON number as {@link #skipNumber}; this
         * version additionally captures the text for the caller to parse.
         */
        fun readNumber(): String? {
            val start = i
            if (!skipNumber()) return null
            return s.substring(start, i)
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
            if (depth >= MAX_NESTING_DEPTH) return false
            depth++
            val ok = skipObjectBody()
            depth--
            return ok
        }

        private fun skipObjectBody(): Boolean {
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
            if (depth >= MAX_NESTING_DEPTH) return false
            depth++
            val ok = skipArrayBody()
            depth--
            return ok
        }

        private fun skipArrayBody(): Boolean {
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
