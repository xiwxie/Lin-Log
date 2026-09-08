package com.lin.log.printer.file

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.lin.log.LinLogConstants
import com.lin.log.LogLevel
import com.lin.log.internal.LinLogDispatcher
import com.lin.log.cleaner.SafeLogCleaner
import com.lin.log.printer.LogEntry
import com.lin.log.printer.LogPrinter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 高性能异步文件日志写入器
 *
 * 核心技术架构：
 * 1. **双模线程调度架构**：
 *    - 默认共享模式 (`isolateThread = false`)：复用全局单一受管调度器 [LinLogDispatcher]，无论开多少个桶全库恒定 1 个线程；
 *    - 独立专线模式 (`isolateThread = true`)：针对超高频 APM 或核心支付领域独占专属线程，物理隔离慢 I/O 与内存积压。
 * 2. **协程 Channel 削峰填谷**：主调用线程 0 阻塞，后台批量消费最多 64 条日志，单次批量落盘，降低内核态切换开销。
 * 3. **物理存活性感知自愈机制**：开发调试阶段若日志目录或文件被外部删除 (`rm -rf`)，自动识别并原地重建父目录和新文件。
 * 4. **按天 + 单文件大小自动滚动**：单文件超过 [maxFileSize] 时自动切分序号文件（基于 [FileNameGenerator] 生成）。
 * 5. **冷启动 0 阻塞**：构造器内 0 磁盘 I/O，目录创建和文件清理全异步静默执行。
 *
 * @param logDirectory 日志文件存储根目录
 * @param minLevel 写入磁盘的最低门槛级别，低于此级别的日志只在控制台输出，不写入磁盘
 * @param maxFileSize 单个日志文件最大字节容量，超过此容量自动触发轮转，默认 2MB
 * @param fileNameGenerator 文件命名生成策略，默认为 [DefaultFileNameGenerator]
 * @param cleaner 关联的防时间篡改安全清理器
 * @param bufferCapacity 内存通道容量缓冲区大小
 * @param isolateThread 是否独占独立后台线程
 * @param domainTag 领域标签名称（用于独立线程命名与追踪）
 */
