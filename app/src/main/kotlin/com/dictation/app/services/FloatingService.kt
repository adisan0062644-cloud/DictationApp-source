package com.dictation.app.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dictation.app.DictationApp
import com.dictation.app.R
import com.dictation.app.api.GeminiClient
import com.dictation.app.data.HistoryRepository
import com.dictation.app.data.Settings
import com.dictation.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var recorder: RecordingController

    private var handleView: View? = null
    private var panelView: View? = null

    private var handleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    // UI элементы панели
    private var recBtn: FrameLayout? = null
    private var pauseBtn: ImageButton? = null
    private var stopBtn: ImageButton? = null
    private var closeBtn: ImageButton? = null
    private var openAppBtn: ImageButton? = null
    private var timerInBtn: TextView? = null
    private var labelInBtn: TextView? = null
    private var timerBig: TextView? = null
    private var resultText: TextView? = null
    private var panelRoot: LinearLayout? = null

    // Состояние UI
    private var panelOpen = false

    // Защита стопа (долгий тап)
    private var stopHoldRunnable: Runnable? = null
    private var stopHoldTriggered = false

    // Таймер
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateTimer()
            tickHandler.postDelayed(this, 100)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        recorder = RecordingController(this)
        startInForeground()
        showHandle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_PANEL -> showPanel()
            ACTION_HIDE_PANEL -> hidePanel()
            ACTION_TOGGLE -> togglePanel()
            ACTION_REC -> onRecClick()
            ACTION_PAUSE -> onPauseClick()
            ACTION_STOP -> onStopClick(force = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tickRunnable)
        try { recorder.cancel() } catch (_: Exception) {}
        removeHandle()
        removePanel()
    }

    private fun startInForeground() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, DictationApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notify_recording))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Android 14 требует указания foregroundServiceType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                return
            } catch (e: Exception) {
                // fallthrough
            }
        }
        startForeground(NOTIF_ID, notification)
    }

    // ---------- Якорь ----------

    private fun showHandle() {
        if (handleView != null) return
        if (!hasOverlayPermission()) {
            stopSelf()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.handle_overlay, null) as FrameLayout
        val s = Settings.get()

        val size = dp(48)
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        // Восстановим позицию из настроек
        val (x, y) = rememberedPosition()
        params.x = x
        params.y = y

        view.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0
            var rawX = 0f; var rawY = 0f
            var moved = false
            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        rawX = ev.rawX; rawY = ev.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - rawX
                        val dy = ev.rawY - rawY
                        if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true
                        if (moved) {
                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt()
                            clampToScreen(params)
                            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        else savePosition(params.x, params.y)
                    }
                }
                return true
            }
        })

        try {
            windowManager.addView(view, params)
            handleView = view
            handleParams = params
        } catch (e: Exception) {
            // нет разрешения
        }
    }

    private fun removeHandle() {
        handleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        handleView = null
    }

    // ---------- Панель ----------

    private fun togglePanel() { if (panelOpen) hidePanel() else showPanel() }

    private fun showPanel() {
        if (!hasOverlayPermission()) { requestOverlay(); return }
        if (!hasMicPermission()) { Toast.makeText(this, R.string.perm_audio_text, Toast.LENGTH_LONG).show(); return }
        if (panelView != null) {
            panelView!!.visibility = View.VISIBLE
            panelOpen = true
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.panel_overlay, null) as LinearLayout
        panelRoot = view

        recBtn = view.findViewById(R.id.recBtn)
        pauseBtn = view.findViewById(R.id.pauseBtn)
        stopBtn = view.findViewById(R.id.stopBtn)
        closeBtn = view.findViewById(R.id.closeBtn)
        openAppBtn = view.findViewById(R.id.openAppBtn)
        timerInBtn = view.findViewById(R.id.timerInBtn)
        labelInBtn = view.findViewById(R.id.labelInBtn)
        timerBig = view.findViewById(R.id.timerBig)
        resultText = view.findViewById(R.id.resultText)

        recBtn?.setOnClickListener { onRecClick() }
        pauseBtn?.setOnClickListener { onPauseClick() }
        closeBtn?.setOnClickListener { hidePanel() }
        openAppBtn?.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(i)
        }

        // Защита стопа: длинный тап если включена
        stopBtn?.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        stopHoldTriggered = false
                        if (Settings.get().isStopProtect()) {
                            stopHoldRunnable = Runnable {
                                stopHoldTriggered = true
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                onStopClick(force = true)
                            }
                            v.postDelayed(stopHoldRunnable, 2000)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopHoldRunnable?.let { v.removeCallbacks(it) }
                        stopHoldRunnable = null
                        if (!Settings.get().isStopProtect() && !stopHoldTriggered) {
                            // короткий тап → стоп
                            onStopClick(force = true)
                        }
                    }
                }
                return true
            }
        })

        val params = buildPanelParams()
        try {
            windowManager.addView(view, params)
            panelView = view
            panelParams = params
            panelOpen = true
            updateControlsState()
        } catch (e: Exception) {
            // overlay не разрешён
        }
    }

    private fun hidePanel() {
        panelView?.let { it.visibility = View.GONE }
        panelOpen = false
    }

    private fun removePanel() {
        panelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panelView = null
        panelRoot = null
        panelOpen = false
    }

    private fun buildPanelParams(): WindowManager.LayoutParams {
        val s = Settings.get()
        val size = when (s.getSize()) {
            Settings.Size.S -> 300
            Settings.Size.M -> 340
            Settings.Size.L -> 400
        }
        val (w, h) = if (s.getOrient() == Settings.Orient.VERTICAL) {
            Pair(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            Pair(dp(size), dp(64))
        }
        val params = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = if (s.getOrient() == Settings.Orient.VERTICAL)
            Gravity.TOP or Gravity.END
        else
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.x = if (s.getOrient() == Settings.Orient.VERTICAL) 0 else 0
        params.y = if (s.getOrient() == Settings.Orient.VERTICAL) dp(120) else dp(24)
        return params
    }

    // ---------- Управление записью ----------

    private fun onRecClick() {
        when (recorder.state()) {
            RecordingController.State.IDLE -> {
                try {
                    recorder.start()
                    tickHandler.post(tickRunnable)
                    updateControlsState()
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось начать запись: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            RecordingController.State.RECORDING -> {
                recorder.pause()
                updateControlsState()
            }
            RecordingController.State.PAUSED -> {
                recorder.resume()
                updateControlsState()
            }
        }
    }

    private fun onPauseClick() {
        when (recorder.state()) {
            RecordingController.State.RECORDING -> recorder.pause()
            RecordingController.State.PAUSED -> recorder.resume()
            else -> {}
        }
        updateControlsState()
    }

    private fun onStopClick(force: Boolean) {
        if (recorder.state() == RecordingController.State.IDLE) return
        tickHandler.removeCallbacks(tickRunnable)
        val session = recorder.stop() ?: run {
            updateControlsState()
            return
        }
        showResultInPanel("Распознаю…")
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { session.file.readBytes() }
            val mime = Settings.get().getCodec().mime
            val r = withContext(Dispatchers.IO) { GeminiClient().transcribe(mime, bytes) }
            when (r) {
                is GeminiClient.Result.Ok -> {
                    showResultInPanel(r.text)
                    if (Settings.get().isAutoCopy()) copyToClipboard(r.text)
                    withContext(Dispatchers.IO) {
                        HistoryRepository.addItem(
                            ts = System.currentTimeMillis(),
                            durationMs = session.durationMs,
                            text = r.text,
                            audioPath = session.file.absolutePath,
                            audioSize = session.sizeBytes
                        )
                        HistoryRepository.cleanupOldAudio()
                    }
                }
                is GeminiClient.Result.Err -> {
                    showResultInPanel("Ошибка: ${r.message}")
                }
            }
            updateControlsState()
        }
    }

    private fun showResultInPanel(text: String) {
        resultText?.apply {
            this.text = text
            this.visibility = View.VISIBLE
        }
        // Таймер спрячем, чтобы не накладывался
        timerBig?.visibility = View.GONE
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dictation", text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun updateTimer() {
        val ms = recorder.durationMs()
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        val ds = (ms % 1000) / 100
        timerInBtn?.text = String.format("%02d:%02d", m, s)
        timerBig?.text = String.format("%02d:%02d.%d", m, s, ds)
    }

    private fun updateControlsState() {
        val s = recorder.state()
        when (s) {
            RecordingController.State.IDLE -> {
                labelInBtn?.text = getString(R.string.rec)
                timerBig?.visibility = View.VISIBLE
                resultText?.visibility = View.GONE
                timerInBtn?.text = "00:00"
                timerBig?.text = "00:00.0"
                pauseBtn?.setImageResource(R.drawable.ic_pause)
                tickHandler.removeCallbacks(tickRunnable)
            }
            RecordingController.State.RECORDING -> {
                labelInBtn?.text = getString(R.string.rec)
                pauseBtn?.setImageResource(R.drawable.ic_pause)
                resultText?.visibility = View.GONE
                timerBig?.visibility = View.VISIBLE
            }
            RecordingController.State.PAUSED -> {
                labelInBtn?.text = getString(R.string.pause)
                pauseBtn?.setImageResource(R.drawable.ic_play)
                resultText?.visibility = View.GONE
                timerBig?.visibility = View.VISIBLE
            }
        }
    }

    // ---------- Хелперы ----------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun clampToScreen(params: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val maxX = dm.widthPixels - params.width
        val maxY = dm.heightPixels - params.height
        if (params.x < 0) params.x = 0
        if (params.y < 0) params.y = 0
        if (params.x > maxX) params.x = maxX
        if (params.y > maxY) params.y = maxY
    }

    private fun rememberedPosition(): Pair<Int, Int> {
        val s = Settings.get()
        val ax = s.getHandleX()
        val ay = s.getHandleY()
        if (ax >= 0 && ay >= 0) return ax to ay
        // дефолт — левый край по центру
        return when (s.getAnchor()) {
            Settings.Anchor.LEFT -> 16 to -1
            Settings.Anchor.RIGHT -> -1 to -1
            Settings.Anchor.REMEMBER -> 16 to -1
        }
    }

    private fun savePosition(x: Int, y: Int) {
        val s = Settings.get()
        if (s.getAnchor() == Settings.Anchor.REMEMBER) {
            s.setHandleX(x); s.setHandleY(y)
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            SystemSettings.canDrawOverlays(this)
        } else true
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestOverlay() {
        Toast.makeText(this, R.string.perm_overlay_text, Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val i = Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(i)
        }
    }

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_SHOW_PANEL = "show_panel"
        const val ACTION_HIDE_PANEL = "hide_panel"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_REC = "rec"
        const val ACTION_PAUSE = "pause"
        const val ACTION_STOP = "stop"

        fun start(context: Context) {
            val i = Intent(context, FloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingService::class.java))
        }
    }
}