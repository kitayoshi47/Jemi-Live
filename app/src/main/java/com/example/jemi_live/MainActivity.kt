package com.example.jemi_live

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var tts: android.speech.tts.TextToSpeech

    private val mainScope = MainScope()
    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.1-flash-lite-preview",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    @SuppressLint("SetTextI18n")
    private suspend fun getJemiCommentary(bitmap: Bitmap) {
        val prompt = "あなたは実況者の『ジェミちゃん』です。このゲーム画面を見て、明るく元気に、23歳の大学生らしい口調で一言実況して！"

        try {
            // Geminiさんに「画像とプロンプト」を渡す魔法の瞬間っ！
            val response = generativeModel.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )

            // 返ってきた言葉を画面に表示するよっ🌸
            runOnUiThread {
                val tvCommentary = findViewById<android.widget.TextView>(R.id.tv_jemi_commentary)
                val text = response.text ?: ""
                tvCommentary.text = "ジェミちゃん：$text"

                // 📢 ここで実際に喋るよっ！！
                // QUEUE_FLUSH は「今喋ってる途中でも、新しいのが来たらそっちを優先してね！」っていう設定だよ🌟
                // 正規表現で「絵文字や記号」を空っぽに置き換えるよ🌟
                val cleanText = text.replace(Regex("[\\p{So}\\p{Cn}]"), "")
                tts.speak(cleanText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "JemiVoice")
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "あわわ……お喋り失敗しちゃった：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val imageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // インテントから取るんじゃなくて、机から直接拾うっ！
            val bitmap = ImageStorage.capturedBitmap

            if (bitmap != null) {
                findViewById<ImageView>(R.id.iv_preview).setImageBitmap(bitmap)
                // imageReceiver の onReceive の中、setImageBitmap(bitmap) のすぐ下に追加！
                mainScope.launch {
                    getJemiCommentary(bitmap)
                }
                Toast.makeText(context, "ジェミちゃんの視界を共有したよっ🌸", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val startMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // 1. サービスに「許可証（resultData）」を添えて起動する！
            val serviceIntent = Intent(this, JemiCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
            }
            startForegroundService(serviceIntent)

            Toast.makeText(this, "ジェミちゃん：バトンタッチ完了！実況準備に入るねっ🌸", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "ジェミちゃん：あれれ、見えなくなっちゃった……🥹", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 他のアプリの上に重ねるための権限チェック
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // 「声」の初期化
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                // 日本語を話すように設定するよっ♪
                val result = tts.setLanguage(java.util.Locale.JAPANESE)
                // 1.2f くらいにすると、少し高めの元気な女の子の声になるよっ♪
                tts.setPitch(1.3f)
                // 1.1f くらいにすると、ハキハキした感じになるよっ🌟
                tts.setSpeechRate(1.1f)
                if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                    result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                    android.util.Log.e("TTS", "日本語がサポートされてないみたい……😭")
                } else {
                    android.util.Log.v("TTS", "お喋りする準備完了だよ！")
                }
            }
        }

        // onCreate の中に追加（受信機を登録！）
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(imageReceiver, IntentFilter("JEMI_IMAGE_CAPTURED"))

        // onCreate の中、ボタンの設定の前あたりに置いてねっ！
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // ボタンを探して変数に入れる
        val btnCommentary = findViewById<Button>(R.id.btn_commentary)

        // マネージャーの初期化
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // ボタンが押された時の処理！
        btnCommentary.setOnClickListener {
            // 画面キャプチャ許可のインテント（お願い状）を飛ばす！
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onDestroy() {
        // アプリが終わるときにTTSを止めて、資源を解放するよっ！
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}