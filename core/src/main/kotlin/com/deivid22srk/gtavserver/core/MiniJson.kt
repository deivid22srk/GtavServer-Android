package com.deivid22srk.gtavserver.core

import java.io.ByteArrayOutputStream

/**
 * Parser JSON minimalista para o endpoint POST /data/batch.
 * Mensagens de erro replicam json.JSONDecodeError do CPython
 * ("Expecting value: line L column C (char N)" / "Extra data: ...").
 *
 * Valores: array, objeto, string (com escapes \uXXXX), número (int/float),
 * true/false/null — suficiente e fiel para o protocolo do mirror.
 */
object MiniJson {

    sealed class Value {
        data class JNull(val marker: Int = 0) : Value()
        data class JBool(val v: Boolean) : Value()
        data class JInt(val v: Long) : Value()
        data class JDouble(val v: Double) : Value()
        data class JString(val v: String) : Value()
        data class JArray(val items: MutableList<Value> = mutableListOf()) : Value()
        data class JObject(val fields: MutableMap<String, Value> = linkedMapOf()) : Value()
    }

    class JsonDecodeError(message: String) : Exception(message)

    fun parse(text: String): Value {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) {
            throw JsonDecodeError("Extra data: line ${p.line()} column ${p.col()} (char ${p.pos})")
        }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun line(): Int {
            var l = 1
            for (i in 0 until pos) if (s[i] == '\n') l++
            return l
        }

        fun col(): Int {
            var c = 1
            for (i in 0 until pos) {
                if (s[i] == '\n') c = 1
                else c++
            }
            return c
        }

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun parseValue(): Value {
            skipWs()
            if (atEnd()) throw errExpecting()
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Value.JString(parseString())
                't' -> literal("true", Value.JBool(true))
                'f' -> literal("false", Value.JBool(false))
                'n' -> literal("null", Value.JNull())
                else -> parseNumberOrNull()
            }
        }

        private fun errExpecting(): JsonDecodeError =
            JsonDecodeError("Expecting value: line ${line()} column ${col()} (char $pos)")

        private fun literal(word: String, v: Value): Value {
            if (pos + word.length > s.length || s.substring(pos, pos + word.length) != word) throw errExpecting()
            pos += word.length
            return v
        }

        fun parseObject(): Value.JObject {
            val obj = Value.JObject()
            pos++ // {
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return obj }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw errExpecting()
                val key = parseString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw errExpecting()
                pos++
                obj.fields[key] = parseValue()
                skipWs()
                if (atEnd()) throw errExpecting()
                when (s[pos]) {
                    ',' -> { pos++ }
                    '}' -> { pos++; return obj }
                    else -> throw JsonDecodeError(
                        "Expecting ',' delimiter: line ${line()} column ${col()} (char $pos)")
                }
            }
        }

        fun parseArray(): Value.JArray {
            val arr = Value.JArray()
            pos++ // [
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return arr }
            while (true) {
                arr.items.add(parseValue())
                skipWs()
                if (atEnd()) throw errExpecting()
                when (s[pos]) {
                    ',' -> { pos++ }
                    ']' -> { pos++; return arr }
                    else -> throw JsonDecodeError(
                        "Expecting ',' delimiter: line ${line()} column ${col()} (char $pos)")
                }
            }
        }

        fun parseString(): String {
            pos++ // "
            val out = ByteArrayOutputStream()
            while (true) {
                if (atEnd()) throw errExpecting()
                when (val c = s[pos]) {
                    '"' -> { pos++; break }
                    '\\' -> {
                        pos++
                        if (atEnd()) throw errExpecting()
                        when (val e = s[pos]) {
                            '"' -> out.write('"'.code)
                            '\\' -> out.write('\\'.code)
                            '/' -> out.write('/'.code)
                            'b' -> out.write('\b'.code)
                            'f' -> out.write('\u000C'.code)
                            'n' -> out.write('\n'.code)
                            'r' -> out.write('\r'.code)
                            't' -> out.write('\t'.code)
                            'u' -> {
                                if (pos + 4 >= s.length) throw errExpecting()
                                val hex = s.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw errExpecting()
                                pos += 4
                                for (b in String(intArrayOf(code), 0, 1).toByteArray(Charsets.UTF_8)) out.write(b.toInt())
                            }
                            else -> throw errExpecting()
                        }
                        pos++
                    }
                    else -> {
                        if (c.code < 0x20) throw errExpecting()
                        for (b in c.toString().toByteArray(Charsets.UTF_8)) out.write(b.toInt())
                        pos++
                    }
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private fun parseNumberOrNull(): Value {
            val start = pos
            if (atEnd()) throw errExpecting()
            if (s[pos] == '-') pos++
            var isDouble = false
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' ||
                        s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) {
                if (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E') isDouble = true
                pos++
            }
            val token = s.substring(start, pos)
            if (token.isEmpty() || token == "-") throw errExpecting()
            return if (!isDouble) {
                val l = token.toLongOrNull() ?: throw errExpecting()
                Value.JInt(l)
            } else {
                val d = token.toDoubleOrNull() ?: throw errExpecting()
                Value.JDouble(d)
            }
        }
    }
}
