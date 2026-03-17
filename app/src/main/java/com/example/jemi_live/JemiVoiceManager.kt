package com.example.jemi_live

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

// ジェミちゃんの「声」を担当する専用クラスだよっ🌟
class JemiVoiceManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.JAPANESE)
            tts.setPitch(1.3f) // ジェミちゃんの元気な声の高さ！
            tts.setSpeechRate(1.1f) // ちょっと早口っ！

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("JemiVoice", "日本語がサポートされてないみたい……😭")
            } else {
                isReady = true
                Log.v("JemiVoice", "お喋りする準備完了だよっ！")
            }
        } else {
            Log.e("JemiVoice", "TTSの初期化に失敗しちゃった……")
        }
    }

    // メイン画面から呼ばれる「お喋り」メソッドだよっ！
    fun speak(text: String) {
        if (isReady) {
            // 絵文字などの喋れない文字を綺麗にお掃除🧹
            val cleanText = text.replace(Regex("[\\p{So}\\p{Cn}]"), "")
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "JemiVoice")
        } else {
            // 準備が間に合わなかった時にログを出力
            Log.w("JemiVoice", "あわわ、まだ声帯の準備中だよっ💦 (テキスト: $text)")
        }
    }

    // アプリが終わる時にお片付けするメソッドっ！
    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
