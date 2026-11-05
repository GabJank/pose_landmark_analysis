package com.google.mediapipe.examples.poselandmarker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.Collections

class AssessmentHistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    File(context.noBackupFilesDir, "assessment_history.db").absolutePath,
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE assessments (id TEXT PRIMARY KEY, saved_at INTEGER NOT NULL, test_code TEXT NOT NULL, fields TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Migração do histórico não disponível: $oldVersion para $newVersion")
    }

    fun save(record: SavedAssessment) {
        val values = ContentValues().apply {
            put("id", record.id)
            put("saved_at", record.savedAt)
            put("test_code", record.testCode)
            put("fields", JSONObject(record.values).toString())
        }
        writableDatabase.insertOrThrow("assessments", null, values)
    }

    fun all(): List<SavedAssessment> = readableDatabase.query(
        "assessments", arrayOf("id", "saved_at", "test_code", "fields"),
        null, null, null, null, "saved_at DESC, rowid DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val json = JSONObject(cursor.getString(3))
                val fields = linkedMapOf<String, String>()
                json.keys().forEach { key -> fields[key] = json.getString(key) }
                add(SavedAssessment(cursor.getString(0), cursor.getLong(1), cursor.getString(2),
                    Collections.unmodifiableMap(fields)))
            }
        }
    }

    fun delete(id: String) {
        writableDatabase.delete("assessments", "id = ?", arrayOf(id))
    }

    fun deleteAll() {
        writableDatabase.delete("assessments", null, null)
    }
}
