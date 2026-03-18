package com.example.jemi_live

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
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

class JemiCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCaptureRequested = false

    // ジェミちゃんのアルバム（最大4枚！）
    private val imageBuffer = mutableListOf<Bitmap>()
    private val MAX_BUFFER_SIZE = 4

    // 自動撮影のタイマー（メトロノーム）
    private var autoCaptureJob: Job? = null

    // ジェミちゃんの声帯！
    private lateinit var jemiVoice: JemiVoiceManager

    // サービス専用のコルーチンスコープ（非同期処理のお弁当箱だよっ！）
    private val serviceScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob()
    )

    // ジェミちゃんの脳みそ（Gemini API）！
    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.1-flash-lite-preview",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    // 今はまだテストだからtrueにしておくねっ！
    private val isDebugMode = false //true

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        // レイアウトを膨らませて画面に追加！
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        windowManager.addView(floatingView, params)

        // 撮影ボタンを押した時にフラグをONにする
        val btnCapture = floatingView.findViewById<Button>(R.id.btn_floating_capture)

        btnCapture.setOnClickListener {
            // アルバムに写真がなかったら何もしない！
            if (imageBuffer.isEmpty()) {
                android.widget.Toast.makeText(this, "まだ写真が貯まってないよぉ💦", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1. アルバムの写真をコピーして、職人にガッチャンコしてもらう！
            val gatchankoBitmap = createGatchankoBitmap(imageBuffer.toList())

            if (gatchankoBitmap != null) {
                // 2. 魔法の透明レイヤーにある「プレビュー画面（iv_mini_preview）」に表示！
                val ivMiniPreview = floatingView.findViewById<ImageView>(R.id.iv_mini_preview)
                ivMiniPreview.setImageBitmap(gatchankoBitmap)
                ivMiniPreview.visibility = android.view.View.VISIBLE
                // 3. ジェミちゃんの脳みそに「完成した横長フィルム」を渡す！
                getJemiCommentary(gatchankoBitmap)
            }
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
                MotionEvent.ACTION_DOWN -> {
                    // 👆 指が触れた瞬間の、UIの場所と指の場所をメモする！
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    // falseを返すことで、中にあるボタン（パシャリ！ボタン）もちゃんと押せるようにするよっ
                    false
                }
                MotionEvent.ACTION_MOVE -> {
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
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JemiCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // 画面の準備ができたらタイマー開始っ！
        startAutoCaptureTimer()

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

                imageBuffer.add(bitmap) // アルバムの最後に追加！

                // 4枚を超えたら、一番古い写真（0番目）を抜いて、破棄（recycle）する！
                if (imageBuffer.size > MAX_BUFFER_SIZE) {
                    val oldBitmap = imageBuffer.removeAt(0)
                    oldBitmap.recycle() // 🧹これを忘れるとアプリがメモリ不足(OOM)で落ちちゃうんだ！
                }

                Log.d("Jemi-Live", "カシャッ📸 アルバムの枚数: ${imageBuffer.size}枚 (最大$MAX_BUFFER_SIZE)")

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

        autoCaptureJob?.cancel() // タイマーを止める！
        imageBuffer.forEach { it.recycle() } // アルバムの写真を全部破棄する！
        imageBuffer.clear()

        if (::jemiVoice.isInitialized) {
            jemiVoice.shutdown() // 声帯をお片付け
        }
        serviceScope.cancel() // 通信のお弁当箱も綺麗にするっ！
    }

    private fun startAutoCaptureTimer() {
        // サービスのお弁当箱（serviceScope）の中でループさせるよ！
        autoCaptureJob = serviceScope.launch {
            while (isActive) {
                isCaptureRequested = true // 「写真撮ってー！」とお願いする

                // 画面が止まっているとカメラがサボるから、透明度をわずかに変えてOSを騙す魔法🪄
                withContext(Dispatchers.Main) {
                    if (floatingView.alpha == 1.0f) {
                        floatingView.alpha = 0.99f
                    } else {
                        floatingView.alpha = 1.0f
                    }
                }

                delay(2000) // 2秒（2000ミリ秒）待つっ！
            }
        }
    }

    private fun getJemiCommentary(bitmap: Bitmap) {
        if (isDebugMode) {
            // 👇 ここからランダムテキストの魔法だよっ！ 👇
            val dummyResponses = listOf(
                "わわっ！今のプレイ、めちゃくちゃカッコいいかもっ！🌸",
                "あちゃー、今の惜しいっ！次、次いこっ！✨",
                "ヨチオさん、天才じゃない！？今の動き、ジェミも見習いたいな〜🌟",
                "ふむふむ、ここは慎重に進むのが吉だねっ！大学生の知恵だよっ🎓",
                "えへへ、画面がキラキラしてて楽しいねっ！応援してるよっ！📣",
                "ヨチオさん、スクショ撮れたよっ！裏側に引っ越しても元気だよぉ〜！🌸"
            )
            val dummyText = dummyResponses.random()

            // サービスの中ではUI（画面）をいじる時にメインスレッド（表舞台）にお願いする必要があるから、
            // serviceScope（Mainディスパッチャ）の中で実行するよっ！
            serviceScope.launch {
                jemiVoice.speak(dummyText)
                android.util.Log.d("Jemi-Live", "📢 デバッグ実況： $dummyText")
            }
            return
        }

        // ここから本番のAPI通信（デバッグモードだと呼ばれないよ！）
        serviceScope.launch {
            try {
                // JPEG圧縮
                val outputStream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)

                // ログで圧縮後のサイズ（キロバイト）を確認してみよう！
                val jpegBytes = outputStream.toByteArray()
                android.util.Log.d("Jemi-Live", "圧縮後の画像サイズ: ${jpegBytes.size / 1024} KB")

                // APIのキャッシュサボりを防ぐために、現在の「時間」を取得！
                val currentTime = System.currentTimeMillis()

                // 仕様書を完全再現した「最強のプロンプト」だよっ！🔥
                val prompt = """
                    あなたはゲーム実況の相棒「ジェミちゃん」です。
                    これは1秒〜2秒間隔で撮影された、連続するゲーム画面（左から右へ時間が進みます）です。
                    23歳の元気な大学生らしい、親密な口調で一言だけ実況して！
    
                    【重要ルール】
                    ・確信が持てない詳細（数値や敵の名前など）については断定を避け、「推測であること」を明示するか、別の切り口（音、色、勢い）でリアクションしてください。
                    ・画像が不鮮明だったり展開が早すぎた場合は、「ごめん、よく見えなかった！ｗ」などのマイルドな敗北宣言を混ぜてください。
                    ・前の状況を引きずらず、必ず「今（一番右の画像）」何が起きているかに注目してください。
                    
                    (システム用ハッシュ: $currentTime)
                """.trimIndent()

                val inputContent = com.google.ai.client.generativeai.type.content {
                    blob("image/jpeg", jpegBytes)
                    text(prompt)
                }

                val response = generativeModel.generateContent(inputContent)
                val replyText = response.text ?: "うーん、ちょっとわかんなかったみたい💦"

                // ログに「Jemi-Live」というタグで実況内容を流すよっ！
                android.util.Log.d("Jemi-Live", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                android.util.Log.d("Jemi-Live", "📢 ジェミの実況： $replyText")
                android.util.Log.d("Jemi-Live", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // 画面の更新とお喋りはメイン舞台（Mainスレッド）にお願いするよっ！
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    jemiVoice.speak(replyText)
                }

            } catch (e: Exception) {
                android.util.Log.e("Jemi-Live", "通信エラーみたい……", e)
            }
        }
    }

    private fun createGatchankoBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null

        // 1. メモリ爆発を防ぐためのリサイズ計算！
        // 4枚横に並べると巨大になっちゃうから、1枚の高さを「300px」くらいに圧縮するよ！
        val targetHeight = 300
        val scale = targetHeight.toFloat() / bitmaps[0].height
        val targetWidth = (bitmaps[0].width * scale).toInt()

        // 2. 4枚を横に並べるための、大きな横長キャンバス（フィルム）を作る！
        val totalWidth = targetWidth * bitmaps.size
        val resultBitmap = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resultBitmap)

        // 3. AIに時間の区切りを教えるため、背景を真っ黒に塗るっ！（これが境界線になるよ！）
        canvas.drawColor(android.graphics.Color.BLACK)

        val paint = android.graphics.Paint()
        var currentX = 0

        // 4. 写真を1枚ずつ、キャンバスにペタペタ貼っていくよ！
        for (bitmap in bitmaps) {
            // 意図的に上下左右に2pxの隙間を空けて貼ることで、黒い境界線が浮かび上がる職人技✨
            val destRect = android.graphics.Rect(
                currentX + 2, 2, currentX + targetWidth - 2, targetHeight - 2
            )
            canvas.drawBitmap(bitmap, null, destRect, paint)

            // 次の写真を貼る位置へ移動！
            currentX += targetWidth
        }

        return resultBitmap
    }
}
