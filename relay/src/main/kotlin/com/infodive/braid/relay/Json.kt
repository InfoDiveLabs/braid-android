package com.infodive.braid.relay

/**
 * Just enough JSON for the control plane, so the relay module stays free of
 * dependencies and runs unchanged in JVM tests, where Android's org.json is a stub.
 */
object Json {
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    /** Strings are quoted; numbers and booleans are written as-is; null fields are left out. */
    fun obj(vararg fields: Pair<String, Any?>): String = fields
        .filter { it.second != null }
        .joinToString(",", "{", "}") { (name, value) -> quote(name) + ":" + encode(value!!) }

    private fun encode(value: Any): String = when (value) {
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is RawJson -> value.text
        else -> throw IllegalArgumentException("cannot encode ${value::class}")
    }

    /** Already-encoded JSON, for nesting the output of [obj] inside another. */
    class RawJson(val text: String)

    fun array(items: List<String>): RawJson = RawJson(items.joinToString(",", "[", "]"))

    /** One object, or null for anything else. Values are String, Double, Boolean, null, Map or List. */
    fun parseObject(text: String): Map<String, Any?>? = try {
        Parser(text).run {
            skipSpace()
            val value = parseObject()
            skipSpace()
            if (pos != text.length) null else value
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        private fun expect(c: Char) {
            require(s[pos] == c)
            pos++
        }

        fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipSpace()
                val key = parseString()
                skipSpace()
                expect(':')
                out[key] = parseValue()
                skipSpace()
                if (s[pos] == ',') {
                    pos++
                    continue
                }
                expect('}')
                return out
            }
        }

        private fun parseValue(): Any? {
            skipSpace()
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipSpace()
            if (s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipSpace()
                if (s[pos] == ',') {
                    pos++
                    continue
                }
                expect(']')
                return out
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos))
            pos += word.length
            return value
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < s.length && s[pos] in "+-0123456789.eE") pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException()
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException()
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException()
                    else -> out.append(c)
                }
            }
        }
    }
}
