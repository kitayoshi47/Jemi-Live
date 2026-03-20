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
 * JemiCaptureService (v2.7.1 - Full Complete Version)
 * - 中央4:3 / 16:9 自動クロップモードを追加
 * - アスペクト比に応じたパッキングロジックの最適化
 * - 欠落していたすべてのメソッドとログを完全収録
 */
class JemiCaptureService : Service() {
    private lateinit var mainWindowManager: WindowManager
    private lateinit var uiWindowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var recognitionFrameView: FrameLayout
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
    private var captureAreaMode = "manual" // "manual", "4_3", "16_9"

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
        // 💡 撮影エリア設定を読み込むよっ
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
                    Log.d("Jemi-Display", "✨ サブディスプレイ発見！ UIをこっちに移動するねっ！")
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

        // --- 1. 実況認識ガイド（🟩 メイン画面） ---
        var frameW = prefs.getInt("frame_w", (screenH * 4 / 3).coerceAtMost(screenW))
        var frameH = prefs.getInt("frame_h", screenH.coerceAtMost((frameW * 3 / 4)))

        val frameParams = WindowManager.LayoutParams(
            frameW, frameH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("frame_x", 0)
            y = prefs.getInt("frame_y", 0)
        }

        recognitionFrameView = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // 💡 手動モード以外なら枠を透明にするよっ（お掃除！）
            val frameAlpha = if (captureAreaMode == "manual") 0.5f else 0.0f
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
                setBackgroundColor(Color.GREEN); alpha = frameAlpha
            })
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
                setBackgroundColor(Color.GREEN); alpha = frameAlpha
            })
        }
        mainWindowManager.addView(recognitionFrameView, frameParams)

        // 💡 クロップ範囲の自動計算大作戦！
        if (captureAreaMode != "manual") {
            calculateAutoCropRect(screenW, screenH)
        }

        // モードに合わせてUIを展開
        if (isSingleMode) {
            setupSingleModeUI()
        } else {
            setupDualModeCockpitUI()
        }

        if (captureAreaMode == "manual") {
            setupDraggable(recognitionFrameView, frameParams, mainWindowManager, true, false, true) { updateCropCache(); saveCoords("frame", frameParams) }
        }

        startForegroundService()
        handler.postDelayed({ updateCropCache() }, 500)
    }

    // 💡 Thorの画面サイズから、4:3や16:9の範囲をピタリと当てるよっ！
    private fun calculateAutoCropRect(screenW: Int, screenH: Int) {
        when (captureAreaMode) {
            "4_3" -> {
                val targetW = (screenH * 4 / 3)
                val offsetX = (screenW - targetW) / 2
                cachedCropRect.set(offsetX, 0, offsetX + targetW, screenH)
                Log.d("Jemi-Crop", "🟦 中央 4:3 クロップ設定: $cachedCropRect")
            }
            "16_9" -> {
                cachedCropRect.set(0, 0, screenW, screenH)
                Log.d("Jemi-Crop", "🟥 全画面 16:9 クロップ設定: $cachedCropRect")
            }
        }
    }

    private fun updateCropCache() {
        if (captureAreaMode != "manual") return // 自動モードなら何もしないよっ
        val location = IntArray(2)
        recognitionFrameView.getLocationOnScreen(location)
        cachedCropRect.set(location[0], location[1], location[0] + recognitionFrameView.width, location[1] + recognitionFrameView.height)
    }

    // 💡 ヨチオさんごめんね！枠の位置を記憶する関数を復活させたよっ！
    private fun saveCoords(prefix: String, params: WindowManager.LayoutParams) {
        prefs.edit().apply {
            putInt("${prefix}_x", params.x)
            putInt("${prefix}_y", params.y)
            if (prefix == "frame") {
                putInt("${prefix}_w", params.width)
                putInt("${prefix}_h", params.height)
            }
            apply()
        }
    }

    @SuppressLint("InflateParams")
    private fun setupSingleModeUI() {
        Log.d("Jemi-Live", "📱 1画面モード（従来レイアウト）で起動するよっ！")

        // --- デバッグモニター ---
        val previewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 16 }
        singlePreviewView = LayoutInflater.from(this).inflate(R.layout.layout_floating_preview, null)
        previewView = singlePreviewView as ImageView
        previewView.visibility = View.GONE
        uiWindowManager.addView(singlePreviewView, previewParams)

        // --- 実況テキストエリア ---
        val commentaryParams = WindowManager.LayoutParams(
            200.toPx(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = prefs.getInt("comm_x", 0); y = prefs.getInt("comm_y", 0) }
        singleCommentaryView = LayoutInflater.from(this).inflate(R.layout.layout_floating_commentary, null)
        tvCommentary = singleCommentaryView!!.findViewById(R.id.tv_floating_commentary)
        uiWindowManager.addView(singleCommentaryView, commentaryParams)

        // --- 操作ボタン ---
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = prefs.getInt("ctrl_x", 8); y = prefs.getInt("ctrl_y", 8) }
        singleControlView = LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        uiWindowManager.addView(singleControlView, controlParams)

        // ボタンの機能設定
        btnCapture = singleControlView!!.findViewById(R.id.btn_floating_capture)
        singleControlView!!.findViewById<Button>(R.id.btn_floating_toggle_preview).apply {
            if (isDebugMode) visibility = View.VISIBLE
            setOnClickListener { previewView.visibility = if (previewView.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        setupButtonActions(singleControlView!!.findViewById(R.id.btn_floating_close))

        // ドラッグ移動の設定
        setupDraggable(singleControlView!!, controlParams, uiWindowManager, false, true, false) { saveCoords("ctrl", controlParams) }
        setupDraggable(singleCommentaryView!!, commentaryParams, uiWindowManager, true, false, false) { saveCoords("comm", commentaryParams) }
    }

    @SuppressLint("InflateParams")
    private fun setupDualModeCockpitUI() {
        Log.d("Jemi-Live", "📺 2画面モード（専用コックピット）で起動するよっ！")

        // サブ画面全体を覆う専用レイアウトを展開！ドラッグは不要だよっ🌸
        val cockpitParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        dualCockpitView = LayoutInflater.from(this).inflate(R.layout.layout_cockpit_dashboard, null)
        tvCommentary = dualCockpitView!!.findViewById(R.id.tv_cockpit_commentary)
        previewView = dualCockpitView!!.findViewById(R.id.iv_cockpit_preview)
        btnCapture = dualCockpitView!!.findViewById(R.id.btn_cockpit_capture)
        val btnClose = dualCockpitView!!.findViewById<Button>(R.id.btn_cockpit_close)

        uiWindowManager.addView(dualCockpitView, cockpitParams)

        // コックピット用のボタン機能を設定
        setupButtonActions(btnClose)
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
                    val planes = img.planes; val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
                    val bitmapWidth = metrics.widthPixels + (rowStride - pixelStride * metrics.widthPixels) / pixelStride
                    val fullBitmap = Bitmap.createBitmap(bitmapWidth, img.height, Bitmap.Config.ARGB_8888)
                    fullBitmap.copyPixelsFromBuffer(buffer)

                    val cropX = max(0, cachedCropRect.left)
                    val cropY = max(0, cachedCropRect.top)
                    val cropW = min(fullBitmap.width - cropX, cachedCropRect.width())
                    val cropH = min(fullBitmap.height - cropY, cachedCropRect.height())

                    if (cropW > 0 && cropH > 0) {
                        val cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                        synchronized(imageBuffer) { imageBuffer.add(cropped); if (imageBuffer.size > MAX_BUFFER_SIZE) imageBuffer.removeAt(0).recycle() }
                        // 💡 バッファの枚数確認ログ
                        Log.d("Jemi-Capture", "📸 キャプチャ成功！ ($captureAreaMode | Buffer: ${imageBuffer.size}/$MAX_BUFFER_SIZE)")
                    }
                    fullBitmap.recycle()
                }
            } catch (e: Exception) { Log.e("Jemi-Live", "Capture failed", e) }
        }, null)
    }

    // 💡 ヨチオさんのアイディア！アスペクト比を維持したままタイル状にガッチャンコするよっ！
    private fun createGatchankoBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null

        // Gemini用には 512x512 のキャンバスを用意するよ
        val res = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        val canvas = Canvas(res)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { isFilterBitmap = true }

        synchronized(imageBuffer) {
            for (i in bitmaps.indices) {
                if (i >= 4) break
                val src = bitmaps[i]
                val l = (i % 2) * 256
                val t = (i / 2) * 256

                // タイル内（256x256）での描画サイズを計算
                // 幅は256固定にして、高さは元画像のアスペクト比に合わせるよっ🌸
                val ratio = src.height.toFloat() / src.width.toFloat()
                val destH = (256 * ratio).toInt().coerceAtMost(256)
                val verticalOffset = (256 - destH) / 2 // 中央寄せ

                canvas.drawBitmap(src, null, Rect(l + 2, t + verticalOffset + 2, l + 254, t + verticalOffset + destH - 2), paint)
            }
        }
        return res
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggable(view: View, params: WindowManager.LayoutParams, targetWindowManager: WindowManager, isTop: Boolean, isBottom: Boolean, isLeft: Boolean, onMoved: () -> Unit) {
        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY
                    v.performClick(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt(); val dy = (event.rawY - initialTouchY).toInt()
                    if (isLeft) params.x = initialX + dx else params.x = initialX - dx
                    if (isTop) params.y = initialY + dy
                    if (isBottom) params.y = initialY - dy
                    targetWindowManager.updateViewLayout(v, params); onMoved(); true
                }
                else -> false
            }
        }
    }

    private fun setupButtonActions(btnClose: Button) {
        btnCapture.setOnClickListener {
            if (isGeminiThinking || System.currentTimeMillis() - lastCaptureTime < 10000) {
                Toast.makeText(this, "ジェミ、まだ考え中だよっ🌸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imageBuffer.isEmpty()) {
                Toast.makeText(this, "まだ画像が撮れてないよぉ💦ちょっとだけ待ってね！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val gatchanko = createGatchankoBitmap(imageBuffer.toList())
            if (gatchanko != null) {
                lastCaptureTime = System.currentTimeMillis()
                currentPreviewBitmap?.recycle()
                currentPreviewBitmap = gatchanko
                previewView.setImageBitmap(gatchanko)
                updateCaptureButtonState()
                getJemiCommentary(gatchanko)
            }
        }
        btnClose.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) })
            stopSelf()
        }
    }

    private fun updateCaptureButtonState() {
        if (!::btnCapture.isInitialized) return
        val timeSinceLastCapture = System.currentTimeMillis() - lastCaptureTime
        val isCoolingDown = timeSinceLastCapture < 10000
        if (isGeminiThinking || isCoolingDown) {
            btnCapture.isEnabled = false; btnCapture.text = "⏳"; btnCapture.alpha = 0.5f
            if (isCoolingDown) handler.postDelayed({ updateCaptureButtonState() }, 10000 - timeSinceLastCapture + 50)
        } else {
            btnCapture.isEnabled = true; btnCapture.text = "📸"; btnCapture.alpha = 1.0f
        }
    }

    private fun startAutoCaptureTimer() {
        autoCaptureJob = serviceScope.launch {
            while (isActive) {
                if (!isSingleMode || (::previewView.isInitialized && previewView.visibility != View.VISIBLE)) {
                    isCaptureRequested = true
                } else {
                    // 💡 プレビュー中の撮影ストップログ
                    Log.d("Jemi-Capture", "⏸ プレビュー表示中のため、自動撮影を一時停止してるよっ！")
                }
                delay(2000)
            }
        }
    }

    private fun startForegroundService() {
        val channel = NotificationChannel("jemi_channel", "Jemi-Live", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        startForeground(1, NotificationCompat.Builder(this, "jemi_channel").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Jemi-Live 起動中！").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("API_KEY") ?: prefs.getString("api_key", "") ?: ""
        customOmajinai = intent?.getStringExtra("OMAJINAI") ?: prefs.getString("omajinai", "") ?: ""
        if (apiKey.isNotEmpty() && generativeModel == null) generativeModel = GenerativeModel(modelName = "gemini-1.5-flash-latest", apiKey = apiKey)
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, "RESULT_DATA", Intent::class.java) }
        if (resultCode != 0 && resultData != null && mediaProjection == null) {
            mediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode, resultData)
            setupCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        singleControlView?.let { uiWindowManager.removeView(it) }
        singleCommentaryView?.let { uiWindowManager.removeView(it) }
        singlePreviewView?.let { uiWindowManager.removeView(it) }
        dualCockpitView?.let { uiWindowManager.removeView(it) }
        if (::recognitionFrameView.isInitialized) mainWindowManager.removeView(recognitionFrameView)
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop(); autoCaptureJob?.cancel()
        synchronized(imageBuffer) { imageBuffer.forEach { it.recycle() }; imageBuffer.clear() }
        currentPreviewBitmap?.recycle()
        if (::jemiVoice.isInitialized) jemiVoice.shutdown(); serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun getJemiCommentary(bitmap: Bitmap) {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
        val jpeg = out.toByteArray()

        // 💡 JPEGサイズの確認ログ
        Log.d("Jemi-Live", "📊 送信用JPEG作成完了: ${jpeg.size / 1024} KB")

        isGeminiThinking = true
        if (isDebugMode) {
            val dummyText = "ヨチオさん、中央クロップ成功だよっ！🚀 ${captureAreaMode}でバッチリ撮れてるかな？🌸"
            serviceScope.launch {
                delay(2000); tvCommentary.text = dummyText; jemiVoice.speak(dummyText)

                // 💡 デバッグ中のジェミのお喋りログ
                Log.d("Jemi-Live", "🎤 [DEBUG] ジェミの実況: $dummyText")

                isGeminiThinking = false; updateCaptureButtonState()
            }
            return
        }
        val model = generativeModel ?: return
        serviceScope.launch {
            try {
                val prompt = """
                    あなたはゲーム実況の相棒「ジェミちゃん」です。明るく元気に応援して！
                    おまじない：$customOmajinai
                    【ルール】実況は最大3行、100文字以内で簡潔に！画像は2x2タイルの時系列です。
                """.trimIndent()
                val response = model.generateContent(content { blob("image/jpeg", jpeg); text(prompt) })
                val reply = response.text ?: "うーん、読み取れなかったみたい💦"
                withContext(Dispatchers.Main) {
                    tvCommentary.text = reply
                    jemiVoice.speak(reply)
                    // 💡 ジェミの実際の実況テキスト
                    Log.d("Jemi-Live", "🎤 ジェミの実況テキスト: $reply")
                }
            } catch (e: Exception) { Log.e("Jemi-Live", "Error", e) } finally { isGeminiThinking = false; withContext(Dispatchers.Main) { updateCaptureButtonState() } }
        }
    }
}