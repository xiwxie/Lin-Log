package com.lin.log.printer

import android.util.Log
import com.lin.log.LinLogConstants

/**
 * 针对 Android Logcat 的控制台输出打印器
 *
 * 具备长日志分片与换行智能对齐能力，防止底层 Logcat 4KB 物理截断。
 *
 * @param maxChunkSize 单次打印的最大字符分片阈值，默认从 [LinLogConstants.MAX_LOGCAT_CHUNK_SIZE] 读取
 */
public class AndroidLogPrinter(
    private val maxChunkSize: Int = LinLogConstants.MAX_LOGCAT_CHUNK_SIZE
) : LogPrinter {

    override fun print(entry: LogEntry) {
        val fullMessage = buildString {
            if (entry.threadName != null) {
                append("[").append(entry.threadName).append("] ")
            }
            append(entry.message)
            if (entry.stackTrace != null) {
                append("\n").append(entry.stackTrace)
            }
        }

        val length = fullMessage.length
        if (length <= maxChunkSize) {
            printChunk(entry.level, entry.tag, fullMessage)
            return
        }

        // 分片打印，优先在最近的换行符截断以保持阅读完整性
        var start = 0
        while (start < length) {
            var end = (start + maxChunkSize).coerceAtMost(length)
            if (end < length) {
                val nextLineBreak = fullMessage.lastIndexOf('\n', end)
                if (nextLineBreak > start) {
                    end = nextLineBreak + 1
                }
            }
            printChunk(entry.level, entry.tag, fullMessage.substring(start, end))
            start = end
        }
    }

    private fun printChunk(level: Int, tag: String, msg: String) {
        when (level) {
            Log.VERBOSE -> Log.v(tag, msg)
            Log.DEBUG -> Log.d(tag, msg)
            Log.INFO -> Log.i(tag, msg)
            Log.WARN -> Log.w(tag, msg)
            Log.ERROR -> Log.e(tag, msg)
            else -> Log.d(tag, msg)
        }
    }
}
