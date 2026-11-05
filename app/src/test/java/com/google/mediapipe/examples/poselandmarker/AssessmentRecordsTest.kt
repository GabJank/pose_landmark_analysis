package com.google.mediapipe.examples.poselandmarker

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentRecordsTest {
    private val posture = PoseAngles(10.25, 70.0, 90.0, 50.0, neck = 12.0, trunk = 20.0)
    private val session = RulaTwoViewSession(posture, posture)

    @Test
    fun storesRebaAndOptionalOwasWithTheirInputs() {
        val inputs = ErgonomicInputs(loadKg = 3.5)
        val observation = OwasObservation(2, 1, 3, 2)
        val record = AssessmentRecords.create("P1", session, inputs, observation)
        val reba = ErgonomicAssessment.calculate(session.combinedAngles()!!, inputs).reba
        assertEquals("2", record.values["versao_registro"])
        assertEquals(reba.score.toString(), record.values["reba_simplificado_score"])
        assertEquals(reba.risk, record.values["reba_simplificado_risk"])
        assertEquals("2", record.values["owas_costas"])
        assertEquals("1", record.values["owas_bracos"])
        assertEquals("3", record.values["owas_pernas"])
        assertEquals("2", record.values["owas_carga"])
        assertEquals(observation.result.score.toString(), record.values["owas_categoria"])
        val absent = AssessmentRecords.create("P2", session, inputs)
        assertEquals("", absent.values["owas_categoria"])
        val rows = parseCsv(StringWriter().also { AssessmentCsv.write(listOf(record, absent), it) }.toString().removePrefix("\uFEFF"))
        assertEquals(observation.result.score.toString(), rows[0].zip(rows[1]).toMap()["owas_categoria"])
        assertEquals("", rows[0].zip(rows[2]).toMap()["owas_categoria"])
    }

    @Test
    fun exportsOldRecordsWithoutInventingNewResults() {
        val old = SavedAssessment("old", 0L, "P0", mapOf("versao_registro" to "1", "rula_score" to "3"))
        val rows = parseCsv(StringWriter().also { AssessmentCsv.write(listOf(old), it) }.toString().removePrefix("\uFEFF"))
        val values = rows[0].zip(rows[1]).toMap()
        assertEquals("3", values["rula_score"])
        assertEquals("", values["reba_simplificado_score"])
        assertEquals("", values["owas_categoria"])
    }

    @Test
    fun snapshotKeepsTheViewsInputsAndResultUsedInTheAssessment() {
        val inputs = ErgonomicInputs(loadKg = 3.5, repeatedOrStatic = true,
            adjustments = RulaAdjustments(neckTwisted = true, rightArmSupported = true))
        val expected = ErgonomicAssessment.calculate(session.combinedAngles()!!, inputs).rula
        val record = AssessmentRecords.create("P1_POSTURA_02", session, inputs)

        assertEquals("3.5", record.values["carga_kg"])
        assertEquals("true", record.values["pescoco_rotacionado"])
        assertEquals("true", record.values["braco_direito_apoiado"])
        assertEquals("10.25", record.values["frontal_ombro_esquerdo_graus"])
        assertEquals("10.25", record.values["combinado_ombro_esquerdo_graus"])
        assertEquals(expected.worst.final.score.toString(), record.values["rula_score"])
        assertEquals(expected.left!!.groupA.toString(), record.values["rula_left_grupo_a"])
        assertEquals(expected.right!!.groupB.toString(), record.values["rula_right_grupo_b"])
        assertEquals(AssessmentRecords.valueColumns.toSet(), record.values.keys)
        assertTrue(record.savedAt > 0)
        assertThrows(UnsupportedOperationException::class.java) {
            (record.values as MutableMap<String, String>)["rula_score"] = "7"
        }
    }

    @Test
    fun undetectedLimbsAreBlankInsteadOfZeroAndPartialStatusIsKept() {
        val partial = posture.copy(leftArmDetected = false, leftArmReliable = false)
        val record = AssessmentRecords.create("P2", RulaTwoViewSession(partial, partial), ErgonomicInputs())

        assertEquals("", record.values["combinado_ombro_esquerdo_graus"])
        assertEquals("", record.values["rula_left_score"])
        assertEquals("", record.values["combinado_joelho_direito_graus"])
        assertEquals("false", record.values["combinado_braco_esquerdo_detectado"])
        assertEquals("true", record.values["rula_parcial"])
        assertFalse(record.values["rula_right_score"].isNullOrBlank())
    }

    @Test
    fun rejectsIncompleteViewsInvalidCodesAndInvalidLoads() {
        assertThrows(IllegalArgumentException::class.java) {
            AssessmentRecords.create("P1", RulaTwoViewSession(frontal = posture), ErgonomicInputs())
        }
        listOf("", "nome completo", "=SUM(1)", "A".repeat(33)).forEach { code ->
            assertThrows(IllegalArgumentException::class.java) {
                AssessmentRecords.create(code, session, ErgonomicInputs())
            }
        }
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { load ->
            assertThrows(IllegalArgumentException::class.java) {
                AssessmentRecords.create("P1", session, ErgonomicInputs(loadKg = load))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssessmentRecords.create("P1", session.copy(lateral = posture.copy(neck = Double.NaN)), ErgonomicInputs())
        }
    }

    @Test
    fun csvHasStableColumnsUtcDateAndPortugueseDecimalSeparator() {
        val record = AssessmentRecords.create("P1", session, ErgonomicInputs(loadKg = 3.5))
            .copy(savedAt = 0L)
        val output = StringWriter().also { AssessmentCsv.write(listOf(record), it) }.toString()
        val rows = parseCsv(output.removePrefix("\uFEFF"))

        assertTrue(output.startsWith("\uFEFF"))
        assertTrue(output.endsWith("\r\n"))
        assertEquals(AssessmentCsv.columns, rows[0])
        assertEquals(rows[0].size, rows[1].size)
        val values = rows[0].zip(rows[1]).toMap()
        assertEquals("1970-01-01T00:00:00.000Z", values["data_hora_utc"])
        assertEquals("10,25", values["combinado_ombro_esquerdo_graus"])
        assertEquals("3,5", values["carga_kg"])
        assertEquals("", values["combinado_joelho_esquerdo_graus"])
        assertEquals("10.25", record.values["combinado_ombro_esquerdo_graus"])
    }

    @Test
    fun csvEscapesSeparatorsQuotesNewlinesAndFormulaPrefixes() {
        val action = "Mudar; conferir \"pontos\"\r\nantes de usar"
        val record = AssessmentRecords.create("P1", session, ErgonomicInputs()).let {
            it.copy(testCode = "=1+1", values = it.values + ("rula_action" to action))
        }
        val output = StringWriter().also { AssessmentCsv.write(listOf(record), it) }.toString()
        val rows = parseCsv(output.removePrefix("\uFEFF"))
        val values = rows[0].zip(rows[1]).toMap()

        assertEquals(2, rows.size)
        assertEquals("'=1+1", values["codigo_teste"])
        assertEquals(action, values["rula_action"])
        assertEquals(rows[0].size, rows[1].size)
    }

    @Test
    fun emptyExportStillHasHeader() {
        val output = StringWriter().also { AssessmentCsv.write(emptyList(), it) }.toString()
        assertEquals(listOf(AssessmentCsv.columns), parseCsv(output.removePrefix("\uFEFF")))
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> if (quoted && i + 1 < text.length && text[i + 1] == '"') {
                    cell.append('"'); i++
                } else quoted = !quoted
                ';' -> if (quoted) cell.append(c) else { cells.add(cell.toString()); cell.clear() }
                '\r' -> if (quoted) cell.append(c) else {
                    cells.add(cell.toString()); cell.clear(); rows.add(cells); cells = mutableListOf()
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        return rows
    }
}
