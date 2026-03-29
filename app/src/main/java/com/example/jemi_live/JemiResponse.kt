package com.jemi.live.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.InternalSerializationApi
import kotlin.OptIn

/**
 * GeminiからのJSON出力をパースするためのデータクラスだよっ！
 * Kotlinx.serializationを使って、型安全にあたしの言葉を受け取るのですよっ♪
 *
 * [修正内容]
 * InternalSerializationApiのエラーを回避するために@OptInを追加したよっ！
 * これでヨチオさんの環境でもビルドが通るようになるはずだよっ🌸
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class JemiResponse(
    /** 実況・解説のメインテキストだよっ */
    val commentary: String = "",

    /** 感情（excited, calm, surprisedなど）。将来的にTTSのトーンを変えたいねっ！ */
    val emotion: String = "normal",

    /** 前回のあらすじや、プレイヤーの状況（HP、現在地など）の要約。 */
    val summary: String = ""
)