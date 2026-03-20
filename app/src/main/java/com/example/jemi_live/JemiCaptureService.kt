package com.example.jemi_live

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
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

class JemiCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View // 🎮 リモコン
    private lateinit var previewView: ImageView // 📺 モニター
    private lateinit var recognitionFrameView: FrameLayout // 🟩 実況認識ガイド（区切り線）

    private var lastCaptureTime = 0L
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCaptureRequested = false

    private val imageBuffer = mutableListOf<Bitmap>()
    private val MAX_BUFFER_SIZE = 4

    private var autoCaptureJob: Job? = null
    private lateinit var jemiVoice: JemiVoiceManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.1-flash-lite-preview",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private val isDebugMode = true

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        jemiVoice = JemiVoiceManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 📏 デバイスの画面サイズから4:3サイズを計算
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        var frameW = (screenH * 4 / 3)
        var frameH = screenH
        if (frameW > screenW) {
            frameW = screenW
            frameH = (frameW * 3 / 4)
        }

        // --- 🟩 1. 実況認識エリア（区切り線ガイド）の設定 ---
        val frameParams = WindowManager.LayoutParams(
            frameW, frameH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        recognitionFrameView = FrameLayout(this).apply {
            // 背景は透明にして、中に「線」を追加するよっ！
            setBackgroundColor(Color.TRANSPARENT)

            // 左側の縦線
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
                setBackgroundColor(Color.GREEN)
                alpha = 0.5f // 少し透けさせてオシャレにっ🌸
            })

            // 右側の縦線（これがゲーム画面と実況席の「区切り線」になるよ！）
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
                setBackgroundColor(Color.GREEN)
                alpha = 0.5f
            })
        }
        windowManager.addView(recognitionFrameView, frameParams)

        // ガイド（区切り線）のドラッグ処理
        var fInitialX = 0
        var fInitialY = 0
        var fTouchX = 0f
        var fTouchY = 0f
        recognitionFrameView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    fInitialX = frameParams.x
                    fInitialY = frameParams.y
                    fTouchX = event.rawX
                    fTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    frameParams.x = fInitialX + (event.rawX - fTouchX).toInt()
                    frameParams.y = fInitialY + (event.rawY - fTouchY).toInt()
                    windowManager.updateViewLayout(recognitionFrameView, frameParams)
                    true
                }
                else -> false
            }
        }

        // --- 📺 2. モニター（プレビュー）の設定 ---
        val previewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 16
        }
        previewView = LayoutInflater.from(this).inflate(R.layout.layout_floating_preview, null) as ImageView
        previewView.visibility = View.GONE
        windowManager.addView(previewView, previewParams)

        // --- 🎮 3. リモコン（ボタン）の設定 ---
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 32
            y = 32
        }
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        windowManager.addView(floatingView, controlParams)

        val btnTogglePreview = floatingView.findViewById<Button>(R.id.btn_floating_toggle_preview)
        if (isDebugMode) btnTogglePreview.visibility = View.VISIBLE
        btnTogglePreview.setOnClickListener {
            previewView.visibility = if (previewView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val btnCapture = floatingView.findViewById<Button>(R.id.btn_floating_capture)
        btnCapture.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCaptureTime < 10000) {
                Toast.makeText(this, "ジェミは今、前の実況を考え中だよっ！あと少し待ってね🌸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imageBuffer.isEmpty()) {
                Toast.makeText(this, "まだ写真が貯まってないよぉ💦", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val gatchankoBitmap = createGatchankoBitmap(imageBuffer.toList())
            if (gatchankoBitmap != null) {
                lastCaptureTime = currentTime
                if (isDebugMode) {
                    previewView.setImageBitmap(gatchankoBitmap)
                    previewView.visibility = View.VISIBLE
                }
                getJemiCommentary(gatchankoBitmap)
            }
        }

        floatingView.findViewById<Button>(R.id.btn_floating_close).setOnClickListener {
            stopSelf()
        }

        var cInitialX = 0
        var cInitialY = 0
        var cTouchX = 0f
        var cTouchY = 0f
        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cInitialX = controlParams.x
                    cInitialY = controlParams.y
                    cTouchX = event.rawX
                    cTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    controlParams.x = cInitialX - (event.rawX - cTouchX).toInt()
                    controlParams.y = cInitialY - (event.rawY - cTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, controlParams)
                    true
                }
                else -> false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "jemi_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Jemi-Live Capture", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jemi-Live 起動中！")
            .setContentText("ヨチオさんの画面をパシャリする準備ができたよっ🌸")
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

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
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JemiCapture", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        startAutoCaptureTimer()

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!isCaptureRequested) {
                image.close()
                return@setOnImageAvailableListener
            }
            isCaptureRequested = false

            image.use { img ->
                val planes = img.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth
                val bitmapWidth = screenWidth + rowPadding / pixelStride

                val fullBitmap = createBitmap(bitmapWidth, img.height, Bitmap.Config.ARGB_8888)
                fullBitmap.copyPixelsFromBuffer(buffer)

                val location = IntArray(2)
                recognitionFrameView.post {
                    recognitionFrameView.getLocationOnScreen(location)

                    val cropX = max(0, location[0])
                    val cropY = max(0, location[1])
                    val cropW = min(fullBitmap.width - cropX, recognitionFrameView.width)
                    val cropH = min(fullBitmap.height - cropY, recognitionFrameView.height)

                    if (cropW > 0 && cropH > 0) {
                        val finalBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)

                        imageBuffer.add(finalBitmap)
                        if (imageBuffer.size > MAX_BUFFER_SIZE) {
                            imageBuffer.removeAt(0).recycle()
                        }
                        Log.d("Jemi-Live", "区切り線内をクロップ成功📸")
                    }
                    fullBitmap.recycle()
                }
            }
        }, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        if (::previewView.isInitialized) windowManager.removeView(previewView)
        if (::recognitionFrameView.isInitialized) windowManager.removeView(recognitionFrameView)

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        autoCaptureJob?.cancel()
        imageBuffer.forEach { it.recycle() }
        imageBuffer.clear()
        if (::jemiVoice.isInitialized) jemiVoice.shutdown()
        serviceScope.cancel()
    }

    private fun startAutoCaptureTimer() {
        autoCaptureJob = serviceScope.launch {
            while (isActive) {
                if (previewView.visibility != View.VISIBLE) {
                    isCaptureRequested = true
                    withContext(Dispatchers.Main) {
                        floatingView.alpha = if (floatingView.alpha == 1.0f) 0.99f else 1.0f
                    }
                }
                delay(2000)
            }
        }
    }

    private fun getJemiCommentary(bitmap: Bitmap) {
        if (isDebugMode) {
            val dummyText = "ヨチオさん、実況エリアの区切りバッチリだよっ！✨"
            serviceScope.launch {
                jemiVoice.speak(dummyText)
            }
            return
        }

        serviceScope.launch {
            try {
                val outputStream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                val jpegBytes = outputStream.toByteArray()
                val currentTime = System.currentTimeMillis()

                val prompt = """
                    あなたはゲーム実況の相棒「ジェミちゃん」です。
                    これは1秒〜2秒間隔で撮影された、連続するゲーム画面（左から右へ時間が進みます）です。
                    23歳の元気な大学生らしい、親密な口調で一言だけ実況して！
                    【重要ルール】
                    ・送信された画像はゲーム画面の重要部分のみを切り抜いたものです。アイコン等には言及しないで！
                    (ハッシュ: $currentTime)
                """.trimIndent()

                val inputContent = content {
                    blob("image/jpeg", jpegBytes)
                    text(prompt)
                }

                val response = generativeModel.generateContent(inputContent)
                val replyText = response.text ?: "うーん、ちょっとわかんなかったみたい💦"

                withContext(Dispatchers.Main) {
                    jemiVoice.speak(replyText)
                }
            } catch (e: Exception) {
                Log.e("Jemi-Live", "通信エラー", e)
            }
        }
    }

    private fun createGatchankoBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null
        val targetHeight = 300
        val scale = targetHeight.toFloat() / bitmaps[0].height
        val targetWidth = (bitmaps[0].width * scale).toInt()
        val totalWidth = targetWidth * bitmaps.size
        val resultBitmap = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resultBitmap)
        canvas.drawColor(Color.BLACK)
        val paint = android.graphics.Paint()
        var currentX = 0
        for (bitmap in bitmaps) {
            val destRect = android.graphics.Rect(currentX + 2, 2, currentX + targetWidth - 2, targetHeight - 2)
            canvas.drawBitmap(bitmap, null, destRect, paint)
            currentX += targetWidth
        }
        return resultBitmap
    }

    override fun onBind(intent: Intent?): IBinder? = null
}