public class AsyncFilePrinter(
    public val logDirectory: File,
    public val minLevel: Int = LogLevel.ALL,
    private val maxFileSize: Long = LinLogConstants.DEFAULT_MAX_FILE_SIZE,
    private val fileNameGenerator: FileNameGenerator = DefaultFileNameGenerator(),
    public val cleaner: SafeLogCleaner = SafeLogCleaner(),
    private val bufferCapacity: Int = 2048,
    public val isolateThread: Boolean = false,
    public val domainTag: String = "Common"
) : LogPrinter {

    private val logChannel = Channel<LogEntry>(capacity = bufferCapacity)
    private val isRunning = AtomicBoolean(true)

    // 独立专线执行器：仅在 isolateThread = true 时按需开辟
    private val dedicatedExecutor = if (isolateThread) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "${LinLogConstants.THREAD_NAME_ASYNC_WRITER}-$domainTag").apply {
                priority = Process.THREAD_PRIORITY_BACKGROUND
            }
        }
    } else null

    private val writerScope: CoroutineScope = if (dedicatedExecutor != null) {
        CoroutineScope(SupervisorJob() + dedicatedExecutor.asCoroutineDispatcher())
    } else {
        LinLogDispatcher.writerScope
    }

    @Volatile
    private var currentFile: File? = null
    private var currentWriter: BufferedWriter? = null
    private var currentFileLength: Long = 0L
    private var currentDayIndex: String = ""
    private var fileIndex: Int = 0

    private val dayFormat = SimpleDateFormat(LinLogConstants.DATE_PATTERN_DAY, Locale.US)
    private val timeFormat = SimpleDateFormat(LinLogConstants.DATE_PATTERN_TIMESTAMP, Locale.US)

    init {
        // 构造器内 0 磁盘 I/O，所有目录创建与消费监听全异步启动
        startConsumerLoop()
        triggerAsyncClean()
    }

    private fun startConsumerLoop() {
        writerScope.launch {
            try {
                ensureDirectoryExists()

                val batchBuffer = mutableListOf<LogEntry>()
                // 平滑消费：通道关闭后仍会消费完剩余全部数据
                for (first in logChannel) {
                    batchBuffer.add(first)

                    // 批量聚合抽取最多 64 条，实现削峰填谷
                    while (batchBuffer.size < LinLogConstants.BATCH_DRAIN_LIMIT) {
                        val next = logChannel.tryReceive().getOrNull() ?: break
                        batchBuffer.add(next)
                    }

                    writeBatch(batchBuffer)
                    batchBuffer.clear()
                }
            } catch (t: Throwable) {
                Log.e(LinLogConstants.DEFAULT_TAG, "Fatal error in AsyncWriter consumer loop", t)
            } finally {
                // 通道关闭且数据完全抽干后，安全释放底层文件句柄
                closeCurrentWriter()
            }
        }
    }

    private fun writeBatch(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        try {
            ensureWriterOpen()
            val writer = currentWriter ?: return

            val sb = StringBuilder(entries.size * 128)
            for (i in entries.indices) {
                val entry = entries[i]
                sb.append(timeFormat.format(Date(entry.timestamp)))
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
            }

            val text = sb.toString()
            writer.write(text)
            writer.flush()
            
            // 0 内存堆分配：准确计算 UTF-8 物理字节数，避免字符与字节混淆导致 2MB 轮转失效
            currentFileLength += calculateUtf8ByteCount(text)

            // 达到单文件容量上限，触发轮转
            if (currentFileLength >= maxFileSize) {
                rotateFile()
            }
        } catch (t: Throwable) {
            Log.e(LinLogConstants.DEFAULT_TAG, "Write batch log failed, triggering self-healing recovery", t)
            // 异常驱动自愈：发生写入异常（如文件被删/句柄断开）重置句柄，下一次写入将自动自愈重建
            closeCurrentWriter()
        }
    }

    /**
     * 0 堆内存分配计算 UTF-8 字节数，严禁通过 text.toByteArray() 产生 GC 抖动
     */
    private fun calculateUtf8ByteCount(sequence: CharSequence): Long {
        var count = 0L
        var i = 0
        val len = sequence.length
        while (i < len) {
            val ch = sequence[i].code
            when {
                ch <= 0x7F -> count += 1
                ch <= 0x7FF -> count += 2
                ch in 0xD800..0xDBFF -> {
                    count += 4
                    i++ // 跳过 UTF-16 低位代理对
                }
                else -> count += 3
            }
            i++
        }
        return count
    }

    /**
     * 写入前检查：
     * 1. 如果是新的一天 -> 切新文件
     * 2. 如果 currentWriter 为空 -> 打开文件
     * 3. 如果底层文件或父目录被外部手动删除 (rm -rf) -> 识别并自动原地自愈重建
     */
    private fun ensureWriterOpen() {
        val today = dayFormat.format(Date())
        val isDateChanged = today != currentDayIndex
        // 防御 Linux 文件系统已打开句柄被 unlink 后 write 不抛异常的内核特性：
        // 显式感知当前物理文件或目录是否已被外部删除
        val isPhysicalMissing = currentWriter == null || currentFile?.exists() != true || !logDirectory.exists()

        if (isPhysicalMissing || isDateChanged) {
            if (isDateChanged || !logDirectory.exists()) {
                currentDayIndex = today
                fileIndex = 0
                triggerAsyncClean()
            }
            openNewFile()
        }
    }

    /**
     * 当前正在写入的物理热文件句柄
     */
    public val activeFile: File? get() = currentFile

    /**
     * 主动将当前正在写入的热日志文件刷盘封存，并平滑滚动开启下一个新文件（实现冷热隔离）
     * @return 刚刚滚动开启的全新热文件（调用方需在打包和删除时显式排除该热文件）
     */
    public suspend fun rollActiveFile(): File? {
        if (!isRunning.get()) return null
        return withContext(writerScope.coroutineContext) {
            try {
                // 1. 先把当前已进入 Channel 的数据完全排空落盘
                val remaining = mutableListOf<LogEntry>()
                while (true) {
                    val entry = logChannel.tryReceive().getOrNull() ?: break
                    remaining.add(entry)
                    if (remaining.size >= LinLogConstants.BATCH_DRAIN_LIMIT) {
                        writeBatch(remaining)
                        remaining.clear()
                    }
                }
                if (remaining.isNotEmpty()) {
                    writeBatch(remaining)
                }
                currentWriter?.flush()

                // 2. 若当前热文件有数据，关闭封存并切到新文件
                if (currentFile != null && currentFileLength > 0) {
                    closeCurrentWriter()
                    fileIndex++
                    openNewFile()
                }
                currentFile
            } catch (_: Throwable) {
                currentFile
            }
        }
    }

    private fun rotateFile() {
        closeCurrentWriter()
        fileIndex++
        openNewFile()
        triggerAsyncClean()
    }

    private fun openNewFile() {
        closeCurrentWriter()
        ensureDirectoryExists()

        var targetFile: File
        do {
            val fileName = fileNameGenerator.generateFileName(currentDayIndex, fileIndex)
            targetFile = File(logDirectory, fileName)
            if (!targetFile.exists() || targetFile.length() < maxFileSize) break
            fileIndex++
        } while (true)

        try {
            currentFile = targetFile
            currentFileLength = targetFile.length()
            currentWriter = BufferedWriter(FileWriter(targetFile, true), LinLogConstants.BUFFER_SIZE_BYTES)
        } catch (t: Throwable) {
            Log.e(LinLogConstants.DEFAULT_TAG, "Failed to open new log file: ${targetFile.absolutePath}", t)
            currentWriter = null
        }
    }

    private fun ensureDirectoryExists() {
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
    }

    private fun closeCurrentWriter() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (_: Throwable) {}
        currentWriter = null
        currentFile = null
    }

    override fun print(entry: LogEntry) {
        if (!isRunning.get()) return
        // 核心过滤：若声明不写文件或级别低于文件打印器门槛，直接短路
        if (!entry.writeToFile || entry.level < minLevel) return

        val result = logChannel.trySend(entry)
        if (result.isFailure) {
            Log.w(LinLogConstants.DEFAULT_TAG, "Log buffer queue is full! Dropped message.")
        }
    }

    override fun flush() {
        if (!isRunning.get()) return
        // 异步排队落盘：将 flush 任务排在后台单线程队列末尾，避免阻塞调用线程与 ANR 风险
        writerScope.launch {
            try {
                currentWriter?.flush()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 针对 Crash 现场等紧急场景的同步阻塞刷盘
     * 阻塞调用线程直到 Channel 剩余内容与 BufferedWriter 彻底排空或超时
     */
    public fun flushSync(timeoutMs: Long = 1000L): Boolean {
        if (!isRunning.get()) return true
        val latch = CountDownLatch(1)
        writerScope.launch {
            try {
                val remaining = mutableListOf<LogEntry>()
                while (true) {
                    val entry = logChannel.tryReceive().getOrNull() ?: break
                    remaining.add(entry)
                    if (remaining.size >= LinLogConstants.BATCH_DRAIN_LIMIT) {
                        writeBatch(remaining)
                        remaining.clear()
                    }
                }
                if (remaining.isNotEmpty()) {
                    writeBatch(remaining)
                }
                currentWriter?.flush()
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun release() {
        if (isRunning.compareAndSet(true, false)) {
            // 平滑退出：关闭通道触发消费循环自然结束并落盘
            logChannel.close()
            dedicatedExecutor?.shutdown()
        }
    }

    /**
     * 当前正在写入的物理日志文件（可能为 null）
     */
    public val activeWritingFile: File?
        get() = currentFile

    /**
     * 主动将当前活跃写入文件刷盘封存并切入下一轮转，使已有日志变为可安全导出的只读冷文件
     */
    public fun rollNextFile() {
        if (!isRunning.get()) return
        writerScope.launch {
            closeCurrentWriter()
            fileIndex++
        }
    }

    @Volatile
    private var lastCleanTimeMillis: Long = 0L

    /**
     * 触发异步防篡改安全清理（自动排除当前热文件，附带 30 秒防抖，避免高频 I/O 抖动）
     *
     * @param force 是否忽略防抖强行扫描
     */
    public fun triggerAsyncClean(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCleanTimeMillis < LinLogConstants.CLEAN_THROTTLE_INTERVAL_MILLIS) {
            return
        }
        lastCleanTimeMillis = now
        writerScope.launch(Dispatchers.IO) {
            cleaner.clean(logDirectory, excludeFile = currentFile)
        }
    }
}

