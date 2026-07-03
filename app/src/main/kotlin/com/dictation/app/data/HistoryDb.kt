package com.dictation.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dictation.app.DictationApp

/**
 * Простая обёртка над SQLite. Без Room — чтобы не тянуть kapt/ksp и
 * ускорить сборку.
 */
class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                text TEXT NOT NULL,
                audio_path TEXT,
                audio_size INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ts ON items(ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun insert(ts: Long, durationMs: Long, text: String, audioPath: String?, audioSize: Long): Long {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("duration_ms", durationMs)
            put("text", text)
            put("audio_path", audioPath)
            put("audio_size", audioSize)
        }
        return writableDatabase.insert("items", null, cv)
    }

    fun delete(id: Long) {
        writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    }

    fun deleteAll(): List<Pair<Long, String?>> {
        // возвращаем пути к аудио для последующего удаления файлов
        val cursor = readableDatabase.rawQuery("SELECT id, audio_path FROM items", null)
        val paths = mutableListOf<Pair<Long, String?>>()
        cursor.use {
            while (it.moveToNext()) {
                paths.add(it.getLong(0) to (if (it.isNull(1)) null else it.getString(1)))
            }
        }
        writableDatabase.delete("items", null, null)
        return paths
    }

    fun list(query: String?): List<HistoryItem> {
        val items = mutableListOf<HistoryItem>()
        val sql = if (query.isNullOrBlank())
            "SELECT id, ts, duration_ms, text, audio_path, audio_size FROM items ORDER BY ts DESC"
        else
            "SELECT id, ts, duration_ms, text, audio_path, audio_size FROM items WHERE text LIKE ? ORDER BY ts DESC"
        val args = if (query.isNullOrBlank()) null else arrayOf("%$query%")
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                items.add(
                    HistoryItem(
                        id = c.getLong(0),
                        ts = c.getLong(1),
                        durationMs = c.getLong(2),
                        text = c.getString(3),
                        audioPath = if (c.isNull(4)) null else c.getString(4),
                        audioSize = if (c.isNull(5)) 0 else c.getLong(5)
                    )
                )
            }
        }
        return items
    }

    fun olderThan(ts: Long): List<Pair<Long, String?>> {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, audio_path FROM items WHERE ts < ?", arrayOf(ts.toString())
        )
        val res = mutableListOf<Pair<Long, String?>>()
        cursor.use {
            while (it.moveToNext()) res.add(it.getLong(0) to if (it.isNull(1)) null else it.getString(1))
        }
        return res
    }

    companion object {
        @Volatile private var INSTANCE: HistoryDb? = null
        fun get(): HistoryDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: HistoryDb(DictationApp.instance).also { INSTANCE = it }
        }
    }
}

data class HistoryItem(
    val id: Long,
    val ts: Long,
    val durationMs: Long,
    val text: String,
    val audioPath: String?,
    val audioSize: Long
)