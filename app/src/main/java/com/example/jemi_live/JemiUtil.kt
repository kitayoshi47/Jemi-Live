package com.example.jemi_live

import android.os.Build

class JemiUtil {
}

/**
 * エミュレータ環境かどうかのチェック
 */
fun isAndroidStudioEmulator(): Boolean {
    return Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.PRODUCT.contains("sdk_gphone")
            || Build.PRODUCT.contains("google_sdk")
            || Build.PRODUCT.contains("sdk")
}
