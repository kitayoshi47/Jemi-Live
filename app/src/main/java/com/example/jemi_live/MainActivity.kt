package com.example.jemi_live

import android.content.Intent
import android.media.projection.MediaProjectionManager
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
import androidx.core.net.toUri

/**
 * Jemi-Live 準備室 (v3.0.8 - Secure Key Implementation)
 */
class MainActivity : AppCompatActivity() {
    private lateinit var etApiKey: EditText
    private lateinit var etOmajinai: EditText
    private lateinit var rgDisplayMode: RadioGroup
    private lateinit var rgCaptureArea: RadioGroup
    private lateinit var cbDebugMode: CheckBox
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var apiKeyManager: ApiKeyManager

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val prefs = getSharedPreferences("JemiSettings", MODE_PRIVATE)

            val apiKey = etApiKey.text.toString()
            val omajinai = etOmajinai.text.toString()
            val selectedDisplayModeId = rgDisplayMode.checkedRadioButtonId
            val selectedCaptureAreaId = rgCaptureArea.checkedRadioButtonId
            val isSingleMode = selectedDisplayModeId == R.id.rb_display_single
            val isDebug = cbDebugMode.isChecked

            // 🔐 APIキーは暗号化して保存するよっ！
            apiKeyManager.saveApiKey(apiKey)

            // その他の設定は今まで通り保存ね♪
            prefs.edit().apply {
                putString("omajinai", omajinai)
                putInt("last_display_mode_id", selectedDisplayModeId)
                putInt("last_capture_area_id", selectedCaptureAreaId)
                putBoolean("is_single_display_mode", isSingleMode)
                putBoolean("last_debug_state", isDebug)
                apply()
            }

            val serviceIntent = Intent(this, JemiCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
                putExtra("API_KEY", apiKey)
                putExtra("OMAJINAI", omajinai)
                putExtra("IS_DEBUG", isDebug)
            }
            startForegroundService(serviceIntent)
            finish()
        } else {
            Toast.makeText(this, "画面キャプチャが許可されなかったよぉ💦", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)

        // 🔐 Managerを初期化
        apiKeyManager = ApiKeyManager(this)

        etApiKey = findViewById(R.id.et_api_key)
        etOmajinai = findViewById(R.id.et_omajinai)
        rgDisplayMode = findViewById(R.id.rg_display_mode)
        rgCaptureArea = findViewById(R.id.rg_capture_area)
        cbDebugMode = findViewById(R.id.cb_debug_mode)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupPermissions()

        val prefs = getSharedPreferences("JemiSettings", MODE_PRIVATE)

        // 🔐 暗号化ストレージからキーを読み込むのですよっ♪
        etApiKey.setText(apiKeyManager.getApiKey() ?: "")

        etOmajinai.setText(prefs.getString("omajinai", ""))
        cbDebugMode.isChecked = prefs.getBoolean("last_debug_state", false)

        val lastDisplayModeId = prefs.getInt("last_display_mode_id", -1)
        if (lastDisplayModeId != -1) try { rgDisplayMode.check(lastDisplayModeId) } catch (_: Exception) {}

        val lastCaptureAreaId = prefs.getInt("last_capture_area_id", -1)
        if (lastCaptureAreaId != -1) try { rgCaptureArea.check(lastCaptureAreaId) } catch (_: Exception) {}

        findViewById<Button>(R.id.btn_start_live).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "「他のアプリの上に重ねて表示」を許可してね！🌸", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                startActivity(intent)
                return@setOnClickListener
            }
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            captureLauncher.launch(intent)
        }
    }

    private fun setupPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            startActivity(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}