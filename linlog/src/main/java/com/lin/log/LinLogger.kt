package com.lin.log

import android.content.Context
import com.lin.log.internal.EarlyBirdSpooler
import com.lin.log.internal.StackTraceUtil
import com.lin.log.printer.AndroidLogPrinter
import com.lin.log.printer.LogEntry
import com.lin.log.printer.file.AsyncFilePrinter
import com.lin.log.uploader.LogExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 具备独立配置、独立异步写入通道与独立存储生命周期的领域日志器
 *
 * 用于高频网络、用户行为轨迹、性能 APM 等独立业务域的物理隔离日志输出与管理。
 *
 * 核心特性：
 * 1. **单例句柄绝对稳定 (Stable Handle)**：支持极早获取，对象引用全局唯一，不因延迟配置而产生对象分裂；
 * 2. **全域物理早鸟写入 (Universal Early-Bird Spool)**：未完成配置前，任何线程调用的日志直接进入物理早鸟预写卷实时落盘，遭遇冷启动 Crash 不丢日志；
 * 3. **异步初始化与零孤儿并发状态机 (Atomic State Handover)**：三态原子交接（UNINITIALIZED -> HANDOVER -> CONFIGURED），彻底消灭异步初始化时的孤儿日志永久滞留竞态；
 * 4. **多管道物理隔离 (Domain Segregation)**：独立 Channel、独立文件滚动、独立过期淘汰策略。
 *
 * @property domainName 绑定的领域唯一名称标识
 */
