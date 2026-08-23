package com.abhiram.photoq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

data class CameraPhoto(val id: Long, val uri: String, val capturedAt: Long, val mime: String)
data class ResultRow(
    val id: Long,
    val questionNumber: String?,
    val answers: List<Int>,
    val answerText: String?,
    val raw: String,
    val imageUris: List<String>,
    val completedAt: Long,
    val status: String,
    val error: String?
)
data class QueueRow(val mediaId: Long, val uri: String, val capturedAt: Long, val state: String, val bundleId: String?)

class PhotoStore(context: Context) : SQLiteOpenHelper(context, "photoq.db", null, 1) {
    val changes = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE images(
                media_id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                mime TEXT NOT NULL,
                state TEXT NOT NULL,
                bundle_id TEXT,
                updated_at INTEGER NOT NULL,
                error TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE results(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                question_number TEXT,
                answers_json TEXT NOT NULL,
                answer_text TEXT,
                raw TEXT NOT NULL,
                image_uris_json TEXT NOT NULL,
                completed_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                error TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_results_completed ON results(completed_at DESC)")
        db.execSQL("CREATE INDEX idx_images_state ON images(state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun claimBundle(photos: List<CameraPhoto>, bundleId: String): Boolean {
        if (photos.isEmpty()) return false
        val db = writableDatabase
        var claimed = false
        db.beginTransaction()
        try {
            var collision = false
            for (p in photos) {
                val exists = db.rawQuery("SELECT 1 FROM images WHERE media_id=?", arrayOf(p.id.toString())).use { it.moveToFirst() }
                if (exists) { collision = true; break }
            }
            if (!collision) {
                val now = System.currentTimeMillis()
                for (p in photos) {
                    val v = ContentValues().apply {
                        put("media_id", p.id); put("uri", p.uri); put("captured_at", p.capturedAt)
                        put("mime", p.mime); put("state", "WAITING"); put("bundle_id", bundleId)
                        put("updated_at", now)
                    }
                    db.insertOrThrow("images", null, v)
                }
                db.setTransactionSuccessful()
                claimed = true
            }
        } finally { db.endTransaction() }
        if (claimed) bump()
        return claimed
    }

    @Synchronized
    fun setBundleState(photos: List<CameraPhoto>, state: String, error: String? = null) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            photos.forEach { p ->
                val v = ContentValues().apply {
                    put("state", state); put("updated_at", now)
                    if (error == null) putNull("error") else put("error", error)
                }
                db.update("images", v, "media_id=?", arrayOf(p.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump()
    }

    fun insertResult(question: String?, answers: List<Int>, answerText: String?, raw: String,
                     imageUris: List<String>, status: String = "DONE", error: String? = null) {
        val v = ContentValues().apply {
            if (question == null) putNull("question_number") else put("question_number", question)
            put("answers_json", JSONArray(answers).toString())
            if (answerText == null) putNull("answer_text") else put("answer_text", answerText)
            put("raw", raw); put("image_uris_json", JSONArray(imageUris).toString())
            put("completed_at", System.currentTimeMillis()); put("status", status)
            if (error == null) putNull("error") else put("error", error)
        }
        writableDatabase.insert("results", null, v)
        bump()
    }

    fun results(): List<ResultRow> {
        val out = mutableListOf<ResultRow>()
        readableDatabase.rawQuery("SELECT id,question_number,answers_json,answer_text,raw,image_uris_json,completed_at,status,error FROM results ORDER BY completed_at DESC,id DESC", null).use { c ->
            while (c.moveToNext()) {
                val a = JSONArray(c.getString(2)); val answers = (0 until a.length()).map { a.getInt(it) }
                val u = JSONArray(c.getString(5)); val uris = (0 until u.length()).map { u.getString(it) }
                out += ResultRow(c.getLong(0), c.getString(1), answers, c.getString(3), c.getString(4), uris, c.getLong(6), c.getString(7), c.getString(8))
            }
        }
        return out
    }

    fun queue(): List<QueueRow> {
        val out = mutableListOf<QueueRow>()
        readableDatabase.rawQuery("SELECT media_id,uri,captured_at,state,bundle_id FROM images ORDER BY updated_at DESC", null).use { c ->
            while (c.moveToNext()) out += QueueRow(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4))
        }
        return out
    }

    fun pendingBundles(): List<List<CameraPhoto>> {
        val map = linkedMapOf<String, MutableList<CameraPhoto>>()
        readableDatabase.rawQuery("SELECT media_id,uri,captured_at,mime,bundle_id FROM images WHERE state IN ('WAITING','RETRY') ORDER BY captured_at ASC", null).use { c ->
            while (c.moveToNext()) {
                val bundle = c.getString(4) ?: "single-${c.getLong(0)}"
                map.getOrPut(bundle) { mutableListOf() } += CameraPhoto(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3))
            }
        }
        return map.values.toList()
    }

    fun retryMedia(mediaId: Long): List<CameraPhoto> {
        val bundle = readableDatabase.rawQuery("SELECT bundle_id FROM images WHERE media_id=?", arrayOf(mediaId.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return emptyList()
        val out = mutableListOf<CameraPhoto>()
        readableDatabase.rawQuery("SELECT media_id,uri,captured_at,mime FROM images WHERE bundle_id=?", arrayOf(bundle)).use { c ->
            while (c.moveToNext()) out += CameraPhoto(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3))
        }
        if (out.isNotEmpty()) setBundleState(out, "RETRY")
        return out
    }

    fun deleteResult(id: Long) { writableDatabase.delete("results", "id=?", arrayOf(id.toString())); bump() }

    private fun bump() { changes.value = changes.value + 1 }
}
