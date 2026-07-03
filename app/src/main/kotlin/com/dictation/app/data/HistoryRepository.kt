package com.dictation.app.data

import android.content.Context
import java.io.File

object HistoryRepository {

    private val db: HistoryDb get() = HistoryDb.get()
    private val settings: Settings get() = Settings.get()

    /** Папка для аудиозаписей во внутреннем хранилище приложения. */
    fun audioDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "audio")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun newAudioFile(ctx: Context): File {
        val ts = System.currentTimeMillis()
        val ext = settings.getCodec().ext
        return File(audioDir(ctx), "rec_${ts}.$ext")
    }

    fun list(query: String?) = db.list(query)

    fun addItem(ts: Long, durationMs: Long, text: String, audioPath: String?, audioSize: Long): Long {
        return db.insert(ts, durationMs, text, audioPath, audioSize)
    }

    fun delete(id: Long) {
        val items = db.list(null).filter { it.id == id }
        items.firstOrNull()?.audioPath?.let { File(it).delete() }
        db.delete(id)
    }

    fun deleteAll() {
        val withPaths = db.deleteAll()
        withPaths.forEach { (_, path) -> path?.let { File(it).delete() } }
    }

    fun cleanupOldAudio() {
        val days = settings.getAutoDeleteDays()
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val items = db.olderThan(cutoff)
        items.forEach { (id, path) ->
            path?.let { File(it).delete() }
            db.delete(id)
        }
    }
}