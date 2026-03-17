package com.example.jemi_live

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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap

class JemiCaptureService : Service() {
    private lateinit var windowManager: android.view.WindowManager
    private lateinit var floatingView: android.view.View

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager

        // フローティング・ウィンドウの設定（OSへの「貼り付け指定」）
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // Android 15対応
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // ゲームの操作を邪魔しない！
            android.graphics.PixelFormat.TRANSLUCENT
        )

        // レイアウトを膨らませて画面に追加！
        floatingView = android.view.LayoutInflater.from(this).inflate(R.layout.layout_floating_jemi, null)
        windowManager.addView(floatingView, params)
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // JemiCaptureService.kt の onStartCommand をこれに差し替え！
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "jemi_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 1. まずは通知チャンネルを作る（Android 8.0以降は必須！）
        val channel = NotificationChannel(channelId, "Jemi-Live Capture", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        // 2. 「実況中だよっ🌸」という通知を作る
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jemi-Live 起動中！")
            .setContentText("ヨチオさんの画面をパシャリする準備ができたよっ🌸")
            .build()

        // 3. 【最重要】OSに「今からキャプチャするよ！」と宣言してサービスを開始！
        // これを呼んだ直後じゃないと createVirtualDisplay は成功しないんだっ
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        // 4. MainActivityから送られてきた「許可証」を受け取る
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            intent?.getParcelableExtra("RESULT_DATA")
        }

        if (resultCode != 0 && resultData != null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // 許可証を使って MediaProjection オブジェクトを作る
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

        // Android 14/15 で必須になった「コールバックの登録」！
        // これを createVirtualDisplay の前に置くのが絶対条件だよっ🌟
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        // ImageReader の準備
        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        // ここでやっと VirtualDisplay を作成できるよっ！
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JemiCapture", width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            // 1. ここで Bitmap を作る（この部屋の中だけで 'bitmap' は有効だよ！）
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // 2. 【ここが重要！】Bitmapが生きているうちに、共有の机（ImageStorage）に置く！
            ImageStorage.capturedBitmap = bitmap

            // 3. 「置いたよ！」という合図を出す
            val broadcastIntent = Intent("JEMI_IMAGE_CAPTURED")
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)

            android.util.Log.d("Jemi-Live", "パシャリ成功して、机に置いたよっ！🌟")

            stopSelf() // テスト用に一枚で止めるよっ♪
        }, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
