package com.google.mediapipe.examples.poselandmarker

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssessmentHistoryStoreTest {
    @Test
    fun savesReopensExportsAndDeletesWithoutTouchingApplicationHistory() {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = testContext.cacheDir.canonicalFile
        val directory = File(cache, "assessment-history-test-${UUID.randomUUID()}").canonicalFile
        assertEquals(cache, directory.parentFile)
        assertTrue(directory.mkdir())
        val isolatedContext = object : ContextWrapper(testContext) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val record = SavedAssessment("record-test-1", 1700000000000L, "P1_TESTE", mapOf(
            "rula_score" to "3",
            "combinado_ombro_esquerdo_graus" to "12.5",
            "rula_action" to "Conferir \"postura\"; repetir",
            "carga_kg" to "0.0"
        ))

        try {
            val bitmap = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val photos = AssessmentPhotos(isolatedContext)
            photos.save(record.id, bitmap, emptyList(), bitmap, emptyList())
            assertTrue(photos.available(record.id))
            val preview = photos.loadPreview(record.id, 1)
            assertEquals(12, preview.width)
            assertEquals(16, preview.height)
            preview.recycle()
            bitmap.recycle()
            AssessmentHistoryStore(isolatedContext).use { it.save(record) }
            AssessmentHistoryStore(isolatedContext).use { store ->
                val restored = store.all()
                assertEquals(listOf(record), restored)
                val export = File(directory, "avaliacoes.csv")
                export.writer(Charsets.UTF_8).use { AssessmentCsv.write(restored, it) }
                val csv = export.readText(Charsets.UTF_8)
                assertTrue(csv.startsWith("\uFEFF\"id_avaliacao\";"))
                assertTrue(csv.contains("\"P1_TESTE\""))
                assertTrue(csv.contains("\"12,5\""))
                assertTrue(csv.contains("\"Conferir \"\"postura\"\"; repetir\""))
                store.delete(record.id)
            }
            photos.delete(record.id)
            assertTrue(!photos.available(record.id))
            AssessmentHistoryStore(isolatedContext).use { assertTrue(it.all().isEmpty()) }
            AssessmentHistoryStore(isolatedContext).use { store ->
                store.save(record)
                store.save(record.copy(id = "record-test-2", testCode = "P2_TESTE"))
                assertEquals(2, store.all().size)
                store.deleteAll()
            }
            AssessmentHistoryStore(isolatedContext).use { assertTrue(it.all().isEmpty()) }
            assertTrue(File(directory, "avaliacoes.csv").readText(Charsets.UTF_8).contains("\"P1_TESTE\""))
        } finally {
            // Restrict cleanup to this test's unique directory, never the real history.
            check(directory.canonicalFile.parentFile == cache && directory.name.startsWith("assessment-history-test-"))
            assertTrue(directory.deleteRecursively())
        }
    }
}
