package com.lin.log.cleaner

import android.os.SystemClock
import android.util.Log
import com.lin.log.LinLogConstants
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 具备防系统时间篡改能力的日志存储安全清理器
 *
 * 核心防御策略：
 * 1. **单调时钟配对锚点**：依托 [SystemClock.elapsedRealtime] 构建单调时间戳，免疫用户将系统时间拨到未来导致的误清空。
 * 2. **物理容量硬顶淘汰**：当总目录容量突破 [maxDirectoryBytes] 时，按 FIFO 淘汰最老文件直至降至安全水位。
 * 3. **热文件排除保护**：显式排除当前正在写入的活跃日志文件，防止句柄失效。
 * 4. **异常超前时间窗口隔离**：将篡改到未来的文件隔离，避免误杀正常历史日志。
 *
 * @param maxDirectoryBytes 日志目录物理存储容量硬限制，默认 30MB
 * @param maxKeepDaysMillis 日志文件默认保存时长（毫秒），默认 7 天
 */
public class SafeLogCleaner(
    private val maxDirectoryBytes: Long = LinLogConstants.DEFAULT_MAX_DIR_CAPACITY,
    private val maxKeepDaysMillis: Long = LinLogConstants.DEFAULT_MAX_KEEP_DAYS_MILLIS
) {
    // 系统绝对时间锚点（毫秒）
    private val anchorWallTime = AtomicLong(System.currentTimeMillis())
    // 物理单调时钟锚点（开机毫秒数）
    private val anchorElapsedTime = AtomicLong(SystemClock.elapsedRealtime())

    /**
     * 当客户端接收到服务端权威授时（如网络响应头 Date）时调用，校准防篡改锚点
     *
     * @param serverTimeMillis 服务端权威当前时间戳（毫秒）
     */
    public fun updateServerTimeAnchor(serverTimeMillis: Long) {
        anchorWallTime.set(serverTimeMillis)
        anchorElapsedTime.set(SystemClock.elapsedRealtime())
    }

    /**
     * 计算并获取防篡改的可信单调绝对时间戳
     */
    public fun getReliableCurrentTime(): Long {
        val elapsedDiff = SystemClock.elapsedRealtime() - anchorElapsedTime.get()
        return if (elapsedDiff >= 0) anchorWallTime.get() + elapsedDiff else System.currentTimeMillis()
    }

    /**
     * 执行全流程安全扫描与智能淘汰（建议在 IO 协程/后台线程中调用）
     *
     * @param logDir 需要扫描清理的日志目标目录
     * @param excludeFile 排除当前正在写入的热日志文件，防止被淘汰误删
     */
    public fun clean(logDir: File, excludeFile: File? = null) {
        if (!logDir.exists() || !logDir.isDirectory) return

        val files = logDir.listFiles { file -> file.isFile } ?: return
        if (files.isEmpty()) return

        val now = getReliableCurrentTime()

        // 1. 基于防篡改时间的生命周期检查（跳过当前热文件）
        for (file in files) {
            if (excludeFile != null && file.absolutePath == excludeFile.absolutePath) continue

            val lastModified = file.lastModified()
            val age = now - lastModified

            // 防御：若最后修改时间比可靠当前时间超前 24 小时以上，说明是在被篡改的未来时间写入的脏文件，跳过时间清理，交由容量策略处理
            if (lastModified - now > LinLogConstants.FUTURE_TIMESTAMP_THRESHOLD_MILLIS) {
                continue
            }

            // 正常过期删除
            if (age > maxKeepDaysMillis) {
                try {
                    file.delete()
                } catch (t: Throwable) {
                    Log.w(LinLogConstants.DEFAULT_TAG, "Failed to delete expired log: ${file.name}", t)
                }
            }
        }

        // 2. 基于物理容量的 LRU/FIFO 兜底硬防御与低存储空间紧急熔断（排除当前热文件）
        val remainingFiles = logDir.listFiles { file ->
            file.isFile && (excludeFile == null || file.absolutePath != excludeFile.absolutePath)
        } ?: return
        var totalSize = remainingFiles.sumOf { it.length() }

        // 设备剩余存储空间安全感知：若宿主存储剩余低于 50MB，触发熔断性强力削峰，目标水位降至 30%
        val usableSpace = logDir.usableSpace
        val isLowStorage = usableSpace in 1 until LinLogConstants.LOW_STORAGE_EMERGENCY_THRESHOLD_BYTES
        val watermarkRatio = if (isLowStorage) 0.3 else LinLogConstants.CAPACITY_SAFE_WATERMARK_RATIO

        if (totalSize > maxDirectoryBytes || isLowStorage) {
            // 按最后修改时间升序排列（最旧的排在前面）
            val sortedFiles = remainingFiles.sortedBy { it.lastModified() }
            val targetSize = (maxDirectoryBytes * watermarkRatio).toLong()
            for (file in sortedFiles) {
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                }
                if (totalSize <= targetSize) {
                    break
                }
            }
        }
    }
}

