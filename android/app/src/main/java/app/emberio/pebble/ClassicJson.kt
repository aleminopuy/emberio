package app.emberio.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import java.util.Base64

/**
 * The JSON that PebbleKit Classic puts in its broadcasts' `msg_data`: an array of tuples,
 * `{"key": 1, "type": "uint", "length": 1, "value": 3}`, where `type` is bytes, string, uint or
 * int, `length` is the integer width (1, 2 or 4; 0 for strings and bytes) and bytes are base64.
 *
 * Hand-written (no org.json) so it runs in plain JVM unit tests.
 */
object ClassicJson {
    fun encode(dict: PebbleDictionary): String = dict.entries.joinToString(",", "[", "]") { (key, item) ->
        val (type, width, value) = when (item) {
            is PebbleDictionaryItem.Text -> Triple("string", 0, quote(item.value))
            is PebbleDictionaryItem.Bytes -> Triple("bytes", 0, quote(Base64.getEncoder().encodeToString(item.value)))
            is PebbleDictionaryItem.Int8 -> Triple("int", 1, item.value.toString())
            is PebbleDictionaryItem.Int16 -> Triple("int", 2, item.value.toString())
            is PebbleDictionaryItem.Int32 -> Triple("int", 4, item.value.toString())
            is PebbleDictionaryItem.UInt8 -> Triple("uint", 1, item.value.toString())
            is PebbleDictionaryItem.UInt16 -> Triple("uint", 2, item.value.toString())
            // Classic reads a uint32 back with getInt, so values above Int.MAX_VALUE go negative there.
            is PebbleDictionaryItem.UInt32 -> Triple("uint", 4, item.value.toInt().toString())
        }
        """{"key":$key,"type":"$type","length":$width,"value":$value}"""
    }

    /** Tuples we can't read are skipped; malformed JSON throws [IllegalArgumentException]. */
    fun decode(json: String): PebbleDictionary {
        val parsed = Parser(json).parseDocument()
        require(parsed is List<*>) { "msg_data isn't an array" }
        val dict = linkedMapOf<UInt, PebbleDictionaryItem>()
        for (element in parsed) {
            val tuple = element as? Map<*, *> ?: continue
            val key = (tuple["key"] as? Number)?.toLong() ?: continue
            if (key < 0 || key > 0xFFFFFFFFL) continue
            val width = (tuple["length"] as? Number)?.toInt() ?: 0
            val value = tuple["value"]
            val item = when (tuple["type"]) {
                "string" -> (value as? String)?.let { PebbleDictionaryItem.Text(it) }
                "bytes" -> (value as? String)?.let {
                    runCatching { PebbleDictionaryItem.Bytes(Base64.getDecoder().decode(it)) }.getOrNull()
                }
                "int" -> (value as? Number)?.toLong()?.let {
                    when (width) {
                        1 -> PebbleDictionaryItem.Int8(it.toInt().toByte())
                        2 -> PebbleDictionaryItem.Int16(it.toInt().toShort())
                        else -> PebbleDictionaryItem.Int32(it.toInt())
                    }
                }
                "uint" -> (value as? Number)?.toLong()?.let {
                    when (width) {
                        1 -> PebbleDictionaryItem.UInt8((it and 0xFF).toInt())
                        2 -> PebbleDictionaryItem.UInt16((it and 0xFFFF).toInt())
                        else -> PebbleDictionaryItem.UInt32(it and 0xFFFFFFFFL)
                    }
                }
                else -> null
            } ?: continue
            dict[key.toUInt()] = item
        }
        return dict
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    /** A small JSON reader: objects, arrays, strings, numbers, true/false/null. */
    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            val value = parseValue()
            skipSpace()
            require(i == s.length) { "trailing characters at $i" }
            return value
        }

        private fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun expect(c: Char) {
            skipSpace()
            require(i < s.length && s[i] == c) { "expected '$c' at $i" }
            i++
        }

        private fun parseValue(): Any? {
            skipSpace()
            require(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skipSpace()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipSpace()
                val key = parseString()
                expect(':')
                map[key] = parseValue()
                skipSpace()
                require(i < s.length) { "unterminated object" }
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("expected ',' or '}' at ${i - 1}")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipSpace()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipSpace()
                require(i < s.length) { "unterminated array" }
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${i - 1}")
                }
            }
        }

        private fun parseString(): String {
            require(i < s.length && s[i] == '"') { "expected a string at $i" }
            i++
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad \\u escape" }
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            require(text.isNotEmpty()) { "unexpected '${s.getOrNull(start)}' at $start" }
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $text")
        }
    }
}
