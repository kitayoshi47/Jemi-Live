package com.example.jemi_live

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

/**
 * 🔐 Gemini APIキーを安全に管理するクラス
 */
class ApiKeyManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "jemi_secure_prefs", // APIキー専用の暗号化ファイル
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(apiKey: String) {
        securePrefs.edit { putString("api_key", apiKey) }
    }

    fun getApiKey(): String? {
        return securePrefs.getString("api_key", null)
    }
}
