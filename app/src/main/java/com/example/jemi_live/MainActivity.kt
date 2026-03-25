package com.example.jemi_live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Jemi-Live 準備室 (v3.0.7)
 * - [Fix] オーバーレイ権限（他のアプリの上に重ねて表示）のチェックを強化。
 * - [Update] 各種設定の保存とMediaProjectionの開始を担当。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var etApiKey: EditText
    private lateinit var etOmajinai: EditText
    private lateinit var rgDisplayMode: RadioGroup
    private lateinit var rgCaptureArea: RadioGroup
    private lateinit var cbDebugMode: CheckBox
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // MediaProjection（画面キャプチャ）の許可リクエスト結果を受け取るランチャーだよっ
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)

            val apiKey = etApiKey.text.toString()
            val omajinai = etOmajinai.text.toString()
            val selectedDisplayModeId = rgDisplayMode.checkedRadioButtonId
            val selectedCaptureAreaId = rgCaptureArea.checkedRadioButtonId
            val isSingleMode = selectedDisplayModeId == R.id.rb_display_single
            val isDebug = cbDebugMode.isChecked

            // 設定を保存しておくのですよっ♪
            prefs.edit().apply {
                putString("api_key", apiKey)
                putString("omajinai", omajinai)
                putInt("last_display_mode_id", selectedDisplayModeId)
                putInt("last_capture_area_id", selectedCaptureAreaId)
                putBoolean("is_single_display_mode", isSingleMode)
                putBoolean("last_debug_state", isDebug)
                apply()
            }

            // JemiCaptureServiceを起動して実況開始っ！🚀
            val serviceIntent = Intent(this, JemiCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
                putExtra("API_KEY", apiKey)
                putExtra("OMAJINAI", omajinai)
                putExtra("IS_DEBUG", isDebug)
            }
            startForegroundService(serviceIntent)
            finish() // 準備室は閉じて、サービスに任せるねっ♪
        } else {
            Toast.makeText(this, "画面キャプチャが許可されなかったよぉ💦", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ダークモードに固定するよっ
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.et_api_key)
        etOmajinai = findViewById(R.id.et_omajinai)
        rgDisplayMode = findViewById(R.id.rg_display_mode)
        rgCaptureArea = findViewById(R.id.rg_capture_area)
        cbDebugMode = findViewById(R.id.cb_debug_mode)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 最初にな権限の確認をするのですよっ
        setupPermissions()

        // 保存されている設定を読み込むねっ♪
        val prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etOmajinai.setText(prefs.getString("omajinai", ""))
        cbDebugMode.isChecked = prefs.getBoolean("last_debug_state", false)

        val lastDisplayModeId = prefs.getInt("last_display_mode_id", -1)
        if (lastDisplayModeId != -1) try { rgDisplayMode.check(lastDisplayModeId) } catch (e: Exception) {}

        val lastCaptureAreaId = prefs.getInt("last_capture_area_id", -1)
        if (lastCaptureAreaId != -1) try { rgCaptureArea.check(lastCaptureAreaId) } catch (e: Exception) {}

        findViewById<Button>(R.id.btn_start_live).setOnClickListener {
            // 起動直前にもう一度オーバーレイ権限をチェック！
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "「他のアプリの上に重ねて表示」を許可してね！🌸", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
                return@setOnClickListener
            }

            val intent = mediaProjectionManager.createScreenCaptureIntent()
            captureLauncher.launch(intent)
        }
    }

    /**
     * 🛡️ 必要な権限をまとめてチェック・リクエストするよっ！
     */
    private fun setupPermissions() {
        // オーバーレイ権限（WindowManager用）
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        // Android 13以上は通知権限も必要だねっ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}