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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
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
 * JemiCaptureService (v2.4.4 - Button State Feedback)
 * - 撮影ボタンのクールタイム＆思考中UIフィードバックを追加（📸 ⇄ ⏳）
 */
class JemiCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var controlView: View
    private lateinit var commentaryView: View
    private lateinit var previewView: ImageView
    private lateinit var recognitionFrameView: FrameLayout
    private lateinit var tvCommentary: TextView
    private lateinit var btnCapture: Button // 💡 キャプチャボタンの状態を変えるために保持するよっ

    private var lastCaptureTime = 0L
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCaptureRequested = false
    private var isGeminiThinking = false // 💡 ジェミが考え中かどうかを判定するフラグだよっ
    private var currentPreviewBitmap: Bitmap? = null // 💡 プレビュー中の画像を保持しておくよっ！

    private val imageBuffer = mutableListOf<Bitmap>()
    private val MAX_BUFFER_SIZE = 4

    private var autoCaptureJob: Job? = null
    private lateinit var jemiVoice: JemiVoiceManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val cachedCropRect = Rect()
    private var generativeModel: GenerativeModel? = null
    private var customOmajinai: String = ""

    private var isDebugMode = true // 🛠️ テスト用にtrue

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)
        jemiVoice = JemiVoiceManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingWindows()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingWindows() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // --- 1. 実況認識ガイド ---
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
        windowManager.addView(recognitionFrameView, frameParams)

        // --- 2. デバッグモニター ---
        val previewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 16 }
        previewView = LayoutInflater.from(this).inflate(R.layout.layout_floating_preview, null) as ImageView
        previewView.visibility = View.GONE
        windowManager.addView(previewView, previewParams)

        // --- 3. 実況テキストエリア ---
        val commentaryParams = WindowManager.LayoutParams(
            200.toPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt("comm_x", 0)
            y = prefs.getInt("comm_y", 0)
        }
        commentaryView = LayoutInflater.from(this).inflate(R.layout.layout_floating_commentary, null)
        tvCommentary = commentaryView.findViewById(R.id.tv_floating_commentary)
        windowManager.addView(commentaryView, commentaryParams)

        // --- 4. 操作ボタン ---
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = prefs.getInt("ctrl_x", 8)
            y = prefs.getInt("ctrl_y", 8)
        }
        controlView = LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        windowManager.addView(controlView, controlParams)

        // ボタン設定
        controlView.findViewById<Button>(R.id.btn_floating_toggle_preview).apply {
            if (isDebugMode) visibility = View.VISIBLE
            setOnClickListener { previewView.visibility = if (previewView.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }

        btnCapture = controlView.findViewById(R.id.btn_floating_capture)
        btnCapture.setOnClickListener {
            if (isGeminiThinking || System.currentTimeMillis() - lastCaptureTime < 10000) {
                Toast.makeText(this, "ジェミ、まだ考え中だよっ🌸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imageBuffer.isEmpty()) return@setOnClickListener
            val gatchanko = createGatchankoBitmap(imageBuffer.toList())
            if (gatchanko != null) {
                lastCaptureTime = System.currentTimeMillis()

                // 💡 古いプレビュー画像を捨てて、新しいものを覚えるよっ
                currentPreviewBitmap?.recycle()
                currentPreviewBitmap = gatchanko

                previewView.setImageBitmap(gatchanko)

                updateCaptureButtonState() // 💡 撮影直後にボタンを砂時計に変えるよっ
                getJemiCommentary(gatchanko)
            }
        }

        controlView.findViewById<Button>(R.id.btn_floating_close).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) })
            stopSelf()
        }

        setupDraggable(recognitionFrameView, frameParams, true, false, true, { updateCropCache(); saveCoords("frame", frameParams) })
        setupDraggable(controlView, controlParams, false, true, false, { saveCoords("ctrl", controlParams) })
        setupDraggable(commentaryView, commentaryParams, true, false, false, { saveCoords("comm", commentaryParams) })

        startForegroundService()
        handler.postDelayed({ updateCropCache() }, 500)
    }

    // 💡 ボタンの見た目（クールタイム・思考中）を賢く管理するメソッドだよっ
    private fun updateCaptureButtonState() {
        if (!::btnCapture.isInitialized) return

        val timeSinceLastCapture = System.currentTimeMillis() - lastCaptureTime
        val isCoolingDown = timeSinceLastCapture < 10000

        if (isGeminiThinking || isCoolingDown) {
            // 考え中、またはクールタイム中ならボタンをロック
            btnCapture.isEnabled = false
            btnCapture.text = "⏳"
            btnCapture.alpha = 0.5f

            // もしクールタイム待ちなら、時間が来たらもう一度状態をチェックするよ
            if (isCoolingDown) {
                val remainingTime = 10000 - timeSinceLastCapture
                handler.postDelayed({ updateCaptureButtonState() }, remainingTime + 50)
            }
        } else {
            // 準備完了！
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
    private fun setupDraggable(view: View, params: WindowManager.LayoutParams, isTop: Boolean, isBottom: Boolean, isLeft: Boolean, onMoved: () -> Unit) {
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
                    windowManager.updateViewLayout(v, params); onMoved(); true
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
        windowManager.defaultDisplay.getRealMetrics(metrics)
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

        isGeminiThinking = true // 💡 思考開始！

        if (isDebugMode) {
            val dummyText = "ヨチオさん、デバッグモード復活だよっ！🚀 ログもしっかり出てるかな？🌸"
            serviceScope.launch {
                delay(2000) // 思考中を演出
                tvCommentary.text = dummyText
                jemiVoice.speak(dummyText)
                Log.d("Jemi-Live", "🎤 [DEBUG] ジェミの実況: $dummyText")

                isGeminiThinking = false // 💡 思考終了
                updateCaptureButtonState() // ボタンの状態を更新
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
                // 💡 API通信が終わったらフラグを戻してボタンを更新するよっ
                isGeminiThinking = false
                withContext(Dispatchers.Main) {
                    updateCaptureButtonState()
                }
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
                if (previewView.visibility != View.VISIBLE) {
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
        if (::controlView.isInitialized) windowManager.removeView(controlView)
        if (::commentaryView.isInitialized) windowManager.removeView(commentaryView)
        if (::previewView.isInitialized) windowManager.removeView(previewView)
        if (::recognitionFrameView.isInitialized) windowManager.removeView(recognitionFrameView)
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop(); autoCaptureJob?.cancel()
        synchronized(imageBuffer) { imageBuffer.forEach { it.recycle() }; imageBuffer.clear() }
        currentPreviewBitmap?.recycle() // 💡 アプリ終了時に忘れずにお片付け！
        if (::jemiVoice.isInitialized) jemiVoice.shutdown(); serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()
}