package com.example.jemi_live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

/**
 * MainActivity (v2.6 - Build Fix)
 * - ユーザー設定（APIキー、おまじない）の永続化
 * - 動作モード・ジャンルの選択ロジック統合
 * - 参照エラー修正（R.id.rg_modes 等の不整合を解消）
 */
class MainActivity : AppCompatActivity() {
    private lateinit var etApiKey: EditText
    private lateinit var etOmajinai: EditText
    private lateinit var rgModes: RadioGroup
    private lateinit var rgPresets: RadioGroup
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // スクリーンキャプチャの許可ダイアログからの戻り
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)

            val apiKey = etApiKey.text.toString()
            val omajinai = etOmajinai.text.toString()
            val selectedModeId = rgModes.checkedRadioButtonId
            val selectedPresetId = rgPresets.checkedRadioButtonId

            // 選択されたテキストを取得するよ
            val modeText = findViewById<RadioButton>(selectedModeId)?.text?.toString() ?: ""
            val presetText = findViewById<RadioButton>(selectedPresetId)?.text?.toString() ?: ""

            // サービスを起動して設定を渡すよっ🌸
            val serviceIntent = Intent(this, JemiCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
                putExtra("API_KEY", apiKey)
                putExtra("OMAJINAI", omajinai)
                putExtra("SELECTED_MODE", modeText)
                putExtra("SELECTED_PRESET", presetText)
            }

            // 開始時に設定を保存！
            prefs.edit().apply {
                putString("api_key", apiKey)
                putString("omajinai", omajinai)
                putInt("last_mode_id", selectedModeId)
                putInt("last_preset_id", selectedPresetId)
                apply()
            }

            startForegroundService(serviceIntent)
            Toast.makeText(this, "実況準備に入るねっ！いってらっしゃい🌸", Toast.LENGTH_SHORT).show()
            finish() // 準備画面を閉じてゲームに集中！
        } else {
            Toast.makeText(this, "画面が見えないと実況できないよぉ……🥹", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Viewの紐付け（activity_main.xml の ID と一致させているよ）
        etApiKey = findViewById(R.id.et_api_key)
        etOmajinai = findViewById(R.id.et_omajinai)
        rgModes = findViewById(R.id.rg_modes)
        rgPresets = findViewById(R.id.rg_presets)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 権限の準備
        setupPermissions()

        // 💾 保存された設定を復元
        val prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etOmajinai.setText(prefs.getString("omajinai", "23歳の女子大生らしく明るく元気に応援して！"))

        // 前回の選択状態を復元（IDが存在する場合のみ）
        val lastModeId = prefs.getInt("last_mode_id", -1)
        if (lastModeId != -1) {
            try {
                rgModes.check(lastModeId)
            } catch (e: Exception) {
                // IDが変わっている場合はデフォルトを選択
            }
        }

        val lastPresetId = prefs.getInt("last_preset_id", -1)
        if (lastPresetId != -1) {
            try {
                rgPresets.check(lastPresetId)
            } catch (e: Exception) {
                // IDが変わっている場合はデフォルトを選択
            }
        }

        // 🚀 LIVE開始ボタン
        findViewById<Button>(R.id.btn_start_live).setOnClickListener {
            if (etApiKey.text.isNullOrEmpty()) {
                Toast.makeText(this, "APIキーを入れてねっ💦", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val modeText = findViewById<RadioButton>(rgModes.checkedRadioButtonId)?.text ?: "未選択"
            val presetText = findViewById<RadioButton>(rgPresets.checkedRadioButtonId)?.text ?: "未選択"
            Toast.makeText(this, "「$modeText」と「$presetText」で開始するねっ！", Toast.LENGTH_SHORT).show()

            captureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun setupPermissions() {
        // オーバーレイ権限
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        }

        // 通知権限（Android 13以上）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }
}