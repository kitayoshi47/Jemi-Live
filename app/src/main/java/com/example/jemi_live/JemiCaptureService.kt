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
 * JemiCaptureService (v2.6.0 - Dual Layout Supported)
 * - 1画面モードと2画面モードのレイアウト分岐を実装
 */
class JemiCaptureService : Service() {
    private lateinit var mainWindowManager: WindowManager
    private lateinit var uiWindowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // 💡 どちらのモードでも共通で使う部品だよ
    private lateinit var recognitionFrameView: FrameLayout
    private lateinit var tvCommentary: TextView
    private lateinit var btnCapture: Button
    private lateinit var previewView: ImageView

    // 💡 UI削除用に保持しておくView
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
    private var isSingleMode = false // 💡 モード判定を全体で使えるようにメンバ変数として追加したよっ！

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)
        jemiVoice = JemiVoiceManager(this)

        mainWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        uiWindowManager = mainWindowManager

        setupFloatingWindows()
    }

    private fun setupFloatingWindows() {
        // 💡 ここにあった "val" を消して、上のメンバ変数に記憶させるようにしたよっ！
        isSingleMode = prefs.getBoolean("is_single_display_mode", false)

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

        // --- 1. 実況認識ガイド（🟩 メイン画面に共通で配置） ---
        val mainMetrics = DisplayMetrics()
        mainWindowManager.defaultDisplay.getRealMetrics(mainMetrics)
        val screenW = mainMetrics.widthPixels
        val screenH = mainMetrics.heightPixels

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
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
                setBackgroundColor(Color.GREEN); alpha = 0.5f
            })
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
                setBackgroundColor(Color.GREEN); alpha = 0.5f
            })
        }
        mainWindowManager.addView(recognitionFrameView, frameParams)

        // 💡 モードに合わせてUIを展開するよっ！
        if (isSingleMode) {
            setupSingleModeUI()
        } else {
            setupDualModeCockpitUI()
        }

        // 共通の設定
        setupDraggable(recognitionFrameView, frameParams, mainWindowManager, true, false, true) { updateCropCache(); saveCoords("frame", frameParams) }

        startForegroundService()
        handler.postDelayed({ updateCropCache() }, 500)
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

    private fun setupButtonActions(btnClose: Button) {
        btnCapture.setOnClickListener {
            if (isGeminiThinking || System.currentTimeMillis() - lastCaptureTime < 10000) {
                Toast.makeText(this, "ジェミ、まだ考え中だよっ🌸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imageBuffer.isEmpty()) {
                // 💡 画像がまだ無い時に無反応にならないようにお知らせ（Toast）を追加したよっ！
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
            btnCapture.isEnabled = false
            btnCapture.text = "⏳"
            btnCapture.alpha = 0.5f
            if (isCoolingDown) {
                val remainingTime = 10000 - timeSinceLastCapture
                handler.postDelayed({ updateCaptureButtonState() }, remainingTime + 50)
            }
        } else {
            btnCapture.isEnabled = true
            btnCapture.text = "📸"
            btnCapture.alpha = 1.0f
        }
    }

    private fun saveCoords(prefix: String, params: WindowManager.LayoutParams) {
        prefs.edit().putInt("${prefix}_x", params.x).putInt("${prefix}_y", params.y).apply {
            if (prefix == "frame") { putInt("${prefix}_w", params.width); putInt("${prefix}_h", params.height) }
        }.apply()
    }

    private fun updateCropCache() {
        val location = IntArray(2)
        recognitionFrameView.getLocationOnScreen(location)
        cachedCropRect.set(location[0], location[1], location[0] + recognitionFrameView.width, location[1] + recognitionFrameView.height)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("API_KEY") ?: prefs.getString("api_key", "") ?: ""
        customOmajinai = intent?.getStringExtra("OMAJINAI") ?: prefs.getString("omajinai", "") ?: ""
        if (apiKey.isNotEmpty() && generativeModel == null) {
            generativeModel = GenerativeModel(modelName = "gemini-3.1-flash-lite-preview", apiKey = apiKey)
        }
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, "RESULT_DATA", Intent::class.java) }
        if (resultCode != 0 && resultData != null && mediaProjection == null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            setupCapture()
        }
        return START_NOT_STICKY
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
                    val cropX = max(0, cachedCropRect.left); val cropY = max(0, cachedCropRect.top)
                    val cropW = min(fullBitmap.width - cropX, cachedCropRect.width()); val cropH = min(fullBitmap.height - cropY, cachedCropRect.height())
                    if (cropW > 0 && cropH > 0) {
                        val cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                        synchronized(imageBuffer) { imageBuffer.add(cropped); if (imageBuffer.size > MAX_BUFFER_SIZE) imageBuffer.removeAt(0).recycle() }
                        Log.d("Jemi-Capture", "📸 キャプチャ成功！ (Buffer: ${imageBuffer.size}/$MAX_BUFFER_SIZE)")
                    }
                    fullBitmap.recycle()
                }
            } catch (e: Exception) { Log.e("Jemi-Live", "Capture failed", e) }
        }, null)
    }

    private fun getJemiCommentary(bitmap: Bitmap) {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
        val jpeg = out.toByteArray()
        Log.d("Jemi-Live", "📊 送信用JPEG作成完了: ${jpeg.size / 1024} KB")
        isGeminiThinking = true

        if (isDebugMode) {
            val dummyText = "ヨチオさん、デバッグモード復活だよっ！🚀 サブ画面に表示されてるかな？🌸"
            serviceScope.launch {
                delay(2000)
                tvCommentary.text = dummyText
                jemiVoice.speak(dummyText)
                Log.d("Jemi-Live", "🎤 [DEBUG] ジェミの実況: $dummyText")
                isGeminiThinking = false
                updateCaptureButtonState()
            }
            return
        }

        val model = generativeModel ?: return

        serviceScope.launch {
            try {
                val prompt = """
                    あなたはゲーム実況の相棒「ジェミちゃん」です。23歳の女子大生らしく明るく元気に応援して！
                    おまじない：$customOmajinai
                    
                    【最重要ルール】
                    ・実況は「最大3行」かつ「100文字以内」で、簡潔にテンション高く伝えてね！
                    ・画像は2x2タイルの時系列画像です。右下の最新状況をメインに話して。
                """.trimIndent()

                val response = model.generateContent(content { blob("image/jpeg", jpeg); text(prompt) })
                val reply = response.text ?: "うーん、読み取れなかったみたい💦"
                withContext(Dispatchers.Main) {
                    tvCommentary.text = reply
                    jemiVoice.speak(reply)
                    Log.d("Jemi-Live", "🎤 ジェミの実況テキスト: $reply")
                }
            } catch (e: Exception) {
                Log.e("Jemi-Live", "Gemini Error", e)
            } finally {
                isGeminiThinking = false
                withContext(Dispatchers.Main) { updateCaptureButtonState() }
            }
        }
    }

    private fun createGatchankoBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null
        val res = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        val canvas = Canvas(res); canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { isFilterBitmap = true }
        synchronized(imageBuffer) {
            for (i in bitmaps.indices) {
                if (i >= 4) break
                val l = (i % 2) * 256; val t = (i / 2) * 256
                canvas.drawBitmap(bitmaps[i], null, Rect(l + 2, t + 2, l + 254, t + 254), paint)
            }
        }
        return res
    }

    private fun startAutoCaptureTimer() {
        autoCaptureJob = serviceScope.launch {
            while (isActive) {
                // 💡 【大修正ポイント！】
                // 1画面モードでプレビューが開いている時「だけ」撮影を止めるよ！
                // 2画面モードなら、プレビューは下画面にあってゲームの邪魔をしないから、常に撮影してOK！
                if (!isSingleMode || previewView.visibility != View.VISIBLE) {
                    isCaptureRequested = true
                } else {
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
}