package com.google.mediapipe.examples.poselandmarker

import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AssessmentCsv {
    val columns: List<String> = listOf("id_avaliacao", "data_hora_utc", "codigo_teste") + AssessmentRecords.valueColumns

    fun write(records: List<SavedAssessment>, writer: Writer) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        writer.write("\uFEFF")
        writeRow(columns, writer)
        records.forEach { record ->
            val values = listOf(record.id, dateFormat.format(Date(record.savedAt)), record.testCode) +
                AssessmentRecords.valueColumns.map { key ->
                    val value = record.values[key].orEmpty()
                    if (key.endsWith("_graus") || key == "carga_kg") value.replace('.', ',') else value
                }
            writeRow(values, writer)
        }
    }

    private fun writeRow(values: List<String>, writer: Writer) {
        writer.write(values.joinToString(";") { value ->
            // Quoting alone does not prevent formulas when a spreadsheet opens a CSV.
            val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
                value.startsWith('\t') || value.startsWith('\r') || value.startsWith('\n')) "'$value" else value
            "\"${safe.replace("\"", "\"\"")}\""
        })
        writer.write("\r\n")
    }
}
