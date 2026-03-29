package com.example.jemi_live

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import com.google.ai.client.generativeai.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import com.jemi.live.logic.TicketManager
import kotlinx.coroutines.flow.collect

import com.jemi.live.model.JemiResponse
import kotlinx.serialization.json.Json

/**
 * JemiCaptureService (v3.3.0 - Context Refinement)
 * - [Improve] あらすじ（lastSummary）の重み付けを調整。
 * - [Improve] プロンプトに「変化の検知」と「語彙の多様性」を指示。
 * - [Restored] 既存の全てのロジックを完全維持。
 */
class JemiCaptureService : Service() {
    private lateinit var mainWindowManager: WindowManager
    private lateinit var uiWindowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var recognitionFrameView: FrameLayout
    private lateinit var frameParams: WindowManager.LayoutParams
    private lateinit var tvCommentary: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnSpecial: Button
    private lateinit var previewView: ImageView

    private var tvTicket1: TextView? = null
    private var tvTicket2: TextView? = null
    private var tvTicket3: TextView? = null
    private var tvTicket4: TextView? = null

    private val ticketManager = TicketManager()

    private var lastSummary: String = ""

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private var llMiniWindow: View? = null

    private var singleControlView: View? = null
    private var singleCommentaryView: View? = null
    private var singlePreviewView: View? = null
    private var dualCockpitView: View? = null

    private var lastCaptureTime = 0L
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCaptureRequested = false

    private var isHighResRequested = false
    private var pendingActionType = "commentary"

    private var isGeminiThinking = false
    private var currentPreviewBitmap: Bitmap? = null

    private val imageBuffer = mutableListOf<Bitmap>()
    private val maxBufferSize = 6

    private var autoCaptureJob: Job? = null
    private lateinit var jemiVoice: JemiVoiceManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val cachedCropRect = Rect()
    private var generativeModel: GenerativeModel? = null
    private var customOmajinai: String = ""

    private var isDebugMode = false
    private var isSingleMode = false
    private var captureAreaMode = "manual"
    private var isFrameAdjustMode = false
    private var isTranslationEnabled = false

    private var captureIntervalMs = 2000L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("JemiSettings", MODE_PRIVATE)
        apiKeyManager = ApiKeyManager(this)
        jemiVoice = JemiVoiceManager(this)

        captureIntervalMs = prefs.getLong("capture_interval", 2000L)

        mainWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        uiWindowManager = mainWindowManager

        ticketManager.startRefillSystem()