public class LinLogger internal constructor(
    public val domainName: String = LinLogConstants.DEFAULT_TAG,
    initialConfig: LinLogConfiguration? = null
) {
    internal constructor(initialConfig: LinLogConfiguration?) : this(LinLogConstants.DEFAULT_TAG, initialConfig)

    internal companion object {
        private const val STATE_UNINITIALIZED = 0
        private const val STATE_HANDOVER = 1
        private const val STATE_CONFIGURED = 2
    }

    private val lifecycleState = AtomicInteger(if (initialConfig != null) STATE_CONFIGURED else STATE_UNINITIALIZED)
    private val handoverLock = ReentrantLock()

    // 状态切换期间并发拦截队列，保证时序严格递增
    private val handoverQueue = ConcurrentLinkedQueue<LogEntry>()
    private val earlyBirdSpooler = EarlyBirdSpooler(domainName)

    @PublishedApi
    internal val isConfigured: AtomicBoolean = AtomicBoolean(initialConfig != null)

    @Volatile
    private var _configuration: LinLogConfiguration? = initialConfig

    internal val rawConfiguration: LinLogConfiguration?
        get() = _configuration

    public val configuration: LinLogConfiguration
        get() = _configuration ?: LinLog.defaultConfiguration

    @Volatile
    public var filePrinterRef: AsyncFilePrinter? =
        initialConfig?.printers?.filterIsInstance<AsyncFilePrinter>()?.firstOrNull()

    /**
     * 动态/异步挂载领域独立配置，并原子交接该领域在就绪前暂存的所有物理早鸟日志
     */
    public fun attachConfiguration(config: LinLogConfiguration) {
        handoverLock.withLock {
            // 防御 1：进入 HANDOVER 状态，此时任何并发线程调用的新日志均被安全拦截进临界队列，杜绝孤儿日志滞留
            lifecycleState.set(STATE_HANDOVER)

            val oldConfig = this._configuration
            // 防御 2：若已有旧配置（重复注册场景），平滑释放旧打印器的异步线程池与文件句柄，杜绝资源泄漏
            if (oldConfig != null && oldConfig !== config) {
                for (printer in oldConfig.printers) {
                    printer.flush()
                    printer.release()
                }
            }

            this._configuration = config
            val targetFilePrinter = config.printers.filterIsInstance<AsyncFilePrinter>().firstOrNull()
            this.filePrinterRef = targetFilePrinter

            // 防御 3：将早鸟物理预写卷直接物理合并灌入正式目录，0 堆内存浪费
            if (targetFilePrinter != null) {
                val today = SimpleDateFormat(LinLogConstants.DATE_PATTERN_DAY, Locale.US).format(Date())
                val defaultName = LinLogConstants.formatLogFileName(today, 0)
                earlyBirdSpooler.drainToTargetDirectory(targetFilePrinter.logDirectory, defaultName)
            }

            // 防御 4：严格遵循“交接锁定 -> 抽干交接期并发队列 -> 原子切入就绪态”，保证物理时序 100% 严格递增
            while (true) {
                val entry = handoverQueue.poll() ?: break
                for (printer in config.printers) {
                    if (printer is AndroidLogPrinter) continue
                    printer.print(entry)
                }
            }

            isConfigured.set(true)
            lifecycleState.set(STATE_CONFIGURED)
        }
    }

    // ==================== VERBOSE 级别多态重载 ====================

    /**
     * 输出 VERBOSE 级别日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(crossinline message: () -> String) {
        if (shouldLog(LogLevel.VERBOSE)) logDirect(LogLevel.VERBOSE, null, true, message(), null)
    }

    /**
     * 输出 VERBOSE 级别日志（内联高阶函数，支持短路懒求值与落盘控制）
     *
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(toFile: Boolean, crossinline message: () -> String) {
        if (shouldLog(LogLevel.VERBOSE)) logDirect(LogLevel.VERBOSE, null, toFile, message(), null)
    }

    /**
     * 输出 VERBOSE 级别日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(tag: String?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.VERBOSE)) logDirect(LogLevel.VERBOSE, tag, toFile, message(), null)
    }

    /**
     * 输出 VERBOSE 级别日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.VERBOSE)) logDirect(LogLevel.VERBOSE, null, toFile, message(), throwable)
    }

    /**
     * 输出 VERBOSE 级别日志（内联高阶函数，支持自定义 Tag、异常携带与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.VERBOSE)) logDirect(LogLevel.VERBOSE, tag, toFile, message(), throwable)
    }

    /**
     * 输出 VERBOSE 级别普通字符串日志（直通 Fast-Path）
     *
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun v(message: String, toFile: Boolean = true) {
        v(null, message, toFile)
    }

    /**
     * 输出 VERBOSE 级别普通字符串日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun v(tag: String?, message: String, toFile: Boolean = true) {
        if (shouldLog(LogLevel.VERBOSE)) logDirect(LogLevel.VERBOSE, tag, toFile, message, null)
    }

    /**
     * 输出 VERBOSE 级别异常日志（带消息与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun v(message: String, throwable: Throwable?, toFile: Boolean = true) {
        v(null, message, throwable, toFile)
    }

    /**
     * 输出 VERBOSE 级别异常日志（带自定义 Tag、消息与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun v(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true) {
        if (shouldLog(LogLevel.VERBOSE)) logDirect(LogLevel.VERBOSE, tag, toFile, message, throwable)
    }

    /**
     * 输出 VERBOSE 级别纯异常日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun v(throwable: Throwable, toFile: Boolean = true) {
        v(null, throwable.message ?: throwable.javaClass.simpleName, throwable, toFile)
    }

    // ==================== DEBUG 级别多态重载 ====================

    /**
     * 输出 DEBUG 级别日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(crossinline message: () -> String) {
        if (shouldLog(LogLevel.DEBUG)) logDirect(LogLevel.DEBUG, null, true, message(), null)
    }

    /**
     * 输出 DEBUG 级别日志（内联高阶函数，支持短路懒求值与落盘控制）
     *
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(toFile: Boolean, crossinline message: () -> String) {
        if (shouldLog(LogLevel.DEBUG)) logDirect(LogLevel.DEBUG, null, toFile, message(), null)
    }

    /**
     * 输出 DEBUG 级别日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(tag: String?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.DEBUG)) logDirect(LogLevel.DEBUG, tag, toFile, message(), null)
    }

    /**
     * 输出 DEBUG 级别日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.DEBUG)) logDirect(LogLevel.DEBUG, null, toFile, message(), throwable)
    }

    /**
     * 输出 DEBUG 级别日志（内联高阶函数，支持自定义 Tag、异常携带与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.DEBUG)) logDirect(LogLevel.DEBUG, tag, toFile, message(), throwable)
    }

    /**
     * 输出 DEBUG 级别普通字符串日志（直通 Fast-Path）
     *
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun d(message: String, toFile: Boolean = true) {
        d(null, message, toFile)
    }

    /**
     * 输出 DEBUG 级别普通字符串日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun d(tag: String?, message: String, toFile: Boolean = true) {
        if (shouldLog(LogLevel.DEBUG)) logDirect(LogLevel.DEBUG, tag, toFile, message, null)
    }

    /**
     * 输出 DEBUG 级别异常日志（带消息与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun d(message: String, throwable: Throwable?, toFile: Boolean = true) {
        d(null, message, throwable, toFile)
    }

    /**
     * 输出 DEBUG 级别异常日志（带自定义 Tag、消息与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun d(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true) {
        if (shouldLog(LogLevel.DEBUG)) logDirect(LogLevel.DEBUG, tag, toFile, message, throwable)
    }

    /**
     * 输出 DEBUG 级别纯异常日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun d(throwable: Throwable, toFile: Boolean = true) {
        d(null, throwable.message ?: throwable.javaClass.simpleName, throwable, toFile)
    }

    // ==================== INFO 级别多态重载 ====================

    /**
     * 输出 INFO 级别日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(crossinline message: () -> String) {
        if (shouldLog(LogLevel.INFO)) logDirect(LogLevel.INFO, null, true, message(), null)
    }

    /**
     * 输出 INFO 级别日志（内联高阶函数，支持短路懒求值与落盘控制）
     *
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(toFile: Boolean, crossinline message: () -> String) {
        if (shouldLog(LogLevel.INFO)) logDirect(LogLevel.INFO, null, toFile, message(), null)
    }

    /**
     * 输出 INFO 级别日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(tag: String?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.INFO)) logDirect(LogLevel.INFO, tag, toFile, message(), null)
    }

    /**
     * 输出 INFO 级别日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.INFO)) logDirect(LogLevel.INFO, null, toFile, message(), throwable)
    }

    /**
     * 输出 INFO 级别日志（内联高阶函数，支持自定义 Tag、异常携带与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.INFO)) logDirect(LogLevel.INFO, tag, toFile, message(), throwable)
    }

    /**
     * 输出 INFO 级别普通字符串日志（直通 Fast-Path）
     *
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun i(message: String, toFile: Boolean = true) {
        i(null, message, toFile)
    }

    /**
     * 输出 INFO 级别普通字符串日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun i(tag: String?, message: String, toFile: Boolean = true) {
        if (shouldLog(LogLevel.INFO)) logDirect(LogLevel.INFO, tag, toFile, message, null)
    }

    /**
     * 输出 INFO 级别异常日志（带消息与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun i(message: String, throwable: Throwable?, toFile: Boolean = true) {
        i(null, message, throwable, toFile)
    }

    /**
     * 输出 INFO 级别异常日志（带自定义 Tag、消息与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun i(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true) {
        if (shouldLog(LogLevel.INFO)) logDirect(LogLevel.INFO, tag, toFile, message, throwable)
    }

    /**
     * 输出 INFO 级别纯异常日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun i(throwable: Throwable, toFile: Boolean = true) {
        i(null, throwable.message ?: throwable.javaClass.simpleName, throwable, toFile)
    }

    // ==================== WARN 级别多态重载 ====================

    /**
     * 输出 WARN 级别日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(crossinline message: () -> String) {
        if (shouldLog(LogLevel.WARN)) logDirect(LogLevel.WARN, null, true, message(), null)
    }

    /**
     * 输出 WARN 级别日志（内联高阶函数，支持短路懒求值与落盘控制）
     *
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(toFile: Boolean, crossinline message: () -> String) {
        if (shouldLog(LogLevel.WARN)) logDirect(LogLevel.WARN, null, toFile, message(), null)
    }

    /**
     * 输出 WARN 级别日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(tag: String?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.WARN)) logDirect(LogLevel.WARN, tag, toFile, message(), null)
    }

    /**
     * 输出 WARN 级别日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.WARN)) logDirect(LogLevel.WARN, null, toFile, message(), throwable)
    }

    /**
     * 输出 WARN 级别日志（内联高阶函数，支持自定义 Tag、异常携带与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.WARN)) logDirect(LogLevel.WARN, tag, toFile, message(), throwable)
    }

    /**
     * 输出 WARN 级别普通字符串日志（直通 Fast-Path）
     *
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun w(message: String, toFile: Boolean = true) {
        w(null, message, toFile)
    }

    /**
     * 输出 WARN 级别普通字符串日志（带自定义 Tag，解决位置参数传递 toFile 的类型匹配断层）
     *
     * 示例：LinLog.apm.w(id, "Watcher 远程/本地配置为 null，跳过初始化", true)
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun w(tag: String?, message: String, toFile: Boolean = true) {
        if (shouldLog(LogLevel.WARN)) logDirect(LogLevel.WARN, tag, toFile, message, null)
    }

    /**
     * 输出 WARN 级别异常日志（带消息与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun w(message: String, throwable: Throwable?, toFile: Boolean = true) {
        w(null, message, throwable, toFile)
    }

    /**
     * 输出 WARN 级别异常日志（带自定义 Tag、消息与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun w(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true) {
        if (shouldLog(LogLevel.WARN)) logDirect(LogLevel.WARN, tag, toFile, message, throwable)
    }

    /**
     * 输出 WARN 级别纯异常日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun w(throwable: Throwable, toFile: Boolean = true) {
        w(null, throwable.message ?: throwable.javaClass.simpleName, throwable, toFile)
    }

    // ==================== ERROR 级别多态重载 ====================

    /**
     * 输出 ERROR 级别日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(crossinline message: () -> String) {
        if (shouldLog(LogLevel.ERROR)) logDirect(LogLevel.ERROR, null, true, message(), null)
    }

    /**
     * 输出 ERROR 级别日志（内联高阶函数，支持短路懒求值与落盘控制）
     *
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(toFile: Boolean, crossinline message: () -> String) {
        if (shouldLog(LogLevel.ERROR)) logDirect(LogLevel.ERROR, null, toFile, message(), null)
    }

    /**
     * 输出 ERROR 级别日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(tag: String?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.ERROR)) logDirect(LogLevel.ERROR, tag, toFile, message(), null)
    }

    /**
     * 输出 ERROR 级别日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.ERROR)) logDirect(LogLevel.ERROR, null, toFile, message(), throwable)
    }

    /**
     * 输出 ERROR 级别日志（内联高阶函数，支持自定义 Tag、异常携带与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String) {
        if (shouldLog(LogLevel.ERROR)) logDirect(LogLevel.ERROR, tag, toFile, message(), throwable)
    }

    /**
     * 输出 ERROR 级别普通字符串日志（直通 Fast-Path）
     *
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun e(message: String, toFile: Boolean = true) {
        e(null, message, toFile)
    }

    /**
     * 输出 ERROR 级别普通字符串日志（带自定义 Tag，解决位置参数传递 toFile 的类型匹配断层）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志内容
     * @param toFile 是否落盘
     */
    public fun e(tag: String?, message: String, toFile: Boolean = true) {
        if (shouldLog(LogLevel.ERROR)) logDirect(LogLevel.ERROR, tag, toFile, message, null)
    }

    /**
     * 输出 ERROR 级别异常日志（带消息与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun e(message: String, throwable: Throwable?, toFile: Boolean = true) {
        e(null, message, throwable, toFile)
    }

    /**
     * 输出 ERROR 级别异常日志（带自定义 Tag、消息与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun e(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true) {
        if (shouldLog(LogLevel.ERROR)) logDirect(LogLevel.ERROR, tag, toFile, message, throwable)
    }

    /**
     * 输出 ERROR 级别纯异常日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     * @param toFile 是否落盘
     */
    public fun e(throwable: Throwable, toFile: Boolean = true) {
        e(null, throwable.message ?: throwable.javaClass.simpleName, throwable, toFile)
    }

    // ==================== JSON 格式化输出多态重载 ====================

    /**
     * 格式化并输出 JSON 字符串（级别为 DEBUG，使用领域默认 Tag）
     *
     * @param json 原始 JSON 文本
     * @param toFile 是否落盘
     */
    public fun json(json: String, toFile: Boolean = true) {
        json(json, null, toFile)
    }

    /**
     * 格式化并输出 JSON 字符串（级别为 DEBUG，支持指定 Tag 与短路求值）
     *
     * @param json 原始 JSON 文本
     * @param tag 自定义 Tag，传 null 则使用领域默认 Tag
     * @param toFile 是否落盘
     */
    public fun json(json: String, tag: String?, toFile: Boolean = true) {
        if (!shouldLog(LogLevel.DEBUG)) return
        val formatted = configuration.jsonFormatter.format(json)
        d(tag, formatted, toFile)
    }

    /**
     * 强制将当前领域的内存缓冲日志排队刷入磁盘
     */
    public fun flush() {
        if (lifecycleState.get() == STATE_CONFIGURED) {
            for (printer in configuration.printers) {
                printer.flush()
            }
        }
    }

    /**
     * 针对 Crash 现场等紧急场景的同步阻塞刷盘
     * 阻塞调用线程直到 Channel 剩余内容与 BufferedWriter 彻底排空或超时
     */
    public fun flushSync(timeoutMs: Long = 1000L): Boolean {
        return filePrinterRef?.flushSync(timeoutMs) ?: true
    }

    /**
     * 释放当前领域的打印器资源与后台写线程池
     */
    public fun release() {
        handoverLock.withLock {
            if (lifecycleState.get() == STATE_CONFIGURED) {
                for (printer in configuration.printers) {
                    printer.release()
                }
            }
            earlyBirdSpooler.release()
            handoverQueue.clear()
            isConfigured.set(false)
            lifecycleState.set(STATE_UNINITIALIZED)
            _configuration = null
            filePrinterRef = null
        }
    }

    /**
     * 仅导出当前领域的冷日志 Zip 包
     *
     * @param context Context 实例
     * @param daysCount 导出天数（若 <= 0 则全量导出该领域的全部历史日志）
     * @param allHistory 是否全量收取所有历史日志（默认自动由 daysCount <= 0 判定）
     * @return 打包好的临时 Zip 文件（请在上传后主动删除，或推荐使用 [exportScope] 自动回收）
     */
    public suspend fun exportRecentLogs(
        context: Context,
        daysCount: Int = 3,
        allHistory: Boolean = (daysCount <= 0)
    ): File? {
        val dir = filePrinterRef?.logDirectory ?: run {
            if (!isConfigured.get() && LinLog.isInitialized()) {
                LinLog.get(configuration.tag)
            }
            filePrinterRef?.logDirectory
        } ?: return null
        return LogExporter.exportLogsResult(context, listOf(dir), daysCount, allHistory)?.zipFile
    }

    /**
     * 安全导出当前领域日志并在执行完 [onExported] 业务上报逻辑后自动删除临时 Zip 文件
     *
     * @param context Context 实例
     * @param daysCount 导出天数（若 <= 0 则全量收取所有历史日志）
     * @param allHistory 是否全量收取所有历史日志
     * @param deleteSourceOnSuccess 业务上报成功后是否安全删除已打包的源日志文件
     * @param onExported 业务处理闭包（如发起 HTTP 上传）
     * @return [onExported] 闭包的返回值
     */
    public suspend inline fun <T> exportScope(
        context: Context,
        daysCount: Int = 3,
        allHistory: Boolean = (daysCount <= 0),
        deleteSourceOnSuccess: Boolean = false,
        crossinline onExported: suspend (zipFile: File) -> T
    ): T? {
        val printer = filePrinterRef ?: run {
            if (!isConfigured.get() && LinLog.isInitialized()) {
                LinLog.get(configuration.tag)
            }
            filePrinterRef
        } ?: return null
        val dir = printer.logDirectory
        val hotFile = printer.rollActiveFile()
        val excludeFiles = if (hotFile != null) setOf(hotFile) else emptySet()
        val result = LogExporter.exportLogsResult(context, listOf(dir), daysCount, allHistory, excludeFiles = excludeFiles) ?: return null
        return try {
            val output = onExported(result.zipFile)
            if (deleteSourceOnSuccess) {
                val isSuccess = when (output) {
                    is Boolean -> output
                    is Result<*> -> output.isSuccess
                    else -> true
                }
                if (isSuccess) {
                    LogExporter.deleteSourceFiles(result.sourceFiles)
                }
            }
            output
        } finally {
            result.zipFile.delete()
        }
    }

    @PublishedApi
    internal fun shouldLog(level: Int): Boolean {
        if (lifecycleState.get() != STATE_CONFIGURED) return true
        val config = configuration
        return config.isEnabled && level >= config.minLevel
    }

    @PublishedApi
    internal fun logDirect(level: Int, tag: String?, toFile: Boolean, message: String, throwable: Throwable?) {
        val config = configuration
        val state = lifecycleState.get()
        if (state == STATE_CONFIGURED && (!config.isEnabled || level < config.minLevel)) return

        val logTag = tag ?: config.tag
        val threadInfo = if (config.enableThreadInfo) config.threadFormatter.format(Thread.currentThread()) else null
        val stackTrace = if (config.stackTraceDepth > 0) {
            val cropped = StackTraceUtil.getCroppedRealStackTrace(
                Throwable().stackTrace, config.stackTraceOrigin, config.stackTraceDepth
            )
            config.stackTraceFormatter.format(cropped)
        } else throwable?.let { config.throwableFormatter.format(it) }

        val finalMsg = if (config.enableBorder) {
            config.borderFormatter.format(arrayOf(threadInfo, stackTrace, message))
        } else message

        val entry = LogEntry(
            level = level,
            tag = logTag,
            message = finalMsg,
            threadName = if (config.enableBorder) null else threadInfo,
            stackTrace = if (config.enableBorder) null else stackTrace,
            writeToFile = toFile
        )

        when (state) {
            STATE_CONFIGURED -> {
                val printers = config.printers
                for (i in printers.indices) {
                    printers[i].print(entry)
                }
            }
            STATE_HANDOVER -> {
                if (LinLog.enablePreInitConsoleLog) {
                    LinLog.fallbackAndroidPrinter.print(entry)
                }
                if (toFile) {
                    handoverQueue.offer(entry)
                }
            }
            STATE_UNINITIALIZED -> {
                // 🚀 全域真·早鸟写入：未配置前直接落盘至物理预写卷，抗极端 Crash
                if (LinLog.enablePreInitConsoleLog) {
                    LinLog.fallbackAndroidPrinter.print(entry)
                }
                if (toFile) {
                    earlyBirdSpooler.write(entry)
                }
            }
        }
    }
}
