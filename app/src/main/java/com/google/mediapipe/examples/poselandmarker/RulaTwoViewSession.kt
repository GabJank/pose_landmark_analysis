package com.google.mediapipe.examples.poselandmarker

/** The frontal and lateral images that belong to the same RULA assessment. */
data class RulaTwoViewSession(
    val frontal: PoseAngles? = null,
    val lateral: PoseAngles? = null
) {
    fun combinedAngles(): PoseAngles? {
        val front = frontal ?: return null
        val side = lateral ?: return null
        return side.copy(
            leftShoulder = if (side.leftArmReliable || !front.leftArmDetected) side.leftShoulder else front.leftShoulder,
            rightShoulder = if (side.rightArmReliable || !front.rightArmDetected) side.rightShoulder else front.rightShoulder,
            leftElbow = when {
                side.leftArmReliable -> side.leftElbow
                side.rightArmReliable -> side.rightElbow
                else -> front.leftElbow
            },
            rightElbow = when {
                side.rightArmReliable -> side.rightElbow
                side.leftArmReliable -> side.leftElbow
                else -> front.rightElbow
            },
            leftWrist = if (side.leftArmReliable || !front.leftArmDetected) side.leftWrist else front.leftWrist,
            rightWrist = if (side.rightArmReliable || !front.rightArmDetected) side.rightWrist else front.rightWrist,
            leftArmDetected = side.leftArmDetected || front.leftArmDetected,
            rightArmDetected = side.rightArmDetected || front.rightArmDetected,
            leftArmEstimatedFromFrontal = !side.leftArmReliable && front.leftArmDetected,
            rightArmEstimatedFromFrontal = !side.rightArmReliable && front.rightArmDetected,
            legsDetected = front.legsDetected || side.legsDetected,
            leftKnee = if (front.legsDetected) front.leftKnee else side.leftKnee,
            rightKnee = if (front.legsDetected) front.rightKnee else side.rightKnee,
            suggestedLeftArmAbducted = front.suggestedLeftArmAbducted,
            suggestedRightArmAbducted = front.suggestedRightArmAbducted,
            suggestedNeckSideBending = front.suggestedNeckSideBending,
            suggestedTrunkSideBending = front.suggestedTrunkSideBending
        )
    }
}