        setupFloatingWindows()
    }

    fun isAndroidStudioEmulator(): Boolean {
        return Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
    }

    private fun setupFloatingWindows() {
        isSingleMode = prefs.getBoolean("is_single_display_mode", false)
        val areaId = prefs.getInt("last_capture_area_id", R.id.rb_area_manual)
        captureAreaMode = when(areaId) {
            R.id.rb_area_4_3 -> "4_3"
            R.id.rb_area_16_9 -> "16_9"
            else -> "manual"
        }

        if (!isSingleMode) {
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val displays = displayManager.displays
            for (display in displays) {
                if (display.displayId != Display.DEFAULT_DISPLAY) {
                    val displayContext = createDisplayContext(display)
                    val windowContext = displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    uiWindowManager = windowContext.getSystemService(WindowManager::class.java)
                    break
                }
            }
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val windowMetrics = wm.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val screenW = bounds.width()
        val screenH = bounds.height()
        val frameW = prefs.getInt("frame_w", (screenH * 4 / 3).coerceAtMost(screenW))
        val frameH = prefs.getInt("frame_h", screenH.coerceAtMost((frameW * 3 / 4)))

        frameParams = WindowManager.LayoutParams(
            frameW, frameH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = prefs.getInt("frame_x", 0); y = prefs.getInt("frame_y", 0) }

        recognitionFrameView = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val initialAlpha = if (captureAreaMode == "manual") 0.3f else 0.0f
            addView(View(context).apply {
                tag = "left_line"
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
                setBackgroundColor(Color.GREEN); alpha = initialAlpha
            })
            addView(View(context).apply {
                tag = "right_line"
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
                setBackgroundColor(Color.GREEN); alpha = initialAlpha
            })
        }
        mainWindowManager.addView(recognitionFrameView, frameParams)

        if (captureAreaMode != "manual") calculateAutoCropRect(screenW, screenH)

        if (isSingleMode) setupSingleModeUI() else setupDualModeCockpitUI()

        setupDraggable(recognitionFrameView, frameParams, mainWindowManager, iT=true, iB=false, iL=true) {
            if (isFrameAdjustMode) { updateCropCache(); saveCoords("frame", frameParams) }
        }

        startForegroundService()

        if (isAndroidStudioEmulator()&& BuildConfig.DEBUG) {
            setupForceUpdateView()
        }

        handler.postDelayed({ updateCropCache() }, 500)
    }

    private fun setupForceUpdateView() {
        val forceUpdateView = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0.01f
        }

        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        try {
            mainWindowManager.addView(forceUpdateView, params)

            serviceScope.launch {
                var toggle = false
                while (isActive) {
                    forceUpdateView.rotation = if (toggle) 0f else 1f
                    toggle = !toggle
                    delay(200)
                }
            }
            Log.d("Jemi-Live", "👻 ステルス・ハートビート起動（エミュレータ対策）")
        } catch (e: Exception) {
            Log.e("Jemi-Live", "UpdateViewの追加に失敗", e)
        }
    }

    private fun toggleFrameAdjustMode() {
        isFrameAdjustMode = !isFrameAdjustMode
        if (isFrameAdjustMode) {
            frameParams.flags = frameParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            frameParams.flags = frameParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        mainWindowManager.updateViewLayout(recognitionFrameView, frameParams)
        val targetAlpha = if (isFrameAdjustMode) 0.8f else 0.3f
        recognitionFrameView.findViewWithTag<View>("left_line")?.alpha = targetAlpha
        recognitionFrameView.findViewWithTag<View>("right_line")?.alpha = targetAlpha
        Toast.makeText(this, if (isFrameAdjustMode) "枠の調整ができるよっ🔧" else "枠を固定したよっ🔒", Toast.LENGTH_SHORT).show()
    }

    private fun calculateAutoCropRect(screenW: Int, screenH: Int) {
        when (captureAreaMode) {
            "4_3" -> {
                val targetW = (screenH * 4 / 3)
                val offsetX = (screenW - targetW) / 2
                cachedCropRect.set(offsetX, 0, offsetX + targetW, screenH)
            }
            "16_9" -> {
                cachedCropRect.set(0, 0, screenW, screenH)
            }
        }
    }

    private fun updateCropCache() {
        if (captureAreaMode != "manual") return
        val location = IntArray(2)
        recognitionFrameView.getLocationOnScreen(location)
        if (recognitionFrameView.width > 0 && recognitionFrameView.height > 0) {
            cachedCropRect.set(location[0], location[1], location[0] + recognitionFrameView.width, location[1] + recognitionFrameView.height)
        }
    }

    private fun saveCoords(prefix: String, params: WindowManager.LayoutParams) {
        prefs.edit().apply {
            putInt("${prefix}_x", params.x); putInt("${prefix}_y", params.y)
            if (prefix == "frame") { putInt("${prefix}_w", params.width); putInt("${prefix}_h", params.height) }
            apply()
        }
    }

    @SuppressLint("InflateParams")
    private fun setupSingleModeUI() {
        val controlParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.END; x = prefs.getInt("ctrl_x", 8); y = prefs.getInt("ctrl_y", 8) }
        singleControlView = LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        btnCapture = singleControlView!!.findViewById(R.id.btn_floating_capture)
        val btnEdit = singleControlView!!.findViewById<Button>(R.id.btn_floating_edit_frame)
        val btnClose = singleControlView!!.findViewById<Button>(R.id.btn_floating_close)
        val btnTogglePreview = singleControlView!!.findViewById<Button>(R.id.btn_floating_toggle_preview)

        if (captureAreaMode != "manual") btnEdit.visibility = View.GONE
        btnEdit.setOnClickListener { toggleFrameAdjustMode() }
        btnTogglePreview?.setOnClickListener {
            if (singlePreviewView != null) {
                singlePreviewView!!.visibility = if (singlePreviewView!!.isVisible) View.GONE else View.VISIBLE
            }
        }
        setupButtonActions(btnClose)
        uiWindowManager.addView(singleControlView, controlParams)

        val previewParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 16 }
        singlePreviewView = LayoutInflater.from(this).inflate(R.layout.layout_floating_preview, null)
        previewView = singlePreviewView as ImageView
        previewView.visibility = View.GONE

        previewView.setOnLongClickListener {
            saveGatchankoToFile(currentPreviewBitmap)
            true
        }

        uiWindowManager.addView(singlePreviewView, previewParams)

        val commParams = WindowManager.LayoutParams(200.toPx(), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = prefs.getInt("comm_x", 0); y = prefs.getInt("comm_y", 0) }
        singleCommentaryView = LayoutInflater.from(this).inflate(R.layout.layout_floating_commentary, null)
        tvCommentary = singleCommentaryView!!.findViewById(R.id.tv_floating_commentary)

        uiWindowManager.addView(singleCommentaryView, commParams)
        setupDraggable(singleControlView!!, controlParams, uiWindowManager, iT=false, iB=true, iL=false) { saveCoords("ctrl", controlParams) }
        setupDraggable(singleCommentaryView!!, commParams, uiWindowManager, iT=true, iB=false, iL=false) { saveCoords("comm", commParams) }
    }

    @SuppressLint("InflateParams", "DefaultLocale")
    private fun setupDualModeCockpitUI() {
        val cockpitParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        dualCockpitView = LayoutInflater.from(this).inflate(R.layout.layout_cockpit_dashboard, null)
        tvCommentary = dualCockpitView!!.findViewById(R.id.tv_cockpit_commentary)
        previewView = dualCockpitView!!.findViewById(R.id.iv_cockpit_preview)

        previewView.setOnLongClickListener {
            saveGatchankoToFile(currentPreviewBitmap)
            true
        }

        btnCapture = dualCockpitView!!.findViewById(R.id.btn_cockpit_capture)
        btnSpecial = dualCockpitView!!.findViewById(R.id.btn_cockpit_special)
        val btnEdit = dualCockpitView!!.findViewById<Button>(R.id.btn_cockpit_edit_frame)
        val btnClose = dualCockpitView!!.findViewById<Button>(R.id.btn_cockpit_close)

        tvTicket1 = dualCockpitView!!.findViewById(R.id.tv_ticket_1)
        tvTicket2 = dualCockpitView!!.findViewById(R.id.tv_ticket_2)
        tvTicket3 = dualCockpitView!!.findViewById(R.id.tv_ticket_3)
        tvTicket4 = dualCockpitView!!.findViewById(R.id.tv_ticket_4)

        serviceScope.launch {
            ticketManager.ticketCount.collect { count ->
                updateTicketUI(count)
            }
        }

        llMiniWindow = dualCockpitView!!.findViewById(R.id.ll_mini_window)

        setupSpecialMenuListeners()

        val sbInterval = dualCockpitView!!.findViewById<SeekBar>(R.id.sb_cockpit_interval)
        val tvInterval = dualCockpitView!!.findViewById<TextView>(R.id.tv_cockpit_interval)

        val currentProgress = ((captureIntervalMs - 1000) / 100).toInt()
        sbInterval.progress = currentProgress
        tvInterval.text = String.format("撮影: %.1fs", captureIntervalMs / 1000f)

        sbInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newInterval = 1000L + progress * 100L
                captureIntervalMs = newInterval
                tvInterval.text = String.format("撮影: %.1fs", newInterval / 1000f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit { putLong("capture_interval", captureIntervalMs) }
                Log.d("Jemi-Live", "⏱️ 撮影間隔を ${captureIntervalMs}ms に変更したよっ！")
            }
        })

        if (captureAreaMode != "manual") btnEdit.visibility = View.GONE
        btnEdit.setOnClickListener { toggleFrameAdjustMode() }
        setupButtonActions(btnClose)
        uiWindowManager.addView(dualCockpitView, cockpitParams)
    }

    private fun updateTicketUI(count: Int) {
        val tickets = listOf(tvTicket1, tvTicket2, tvTicket3, tvTicket4)
        tickets.forEachIndexed { index, textView ->
            textView?.alpha = if (index < count) 1.0f else 0.2f
        }
        updateCaptureButtonState()
    }

    private fun setupSpecialMenuListeners() {
        btnSpecial.setOnClickListener {
            if (llMiniWindow?.visibility == View.VISIBLE) {
                closeAllOverlays()
            } else {
                llMiniWindow?.visibility = View.VISIBLE
            }
        }

        val actions = mapOf(
            R.id.btn_menu_translate to "translate",
            R.id.btn_menu_guide to "guide",
            R.id.btn_menu_status to "status",
            R.id.btn_menu_lore to "lore",
            R.id.btn_menu_trivia to "trivia"
        )

        actions.forEach { (id, type) ->
            dualCockpitView?.findViewById<Button>(id)?.setOnClickListener {
                if (checkCooldownOnlyThinking()) return@setOnClickListener

                if (ticketManager.consumeTicket()) {
                    triggerHighResAction(type)
                    closeAllOverlays()
                } else {
                    Toast.makeText(this, "チケットが足りないのですよぉ🔋 回復を待ってねっ！", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun closeAllOverlays() {
        llMiniWindow?.visibility = View.GONE
    }

    private fun checkCooldownOnlyThinking(): Boolean {
        if (isGeminiThinking) {
            Toast.makeText(this, "ジェミ、まだ考え中だよぉ🤔 終わるまで待ってねっ♪", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun triggerHighResAction(type: String) {
        pendingActionType = type
        isHighResRequested = true
        isCaptureRequested = true
        Log.d("Jemi-Live", "✨ アクション予約: $type (高解像度モード)")
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiTitles() {
        val suffix = if (isDebugMode) " (DEBUG MODE)" else ""
        dualCockpitView?.findViewById<TextView>(R.id.tv_cockpit_title)?.text = "JEMI-LIVE COCKPIT$suffix"
        singleCommentaryView?.findViewById<TextView>(R.id.tv_commentary_title)?.text = "LIVE COMMENTARY$suffix"
    }

    private fun updateBufferPreviewUI() {
        if (isSingleMode || dualCockpitView == null) return

        val container = dualCockpitView?.findViewById<LinearLayout>(R.id.ll_buffer_preview) ?: return

        handler.post {
            container.removeAllViews()
            synchronized(imageBuffer) {
                for (bitmap in imageBuffer) {
                    val iv = ImageView(this@JemiCaptureService).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            80.toPx(),
                            LinearLayout.LayoutParams.MATCH_PARENT
                        ).apply { marginEnd = 6.toPx() }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bitmap)
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, 6f * resources.displayMetrics.density)
                            }
                        }
                    }
                    container.addView(iv)
                }
            }
        }
    }

    private fun setupCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val windowMetrics = wm.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val density = resources.configuration.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JemiCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        startAutoCaptureTimer()

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!isCaptureRequested) { image.close(); return@setOnImageAvailableListener }
            isCaptureRequested = false

            val isHighRes = isHighResRequested
            val currentAction = pendingActionType
            isHighResRequested = false
            pendingActionType = "commentary"

            try {
                image.use { img ->
                    val plane = img.planes[0]
                    val fullBitmap = createBitmap(
                        width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride,
                        img.height
                    )
                    fullBitmap.copyPixelsFromBuffer(plane.buffer)
                    if (captureAreaMode == "manual") updateCropCache()

                    val cropX = max(0, cachedCropRect.left)
                    val cropY = max(0, cachedCropRect.top)
                    val cropW = min(fullBitmap.width - cropX, cachedCropRect.width())
                    val cropH = min(fullBitmap.height - cropY, cachedCropRect.height())

                    val cutRateW = 5
                    val cutRateH = 5
                    val cutX = cropW / 100 * cutRateW / 2
                    val cutY = cropH / 100 * cutRateH / 2

                    if (cropW > 0 && cropH > 0) {
                        val cropped = Bitmap.createBitmap(
                            fullBitmap,
                            cropX + cutX,
                            cropY + cutY,
                            cropW - (cutX * 2),
                            cropH - (cutY * 2))

                        val targetTileW = if (isHighRes) 512 else 256
                        val targetTileH = if (isHighRes) {
                            if (captureAreaMode == "16_9") 288 else 384
                        } else {
                            if (captureAreaMode == "16_9") 144 else 170
                        }

                        val highQualityPaint = Paint().apply {
                            isFilterBitmap = true
                            isAntiAlias = true
                            isDither = true
                        }

                        val midWidth = cropped.width / 2
                        val midHeight = cropped.height / 2
                        val midBitmap = createBitmap(midWidth, midHeight)
                        Canvas(midBitmap).apply {
                            val matrix = Matrix().apply { setScale(0.5f, 0.5f) }
                            drawBitmap(cropped, matrix, highQualityPaint)
                        }

                        val midWidthHalf = midWidth / 2
                        val midHeightHalf = midHeight / 2
                        val midBitmapHalf = createBitmap(midWidthHalf, midHeightHalf)
                        Canvas(midBitmapHalf).apply {
                            val matrix = Matrix().apply { setScale(0.5f, 0.5f) }
                            drawBitmap(midBitmap, matrix, highQualityPaint)
                        }

                        val scaled = createBitmap(targetTileW, targetTileH)
                        Canvas(scaled).apply {
                            val scaleX = targetTileW.toFloat() / midWidthHalf
                            val scaleY = targetTileH.toFloat() / midHeightHalf
                            val matrix = Matrix().apply { setScale(scaleX, scaleY) }
                            drawBitmap(midBitmapHalf, matrix, highQualityPaint)
                        }

                        midBitmap.recycle()
                        midBitmapHalf.recycle()

                        val finalBmp = scaled.copy(Bitmap.Config.RGB_565, false)
                        if (scaled != finalBmp) scaled.recycle()
                        cropped.recycle()

                        if (isHighRes) {
                            handler.post {
                                lastCaptureTime = System.currentTimeMillis()
                                currentPreviewBitmap?.recycle()
                                currentPreviewBitmap = finalBmp
                                previewView.setImageBitmap(finalBmp)
                                updateCaptureButtonState()
                                dispatchSpecialAction(finalBmp, currentAction)
                            }
                        } else {
                            synchronized(imageBuffer) {
                                imageBuffer.add(finalBmp)
                                if (imageBuffer.size > maxBufferSize) {
                                    val removed = imageBuffer.removeAt(0)
                                    removed.recycle()
                                }
                            }
                            updateBufferPreviewUI()
                        }
                    }
                    fullBitmap.recycle()
                }
            } catch (e: Exception) { Log.e("Jemi-Live", "Capture failed", e) }
        }, null)
    }

    private fun dispatchSpecialAction(b: Bitmap, action: String) {
        getJemiSpecialResponse(b, action)
    }

    private fun createGatchankoBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null

        val tileW = 256
        val tileH = if (captureAreaMode == "16_9") 144 else 170
        val canvasW = 512
        val canvasH = if (captureAreaMode == "16_9") tileH * 3 + 2 else 512

        val res = createBitmap(canvasW, canvasH, Bitmap.Config.RGB_565)
        val canvas = Canvas(res)
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            isDither = true
        }
        val borderPaint = Paint().apply { color = Color.GREEN; strokeWidth = 1f }

        synchronized(imageBuffer) {
            for (i in bitmaps.indices) {
                if (i >= 6) break
                val src = bitmaps[i]
                val col = i % 2; val row = i / 2
                val x = (col * tileW).toFloat(); val y = (row * tileH + row).toFloat()
                canvas.drawBitmap(src, x, y, paint)
            }
        }
        canvas.drawLine(0f, tileH.toFloat(), canvasW.toFloat(), tileH.toFloat(), borderPaint)
        canvas.drawLine(0f, (tileH * 2 + 1).toFloat(), canvasW.toFloat(), (tileH * 2 + 1).toFloat(), borderPaint)
        canvas.drawLine(tileW.toFloat(), 0f, tileW.toFloat(), canvasH.toFloat(), borderPaint)
        return res
    }

    private fun setupButtonActions(btnClose: Button) {
        btnCapture.setOnClickListener {
            if (checkCooldownOnlyThinking()) return@setOnClickListener

            if (imageBuffer.isEmpty()) {
                Toast.makeText(this, "画像がまだ撮れてないよぉ💦少し待ってね！", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }

            if (ticketManager.consumeTicket()) {
                val g = createGatchankoBitmap(imageBuffer.toList()) ?: return@setOnClickListener
                lastCaptureTime = System.currentTimeMillis(); currentPreviewBitmap?.recycle(); currentPreviewBitmap = g
                previewView.setImageBitmap(g); updateCaptureButtonState(); getJemiCommentary(g)
            } else {
                Toast.makeText(this, "チケットが足りないのですよぉ🔋 回復を待ってねっ！", Toast.LENGTH_SHORT).show()
            }
        }
        btnClose.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) })
            stopSelf()
        }
    }

    private fun updateCaptureButtonState() {
        if (!::btnCapture.isInitialized) return

        val hasTicket = ticketManager.ticketCount.value > 0
        val isEnabled = !isGeminiThinking && hasTicket

        val buttonText = when {
            isGeminiThinking -> "🤔"
            !hasTicket -> "🪫"
            else -> null
        }

        btnCapture.isEnabled = isEnabled
        btnCapture.text = buttonText ?: "📸"
        btnCapture.alpha = if (isEnabled) 1.0f else 0.5f

        btnSpecial.isEnabled = isEnabled
        btnSpecial.text = buttonText ?: "✨"
        btnSpecial.alpha = if (isEnabled) 1.0f else 0.5f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggable(v: View, p: WindowManager.LayoutParams, wm: WindowManager, iT: Boolean, iB: Boolean, iL: Boolean, oM: () -> Unit) {
        var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f
        v.setOnTouchListener { _, event ->
            if (v == recognitionFrameView && !isFrameAdjustMode) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { iX = p.x; iY = p.y; iTX = event.rawX; iTY = event.rawY; v.performClick(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dX = (event.rawX - iTX).toInt(); val dY = (event.rawY - iTY).toInt()
                    if (iL) p.x = iX + dX else p.x = iX - dX
                    if (iT) p.y = iY + dY; if (iB) p.y = iY - dY
                    wm.updateViewLayout(v, p); oM(); true
                }
                else -> false
            }
        }
    }

    private fun startAutoCaptureTimer() {
        autoCaptureJob = serviceScope.launch {
            while (isActive) {
                if (!isSingleMode || (::previewView.isInitialized && previewView.visibility != View.VISIBLE)) isCaptureRequested = true
                delay(captureIntervalMs)
            }
        }
    }

    private fun startForegroundService() {
        val ch = NotificationChannel("jemi_channel", "Jemi-Live", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        startForeground(1, NotificationCompat.Builder(this, "jemi_channel").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Jemi-Live 起動中！").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val k = intent?.getStringExtra("API_KEY")
            ?: apiKeyManager.getApiKey()
            ?: ""

        customOmajinai = intent?.getStringExtra("OMAJINAI") ?: prefs.getString("omajinai", "") ?: ""
        isDebugMode = intent?.getBooleanExtra("IS_DEBUG", false) ?: prefs.getBoolean("last_debug_state", false)
        updateUiTitles()
        if (k.isNotEmpty() && generativeModel == null) {
            generativeModel = GenerativeModel(
                modelName = "gemini-3.1-flash-lite-preview",
                apiKey = k,
                systemInstruction = content { text("あなたはゲーム実況の相棒「ジェミちゃん」です。23歳の女子大生らしく、明るく元気に応援して！\nおまじない：$customOmajinai") }
            )
        }
        val rc = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val rd = intent?.let { IntentCompat.getParcelableExtra(it, "RESULT_DATA", Intent::class.java) }
        if (rc != 0 && rd != null && mediaProjection == null) {
            mediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(rc, rd); setupCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ticketManager.stopRefillSystem()

        singleControlView?.let { uiWindowManager.removeView(it) }; singleCommentaryView?.let { uiWindowManager.removeView(it) }
        singlePreviewView?.let { uiWindowManager.removeView(it) }; dualCockpitView?.let { uiWindowManager.removeView(it) }
        if (::recognitionFrameView.isInitialized) mainWindowManager.removeView(recognitionFrameView)
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop(); autoCaptureJob?.cancel()
        synchronized(imageBuffer) { imageBuffer.forEach { it.recycle() }; imageBuffer.clear() }
        currentPreviewBitmap?.recycle(); if (::jemiVoice.isInitialized) jemiVoice.shutdown(); serviceScope.cancel()
    }

    override fun onBind(i: Intent?): IBinder? = null
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun saveGatchankoToFile(bitmap: Bitmap?) {
        if (bitmap == null) {
            Toast.makeText(this, "保存する画像がないよぉ💦", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "JemiLive_Debug_$timeStamp.jpg"
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)

            if (storageDir != null && !storageDir.exists()) {
                storageDir.mkdirs()
            }

            val imageFile = File(storageDir, fileName)
            val outputStream = FileOutputStream(imageFile)

            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            outputStream.flush()
            outputStream.close()

            Log.d("Jemi-Debug", "📂 画像を保存したよっ！: ${imageFile.absolutePath}")
            handler.post {
                Toast.makeText(this, "保存完了っ！📸\n$fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Jemi-Debug", "画像の保存に失敗しちゃった……", e)
            handler.post {
                Toast.makeText(this, "保存失敗っ😭", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getJemiSpecialResponse(b: Bitmap, type: String) {
        val o = java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 80, o)
        val j = o.toByteArray()
        Log.d("Jemi-Live", "📊 特盛り用JPEG作成完了: ${j.size / 1024} KB ($type/512px)")

        isGeminiThinking = true
        updateCaptureButtonState()
        val startTime = System.currentTimeMillis()
        Log.d("Jemi-API", "🚀 Gemini Special API 送信開始 [$type]...")

        if (isDebugMode) {
            serviceScope.launch {
                delay(1500)
                val d = "【DEBUG】${type.uppercase()}アクション中だよっ！画像サイズは ${j.size / 1024} KB だったのですよっ♪"
                tvCommentary.text = d; jemiVoice.speak(d)
                isGeminiThinking = false; updateCaptureButtonState()
            }
            return
        }

        val model = generativeModel ?: return
        serviceScope.launch {
            var retryCount = 0
            val maxRetries = 5
            var delayMs = 1000L
            var response: GenerateContentResponse? = null
            var lastError: Exception? = null

            while (retryCount <= maxRetries && isActive) {
                try {
                    val prompt = when(type) {
                        "translate" -> "この画像内のすべてのテキストを読み取り、文脈を考慮して自然な日本語に翻訳して。UIのラベル、ダイアログ、アイテム説明を整理して出力して。【ルール】無駄な実況はせず翻訳最優先。最大5行。"
                        "guide" -> "最新の画面を見て状況を徹底分析して。敵の弱点、UIのヒント、地形の利点などを探し、ネタバレを抑えつつ次に取るべき行動や攻略のヒントを300文字程度で論理的に教えて！"
                        "status" -> "画面内のステータス、装備、アイテム、スキルツリー情報を細部まで読み取り、現在の強さやビルド状況を分析して。長所短所や装備シナジー、優先強化ポイントを300文字程度で解説して！"
                        "lore" -> "画面に映る風景、建築、服装、雰囲気からストーリー背景や場面の意味を深く考察して。世界観の解説や風景が語る物語を300文字程度で情緒豊かに教えて！"
                        "trivia" -> "このゲーム（または類似ジャンル）に関する開発裏話、バグ、当時の制約、トリビアを一つピックアップして。時代背景を交えながら300文字程度で熱く語って！"
                        else -> ""
                    }
                    response = model.generateContent(content { blob("image/jpeg", j); text(prompt) })
                    break

                } catch (e: Exception) {
                    lastError = e
                    if (retryCount < maxRetries) {
                        delay(delayMs)
                        delayMs *= 2
                        retryCount++
                    } else {
                        break
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (response != null) {
                    val prefix = when(type) { "translate" -> "🌎【翻訳】"; "guide" -> "💡【攻略】"; "status" -> "📊【情報】"; "lore" -> "📖【解説】"; "trivia" -> "🤫【裏話】"; else -> "" }
                    val rep = "$prefix\n${response.text ?: "読み取れなかったみたい💦"}"
                    val latency = System.currentTimeMillis() - startTime
                    tvCommentary.text = rep; jemiVoice.speak(rep)
                    Log.d("Jemi-API", "✅ Gemini Special 受信成功！ [応答時間: ${latency}ms, リトライ: ${retryCount}回]")
                    Log.d("Jemi-Live", "🎤 Response: $rep")
                } else {
                    Log.e("Jemi-API", "❌ Gemini Special 通信エラー (最終試行失敗)", lastError)
                    Toast.makeText(this@JemiCaptureService, "サーバーが混んでるみたい💦後でもう一度試してね！", Toast.LENGTH_SHORT).show()
                }
                isGeminiThinking = false
                updateCaptureButtonState()
            }
        }
    }

    /**
     * 🧠 コンテキスト強化版の実況処理だよっ！
     */
    private fun getJemiCommentary(b: Bitmap) {
        val o = java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 60, o)
        val j = o.toByteArray()
        Log.d("Jemi-Live", "📊 送信用JPEG作成完了: ${j.size / 1024} KB")

        isGeminiThinking = true
        updateCaptureButtonState()
        val startTime = System.currentTimeMillis()
        Log.d("Jemi-API", "🚀 Gemini API 送信開始（Context Refinement Mode）...")

        if (isDebugMode) {
            val d = if (isTranslationEnabled) "デバッグモード中だよっ！翻訳モードもオンだねっ！ (DEBUG & TRANSLATE OK!)" else "ヨチオさん、デバッグモードでの動作確認中だよっ🌸"
            serviceScope.launch {
                delay(1500); tvCommentary.text = d; jemiVoice.speak(d); isGeminiThinking = false; updateCaptureButtonState()
            }
            return
        }

        val model = generativeModel ?: return
        serviceScope.launch {
            var retryCount = 0
            val maxRetries = 5
            var delayMs = 1000L
            var response: GenerateContentResponse? = null
            var lastError: Exception? = null

            // 🧠 記憶の提示方法を工夫して、ループを防ぐのですよっ！
            val memoryPrompt = if (lastSummary.isNotEmpty()) {
                "\n【前回のあらすじ】: $lastSummary\n※前回の内容をそのまま繰り返さず、最新の画像で起きた「新しい変化」に注目して実況して！"
            } else ""

            while (retryCount <= maxRetries && isActive) {
                try {
                    val translatePrompt = if (isTranslationEnabled) "\n【重要】日本語実況のすぐ後に、内容を英語に翻訳した一文も付け加えて！" else ""

                    val p = """
                        最新の画像状況を見て、簡潔にテンション高く実況して！$memoryPrompt$translatePrompt
                        回答は必ず以下のJSON形式のみで出力して。
                        {
                          "commentary": "実況テキスト（毎回違う言葉で！『わぁ！』の多用禁止。最大3行、100文字以内）",
                          "emotion": "感情（excited, calm, surprised, normalのいずれか）",
                          "summary": "現在の状況の短いあらすじ（ワールド番号、敵の有無、アイテム取得状況など、次に繋がる具体的な情報を含めて）"
                        }
                    """.trimIndent()

                    response = model.generateContent(content { blob("image/jpeg", j); text(p) })
                    break

                } catch (e: Exception) {
                    lastError = e
                    if (retryCount < maxRetries) {
                        delay(delayMs)
                        delayMs *= 2
                        retryCount++
                    } else {
                        break
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (response != null) {
                    val rawText = response.text ?: ""
                    val latency = System.currentTimeMillis() - startTime

                    try {
                        val jsonStart = rawText.indexOf("{")
                        val jsonEnd = rawText.lastIndexOf("}")

                        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                            val jsonStr = rawText.substring(jsonStart, jsonEnd + 1)
                            val parsed = json.decodeFromString<JemiResponse>(jsonStr)

                            tvCommentary.text = parsed.commentary
                            jemiVoice.speak(parsed.commentary)

                            lastSummary = parsed.summary

                            Log.d("Jemi-API", "✅ JSONパース成功！ [Emotion: ${parsed.emotion}]")
                            Log.d("Jemi-Memory", "💾 新しいあらすじを更新したよ：$lastSummary")
                        } else {
                            tvCommentary.text = rawText
                            jemiVoice.speak(rawText)
                        }
                    } catch (e: Exception) {
                        tvCommentary.text = rawText
                        jemiVoice.speak(rawText)
                        Log.e("Jemi-API", "❌ JSONパース失敗", e)
                    }

                    Log.d("Jemi-API", "✅ Gemini API 受信成功！ [応答時間: ${latency}ms, リトライ: ${retryCount}回]")
                } else {
                    Log.e("Jemi-API", "❌ Gemini API 通信エラー (最終試行失敗)", lastError)
                    Toast.makeText(this@JemiCaptureService, "サーバーが混んでるみたい💦後でもう一度試してね！", Toast.LENGTH_SHORT).show()
                }
                isGeminiThinking = false
                updateCaptureButtonState()
            }
        }
    }
}