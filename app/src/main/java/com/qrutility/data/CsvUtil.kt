package com.qrutility.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal CSV read/write. Handles quoted fields, commas, and escaped quotes. */
object CsvUtil {

    /**
     * Parse CSV text into rows. Column mapping:
     *   2+ columns -> (label = col0, content = col1)
     *   1 column   -> (label = "", content = col0)
     * A header row whose first cell is "label"/"name"/"id" (case-insensitive)
     * is skipped.
     */
    fun parse(text: String): List<Row> {
        val out = ArrayList<Row>()
        val lines = splitRecords(text)
        lines.forEachIndexed { idx, line ->
            if (line.isBlank()) return@forEachIndexed
            val cols = parseLine(line)
            if (idx == 0 && cols.isNotEmpty() &&
                cols[0].trim().lowercase() in setOf("label", "name", "id")
            ) return@forEachIndexed // header
            when {
                cols.size >= 2 -> out.add(Row(cols[0].trim(), cols[1]))
                cols.size == 1 && cols[0].isNotBlank() -> out.add(Row("", cols[0]))
            }
        }
        return out
    }

    fun buildScansCsv(scans: List<Triple<String, String, Long>>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("value,type,timestamp\n")
        for ((value, type, ts) in scans) {
            sb.append(field(value)).append(',')
                .append(field(type)).append(',')
                .append(field(fmt.format(Date(ts)))).append('\n')
        }
        return sb.toString()
    }

    /** Split on record boundaries, but not on newlines inside quotes. */
    private fun splitRecords(text: String): List<String> {
        val records = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' -> { inQuotes = !inQuotes; cur.append(ch) }
                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    if (cur.isNotEmpty()) { records.add(cur.toString()); cur.setLength(0) }
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty()) records.add(cur.toString())
        return records
    }

    private fun parseLine(line: String): List<String> {
        val cols = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { cols.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(ch)
            }
            i++
        }
        cols.add(cur.toString())
        return cols
    }

    private fun field(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
}
