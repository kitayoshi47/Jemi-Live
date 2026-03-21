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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.ai.client.generativeai.GenerativeModel
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
import kotlin.math.max
import kotlin.math.min

/**
 * JemiCaptureService (v2.9.5 - API Logging & Status Icons)
 * - Gemini APIの応答時間(latency)をログ出力。
 * - 思考中「🤔」とクールタイム「⌛️」のアイコンを使い分け。
 * - モデル名：gemini-3.1-flash-lite-preview を維持。
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
    private val MAX_BUFFER_SIZE = 4

    private var autoCaptureJob: Job? = null
    private lateinit var jemiVoice: JemiVoiceManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val cachedCropRect = Rect()
    private var generativeModel: GenerativeModel? = null
    private var customOmajinai: String = ""

    private var isDebugMode = true
    private var isSingleMode = false
    private var captureAreaMode = "manual"
    private var isFrameAdjustMode = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)
        jemiVoice = JemiVoiceManager(this)
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
            Log.d("Jemi-Display", "--- 📺 ディスプレイ検出開始 ---")
            for (display in displays) {
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                Log.d("Jemi-Display", "画面ID: ${display.displayId}, 名前: ${display.name}, サイズ: ${metrics.widthPixels}x${metrics.heightPixels}")
                if (display.displayId != Display.DEFAULT_DISPLAY) {
                    Log.d("Jemi-Display", "✨ サブディスプレイ発見！")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val displayContext = createDisplayContext(display)
                        val windowContext = displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                        uiWindowManager = windowContext.getSystemService(WindowManager::class.java)
                    }
                    break
                }
            }
            Log.d("Jemi-Display", "---------------------------")
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
        Log.d("Jemi-Crop", "📐 クロップ範囲自動設定 [$captureAreaMode]: $cachedCropRect")
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
        Log.d("Jemi-Live", "📱 1画面モード起動")
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
        Log.d("Jemi-Live", "📺 2画面コックピット起動")
        val cockpitParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        dualCockpitView = LayoutInflater.from(this).inflate(R.layout.layout_cockpit_dashboard, null)
        tvCommentary = dualCockpitView!!.findViewById(R.id.tv_cockpit_commentary)
        previewView = dualCockpitView!!.findViewById(R.id.iv_cockpit_preview)
        btnCapture = dualCockpitView!!.findViewById(R.id.btn_cockpit_capture)
        val btnEdit = dualCockpitView!!.findViewById<Button>(R.id.btn_cockpit_edit_frame)
        if (captureAreaMode != "manual") btnEdit.visibility = View.GONE
        btnEdit.setOnClickListener { toggleFrameAdjustMode() }
        setupButtonActions(dualCockpitView!!.findViewById(R.id.btn_cockpit_close))
        uiWindowManager.addView(dualCockpitView, cockpitParams)
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
                    val cropX = max(0, cachedCropRect.left); val cropY = max(0, cachedCropRect.top)
                    val cropW = min(fullBitmap.width - cropX, cachedCropRect.width()); val cropH = min(fullBitmap.height - cropY, cachedCropRect.height())
                    if (cropW > 0 && cropH > 0) {
                        val cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                        synchronized(imageBuffer) { imageBuffer.add(cropped); if (imageBuffer.size > MAX_BUFFER_SIZE) imageBuffer.removeAt(0).recycle() }
                        Log.d("Jemi-Capture", "📸 成功！ ($captureAreaMode | Buffer: ${imageBuffer.size})")
                    }
                    fullBitmap.recycle()
                }
            } catch (e: Exception) { Log.e("Jemi-Live", "Capture failed", e) }
        }, null)
    }

    private fun createGatchankoBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null
        val ratio = bitmaps[0].height.toFloat() / bitmaps[0].width.toFloat()
        val canvasW = 512; val canvasH = (512 * ratio).toInt()
        val res = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.RGB_565)
        val canvas = Canvas(res); canvas.drawColor(Color.BLACK); val paint = Paint().apply { isFilterBitmap = true }
        synchronized(imageBuffer) {
            for (i in bitmaps.indices) {
                if (i >= 4) break
                val src = bitmaps[i]; val l = (i % 2) * 256; val t = (i / 2) * (canvasH / 2)
                canvas.drawBitmap(src, null, Rect(l + 2, t + 2, l + 254, t + (canvasH / 2) - 2), paint)
            }
        }
        return res
    }

    private fun setupButtonActions(btnClose: Button) {
        btnCapture.setOnClickListener {
            if (isGeminiThinking || System.currentTimeMillis() - lastCaptureTime < 10000) {
                Toast.makeText(this, "ジェミ、まだ考え中か休憩中だよぉ🌸", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (imageBuffer.isEmpty()) {
                Toast.makeText(this, "画像がまだ撮れてないよぉ💦少し待ってね！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
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

    // 💡 アイコンの出し分けロジックを更新したよっ！
    private fun updateCaptureButtonState() {
        if (!::btnCapture.isInitialized) return
        val t = System.currentTimeMillis() - lastCaptureTime
        val cooling = t < 10000

        btnCapture.isEnabled = !isGeminiThinking && !cooling

        // 💡 思考中を「🤔」、クールタイムを「⌛️」に分けたよ！
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
                else Log.d("Jemi-Capture", "⏸ 撮影停止中")
                delay(2000)
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
        singlePreviewView?.let { uiWindowManager.removeView(it) }
        dualCockpitView?.let { uiWindowManager.removeView(it) }
        if (::recognitionFrameView.isInitialized) mainWindowManager.removeView(recognitionFrameView)
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop(); autoCaptureJob?.cancel()
        synchronized(imageBuffer) { imageBuffer.forEach { it.recycle() }; imageBuffer.clear() }
        currentPreviewBitmap?.recycle(); if (::jemiVoice.isInitialized) jemiVoice.shutdown(); serviceScope.cancel()
    }

    override fun onBind(i: Intent?): IBinder? = null
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun getJemiCommentary(b: Bitmap) {
        val o = java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 60, o)
        val j = o.toByteArray()
        Log.d("Jemi-Live", "📊 送信用JPEG作成完了: ${j.size / 1024} KB")

        isGeminiThinking = true
        updateCaptureButtonState()

        val startTime = System.currentTimeMillis() // 💡 通信開始時間を記録
        Log.d("Jemi-API", "🚀 Gemini API 送信開始...")

        if (isDebugMode) {
            val d = "ヨチオさん、1画面モードのプレビュー切り替えも直したよっ🌸"
            serviceScope.launch {
                delay(2000); tvCommentary.text = d; jemiVoice.speak(d);
                Log.d("Jemi-Live", "🎤 [DEBUG]: $d")
                isGeminiThinking = false; updateCaptureButtonState()
            }
            return
        }
        val model = generativeModel ?: return
        serviceScope.launch {
            try {
                val p = "最新の画像状況を見て、簡潔にテンション高く実況して！\n【ルール】最大3行、100文字以内！画像は2x2タイルの時系列だよ。"
                val res = model.generateContent(content { blob("image/jpeg", j); text(p) })
                val rep = res.text ?: "読み取れなかったみたい💦"
                val latency = System.currentTimeMillis() - startTime // 💡 経過時間を計算

                withContext(Dispatchers.Main) {
                    tvCommentary.text = rep; jemiVoice.speak(rep)
                    Log.d("Jemi-API", "✅ Gemini API 受信成功！ [応答時間: ${latency}ms]")
                    Log.d("Jemi-Live", "🎤 実況: $rep")
                }
            } catch (e: Exception) {
                Log.e("Jemi-API", "❌ Gemini API 通信エラー", e)
                withContext(Dispatchers.Main) { Toast.makeText(this@JemiCaptureService, "実況エラーになっちゃった💦", Toast.LENGTH_SHORT).show() }
            } finally {
                isGeminiThinking = false; withContext(Dispatchers.Main) { updateCaptureButtonState() }
            }
        }
    }
}