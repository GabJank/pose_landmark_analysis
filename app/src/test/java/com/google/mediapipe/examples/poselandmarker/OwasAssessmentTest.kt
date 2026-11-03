package com.google.mediapipe.examples.poselandmarker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OwasAssessmentTest {
    @Test fun referencePostures() {
        assertEquals(1, OwasAssessment.calculate(1, 1, 2, 1).score)
        assertEquals(2, OwasAssessment.calculate(1, 1, 4, 1).score)
        assertEquals(3, OwasAssessment.calculate(2, 1, 2, 3).score)
        assertEquals(4, OwasAssessment.calculate(4, 3, 1, 1).score)
    }

    @Test fun allCodesAreDefinedAndInvalidCodesAreRejected() {
        for (back in 1..4) for (arms in 1..3) for (legs in 1..7) for (load in 1..3) {
            assert(OwasAssessment.calculate(back, arms, legs, load).score in 1..4)
        }
        assertThrows(IllegalArgumentException::class.java) { OwasAssessment.calculate(0, 1, 1, 1) }
    }
}
