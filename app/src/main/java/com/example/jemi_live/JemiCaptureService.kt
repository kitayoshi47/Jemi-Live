package com.example.jemi_live

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.*
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
import android.util.DisplayMetrics
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
import androidx.core.graphics.createBitmap

/**
 * JemiCaptureService (v3.0.7 - High Quality Bicubic Scaling)
 * - [Feature] 縮小処理を `createScaledBitmap` から `Matrix + Paint.FILTER_BITMAP_FLAG` に変更し、文字潰れ（バイキュービック補間）を改善。
 * - [Update] 3段階リサイズ 高品質バイキュービック補間対応。キャプチャ画像の左右上下カットエリア対応。
 */
class JemiCaptureService : Service() {
    private lateinit var mainWindowManager: WindowManager
    private lateinit var uiWindowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var recognitionFrameView: FrameLayout
    private lateinit var frameParams: WindowManager.LayoutParams
    private lateinit var tvCommentary: TextView
    private lateinit var btnCapture: Button
    private lateinit var previewView: ImageView

    private var singleControlView: View? = null
    private var singleCommentaryView: View? = null
    private var singlePreviewView: View? = null
    private var dualCockpitView: View? = null

    private var lastCaptureTime = 0L
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCaptureRequested = false
    private var isGeminiThinking = false
    private var currentPreviewBitmap: Bitmap? = null

