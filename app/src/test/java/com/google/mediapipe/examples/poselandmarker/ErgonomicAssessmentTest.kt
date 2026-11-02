package com.google.mediapipe.examples.poselandmarker

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ErgonomicAssessmentTest {
    @Test
    fun scoresAlwaysStayInsideMethodRanges() {
        val postures = listOf(
            PoseAngles(0.0, 0.0, 90.0, 90.0),
            PoseAngles(120.0, 120.0, 20.0, 20.0, 40.0, 40.0, 45.0, 75.0, 90.0, 90.0)
        )
        postures.forEach { posture ->
            val result = ErgonomicAssessment.calculate(
                posture, ErgonomicInputs(repeatedOrStatic = true, legsUnsupported = true, poorCoupling = true)
            )
            assertTrue(result.rula.left!!.final.score in 1..7)
            assertTrue(result.rula.right!!.final.score in 1..7)
            assertTrue(result.reba.score in 1..15)
        }
    }

    @Test
    fun addedExposureDoesNotReduceScores() {
        val posture = PoseAngles(50.0, 50.0, 80.0, 80.0, 20.0, 20.0, 15.0, 25.0, 20.0, 20.0)
        val baseline = ErgonomicAssessment.calculate(posture)
        val exposed = ErgonomicAssessment.calculate(
            posture, ErgonomicInputs(repeatedOrStatic = true, legsUnsupported = true, poorCoupling = true)
        )
        assertTrue(exposed.rula.worst.final.score >= baseline.rula.worst.final.score)
        assertTrue(exposed.reba.score >= baseline.reba.score)
    }

    @Test
    fun neutralPostureUsesFirstCellsOfOfficialTables() {
        val result = ErgonomicAssessment.calculate(PoseAngles(0.0, 0.0, 90.0, 90.0))
        assertEquals(1, result.rula.left!!.postureA)
        assertEquals(1, result.rula.left!!.postureB)
        assertEquals(1, result.rula.left!!.final.score)
    }

    @Test
    fun evaluatesEachArmIndependently() {
        val result = ErgonomicAssessment.calculate(PoseAngles(10.0, 100.0, 90.0, 30.0))
        assertTrue(result.rula.right!!.groupA > result.rula.left!!.groupA)
        assertEquals("Direito", result.rula.worst.side)
    }

    @Test
    fun confirmedModifiersAreAddedToPostureScores() {
        val posture = PoseAngles(30.0, 30.0, 90.0, 90.0, neck = 15.0, trunk = 15.0)
        val baseline = ErgonomicAssessment.calculate(posture)
        val adjusted = ErgonomicAssessment.calculate(posture, ErgonomicInputs(
            adjustments = RulaAdjustments(
                leftArmAbducted = true,
                neckSideBending = true,
                trunkSideBending = true
            )
        ))
        assertTrue(adjusted.rula.left!!.upperArm > baseline.rula.left!!.upperArm)
        assertTrue(adjusted.rula.left!!.neck > baseline.rula.left!!.neck)
        assertTrue(adjusted.rula.left!!.trunk > baseline.rula.left!!.trunk)
        assertTrue(adjusted.rula.left!!.final.score >= baseline.rula.left!!.final.score)
    }
}
