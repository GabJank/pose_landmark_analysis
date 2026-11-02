package com.google.mediapipe.examples.poselandmarker

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

data class PoseAngles(
    val leftShoulder: Double,
    val rightShoulder: Double,
    val leftElbow: Double,
    val rightElbow: Double,
    val leftWrist: Double = 0.0,
    val rightWrist: Double = 0.0,
    val neck: Double = 0.0,
    val trunk: Double = 0.0,
    val leftKnee: Double = 0.0,
    val rightKnee: Double = 0.0,
    val legsDetected: Boolean = false,
    val suggestedLeftArmAbducted: Boolean = false,
    val suggestedRightArmAbducted: Boolean = false,
    val suggestedNeckSideBending: Boolean = false,
    val suggestedTrunkSideBending: Boolean = false,
    val leftArmDetected: Boolean = true,
    val rightArmDetected: Boolean = true,
    val leftArmReliable: Boolean = true,
    val rightArmReliable: Boolean = true,
    val leftArmEstimatedFromFrontal: Boolean = false,
    val rightArmEstimatedFromFrontal: Boolean = false
)

object PoseAngleCalculator {
    private const val NOSE = 0
    private const val LEFT_EAR = 7
    private const val RIGHT_EAR = 8
    private const val LEFT_SHOULDER = 11
    private const val RIGHT_SHOULDER = 12
    private const val LEFT_ELBOW = 13
    private const val RIGHT_ELBOW = 14
    private const val LEFT_WRIST = 15
    private const val RIGHT_WRIST = 16
    private const val LEFT_INDEX = 19
    private const val RIGHT_INDEX = 20
    private const val LEFT_HIP = 23
    private const val RIGHT_HIP = 24
    private const val LEFT_KNEE = 25
    private const val RIGHT_KNEE = 26
    private const val LEFT_ANKLE = 27
    private const val RIGHT_ANKLE = 28

