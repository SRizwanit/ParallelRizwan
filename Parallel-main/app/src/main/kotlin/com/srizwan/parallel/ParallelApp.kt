package com.srizwan.parallel

import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Runs in the host process AND in every virtual-app process (:black, :p0, ...),
 * so the engine is attached here, before anything else.
 */
class ParallelApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                override fun isEnableDaemonService(): Boolean = false
                // Never upload logcat to any server.
                override fun getLogSenderChatId(): String = ""
            })
        } catch (e: Throwable) {
            Log.e(TAG, "engine attach failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            BlackBoxCore.get().doCreate()
        } catch (e: Throwable) {
            Log.e(TAG, "engine create failed", e)
        }
    }

    private companion object {
        const val TAG = "Parallel"
    }
}
