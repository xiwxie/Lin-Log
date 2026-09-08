package com.lin.log.internal

import android.os.SystemClock
import com.lin.log.LogLevel
import com.lin.log.printer.LogEntry
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 物理早鸟预写卷：负责在任何线程、任何领域未配置前提供无死角物理落盘
 */
internal class EarlyBirdSpooler(private val domainName: String) {

    private val lock: ReentrantLock = ReentrantLock()
    private val timeFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null

    val spoolFile: File by lazy {
        val root = EarlyBirdContext.getSafeDirectory("linlog/early_bird")
        File(root, "spool_${domainName}_${SystemClock.elapsedRealtime()}.eblog")
    }

    fun write(entry: LogEntry) {
        lock.withLock {
            try {
                val parentDir = spoolFile.parentFile
                val isMissing = writer == null || !spoolFile.exists() || parentDir?.exists() != true
                if (isMissing) {
                    try {
                        writer?.close()
                    } catch (_: Throwable) {}
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs()
                    }
                    writer = BufferedWriter(FileWriter(spoolFile, true), 8192)
                }
                val sb = StringBuilder(128)
                    .append(timeFormat.format(Date(entry.timestamp)))
                    .append('|')
                    .append(LogLevel.getShortName(entry.level))
                    .append('|')
                    .append(entry.tag)
                    .append('|')
                if (entry.threadName != null) {
                    sb.append('[').append(entry.threadName).append(']').append('|')
                }
                sb.append(entry.message)
                if (entry.stackTrace != null) {
                    sb.append('\n').append(entry.stackTrace)
                }
                sb.append('\n')
                writer?.write(sb.toString())
                writer?.flush() // 早鸟日志实时强制刷盘，确保启动期 Crash 物理在盘
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 将预写早鸟日志物理归档至正式日志目录，随后安全删除早鸟文件
     */
    fun drainToTargetDirectory(targetDir: File, targetFileName: String) {
        lock.withLock {
            try {
                writer?.flush()
                writer?.close()
                writer = null

                if (spoolFile.exists() && spoolFile.length() > 0) {
                    if (!targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    val destFile = File(targetDir, targetFileName)
                    // 以追加方式直接将早鸟物理字节灌入正式文件（必须显式 append = true，严禁 truncate 截断旧数据）
                    spoolFile.inputStream().use { input ->
                        FileOutputStream(destFile, true).use { output ->
                            input.copyTo(output)
                        }
                    }
                    spoolFile.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun release() {
        lock.withLock {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Throwable) {}
            writer = null
        }
    }
}
