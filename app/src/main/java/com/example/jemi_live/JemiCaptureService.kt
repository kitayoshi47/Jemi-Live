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
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.Choreographer

class JemiCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var previewView: ImageView
    private var lastCaptureTime = 0L
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
    private val isDebugMode = true

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        jemiVoice = JemiVoiceManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 📺 1. モニター（プレビュー）の設定：【左上】に配置！
        val previewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_TOUCHABLE を付けると、写真をタップしても後ろのマリオが動く魔法🪄
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START // 左上！
            x = 16
            y = 16
        }
        // さっき作った新しいXML（モニター）を読み込むよっ！
        previewView = LayoutInflater.from(this).inflate(R.layout.layout_floating_preview, null) as ImageView
        previewView.visibility = View.GONE
        windowManager.addView(previewView, previewParams)

        // 🎮 2. リモコン（ボタン）の設定：【右下】に配置！
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END // 右下！
            x = 32
            y = 32
        }
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        windowManager.addView(floatingView, controlParams)

        val btnTogglePreview = floatingView.findViewById<Button>(R.id.btn_floating_toggle_preview)

        // デバッグモードの時だけ「🖼️」ボタンを見せる魔法✨
        if (isDebugMode) {
            btnTogglePreview.visibility = View.VISIBLE
        }

        btnTogglePreview.setOnClickListener {
            // モニターの表示・非表示をパチパチ切り替えるよ！
            if (previewView.visibility == View.VISIBLE) {
                previewView.visibility = View.GONE
            } else {
                previewView.visibility = View.VISIBLE
            }
        }

        // 📸 撮影ボタンを押した時の処理
        val btnCapture = floatingView.findViewById<Button>(R.id.btn_floating_capture)
        btnCapture.setOnClickListener {
            // 1. まず、今ボタンを押した「今の時間」を確認するよ！
            val currentTime = System.currentTimeMillis()

            // 2. もし、最後に撮った時間からまだ10秒（10000ミリ秒）経ってなかったら…
            if (currentTime - lastCaptureTime < 10000) {
                // 「ちょっと待ってね」って優しく教えてあげるの🌸
                android.widget.Toast.makeText(this, "ジェミは今、前の実況を考え中だよっ！あと少し待ってね🌸", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener // ここで処理をストップして、下には行かないようにするよ
            }

            // 3. ここから下は今まで通り！写真が貯まってるかチェック！
            if (imageBuffer.isEmpty()) {
                android.widget.Toast.makeText(this, "まだ写真が貯まってないよぉ💦", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 4. 職人さんにガッチャンコしてもらう！
            val gatchankoBitmap = createGatchankoBitmap(imageBuffer.toList())

            if (gatchankoBitmap != null) {
                // ⭕️ 無事に撮れたら、今の時間を「最後に撮った時間」としてメモ帳に書き込むよ！
                lastCaptureTime = currentTime

                if (isDebugMode) {
                    previewView.setImageBitmap(gatchankoBitmap)
                    previewView.visibility = View.VISIBLE
                } else {
                    previewView.visibility = View.GONE
                }

                // ジェミの脳みそに画像を送る！
                getJemiCommentary(gatchankoBitmap)
            }
        }

        // ❌ ライブモード終了ボタン処理
        val btnClose = floatingView.findViewById<Button>(R.id.btn_floating_close)
        btnClose.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            stopSelf()
        }

        // 👆 座標を記憶するための「メモ帳」変数📝
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        // タッチセンサー（右下基準のマイナス計算！）
        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlParams.x
                    initialY = controlParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    // 右下（END/BOTTOM）に引っ張っているから、動かした距離を「引く」！
                    controlParams.x = initialX - (event.rawX - initialTouchX).toInt()
                    controlParams.y = initialY - (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, controlParams)
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
                image.close()
                return@setOnImageAvailableListener
            }

            // お願いされてた時（フラグがtrue）は、1枚撮るからフラグを元に戻すよ！
            isCaptureRequested = false

            image.use { img ->
                // 1. ここで Bitmap を作る
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                // 修正: 標準の Bitmap.createBitmap を使用
                val bitmapWidth = image.width + rowPadding / pixelStride
                val bitmap = createBitmap(bitmapWidth, img.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                imageBuffer.add(bitmap)
                if (imageBuffer.size > MAX_BUFFER_SIZE) {
                    val oldBitmap = imageBuffer.removeAt(0)
                    oldBitmap.recycle()
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

        if (::previewView.isInitialized) {
            windowManager.removeView(previewView)
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
        autoCaptureJob = serviceScope.launch {
            while (isActive) {
                // 👇 モニター（previewView）が出ていない時だけ、写真を撮るよっ！
                if (previewView.visibility != View.VISIBLE) {
                    isCaptureRequested = true

                    withContext(Dispatchers.Main) {
                        // OSを騙すための透明度切り替えだけ残しておくねっ🪄
                        floatingView.alpha = if (floatingView.alpha == 1.0f) 0.99f else 1.0f
                    }
                } else {
                    Log.d("Jemi-Live", "モニター表示中だから撮影はお休みするねっ💤")
                }

                delay(2000) // 2秒待機 [cite: 7]
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
                    // 👇 この1文を追加するよっ！ 👇
                    ・画面右下にある「📸」「❌」「🖼️」などのアイコンは実況アプリのUIです。これらには絶対に言及せず、純粋にゲームの映像だけを見て実況してください。
                    // 👆 ---------------------- 👆
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