    fun calculate(result: PoseLandmarkerResult): PoseAngles? {
        val p = result.landmarks().firstOrNull() ?: return null
        val shoulderMid = visibleMidpoint(p[LEFT_SHOULDER], p[RIGHT_SHOULDER]) ?: return null
        val hipMid = visibleMidpoint(p[LEFT_HIP], p[RIGHT_HIP]) ?: return null
        // MediaPipe still estimates occluded joints. Accept those lower-confidence
        // arm points so a frontal view can complete an arm hidden in the lateral view.
        val leftArmDetected = listOf(LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST).all { isArmUsable(p[it]) }
        val rightArmDetected = listOf(RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST).all { isArmUsable(p[it]) }
        val leftArmReliable = listOf(LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST).all { isVisible(p[it]) }
        val rightArmReliable = listOf(RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST).all { isVisible(p[it]) }
        if (!leftArmDetected && !rightArmDetected) return null
        val legsDetected = listOf(LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE)
            .all { isVisible(p[it]) }

        val head = when {
            isVisible(p[LEFT_EAR]) && isVisible(p[RIGHT_EAR]) -> midpoint(p[LEFT_EAR], p[RIGHT_EAR])
            isArmUsable(p[LEFT_EAR]) -> point(p[LEFT_EAR])
            isArmUsable(p[RIGHT_EAR]) -> point(p[RIGHT_EAR])
            isArmUsable(p[NOSE]) -> point(p[NOSE])
            else -> return null
        }
        val trunk = angleFromVertical(hipMid, shoulderMid)
        val headFromVertical = angleFromVertical(shoulderMid, head)
        val leftHipForArm = if (isArmUsable(p[LEFT_HIP])) p[LEFT_HIP] else p[RIGHT_HIP]
        val rightHipForArm = if (isArmUsable(p[RIGHT_HIP])) p[RIGHT_HIP] else p[LEFT_HIP]

        return PoseAngles(
            leftShoulder = if (leftArmDetected) jointAngle(p[LEFT_ELBOW], p[LEFT_SHOULDER], leftHipForArm) else 0.0,
            rightShoulder = if (rightArmDetected) jointAngle(p[RIGHT_ELBOW], p[RIGHT_SHOULDER], rightHipForArm) else 0.0,
            leftElbow = if (leftArmDetected) flexion(p[LEFT_SHOULDER], p[LEFT_ELBOW], p[LEFT_WRIST]) else 0.0,
            rightElbow = if (rightArmDetected) flexion(p[RIGHT_SHOULDER], p[RIGHT_ELBOW], p[RIGHT_WRIST]) else 0.0,
            leftWrist = if (leftArmDetected && isVisible(p[LEFT_INDEX])) deviation(p[LEFT_ELBOW], p[LEFT_WRIST], p[LEFT_INDEX]) else 0.0,
            rightWrist = if (rightArmDetected && isVisible(p[RIGHT_INDEX])) deviation(p[RIGHT_ELBOW], p[RIGHT_WRIST], p[RIGHT_INDEX]) else 0.0,
            neck = abs(headFromVertical - trunk),
            trunk = trunk,
            leftKnee = if (legsDetected) flexion(p[LEFT_HIP], p[LEFT_KNEE], p[LEFT_ANKLE]) else 0.0,
            rightKnee = if (legsDetected) flexion(p[RIGHT_HIP], p[RIGHT_KNEE], p[RIGHT_ANKLE]) else 0.0,
            legsDetected = legsDetected,
            suggestedLeftArmAbducted = leftArmDetected && abs(p[LEFT_ELBOW].x() - p[LEFT_SHOULDER].x()) > 0.04f,
            suggestedRightArmAbducted = rightArmDetected && abs(p[RIGHT_ELBOW].x() - p[RIGHT_SHOULDER].x()) > 0.04f,
            suggestedNeckSideBending = abs(head.x - shoulderMid.x) > 0.025,
            suggestedTrunkSideBending = abs(shoulderMid.x - hipMid.x) > 0.035,
            leftArmDetected = leftArmDetected,
            rightArmDetected = rightArmDetected,
            leftArmReliable = leftArmReliable,
            rightArmReliable = rightArmReliable
        )
    }

    private data class Point(val x: Double, val y: Double)
    private fun midpoint(a: NormalizedLandmark, b: NormalizedLandmark) =
        Point((a.x() + b.x()) / 2.0, (a.y() + b.y()) / 2.0)

    private fun point(p: NormalizedLandmark) = Point(p.x().toDouble(), p.y().toDouble())

    private fun visibleMidpoint(a: NormalizedLandmark, b: NormalizedLandmark): Point? = when {
        isArmUsable(a) && isArmUsable(b) -> midpoint(a, b)
        isArmUsable(a) -> point(a)
        isArmUsable(b) -> point(b)
        else -> null
    }

    private fun angleFromVertical(from: Point, to: Point): Double =
        abs(Math.toDegrees(atan2(to.x - from.x, from.y - to.y)))

    private fun flexion(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark) =
        (180.0 - jointAngle(a, b, c)).coerceIn(0.0, 180.0)

    private fun deviation(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark) =
        abs(180.0 - jointAngle(a, b, c)).coerceIn(0.0, 90.0)

    private fun jointAngle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Double {
        val bax = a.x() - b.x(); val bay = a.y() - b.y()
        val bcx = c.x() - b.x(); val bcy = c.y() - b.y()
        val denominator = sqrt(bax * bax + bay * bay) * sqrt(bcx * bcx + bcy * bcy)
        if (denominator == 0f) return 0.0
        return Math.toDegrees(acos(((bax * bcx + bay * bcy) / denominator).coerceIn(-1f, 1f).toDouble()))
    }

    private fun isVisible(p: NormalizedLandmark) =
        p.visibility().orElse(0f) >= 0.65f && p.presence().orElse(0f) >= 0.65f

    private fun isArmUsable(p: NormalizedLandmark) =
        p.visibility().orElse(0f) >= 0.15f && p.presence().orElse(0f) >= 0.50f
}
