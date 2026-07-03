package com.dictation.app.services

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.dictation.app.data.HistoryRepository
import com.dictation.app.data.Settings
import java.io.File

/**
 * Управление записью аудио в один файл с возможностью паузы/продолжения.
 * После вызова [stop] файл закрывается, и контроллер готов к новой сессии.
 */
class RecordingController(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMs: Long = 0
    private var accumulatedMs: Long = 0
    private var state: State = State.IDLE

    enum class State { IDLE, RECORDING, PAUSED }

    data class SessionResult(val file: File, val durationMs: Long, val sizeBytes: Long)

    fun state(): State = state

    fun durationMs(): Long = when (state) {
        State.RECORDING -> accumulatedMs + (System.currentTimeMillis() - startTimeMs)
        State.PAUSED -> accumulatedMs
        State.IDLE -> 0
    }

    @Suppress("DEPRECATION")
    fun start(): File {
        if (state != State.IDLE) error("recorder not idle")
        val codec = Settings.get().getCodec()
        val file = HistoryRepository.newAudioFile(context)

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context)
        else
            MediaRecorder()

        // Конфигурация выхода в зависимости от кодека
        when (codec) {
            Settings.Codec.OPUS_32, Settings.Codec.OPUS_64 -> {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioEncodingBitRate(codec.bitrate)
                r.setAudioSamplingRate(48000)
                r.setAudioChannels(1)
            }
            Settings.Codec.AAC_64 -> {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(codec.bitrate)
                r.setAudioSamplingRate(44100)
                r.setAudioChannels(1)
            }
            Settings.Codec.AMR_WB -> {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.AMR_WB)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
                r.setAudioEncodingBitRate(codec.bitrate)
                r.setAudioSamplingRate(16000)
                r.setAudioChannels(1)
            }
        }
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()

        recorder = r
        currentFile = file
        startTimeMs = System.currentTimeMillis()
        accumulatedMs = 0
        state = State.RECORDING
        return file
    }

    fun pause() {
        val r = recorder ?: return
        if (state != State.RECORDING) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            r.pause()
            accumulatedMs += System.currentTimeMillis() - startTimeMs
            state = State.PAUSED
        }
    }

    fun resume() {
        val r = recorder ?: return
        if (state != State.PAUSED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            r.resume()
            startTimeMs = System.currentTimeMillis()
            state = State.RECORDING
        }
    }

    /** Останавливает запись и возвращает результат сессии. */
    fun stop(): SessionResult? {
        val r = recorder ?: return null
        val file = currentFile ?: return null
        if (state == State.RECORDING) {
            accumulatedMs += System.currentTimeMillis() - startTimeMs
        }
        val durationMs = accumulatedMs
        try {
            r.stop()
        } catch (_: Exception) { /* если запись слишком короткая — игнорируем */ }
        try { r.reset() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        recorder = null
        currentFile = null
        accumulatedMs = 0
        startTimeMs = 0
        state = State.IDLE
        if (!file.exists() || file.length() == 0L) return null
        return SessionResult(file, durationMs, file.length())
    }

    /** Сбросить без сохранения (отмена). */
    fun cancel() {
        val r = recorder ?: return
        val file = currentFile
        try { r.stop() } catch (_: Exception) {}
        try { r.reset() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        recorder = null
        currentFile = null
        accumulatedMs = 0
        startTimeMs = 0
        state = State.IDLE
        file?.delete()
    }
}