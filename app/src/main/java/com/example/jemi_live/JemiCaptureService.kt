package com.example.jemi_live

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class JemiCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCaptureRequested = false

    // ジェミちゃんの声帯！
    private lateinit var jemiVoice: JemiVoiceManager

    // サービス専用のコルーチンスコープ（非同期処理のお弁当箱だよっ！）
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )

    // ジェミちゃんの脳みそ（Gemini API）！
    private val generativeModel = com.google.ai.client.generativeai.GenerativeModel(
        modelName = "gemini-3.1-flash-lite-preview",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    // 今はまだテストだからtrueにしておくねっ！
    private val isDebugMode = true

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        jemiVoice = JemiVoiceManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // フローティング・ウィンドウの設定（OSへの「貼り付け指定」）
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // ゲームの操作を邪魔しない！
            android.graphics.PixelFormat.TRANSLUCENT
        )

        // レイアウトを膨らませて画面に追加！
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        windowManager.addView(floatingView, params)

        // 撮影ボタンを押した時にフラグをONにする
        val btnCapture = floatingView.findViewById<Button>(R.id.btn_floating_capture)
        btnCapture.setOnClickListener {
            // ボタンが押されたら「写真撮ってー！」とお願いするっ！
            isCaptureRequested = true
        }

        // ライブモード終了ボタン処理
        val btnClose = floatingView.findViewById<Button>(R.id.btn_floating_close)
        btnClose.setOnClickListener {
            // ① メイン画面（MainActivity）を呼び起こすインテント（招待状）を作る！
            val intent = Intent(this, MainActivity::class.java).apply {
                // FLAG_ACTIVITY_NEW_TASK: サービスから画面を開く時の絶対のルール！
                // FLAG_ACTIVITY_CLEAR_TOP: すでにメイン画面が裏にいたら、新しく作らずにそれを手前に引っ張り出す！
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)

            // ② ジェミちゃんのライブモード（サービス）を終了させる！
            stopSelf()
        }

        // 座標を記憶するための「メモ帳」変数だよっ📝
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        // ジェミちゃんの画面（floatingView）に、タッチセンサーを貼り付けるよ！
        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // 👆 指が触れた瞬間の、UIの場所と指の場所をメモする！
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    // falseを返すことで、中にあるボタン（パシャリ！ボタン）もちゃんと押せるようにするよっ
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // 👆 指が動いた距離を計算して、新しい場所を決めるよっ！
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()

                    // 画面の管理人（WindowManager）に「ここへ移動させて！」とお願い✨
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "jemi_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 1. まずは通知チャンネルを作る
        val channel = NotificationChannel(channelId, "Jemi-Live Capture", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        // 2. 「実況中だよっ🌸」という通知を作る
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jemi-Live 起動中！")
            .setContentText("ヨチオさんの画面をパシャリする準備ができたよっ🌸")
            .build()

        // 3. OSに「今からキャプチャするよ！」と宣言してサービスを開始！
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        // 4. MainActivityから送られてきた「許可証」を受け取る
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = intent?.let {
            IntentCompat.getParcelableExtra(it, "RESULT_DATA", Intent::class.java)
        }

        // すでにキャプチャ準備済みの場合は二重に作らないようにする安全対策
        if (resultCode != 0 && resultData != null && mediaProjection == null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

            // 5. さあ、満を持してキャプチャ開始！
            setupCapture()
        }

        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
        }, Handler(Looper.getMainLooper()))

        // ImageReader の準備
        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JemiCapture", width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            // お願いされてない時（フラグがfalse）は、画像をそのまま捨てて何もしない！
            if (!isCaptureRequested) {
                image.close() // メモリがパンクしないように必ず閉じるっ！
                return@setOnImageAvailableListener
            }

            // お願いされてた時（フラグがtrue）は、1枚撮るからフラグを元に戻すよ！
            isCaptureRequested = false

            image.use { image ->
                // 1. ここで Bitmap を作る
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                // 修正: 標準の Bitmap.createBitmap を使用
                val bitmapWidth = image.width + rowPadding / pixelStride
                val bitmap = createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                getJemiCommentary(bitmap)

                android.util.Log.d("Jemi-Live", "パシャリ成功して、机に置いたよっ！🌟")

                // stopSelf() // テスト用に一枚で止める
            }
        }, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 1. フローティングビューを忘れずに剥がす！（WindowLeaked対策）
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }

        // 2. 使い終わった道具はちゃんとお片付け！
        virtualDisplay?.release()
        imageReader?.close() // 追加: ImageReaderのお片付け
        mediaProjection?.stop()

        if (::jemiVoice.isInitialized) {
            jemiVoice.shutdown() // 声帯をお片付け
        }
        serviceScope.cancel() // 通信のお弁当箱も綺麗にするっ！
    }

    private fun getJemiCommentary(bitmap: Bitmap) {
        // 透明レイヤーの文字を書き換える準備っ！
        val tvFloatingCommentary = floatingView.findViewById<android.widget.TextView>(R.id.tv_floating_commentary)

        if (isDebugMode) {
            val dummyText = "ヨチオさん、スクショ撮れたよっ！裏側に引っ越しても元気だよぉ〜！🌸"
            tvFloatingCommentary.text = dummyText
            jemiVoice.speak(dummyText)
            return
        }

        // ここから本番のAPI通信（今はデバッグモードだから呼ばれないけど準備だけね！）
        serviceScope.launch {
            try {
                val prompt = "あなたはゲーム実況の相棒ジェミちゃんです。この画面を見て、親密で元気な口調で一言コメントして！"
                val inputContent = com.google.ai.client.generativeai.type.content {
                    image(bitmap)
                    text(prompt)
                }
                val response = generativeModel.generateContent(inputContent)
                val replyText = response.text ?: "うーん、よく見えなかったみたい💦"

                tvFloatingCommentary.text = replyText
                jemiVoice.speak(replyText)
            } catch (e: Exception) {
                android.util.Log.e("Jemi-Live", "通信エラーみたい……", e)
                tvFloatingCommentary.text = "ごめんね、エラーになっちゃった😭"
            }
        }
    }
}