    private val imageBuffer = mutableListOf<Bitmap>()
    private val MAX_BUFFER_SIZE = 6

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
        prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)
        jemiVoice = JemiVoiceManager(this)

        captureIntervalMs = prefs.getLong("capture_interval", 2000L)

        mainWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        uiWindowManager = mainWindowManager
        setupFloatingWindows()
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
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displays = displayManager.displays
            for (display in displays) {
                if (display.displayId != Display.DEFAULT_DISPLAY) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val displayContext = createDisplayContext(display)
                        val windowContext = displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                        uiWindowManager = windowContext.getSystemService(WindowManager::class.java)
                    }
                    break
                }
            }
        }

        val mainMetrics = DisplayMetrics()
        mainWindowManager.defaultDisplay.getRealMetrics(mainMetrics)
        val screenW = mainMetrics.widthPixels
        val screenH = mainMetrics.heightPixels

        var frameW = prefs.getInt("frame_w", (screenH * 4 / 3).coerceAtMost(screenW))
        var frameH = prefs.getInt("frame_h", screenH.coerceAtMost((frameW * 3 / 4)))

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

        setupDraggable(recognitionFrameView, frameParams, mainWindowManager, true, false, true) {
            if (isFrameAdjustMode) { updateCropCache(); saveCoords("frame", frameParams) }
        }

        startForegroundService()
        handler.postDelayed({ updateCropCache() }, 500)
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
                singlePreviewView!!.visibility = if (singlePreviewView!!.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        setupButtonActions(btnClose)
        uiWindowManager.addView(singleControlView, controlParams)

        val previewParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 16 }
        singlePreviewView = LayoutInflater.from(this).inflate(R.layout.layout_floating_preview, null)
        previewView = singlePreviewView as ImageView
        previewView.visibility = View.GONE

        // 👇 ここに追加！：プレビュー画像長押しで保存するよっ📸
        previewView.setOnLongClickListener {
            saveGatchankoToFile(currentPreviewBitmap)
            true
        }

        uiWindowManager.addView(singlePreviewView, previewParams)

        val commParams = WindowManager.LayoutParams(200.toPx(), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = prefs.getInt("comm_x", 0); y = prefs.getInt("comm_y", 0) }
        singleCommentaryView = LayoutInflater.from(this).inflate(R.layout.layout_floating_commentary, null)
        tvCommentary = singleCommentaryView!!.findViewById(R.id.tv_floating_commentary)

        uiWindowManager.addView(singleCommentaryView, commParams)
        setupDraggable(singleControlView!!, controlParams, uiWindowManager, false, true, false) { saveCoords("ctrl", controlParams) }
        setupDraggable(singleCommentaryView!!, commParams, uiWindowManager, true, false, false) { saveCoords("comm", commParams) }
    }

    @SuppressLint("InflateParams")
    private fun setupDualModeCockpitUI() {
        val cockpitParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        dualCockpitView = LayoutInflater.from(this).inflate(R.layout.layout_cockpit_dashboard, null)
        tvCommentary = dualCockpitView!!.findViewById(R.id.tv_cockpit_commentary)
        previewView = dualCockpitView!!.findViewById(R.id.iv_cockpit_preview)

        // 👇 ここに追加！：コックピットのプレビュー長押しでも保存できるよっ📸
        previewView.setOnLongClickListener {
            saveGatchankoToFile(currentPreviewBitmap)
            true
        }

        btnCapture = dualCockpitView!!.findViewById(R.id.btn_cockpit_capture)
        val btnTranslate = dualCockpitView!!.findViewById<Button>(R.id.btn_cockpit_translate)
        val btnEdit = dualCockpitView!!.findViewById<Button>(R.id.btn_cockpit_edit_frame)
        val btnClose = dualCockpitView!!.findViewById<Button>(R.id.btn_cockpit_close)

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
                prefs.edit().putLong("capture_interval", captureIntervalMs).apply()
                Log.d("Jemi-Live", "⏱️ 撮影間隔を ${captureIntervalMs}ms に変更したよっ！")
            }
        })

        btnTranslate.alpha = if (isTranslationEnabled) 1.0f else 0.5f
        btnTranslate.setOnClickListener {
            isTranslationEnabled = !isTranslationEnabled
            btnTranslate.alpha = if (isTranslationEnabled) 1.0f else 0.5f
            Toast.makeText(this, if (isTranslationEnabled) "翻訳モード ON！🌍" else "翻訳モード OFF！🔕", Toast.LENGTH_SHORT).show()
        }
        if (captureAreaMode != "manual") btnEdit.visibility = View.GONE
        btnEdit.setOnClickListener { toggleFrameAdjustMode() }
        setupButtonActions(btnClose)
        uiWindowManager.addView(dualCockpitView, cockpitParams)
    }

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
        val metrics = DisplayMetrics()
        mainWindowManager.defaultDisplay.getRealMetrics(metrics)
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay("JemiCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
        startAutoCaptureTimer()
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!isCaptureRequested) { image.close(); return@setOnImageAvailableListener }
            isCaptureRequested = false
            try {
                image.use { img ->
                    val plane = img.planes[0]
                    val fullBitmap = Bitmap.createBitmap(metrics.widthPixels + (plane.rowStride - plane.pixelStride * metrics.widthPixels) / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                    fullBitmap.copyPixelsFromBuffer(plane.buffer)
                    if (captureAreaMode == "manual") updateCropCache()

                    // クロップ指定
                    val cropX = max(0, cachedCropRect.left)
                    val cropY = max(0, cachedCropRect.top)
                    val cropW = min(fullBitmap.width - cropX, cachedCropRect.width())
                    val cropH = min(fullBitmap.height - cropY, cachedCropRect.height())

                    // カットエリア
                    val cutRateW = 5 // 画面左右をカットする割合(%)
                    val cutRateH = 5 // 画面上下をカットする割合(%)
                    val cutX = cropW / 100 * cutRateW / 2
                    val cutY = cropH / 100 * cutRateH / 2

                    if (cropW > 0 && cropH > 0) {
                        val cropped = Bitmap.createBitmap(
                            fullBitmap,
                            cropX + cutX,
                            cropY + cutY,
                            cropW - (cutX * 2),
                            cropH - (cutY * 2))

                        val targetTileW = 256
                        val targetTileH = if (captureAreaMode == "16_9") 144 else 170

                        // --- [3段階リサイズ 高品質バイキュービック補間] ---
                        // 高品質Paintの設定
                        val highQualityPaint = Paint().apply {
                            isFilterBitmap = true
                            isAntiAlias = true
                            isDither = true
                        }

                        // --- ステップ1: 中間サイズ（1/2）への縮小 ---
                        val midWidth = cropped.width / 2
                        val midHeight = cropped.height / 2
                        val midBitmap = createBitmap(midWidth, midHeight)
                        Canvas(midBitmap).apply {
                            val matrix = Matrix().apply { setScale(0.5f, 0.5f) }
                            drawBitmap(cropped, matrix, highQualityPaint)
                        }

                        // --- ステップ2: 中間サイズ（1/4）への縮小 ---
                        val midWidth_half = midWidth / 2
                        val midHeight_half = midHeight / 2
                        val midBitmap_half = createBitmap(midWidth_half, midHeight_half)
                        Canvas(midBitmap_half).apply {
                            val matrix = Matrix().apply { setScale(0.5f, 0.5f) }
                            drawBitmap(midBitmap, matrix, highQualityPaint)
                        }

                        // --- ステップ3: 最終ターゲットサイズへの縮小 ---
                        val scaled = createBitmap(targetTileW, targetTileH)
                        Canvas(scaled).apply {
                            val scaleX = targetTileW.toFloat() / midWidth_half
                            val scaleY = targetTileH.toFloat() / midHeight_half
                            val matrix = Matrix().apply { setScale(scaleX, scaleY) }
                            drawBitmap(midBitmap_half, matrix, highQualityPaint)
                        }

                        // 中間ビットマップはもう不要なのでメモリ解放
                        midBitmap.recycle()
                        midBitmap_half.recycle()

                        // 最終結果をリストに追加
                        //val finalBmp = scaled

                        // RGB_565への変換をする場合
                        val finalBmp = scaled.copy(Bitmap.Config.RGB_565, false)
                        if (scaled != finalBmp) scaled.recycle()
                        // -----------------------------------------------------------

                        cropped.recycle()

                        synchronized(imageBuffer) {
                            imageBuffer.add(finalBmp)
                            if (imageBuffer.size > MAX_BUFFER_SIZE) {
                                val removed = imageBuffer.removeAt(0)
                                removed.recycle()
                            }
                        }
                        updateBufferPreviewUI()
                    }
                    fullBitmap.recycle()
                }
            } catch (e: Exception) { Log.e("Jemi-Live", "Capture failed", e) }
        }, null)
    }

    private fun createGatchankoBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null

        val tileW = 256
        val tileH = if (captureAreaMode == "16_9") 144 else 170
        val canvasW = 512
        val canvasH = if (captureAreaMode == "16_9") tileH * 3 + 2 else 512

        val res = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.RGB_565)
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
            if (isGeminiThinking || System.currentTimeMillis() - lastCaptureTime < 10000) {
                Toast.makeText(this, "ジェミ、まだ考え中か休憩中だよぉ🌸", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (imageBuffer.isEmpty()) {
                Toast.makeText(this, "画像がまだ撮れてないよぉ💦少し待ってね！", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val g = createGatchankoBitmap(imageBuffer.toList()) ?: return@setOnClickListener
            lastCaptureTime = System.currentTimeMillis(); currentPreviewBitmap?.recycle(); currentPreviewBitmap = g
            previewView.setImageBitmap(g); updateCaptureButtonState(); getJemiCommentary(g)
        }
        btnClose.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) })
            stopSelf()
        }
    }

    private fun updateCaptureButtonState() {
        if (!::btnCapture.isInitialized) return
        val t = System.currentTimeMillis() - lastCaptureTime
        val cooling = t < 10000
        btnCapture.isEnabled = !isGeminiThinking && !cooling
        btnCapture.text = when {
            isGeminiThinking -> "🤔"
            cooling -> "⌛️"
            else -> "📸"
        }
        btnCapture.alpha = if (btnCapture.isEnabled) 1.0f else 0.5f
        if (cooling && !isGeminiThinking) handler.postDelayed({ updateCaptureButtonState() }, 10000 - t + 50)
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
        val k = intent?.getStringExtra("API_KEY") ?: prefs.getString("api_key", "") ?: ""
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
        singleControlView?.let { uiWindowManager.removeView(it) }; singleCommentaryView?.let { uiWindowManager.removeView(it) }
        singlePreviewView?.let { uiWindowManager.removeView(it) }; dualCockpitView?.let { uiWindowManager.removeView(it) }
        if (::recognitionFrameView.isInitialized) mainWindowManager.removeView(recognitionFrameView)
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop(); autoCaptureJob?.cancel()
        synchronized(imageBuffer) { imageBuffer.forEach { it.recycle() }; imageBuffer.clear() }
        currentPreviewBitmap?.recycle(); if (::jemiVoice.isInitialized) jemiVoice.shutdown(); serviceScope.cancel()
    }

    override fun onBind(i: Intent?): IBinder? = null
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * 💾 ガッチャンコ画像をデバッグ用にファイル出力するよっ！
     */
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

            // JPEG圧縮率
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

    /**
     * [Main Architect Update: v3.0.4]
     * 指数バックオフ（Exponential Backoff）によるリトライ機能を実装したのですよっ♪
     * 503エラー（サーバー過負荷）時に自動で再試行します。
     */
    private fun getJemiCommentary(b: Bitmap) {
        val o = java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 60, o)
        val j = o.toByteArray()
        Log.d("Jemi-Live", "📊 送信用JPEG作成完了: ${j.size / 1024} KB")

        isGeminiThinking = true
        updateCaptureButtonState()
        val startTime = System.currentTimeMillis()
        Log.d("Jemi-API", "🚀 Gemini API 送信開始...")

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

            // --- リトライループ開始だよっ！ ---
            while (retryCount <= maxRetries && isActive) {
                try {
                    val translatePrompt = if (isTranslationEnabled) "\n【重要】日本語実況のすぐ後に、内容を英語に翻訳した一文も付け加えて！" else ""
                    val p = "最新の画像状況を見て、簡潔にテンション高く実況して！$translatePrompt\n【ルール】最大3行、100文字以内！画像は2x3タイルの時系列だよ。"

                    response = model.generateContent(content { blob("image/jpeg", j); text(p) })
                    break // 成功したらループを抜けるのですよっ♪

                } catch (e: Exception) {
                    lastError = e
                    // 503エラーや一時的なエラーの場合のみリトライするねっ
                    if (retryCount < maxRetries) {
                        delay(delayMs)
                        delayMs *= 2 // 1s, 2s, 4s, 8s, 16s と待ち時間を倍にするよっ
                        retryCount++
                    } else {
                        break // 最大回数に達したら諦めるのですよ……
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (response != null) {
                    val rep = response.text ?: "読み取れなかったみたい💦"
                    val latency = System.currentTimeMillis() - startTime
                    tvCommentary.text = rep; jemiVoice.speak(rep)
                    Log.d("Jemi-API", "✅ Gemini API 受信成功！ [応答時間: ${latency}ms, リトライ: ${retryCount}回]")
                    // 👇 ここに実況内容のログ出力を復活させたよっ！✨
                    Log.d("Jemi-Live", "🎤 実況: $rep")
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