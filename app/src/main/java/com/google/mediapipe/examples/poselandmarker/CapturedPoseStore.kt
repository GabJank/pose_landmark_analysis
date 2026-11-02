package com.google.mediapipe.examples.poselandmarker

import android.graphics.Bitmap
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

data class CapturedPoseFrame(
    val bitmap: Bitmap,
    val result: PoseLandmarkerResult,
    val angles: PoseAngles
)

/** Short-lived handoff between the embedded camera and the assessment screen. */
object CapturedPoseStore {
    private val frames = mutableMapOf<String, CapturedPoseFrame>()

    @Synchronized
    fun put(key: String, frame: CapturedPoseFrame) {
        frames[key] = frame
    }

    @Synchronized
    fun remove(key: String): CapturedPoseFrame? = frames.remove(key)
}
