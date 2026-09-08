package com.lin.log.internal

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import java.io.File

@PublishedApi
internal object EarlyBirdContext {

    @Volatile
    private var cachedContext: Context? = null

    /**
     * 免初始化获取可用沙盒目录：优先使用业务注入的 Context，未注入时自省获取宿主 Application
     */
    fun getSafeDirectory(subDir: String): File {
        val ctx = cachedContext ?: resolveApplicationContext()
        val baseDir = ctx?.cacheDir ?: File("/data/local/tmp")
        val target = File(baseDir, subDir)
        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    fun injectContext(context: Context) {
        if (cachedContext == null) {
            cachedContext = context.applicationContext
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun resolveApplicationContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getDeclaredMethod("currentApplication")
            currentAppMethod.isAccessible = true
            val app = currentAppMethod.invoke(null) as? Application
            app?.also { cachedContext = it }
        } catch (_: Throwable) {
            null
        }
    }
}
