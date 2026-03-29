package com.jemi.live.logic

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 🎫 AIコマンド実行チケットを管理するクラスなのですっ！
 * ヨチオさんの指摘を受けて、チケット消費後から正確に10秒数えるように改良したよっ♪
 */
class TicketManager {
    companion object {
        private const val TAG = "TicketManager"
        private const val MAX_TICKETS = 4
        private const val REFILL_INTERVAL_MS = 10000L // 10秒ごとに1枚回復
    }

    private val _ticketCount = MutableStateFlow(MAX_TICKETS)
    val ticketCount: StateFlow<Int> = _ticketCount

    private val handler = Handler(Looper.getMainLooper())
    private var isRefilling = false

    // チケット回復用のタスクだよっ
    private val refillRunnable = object : Runnable {
        override fun run() {
            if (_ticketCount.value < MAX_TICKETS) {
                _ticketCount.value += 1
                Log.d(TAG, "🎫 チケットが回復したよっ！ 現在: ${_ticketCount.value}枚")

                // まだ満タンじゃないなら、次の10秒を予約するのですよっ！
                if (_ticketCount.value < MAX_TICKETS) {
                    handler.postDelayed(this, REFILL_INTERVAL_MS)
                } else {
                    isRefilling = false
                    Log.d(TAG, "🔋 チケットが満タンになったからタイマーを止めるねっ！")
                }
            } else {
                isRefilling = false
            }
        }
    }

    /**
     * チケット回復システムを準備するのですよっ！
     * (Service起動時に呼ばれるけど、実際の回復は消費されてから始まるよっ)
     */
    fun startRefillSystem() {
        Log.d(TAG, "🚀 チケットシステム・スタンバイOKなのですっ！")
        // 初期状態では満タンなので、ここではまだループは回さないよっ
    }

    /**
     * チケット回復システムを完全に停止するんだよっ
     */
    fun stopRefillSystem() {
        Log.d(TAG, "🛑 チケットシステム停止なのですっ")
        handler.removeCallbacks(refillRunnable)
        isRefilling = false
    }

    /**
     * チケットを1枚消費するよっ
     * @return 消費に成功したらtrue、足りなければfalse
     */
    fun consumeTicket(): Boolean {
        return if (_ticketCount.value > 0) {
            _ticketCount.value -= 1
            Log.d(TAG, "🎫 チケットを消費したよっ！ 残り: ${_ticketCount.value}枚")

            // 消費した瞬間に、もしタイマーが動いてなければ開始するんだよっ！
            // これで「消費してからきっちり10秒」が守れるのですよっ✨
            if (!isRefilling) {
                isRefilling = true
                handler.postDelayed(refillRunnable, REFILL_INTERVAL_MS)
            }
            true
        } else {
            Log.w(TAG, "⚠️ チケットが足りないのですよっ！")
            false
        }
    }

    /**
     * デバッグ用：チケットを強制的にリセットするんだよっ
     */
    fun resetTickets() {
        _ticketCount.value = MAX_TICKETS
        handler.removeCallbacks(refillRunnable)
        isRefilling = false
        Log.d(TAG, "🔄 チケットをリセットしたよっ！")
    }
}