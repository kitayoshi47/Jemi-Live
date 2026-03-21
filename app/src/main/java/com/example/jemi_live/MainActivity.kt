package com.example.jemi_live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate // 💡 これを足したよ！

class MainActivity : AppCompatActivity() {
    private lateinit var etApiKey: EditText
    private lateinit var etOmajinai: EditText
    private lateinit var rgDisplayMode: RadioGroup
    private lateinit var rgCaptureArea: RadioGroup
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)

            val apiKey = etApiKey.text.toString()
            val omajinai = etOmajinai.text.toString()
            val selectedDisplayModeId = rgDisplayMode.checkedRadioButtonId
            val selectedCaptureAreaId = rgCaptureArea.checkedRadioButtonId
            val isSingleMode = selectedDisplayModeId == R.id.rb_display_single

            val serviceIntent = Intent(this, JemiCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
                putExtra("API_KEY", apiKey)
                putExtra("OMAJINAI", omajinai)
            }

            prefs.edit().apply {
                putString("api_key", apiKey)
                putString("omajinai", omajinai)
                putInt("last_display_mode_id", selectedDisplayModeId)
                putBoolean("is_single_display_mode", isSingleMode)
                putInt("last_capture_area_id", selectedCaptureAreaId)
                apply()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 💡 魂の1行！アプリを「常にダークモード」に固定するよっ！
        // これでシステムがライトモードでも、準備室が白くなることは絶対にないよぉ🌸
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.et_api_key)
        etOmajinai = findViewById(R.id.et_omajinai)
        rgDisplayMode = findViewById(R.id.rg_display_mode)
        rgCaptureArea = findViewById(R.id.rg_capture_area)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupPermissions()

        val prefs = getSharedPreferences("JemiSettings", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etOmajinai.setText(prefs.getString("omajinai", ""))

        val lastDisplayModeId = prefs.getInt("last_display_mode_id", -1)
        if (lastDisplayModeId != -1) try { rgDisplayMode.check(lastDisplayModeId) } catch (e: Exception) {}

        val lastCaptureAreaId = prefs.getInt("last_capture_area_id", -1)
        if (lastCaptureAreaId != -1) try { rgCaptureArea.check(lastCaptureAreaId) } catch (e: Exception) {}

        findViewById<Button>(R.id.btn_start_live).setOnClickListener {
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            captureLauncher.launch(intent)
        }
    }

    private fun setupPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}