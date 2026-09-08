package com.lin.log.sample

import android.app.Application
import android.content.Context
import com.lin.log.LinLog
import com.lin.log.LinLogger
import com.lin.log.LogLevel
import java.io.File

/**
 * 官方 Sample 应用入口
 *
 * 演示：
 * 1. attachBaseContext 阶段的冷启动第 0 毫秒早鸟直写（Universal Early-Bird Spool）；
 * 2. 多领域（network, track, apm）物理隔离配置；
 * 3. 业务层扩展属性声明（保持底层 SDK 0 业务侵入）。
 *
 * @author LinLog Team
 * @since 1.0.0
 */
class SampleApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 🚀 冷启动第 0 毫秒早鸟直写：自动进入物理预写卷，Crash 绝不丢日志
        LinLog.i("LinLogSample") { "AttachBaseContext invoked, early bird log active!" }
        LinLog["apm"].i("APM early monitor starting before Application.onCreate")
    }

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化业务主通道日志
        LinLog.init(context = this, isDebug = true) {
            tag = "LinLogSample"
            enableThreadInfo = true
            stackTraceDepth = 3
            fileMinLevel = LogLevel.ALL
        }

        // 2. 独立物理领域【网络与长连接】：独立存储在 /linlog/network/ 目录
        LinLog.register("network") {
            tag = "NetworkLayer"
            fileLogDirectory = File(cacheDir, "linlog/network")
            maxFileSize = 2 * 1024 * 1024L
            maxDirCapacity = 10 * 1024 * 1024L
            fileMinLevel = LogLevel.ALL
        }

        // 3. 独立物理领域【用户行为埋点】：独立存储在 /linlog/track/ 目录
        LinLog.register("track") {
            tag = "UserTrack"
            fileLogDirectory = File(cacheDir, "linlog/track")
            maxFileSize = 1 * 1024 * 1024L
            maxDirCapacity = 10 * 1024 * 1024L
            fileMinLevel = LogLevel.INFO
        }

        // 4. 独立物理领域【APM 性能监控】：独立存储在 /linlog/apm/ 目录
        LinLog.register("apm") {
            tag = "ApmMonitor"
            fileLogDirectory = File(cacheDir, "linlog/apm")
            stackTraceDepth = 0 // 0 栈回溯开销
            fileMinLevel = LogLevel.DEBUG
        }
    }
}

// ==================== 业务 App 层专属扩展属性（0 侵入 core:linlog） ====================
inline val LinLog.apm: LinLogger get() = get("apm")
inline val LinLog.net: LinLogger get() = get("network")
inline val LinLog.track: LinLogger get() = get("track")
