package com.deivid22srk.gtavserver.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Utilitários que reproduzem comportamentos da stdlib do CPython 3.12 usados pelo
 * `serve_local.py` original (email.utils.formatdate, html.escape, urllib.parse.quote/
 * unquote/parse_qs, posixpath.splitext/normpath, repr()). Cada função documenta a
 * origem exata para garantir paridade byte a byte.
 */
object PythonHttp {

    // email.utils.formatdate(timeval, usegmt=True) -> "%a, %d %b %Y %H:%M:%S GMT"
    private val httpDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    }

    /** BaseHTTPRequestHandler.date_time_string — cabeçalhos Date/Last-Modified. */
    fun formatHttpDate(epochMillis: Long): String =
        httpDateFormat.get()!!.format(Date(floorToSecond(epochMillis)))

    /** BaseHTTPRequestHandler.log_date_time_string — hora LOCAL no log. */
    fun formatLogDate(): String =
        SimpleDateFormat("dd/MMM/yyyy HH:mm:ss", Locale.US).format(Date())

    fun floorToSecond(epochMillis: Long): Long = (epochMillis / 1000L) * 1000L

    /**
     * email.utils.parsedate_to_datetime equivalente para If-Modified-Since.
     * Aceita os 3 formatos do RFC 2822/1123, RFC 850 e asctime.
     * Retorna (epochMillis UTC, fusoEraUTC) ou null se inválido.
     * (python: fuso ausente -> naive -> tratado como UTC; fuso != UTC -> 304 ignorado).
     */
    fun parseHttpDate(value: String): Pair<Long, Boolean>? {
        val v = value.trim()
        // RFC 1123: "Tue, 14 Nov 2023 22:13:20 GMT"
        //           g1=dia g2=mês g3=ano g4=h g5=min g6=seg g7=fuso
        rfc1123.matchEntire(v)?.let { m ->
            val (tzMillis, isUtc) = parseZone(m.groupValues[7]) ?: return null
            val cal = buildCalendar(
                m.groupValues[3].toInt(), monthFromName(m.groupValues[2]) ?: return null,
                m.groupValues[1].toInt(), m.groupValues[4].toInt(),
                m.groupValues[5].toInt(), m.groupValues[6].toInt()
            ) ?: return null
            return Pair(cal.timeInMillis + tzMillis, isUtc)
        }
        // RFC 850: "Tuesday, 14-Nov-23 22:13:20 GMT"
        //          g1=dia g2=mês g3=ano2 g4=h g5=min g6=seg g7=fuso
        rfc850.matchEntire(v)?.let { m ->
            val (tzMillis, isUtc) = parseZone(m.groupValues[7]) ?: return null
            var year = m.groupValues[3].toInt()
            year = if (year <= 68) year + 2000 else if (year < 100) year + 1900 else year
            val cal = buildCalendar(
                year, monthFromName(m.groupValues[2]) ?: return null,
                m.groupValues[1].toInt(), m.groupValues[4].toInt(),
                m.groupValues[5].toInt(), m.groupValues[6].toInt()
            ) ?: return null
            return Pair(cal.timeInMillis + tzMillis, isUtc)
        }
        // asctime: "Tue Nov 14 22:13:20 2023"
        //          g1=mês g2=dia g3=h g4=min g5=seg g6=ano
        asctime.matchEntire(v)?.let { m ->
            val cal = buildCalendar(
                m.groupValues[6].toInt(), monthFromName(m.groupValues[1]) ?: return null,
                m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt()
            ) ?: return null
            return Pair(cal.timeInMillis, true) // sem fuso -> naive -> UTC (python)
        }
        return null
    }

    private val rfc1123 = Regex(
        """(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{2,4}) (\d{2}):(\d{2}):(\d{2}) (.+)"""
    )
    private val rfc850 = Regex(
        """(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday), (\d{2})-(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-(\d{2}) (\d{2}):(\d{2}):(\d{2}) (.+)"""
    )
    private val asctime = Regex(
        """(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) {1,2}(\d{1,2}) (\d{2}):(\d{2}):(\d{2}) (\d{4})"""
    )

    private val months = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
        "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
    )

    private fun monthFromName(name: String): Int? = months[name.lowercase(Locale.ROOT)]

    /**
     * Mapeamento de fuso do email._parseaddr._timezones (nomes) + offsets numéricos.
     * Retorna (offsetMillis a somar ao horário local informado, fusoÉUTC).
     */
    private fun parseZone(token: String): Pair<Long, Boolean>? {
        val t = token.trim().uppercase(Locale.ROOT)
        return when {
            t == "GMT" || t == "UT" || t == "UTC" || t == "Z" -> Pair(0L, true)
            t == "-0000" || t == "+0000" -> Pair(0L, true)
            t == "EST" -> Pair(-5L * 3600_000, false)
            t == "EDT" -> Pair(-4L * 3600_000, false)
            t == "CST" -> Pair(-6L * 3600_000, false)
            t == "CDT" -> Pair(-5L * 3600_000, false)
            t == "MST" -> Pair(-7L * 3600_000, false)
            t == "MDT" -> Pair(-6L * 3600_000, false)
            t == "PST" -> Pair(-8L * 3600_000, false)
            t == "PDT" -> Pair(-7L * 3600_000, false)
            t.matches(Regex("""[+-]\d{4}""")) -> {
                val sign = if (t[0] == '-') -1L else 1L
                val hh = t.substring(1, 3).toLong()
                val mm = t.substring(3, 5).toLong()
                // python: parsedate_to_datetime cria timezone com esse offset (nao-UTC)
                Pair(-sign * (hh * 3600_000 + mm * 60_000), false)
            }
            else -> null // fuso desconhecido -> parse falha (python levantaria ValueError -> ignora)
        }
    }

    private fun buildCalendar(
        year: Int, month0: Int, day: Int, hour: Int, minute: Int, second: Int
    ): Calendar? {
        if (month0 !in 0..11) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.US)
        cal.clear()
        cal.set(year, month0, day, hour, minute, second)
        return cal
    }

    /** html.escape(s, quote=False) — escapa apenas &, < e >. */
    fun htmlEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /**
     * urllib.parse.quote(s, errors='surrogatepass') com defaults:
     * safe='/', encoding='utf-8'. Nunca escapa [A-Za-z0-9_.\-~/].
     */
    fun quote(s: String, safe: String = "/"): String {
        val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-~"
        val sb = StringBuilder()
        val bytes = s.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt().toChar()
            when {
                c < '!' || c > '~' -> percentEncodeByte(b, sb)
                unreserved.contains(c) -> sb.append(c)
                safe.contains(c) -> sb.append(c)
                else -> percentEncodeByte(b, sb)
            }
        }
        return sb.toString()
    }

    private fun percentEncodeByte(b: Byte, sb: StringBuilder) {
        val v = b.toInt() and 0xFF
        sb.append('%')
        sb.append("0123456789ABCDEF"[(v shr 4) and 0xF])
        sb.append("0123456789ABCDEF"[v and 0xF])
    }

    /**
     * urllib.parse.unquote(path, errors='surrogatepass') com fallback
     * unquote(path) [errors='replace'] do translate_path. Não decoda '+'.
     * Percent-bytes inválidos em UTF-8 viram U+FFFD (comportamento do fallback).
     */
    fun unquote(s: String): String {
        val raw = rawPercentBytes(s)
        return String(raw, Charsets.UTF_8) // JVM decodifica bytes inválidos como U+FFFD
    }

    private fun rawPercentBytes(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val parsed = hex.toIntOrNull(16)
                if (parsed != null) {
                    out.write(parsed)
                    i += 3
                } else {
                    out.write(c.code)
                    i++
                }
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) out.write(b.toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    /**
     * urllib.parse.parse_qs(query) com defaults (keep_blank_values=False,
     * strict_parsing=False): separador '&', unquote_plus (com '+' -> espaço).
     */
    fun parseQs(query: String): Map<String, List<String>> {
        val pairs = mutableMapOf<String, MutableList<String>>()
        for (nv in query.split('&')) {
            if (nv.isEmpty()) continue
            val i = nv.indexOf('=')
            val name: String
            val value: String
            if (i >= 0) {
                name = unquotePlus(nv.substring(0, i))
                value = unquotePlus(nv.substring(i + 1))
            } else {
                name = unquotePlus(nv)
                value = ""
            }
            if (value.isEmpty()) continue // keep_blank_values=False
            pairs.getOrPut(name) { mutableListOf() }.add(value)
        }
        return pairs
    }

    private fun unquotePlus(s: String): String {
        val plusSwapped = s.replace('+', ' ')
        return unquote(plusSwapped)
    }

    /** posixpath.splitext: último '.' após a última barra; ".bashrc" não tem extensão. */
    fun splitExt(path: String): Pair<String, String> {
        var i = path.length
        while (i > 0) {
            val c = path[i - 1]
            if (c == '/') return Pair(path, "")
            if (c == '.') {
                if (i - 1 == 0 || path[i - 2] == '/') return Pair(path, "")
                return Pair(path.substring(0, i - 1), path.substring(i - 1))
            }
            i--
        }
        return Pair(path, "")
    }

    /** str(x) com repr()-style quoting do python para mensagens (%r). */
    fun pyRepr(s: String): String = "'$s'"

    /** Formata OSError à moda do python para mensagens de erro do /data/batch. */
    fun osErrorMessage(errno: Int, path: String): String = when (errno) {
        2 -> "[Errno 2] No such file or directory: '${path.replace("'", "\\'")}'"
        13 -> "[Errno 13] Permission denied: '${path.replace("'", "\\'")}'"
        20 -> "[Errno 20] Not a directory: '${path.replace("'", "\\'")}'"
        21 -> "[Errno 21] Is a directory: '${path.replace("'", "\\'")}'"
        else -> "[Errno $errno] OSError: '$path'"
    }
}
