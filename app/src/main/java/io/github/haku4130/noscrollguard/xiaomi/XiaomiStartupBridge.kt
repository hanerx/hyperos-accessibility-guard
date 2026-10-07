package io.github.haku4130.noscrollguard.xiaomi

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import io.github.haku4130.noscrollguard.GuardApp

object XiaomiStartupBridge {
    private const val TAG = "GuardTrace"
    private val provider = Uri.parse("content://com.mitv.security.permission")
    private const val TVMANAGER_PACKAGE = "com.xiaomi.mitv.tvmanager"

    fun installAndLog(context: Context) {
        val log = GuardApp.eventLog(context)
        val before = runCatching {
            Settings.System.getString(context.contentResolver, "start_3rd_app")
        }.getOrNull()
        log.append(System.currentTimeMillis(), "[xiaomi-bridge] start_3rd_app before=" + before)

        val extras = Bundle().apply {
            putString("start_3rd_app", context.packageName)
        }
        try {
            val result = context.contentResolver.call(
                provider, "start3rdApp", TVMANAGER_PACKAGE, extras
            )
            val after = Settings.System.getString(context.contentResolver, "start_3rd_app")
            Log.i(TAG, "Xiaomi start3rdApp result=" + result + " before=" + before + " after=" + after)
            log.append(
                System.currentTimeMillis(),
                "[xiaomi-bridge] start3rdApp accepted result=" + result + " after=" + after
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Xiaomi start3rdApp rejected", error)
            log.append(
                System.currentTimeMillis(),
                "[xiaomi-bridge] start3rdApp rejected: " + error.javaClass.simpleName + ": " + error.message
            )
        }
    }
}
