package com.google.mediapipe.examples.poselandmarker

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

fun View.enablePhotoSwipe(canSwipe: () -> Boolean = { true }, onSwipe: (Int) -> Unit) {
    var startX = 0f
    var startY = 0f
    val minimum = 48 * resources.displayMetrics.density
    setOnTouchListener { view, event ->
        if (!canSwipe()) return@setOnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; true }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (abs(dx) >= minimum && abs(dx) > abs(dy) * 1.2f) onSwipe(if (dx < 0) 1 else -1)
                else view.performClick()
                true
            }
            else -> true
        }
    }
}
