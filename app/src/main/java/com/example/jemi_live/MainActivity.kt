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
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var jemiVoice: JemiVoiceManager

    // UI部品をキャッシュするための変数
    private lateinit var tvCommentary: TextView
    private lateinit var ivPreview: ImageView

    // メインモードのUI
    private lateinit var rgModes: RadioGroup
    private lateinit var rgPresets: RadioGroup
    private lateinit var btnStartLive: Button

    // MainScope() は削除し、代わりに lifecycleScope を使用します

    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.1-flash-lite-preview",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    // クラスのプロパティにデバッグモードのスイッチを追加
    private val isDebugMode = true

    @SuppressLint("SetTextI18n")
    private suspend fun getJemiCommentary(bitmap: Bitmap) {
        if (isDebugMode) {
            // --- 🧪 デバッグ用のダミー処理 ---
            val dummyResponses = listOf(
                "わわっ！今のプレイ、めちゃくちゃカッコいいかもっ！🌸",
                "あちゃー、今の惜しいっ！次、次いこっ！✨",
                "ヨチオさん、天才じゃない！？今の動き、ジェミも見習いたいな〜🌟",
                "ふむふむ、ここは慎重に進むのが吉だねっ！大学生の知恵だよっ🎓",
                "えへへ、画面がキラキラしてて楽しいねっ！応援してるよっ！📣"
            )
            val dummyText = dummyResponses.random()

            runOnUiThread {
                tvCommentary.text = "ジェミちゃん(Debug)：$dummyText"
                jemiVoice.speak(dummyText)
            }
            return // ここで終了して、下のGemini呼び出しは行わない
        }

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
            val text = response.text ?: ""

            // runOnUiThreadを使わなくても、lifecycleScope.launch はデフォルトでメインスレッドで動くのでUI操作可能です
            tvCommentary.text = "ジェミちゃん：$text"

            // 📢 ここで実際に喋るよっ！！
            jemiVoice.speak(text)

        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "あわわ……お喋り失敗しちゃった：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val imageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // インテントから取るんじゃなくて、机から直接拾うっ！
            val bitmap = ImageStorage.capturedBitmap

            if (bitmap != null) {
                ivPreview.setImageBitmap(bitmap)

                // 拾ったあとは、机の上（ImageStorage）を片付けてメモリを空けやすくするよっ！
                ImageStorage.capturedBitmap = null

                // lifecycleScope を使うことで、Activity が閉じたら安全にキャンセルされるようになります
                lifecycleScope.launch {
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

        // Viewのキャッシュ（毎回 findViewById をしないため）
        tvCommentary = findViewById(R.id.tv_jemi_commentary)
        ivPreview = findViewById(R.id.iv_preview)
        rgModes = findViewById(R.id.rg_modes)
        rgPresets = findViewById(R.id.rg_presets)
        btnStartLive = findViewById(R.id.btn_start_live)

        // 権限の設定
        setupPermissions()

        // ボイスマネージャー
        jemiVoice = JemiVoiceManager(this)

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(imageReceiver, IntentFilter("JEMI_IMAGE_CAPTURED"))

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStartLive.setOnClickListener {
            // 選んだモードとジャンルを読み取る魔法だよっ🌸
            val selectedMode = findViewById<RadioButton>(rgModes.checkedRadioButtonId).text
            val selectedPreset = findViewById<RadioButton>(rgPresets.checkedRadioButtonId).text

            // ちゃんと選べてるか、画面の下にポワッと文字(Toast)を出して確認してみよう！
            Toast.makeText(this, "「$selectedMode」と「$selectedPreset」で開始するねっ！", Toast.LENGTH_SHORT).show()

            // 今まで通り、キャプチャの許可をもらう処理をスタート！
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onDestroy() {
        // メモリリークを防ぐため、Receiverの登録を解除するよっ！
        LocalBroadcastManager.getInstance(this).unregisterReceiver(imageReceiver)

        if (::jemiVoice.isInitialized) {
            jemiVoice.shutdown()
        }
        super.onDestroy()
    }

    private fun setupPermissions() {
        // 1. 魔法の透明レイヤー（オーバーレイ）の権限チェックだよっ！
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
            Toast.makeText(this, "「他のアプリの上に重ねて表示」を許可してねっ！", Toast.LENGTH_LONG).show()
        }

        // 2. 通知の権限チェック（Android 13 / API 33 以上）だよっ！
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }
}
