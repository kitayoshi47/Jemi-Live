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
import android.graphics.Rect
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
import android.widget.TextView
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

/**
 * JemiCaptureService
 * 【セパレート・ワイド吸着UI版】
 * - 実況テキスト: 右上（幅200dp・吸着）
 * - 操作ボタン: 右下（横並び）
 * - 撮影ガイド: 左端（初期配置）
 * - 35KBダイエット連結画像 (2x2グリッド)
 */
class JemiCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var controlView: View
    private lateinit var commentaryView: View
    private lateinit var previewView: ImageView
    private lateinit var recognitionFrameView: FrameLayout
    private lateinit var tvCommentary: TextView

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

    private var isDebugMode = true

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        jemiVoice = JemiVoiceManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // --- 🟩 1. 実況認識ガイド（区切り線） ---
        var frameW = (screenH * 4 / 3)
        var frameH = screenH
        if (frameW > screenW) {
            frameW = screenW
            frameH = (frameW * 3 / 4)
        }

        // 🛠️ 初期配置を「左端（START）」へ！
        val frameParams = WindowManager.LayoutParams(
            frameW, frameH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        recognitionFrameView = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
                setBackgroundColor(Color.GREEN)
                alpha = 0.5f
            })
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
                setBackgroundColor(Color.GREEN)
                alpha = 0.5f
            })
        }
        windowManager.addView(recognitionFrameView, frameParams)

        // --- 📺 2. デバッグモニター ---
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

        // --- 📢 3. 実況テキストエリア（右上：ワイド吸着） ---
        val commentaryParams = WindowManager.LayoutParams(
            200.toPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 0
        }
        commentaryView = LayoutInflater.from(this).inflate(R.layout.layout_floating_commentary, null)
        tvCommentary = commentaryView.findViewById(R.id.tv_floating_commentary)
        windowManager.addView(commentaryView, commentaryParams)

        // --- 🎮 4. 操作ボタン（右下：横並び） ---
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 8
            y = 8
        }
        controlView = LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        windowManager.addView(controlView, controlParams)

        // 各ボタンの処理
        val btnTogglePreview = controlView.findViewById<Button>(R.id.btn_floating_toggle_preview)
        if (isDebugMode) btnTogglePreview.visibility = View.VISIBLE
        btnTogglePreview.setOnClickListener {
            previewView.visibility = if (previewView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val btnCapture = controlView.findViewById<Button>(R.id.btn_floating_capture)
        btnCapture.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCaptureTime < 10000) {
                Toast.makeText(this, "ジェミは今、考え中だよっ🌸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imageBuffer.isEmpty()) return@setOnClickListener

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
        controlView.findViewById<Button>(R.id.btn_floating_close).setOnClickListener { stopSelf() }

        // ドラッグ移動のセットアップ
        setupDraggable(recognitionFrameView, frameParams, isTop = true, isLeft = true) // 撮影エリアは左上基準
        setupDraggable(controlView, controlParams, isBottom = true)
        setupDraggable(commentaryView, commentaryParams, isTop = true)

        startForegroundService()
    }

    /**
     * 各基準座標（Gravity）に合わせた賢いドラッグ計算
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggable(view: View, params: WindowManager.LayoutParams,
                               isTop: Boolean = false, isBottom: Boolean = false,
                               isLeft: Boolean = false) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (isLeft) {
                        // 左端(START)基準：右へ動かすとXが増える
                        params.x = initialX + dx
                    } else {
                        // 右端(END)基準：左へ動かすとXが増える
                        params.x = initialX - dx
                    }

                    if (isTop) params.y = initialY + dy
                    if (isBottom) params.y = initialY - dy

                    windowManager.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

                recognitionFrameView.post {
                    val location = IntArray(2)
                    recognitionFrameView.getLocationOnScreen(location)
                    val cropX = max(0, location[0])
                    val cropY = max(0, location[1])
                    val cropW = min(fullBitmap.width - cropX, recognitionFrameView.width)
                    val cropH = min(fullBitmap.height - cropY, recognitionFrameView.height)
                    if (cropW > 0 && cropH > 0) {
                        val finalBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                        imageBuffer.add(finalBitmap)
                        if (imageBuffer.size > MAX_BUFFER_SIZE) imageBuffer.removeAt(0).recycle()
                    }
                    fullBitmap.recycle()
                }
            }
        }, null)
    }

    private fun getJemiCommentary(bitmap: Bitmap) {
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
        val jpegBytes = outputStream.toByteArray()
        Log.d("Jemi-Live", "📊 ダイエット成功サイズ: ${jpegBytes.size / 1024} KB")

        if (isDebugMode) {
            val dummyText = "ヨチオさん、撮影エリアを左端、実況席を右上に配置したよっ！✨ これが最強の布陣だねっ🌸"
            serviceScope.launch {
                tvCommentary.text = "ジェミ：$dummyText"
                jemiVoice.speak(dummyText)
            }
            return
        }

        serviceScope.launch {
            try {
                val currentTime = System.currentTimeMillis()
                val prompt = """
                    あなたはゲーム実況の相棒「ジェミちゃん」です。
                    送信された画像は、直近のゲーム画面4枚を2x2のタイル状に連結した512pxの正方形画像です。
                    大学生らしい親密な口調で、右下の最新状況を中心に一言実況してね！
                    (ハッシュ: $currentTime)
                """.trimIndent()
                val inputContent = content {
                    blob("image/jpeg", jpegBytes)
                    text(prompt)
                }
                val response = generativeModel.generateContent(inputContent)
                val replyText = response.text ?: "うーん、ちょっとわかんなかったみたい💦"
                withContext(Dispatchers.Main) {
                    tvCommentary.text = "ジェミ：$replyText"
                    jemiVoice.speak(replyText)
                }
                Log.d("Jemi-Live", "📢 実況内容： $replyText")
            } catch (e: Exception) {
                Log.e("Jemi-Live", "通信エラー", e)
            }
        }
    }

    private fun createGatchankoBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null
        val finalSize = 512
        val tileSize = finalSize / 2
        val resultBitmap = Bitmap.createBitmap(finalSize, finalSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resultBitmap)
        canvas.drawColor(Color.BLACK)
        val paint = android.graphics.Paint()
        for (i in bitmaps.indices) {
            if (i >= 4) break
            val left = (i % 2) * tileSize
            val top = (i / 2) * tileSize
            val destRect = Rect(left + 2, top + 2, left + tileSize - 2, top + tileSize - 2)
            canvas.drawBitmap(bitmaps[i], null, destRect, paint)
        }
        return resultBitmap
    }

    private fun startAutoCaptureTimer() {
        autoCaptureJob = serviceScope.launch {
            while (isActive) {
                if (previewView.visibility != View.VISIBLE) {
                    isCaptureRequested = true
                    withContext(Dispatchers.Main) {
                        controlView.alpha = if (controlView.alpha == 1.0f) 0.99f else 1.0f
                    }
                }
                delay(2000)
            }
        }
    }

    private fun startForegroundService() {
        val channelId = "jemi_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Jemi-Live Capture", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jemi-Live 起動中！")
            .setContentText("ヨチオさんの画面を見守ってるよっ🌸")
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controlView.isInitialized) windowManager.removeView(controlView)
        if (::commentaryView.isInitialized) windowManager.removeView(commentaryView)
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

    override fun onBind(intent: Intent?): IBinder? = null
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()
}