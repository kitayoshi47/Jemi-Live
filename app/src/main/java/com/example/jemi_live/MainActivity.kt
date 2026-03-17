package com.example.jemi_live

import android.Manifest
import android.content.Intent
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

class MainActivity : AppCompatActivity() {
    private lateinit var jemiVoice: JemiVoiceManager
    private lateinit var tvCommentary: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var rgModes: RadioGroup
    private lateinit var rgPresets: RadioGroup
    private lateinit var btnStartLive: Button
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
