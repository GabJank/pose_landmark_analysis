package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File

/** Private images belong to one saved assessment and are not part of the CSV. */
class AssessmentPhotos(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "assessment_images")
    private val names = listOf("frontal_original.jpg", "frontal_pontos.jpg", "lateral_original.jpg", "lateral_pontos.jpg")

    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return File(root, id)
    }

    fun save(id: String, frontal: Bitmap, frontPoints: List<List<NormalizedLandmark>>,
             lateral: Bitmap, sidePoints: List<List<NormalizedLandmark>>) {
        check(root.isDirectory || root.mkdirs()) { "Não foi possível criar a pasta de fotos." }
        val dir = directory(id)
        check(dir.mkdir()) { "Não foi possível criar a pasta da avaliação." }
        try {
            writePair(dir, "frontal", frontal, frontPoints)
            writePair(dir, "lateral", lateral, sidePoints)
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    private fun writePair(dir: File, prefix: String, bitmap: Bitmap, poses: List<List<NormalizedLandmark>>) {
        fun write(name: String, image: Bitmap) {
            File(dir, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        }
        write("${prefix}_original.jpg", bitmap)
        val marked = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true))
        try {
            val canvas = Canvas(marked)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.mp_color_primary)
                strokeWidth = (bitmap.width * 0.007f).coerceAtLeast(3f)
                style = Paint.Style.STROKE
            }
            val point = Paint(line).apply { color = Color.YELLOW; style = Paint.Style.FILL; strokeCap = Paint.Cap.ROUND }
            fun visible(p: NormalizedLandmark) = p.visibility().orElse(0f) >= 0.15f && p.presence().orElse(0f) >= 0.50f
            poses.forEach { pose ->
                PoseLandmarker.POSE_LANDMARKS.forEach { edge ->
                    val a = pose.getOrNull(edge.start())
                    val b = pose.getOrNull(edge.end())
                    if (a != null && b != null && visible(a) && visible(b))
                        canvas.drawLine(a.x() * bitmap.width, a.y() * bitmap.height,
                            b.x() * bitmap.width, b.y() * bitmap.height, line)
                }
                pose.filter(::visible).forEach { canvas.drawPoint(it.x() * bitmap.width, it.y() * bitmap.height, point) }
            }
            write("${prefix}_pontos.jpg", marked)
        } finally { marked.recycle() }
    }

    fun available(id: String): Boolean = names.all { File(directory(id), it).isFile }

    fun loadPreview(id: String, index: Int): Bitmap {
        val path = File(directory(id), names[index]).absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        return checkNotNull(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }))
    }

    fun delete(id: String) {
        val dir = directory(id)
        check(!dir.exists() || dir.deleteRecursively()) { "Não foi possível apagar as fotos." }
    }
}
