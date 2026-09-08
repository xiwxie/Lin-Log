package com.lin.log

import android.content.Context
import android.util.Log
import com.lin.log.internal.EarlyBirdContext
import com.lin.log.printer.AndroidLogPrinter
import com.lin.log.printer.LogEntry
import com.lin.log.printer.file.AsyncFilePrinter
import com.lin.log.uploader.ExportResult
import com.lin.log.uploader.LogExporter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.ReadOnlyProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * LinLog 顶层门面类
 *
 * 专注于全局初始化调度、默认日志门面分发、多领域容器生命周期管理。
 *
 * 核心特性：
 * 1. **全域物理早鸟写入 (Universal Early-Bird Spool)**：主通道及所有子领域出生即具备物理直接落盘能力，抗冷启动 Crash；
 * 2. **完全解耦主线程 (Thread-Agnostic / Fully Async Init)**：支持在主线程、任意后台子线程或协程中完全异步初始化；
 * 3. **零孤儿并发状态机 (Atomic State Handover)**：三态原子流转交接，保证 0 孤儿日志丢失，时序绝对严格递增；
 * 4. **0 侵入自适应沙盒**：通过系统无感自省免 Context 极早定位私有目录。
 */
public object LinLog {

    @PublishedApi
    internal val domainLoggers: ConcurrentHashMap<String, LinLogger> = ConcurrentHashMap()

    // 默认主领域日志器，出生即自带物理早鸟写入
    @PublishedApi
    internal val mainLogger: LinLogger by lazy { get("main") }

    @PublishedApi
    internal val isInitializing: AtomicBoolean = AtomicBoolean(false)

    // 原子标记，保证无论多少个组件/线程并发调用，均只有第一次生效
    @PublishedApi
    internal val isInitializedState: AtomicBoolean = AtomicBoolean(false)

    /**
     * 判断 LinLog 日志系统是否已完成初始化
     */
    @JvmStatic
    public fun isInitialized(): Boolean = isInitializedState.get()

    // 默认兜底控制台打印器（初始化前使用）
    @PublishedApi
    internal val fallbackAndroidPrinter: AndroidLogPrinter = AndroidLogPrinter()

    // 预初始化早鸟日志暂存队列（保留字段以确保二进制与反射兼容）
    @PublishedApi
    internal val preInitBuffer: ConcurrentLinkedQueue<LogEntry> = ConcurrentLinkedQueue<LogEntry>()

    @PublishedApi
    internal val preInitBufferCount: AtomicInteger = AtomicInteger(0)

    /**
     * 未初始化前是否向控制台输出日志的全局开关（可在 Application 最开始显式关闭）
     */
    @Volatile
    @PublishedApi
    internal var enablePreInitConsoleLog: Boolean = true

    /**
     * 预初始化早鸟日志队列最大容量（可在初始化前由外部全局调整）
     */
    @Volatile
    @PublishedApi
    internal var preInitBufferCapacity: Int = LinLogConstants.PRE_INIT_BUFFER_MAX_CAPACITY

    @Volatile
    @PublishedApi
    internal var defaultConfiguration: LinLogConfiguration = LinLogConfiguration.Builder().build()

    public val configuration: LinLogConfiguration
        get() = mainLogger.rawConfiguration ?: defaultConfiguration

    /**
     * 仅输出到控制台的快捷门面（语义化调用，绝不写入磁盘文件，适合高频 UI、动画或局部调试）
     */
    public val console: ConsoleFacade = ConsoleFacade


    // ==================== 通用领域隔离与属性委托 (Domain Segregation & Delegation) ====================

    /**
     * Kotlin 属性委托：按需懒加载并注入任意业务领域的独立日志器（0 额外 Class、0 运行时对象分配）
     *
     * 无论该领域是否已在异步线程配置，均天然具备物理早鸟落盘能力。
     *
     * @param domainName 业务领域唯一标识（如 "pay", "order", "media", "apm" 等）
     */
    public inline fun domain(domainName: String): Lazy<LinLogger> = lazy(LazyThreadSafetyMode.NONE) {
        get(domainName)
    }

    /**
     * Kotlin 属性委托：按需懒加载并注入带有初始化配置 DSL 的领域日志器
     */
    public inline fun domain(
        domainName: String,
        noinline block: (LinLogConfiguration.Builder.() -> Unit)?
    ): Lazy<LinLogger> = lazy(LazyThreadSafetyMode.NONE) {
        getOrRegister(domainName, block)
    }

    /**
     * Kotlin 属性委托：自动使用宿主类名或指定 Tag 绑定领域日志器
     */
    public fun logger(
        tag: String? = null,
        domainName: String = "default",
        block: (LinLogConfiguration.Builder.() -> Unit)? = null
    ): ReadOnlyProperty<Any?, LinLogger> {
        return ReadOnlyProperty { thisRef, property ->
            val domainTag = tag ?: thisRef?.let { it::class.java.simpleName } ?: property.name
            getOrRegister(domainTag, block)
        }
    }

    // ==================== 初始化与生命周期管理 ====================

    /**
     * 纯 DSL 方式初始化（保证 CAS 原子性与绝对防重复，0 阻塞返回，支持在主线程或异步子线程中运行）
     */
    @JvmStatic
    @JvmOverloads
    public fun init(block: LinLogConfiguration.Builder.() -> Unit = {}) {
        if (isInitializing.compareAndSet(false, true)) {
            try {
                val builder = LinLogConfiguration.Builder()
                if (builder.fileLogDirectory == null) {
                    builder.fileLogDirectory = EarlyBirdContext.getSafeDirectory("linlog/default")
                }
                builder.block()
                val config = builder.build()
                applyConfiguration(config)
                isInitializedState.set(true)
            } catch (t: Throwable) {
                Log.e(LinLogConstants.DEFAULT_TAG, "LinLog.init failed due to configuration exception!", t)
                isInitializing.set(false)
            }
        } else {
            Log.w(LinLogConstants.DEFAULT_TAG, "LinLog is already initialized or initializing, ignoring repeated init call.")
        }
    }

    /**
     * 便捷 Context 绑定式 DSL 初始化（自动根据 Context 智能分配存储目录，支持完全异步初始化）
     */
    @JvmStatic
    @JvmOverloads
    public fun init(
        context: Context,
        isDebug: Boolean = true,
        block: LinLogConfiguration.Builder.() -> Unit = {}
    ) {
        EarlyBirdContext.injectContext(context)
        if (isInitializing.get()) {
            Log.w(LinLogConstants.DEFAULT_TAG, "LinLog is already initialized or initializing, ignoring repeated init call.")
            return
        }
        init {
            val defaultSubDir = if (isDebug) LinLogConstants.DEFAULT_DIR_DEBUG else LinLogConstants.DEFAULT_DIR_RELEASE
            this.fileLogDirectory = File(context.cacheDir, defaultSubDir)
            this.minLevel = if (isDebug) LogLevel.ALL else LogLevel.INFO
            this.fileMinLevel = if (isDebug) LogLevel.ALL else LogLevel.INFO
            this.block()
        }
        cleanOrphanedUploadZipsAsync(context)
    }

    private fun cleanOrphanedUploadZipsAsync(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tempZipDir = File(appContext.cacheDir, LinLogConstants.UPLOAD_CACHE_DIR_NAME)
                if (tempZipDir.exists() && tempZipDir.isDirectory) {
                    val now = System.currentTimeMillis()
                    tempZipDir.listFiles()?.forEach { file ->
                        // 仅删除 24 小时之前的孤儿 zip，避免误删正在并发上传的临时文件
                        if (file.isFile && file.name.endsWith(LinLogConstants.FILE_EXTENSION_ZIP) &&
                            (now - file.lastModified() > 24 * 60 * 60 * 1000L)) {
                            file.delete()
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun applyConfiguration(config: LinLogConfiguration) {
        this.defaultConfiguration = config
        this.enablePreInitConsoleLog = config.enablePreInitConsoleLog
        this.preInitBufferCapacity = config.preInitBufferCapacity

        // 挂载主通道配置并完成早鸟物理卷合并
        mainLogger.attachConfiguration(config)

        // 若已有先于 init 被调用但未显式定制的子领域，赋予默认配置并触发物理早鸟合并
        for ((domainName, logger) in domainLoggers) {
            if (domainName == "main") continue
            if (!logger.isConfigured.get()) {
                val baseParent = config.fileLogDirectory?.parentFile
                    ?: EarlyBirdContext.getSafeDirectory("linlog")
                val subDir = File(baseParent, domainName)
                val builder = LinLogConfiguration.Builder().apply {
                    this.tag = domainName.uppercase()
                    this.fileLogDirectory = subDir
                    this.minLevel = config.minLevel
                    this.fileMinLevel = config.fileMinLevel
                }
                logger.attachConfiguration(builder.build())
            }
        }
    }

    /**
     * 业务更新服务器时间锚点（防御系统时间被用户随意篡改导致的日志误删）
     */
    @JvmStatic
    public fun updateServerTime(serverTimeMillis: Long) {
        for (logger in domainLoggers.values) {
            logger.configuration.printers.filterIsInstance<AsyncFilePrinter>().firstOrNull()
                ?.cleaner?.updateServerTimeAnchor(serverTimeMillis)
        }
    }

    /**
     * 导出指定领域或全量日志文件并打包为 Zip 文件，返回包含 Zip 及源文件清单的 [ExportResult]
     */
    @JvmStatic
    public suspend fun exportRecentLogsResult(
        context: Context,
        daysCount: Int = 3,
        domains: List<String>? = null,
        allHistory: Boolean = (daysCount <= 0)
    ): ExportResult? {
        // 🚀 紧急崩溃保护：若在初始化完成前触发导出（如冷启动极早闪退），强制就地兜底初始化抽干早鸟日志
        if (!isInitialized()) {
            init(context)
        }
        flushAll()
        val targetDirs = mutableListOf<File>()
        val hotFiles = mutableSetOf<File>()

        val targetLoggers = if (domains == null) {
            domainLoggers.values
        } else {
            domains.map { get(it) }
        }

        for (logger in targetLoggers) {
            val printer = logger.configuration.printers.filterIsInstance<AsyncFilePrinter>().firstOrNull() ?: continue
            val dir = printer.logDirectory
            if (!targetDirs.contains(dir)) {
                targetDirs.add(dir)
            }
            // 🌟 核心防丢：主动滚动封存当前文件，返回全新开启的热文件并加入排除列表，杜绝并发写入丢失
            val newHotFile = printer.rollActiveFile()
            if (newHotFile != null) {
                hotFiles.add(newHotFile)
            }
        }

        if (targetDirs.isEmpty()) return null
        return LogExporter.exportLogsResult(context, targetDirs, daysCount, allHistory, excludeFiles = hotFiles)
    }

    /**
     * 导出指定领域或全量最近 N 天的历史冷日志文件并打包为 Zip 文件
     *
     * @param context Context 实例
     * @param daysCount 导出天数（若 <= 0 则全量收取所有历史日志）
     * @param domains 指定要打包的领域列表（若为 null，则自动打包主日志及所有已注册领域的日志）
     * @param allHistory 是否全量收取所有历史日志（默认由 daysCount <= 0 自动判定）
     * @return 打包好的临时 Zip 文件（上传完成或失败后请主动删除，或使用 [exportScope] 自动回收）
     */
    @JvmStatic
    public suspend fun exportRecentLogs(
        context: Context,
        daysCount: Int = 3,
        domains: List<String>? = null,
        allHistory: Boolean = (daysCount <= 0)
    ): File? {
        return exportRecentLogsResult(context, daysCount, domains, allHistory)?.zipFile
    }

    /**
     * 安全导出作用域：
     * 1. 自动在内存缓冲区落盘后打包；
     * 2. [domains] 为 null 时收取全域行为，指定列表时精准定向多领域；
     * 3. [daysCount] <= 0 或 [allHistory] 为 true 时收取全部历史日志；
     * 4. [deleteSourceOnSuccess] 为 true 时，仅在 [onExported] 成功执行且未抛异常时安全清理源文件；
     * 5. 临时 Zip 文件无论成功与否均在 finally 块中自动删除，杜绝存储泄漏。
     */
    public suspend inline fun <T> exportScope(
        context: Context,
        daysCount: Int = 3,
        domains: List<String>? = null,
        allHistory: Boolean = (daysCount <= 0),
        deleteSourceOnSuccess: Boolean = false,
        crossinline onExported: suspend (zipFile: File) -> T
    ): T? {
        val result = exportRecentLogsResult(context, daysCount, domains, allHistory) ?: return null
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

    /**
     * 强制将主业务内存缓冲日志排队刷入磁盘
     */
    @JvmStatic
    public fun flush() {
        mainLogger.flush()
    }

    /**
     * 针对 Crash 现场等紧急场景的同步阻塞刷盘
     * 阻塞调用线程直到 Channel 剩余内容与 BufferedWriter 彻底排空或超时
     */
    @JvmStatic
    public fun flushSync(timeoutMs: Long = 1000L): Boolean {
        return mainLogger.flushSync(timeoutMs)
    }

    /**
     * 释放主业务打印器资源并重置初始化状态
     */
    @JvmStatic
    public fun release() {
        mainLogger.release()
        isInitializedState.set(false)
        isInitializing.set(false)
    }

    /**
     * 强制将主日志及所有独立领域的内存缓冲全部刷入磁盘
     */
    @JvmStatic
    public fun flushAll() {
        for (logger in domainLoggers.values) {
            logger.flush()
        }
    }

    /**
     * 针对 Crash 现场强制将主日志及所有独立领域的内存缓冲全部同步刷入磁盘
     */
    @JvmStatic
    public fun flushAllSync(timeoutMs: Long = 1000L): Boolean {
        var allSuccess = true
        for (logger in domainLoggers.values) {
            if (!logger.flushSync(timeoutMs)) {
                allSuccess = false
            }
        }
        return allSuccess
    }

    /**
     * 释放主日志及所有独立领域的打印器资源
     */
    @JvmStatic
    public fun releaseAll() {
        for (logger in domainLoggers.values) {
            logger.release()
        }
        domainLoggers.clear()
        isInitializedState.set(false)
        isInitializing.set(false)
    }

    // ==================== 领域日志器获取与注册 ====================

    /**
     * 获取指定领域的独立日志器句柄（支持索引操作符语法 LinLog["domain"]，保证单例句柄全局唯一稳定，0 日志丢失）
     */
    @JvmStatic
    public operator fun get(domainName: String): LinLogger {
        return domainLoggers.getOrPut(domainName) {
            val logger = LinLogger(domainName)
            if (isInitialized()) {
                val baseParent = mainLogger.configuration.fileLogDirectory?.parentFile
                    ?: EarlyBirdContext.getSafeDirectory("linlog")
                val subDir = File(baseParent, domainName)
                val builder = LinLogConfiguration.Builder().apply {
                    this.tag = domainName.uppercase()
                    this.fileLogDirectory = subDir
                    this.minLevel = mainLogger.configuration.minLevel
                    this.fileMinLevel = mainLogger.configuration.fileMinLevel
                }
                logger.attachConfiguration(builder.build())
            }
            logger
        }
    }

    /**
     * 注册/深度定制指定领域的独立日志器配置（支持在主线程或异步子线程中调用，原地挂载配置并回放早鸟日志）
     */
    @JvmStatic
    public fun register(domainName: String, block: LinLogConfiguration.Builder.() -> Unit): LinLogger {
        val logger = domainLoggers.getOrPut(domainName) { LinLogger(domainName) }
        val builder = LinLogConfiguration.Builder()
        builder.block()
        logger.attachConfiguration(builder.build())
        return logger
    }

    @PublishedApi
    internal fun getOrRegister(domainName: String, block: (LinLogConfiguration.Builder.() -> Unit)?): LinLogger {
        val logger = domainLoggers.getOrPut(domainName) { LinLogger(domainName) }
        if (block != null) {
            val builder = LinLogConfiguration.Builder()
            builder.block()
            logger.attachConfiguration(builder.build())
        } else if (!logger.isConfigured.get() && isInitialized()) {
            val baseParent = mainLogger.configuration.fileLogDirectory?.parentFile
                ?: EarlyBirdContext.getSafeDirectory("linlog")
            val subDir = File(baseParent, domainName)
            val builder = LinLogConfiguration.Builder().apply {
                this.tag = domainName.uppercase()
                this.fileLogDirectory = subDir
                this.minLevel = mainLogger.configuration.minLevel
                this.fileMinLevel = mainLogger.configuration.fileMinLevel
            }
            logger.attachConfiguration(builder.build())
        }
        return logger
    }

    // ==================== 主业务日志输出 API（内联懒求值 + Fast-Path 重载） ====================

    // --- VERBOSE ---

    public inline fun v(crossinline message: () -> String): Unit = mainLogger.v(message)

    public inline fun v(toFile: Boolean, crossinline message: () -> String): Unit = mainLogger.v(toFile, message)

    public inline fun v(tag: String?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.v(tag, toFile, message)

    public inline fun v(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.v(throwable, toFile, message)

    public inline fun v(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.v(tag, throwable, toFile, message)

    @JvmStatic
    public fun v(message: String, toFile: Boolean = true): Unit = mainLogger.v(message, toFile)

    @JvmStatic
    public fun v(tag: String?, message: String, toFile: Boolean = true): Unit = mainLogger.v(tag, message, toFile)

    @JvmStatic
    public fun v(message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.v(message, throwable, toFile)

    @JvmStatic
    public fun v(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.v(tag, message, throwable, toFile)

    @JvmStatic
    public fun v(throwable: Throwable, toFile: Boolean = true): Unit = mainLogger.v(throwable, toFile)

    // --- DEBUG ---

    public inline fun d(crossinline message: () -> String): Unit = mainLogger.d(message)

    public inline fun d(toFile: Boolean, crossinline message: () -> String): Unit = mainLogger.d(toFile, message)

    public inline fun d(tag: String?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.d(tag, toFile, message)

    public inline fun d(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.d(throwable, toFile, message)

    public inline fun d(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.d(tag, throwable, toFile, message)

    @JvmStatic
    public fun d(message: String, toFile: Boolean = true): Unit = mainLogger.d(message, toFile)

    @JvmStatic
    public fun d(tag: String?, message: String, toFile: Boolean = true): Unit = mainLogger.d(tag, message, toFile)

    @JvmStatic
    public fun d(message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.d(message, throwable, toFile)

    @JvmStatic
    public fun d(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.d(tag, message, throwable, toFile)

    @JvmStatic
    public fun d(throwable: Throwable, toFile: Boolean = true): Unit = mainLogger.d(throwable, toFile)

    // --- INFO ---

    public inline fun i(crossinline message: () -> String): Unit = mainLogger.i(message)

    public inline fun i(toFile: Boolean, crossinline message: () -> String): Unit = mainLogger.i(toFile, message)

    public inline fun i(tag: String?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.i(tag, toFile, message)

    public inline fun i(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.i(throwable, toFile, message)

    public inline fun i(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.i(tag, throwable, toFile, message)

    @JvmStatic
    public fun i(message: String, toFile: Boolean = true): Unit = mainLogger.i(message, toFile)

    @JvmStatic
    public fun i(tag: String?, message: String, toFile: Boolean = true): Unit = mainLogger.i(tag, message, toFile)

    @JvmStatic
    public fun i(message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.i(message, throwable, toFile)

    @JvmStatic
    public fun i(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.i(tag, message, throwable, toFile)

    @JvmStatic
    public fun i(throwable: Throwable, toFile: Boolean = true): Unit = mainLogger.i(throwable, toFile)

    // --- WARN ---

    public inline fun w(crossinline message: () -> String): Unit = mainLogger.w(message)

    public inline fun w(toFile: Boolean, crossinline message: () -> String): Unit = mainLogger.w(toFile, message)

    public inline fun w(tag: String?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.w(tag, toFile, message)

    public inline fun w(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.w(throwable, toFile, message)

    public inline fun w(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.w(tag, throwable, toFile, message)

    @JvmStatic
    public fun w(message: String, toFile: Boolean = true): Unit = mainLogger.w(message, toFile)

    @JvmStatic
    public fun w(tag: String?, message: String, toFile: Boolean = true): Unit = mainLogger.w(tag, message, toFile)

    @JvmStatic
    public fun w(message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.w(message, throwable, toFile)

    @JvmStatic
    public fun w(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.w(tag, message, throwable, toFile)

    @JvmStatic
    public fun w(throwable: Throwable, toFile: Boolean = true): Unit = mainLogger.w(throwable, toFile)

    // --- ERROR ---

    public inline fun e(crossinline message: () -> String): Unit = mainLogger.e(message)

    public inline fun e(toFile: Boolean, crossinline message: () -> String): Unit = mainLogger.e(toFile, message)

    public inline fun e(tag: String?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.e(tag, toFile, message)

    public inline fun e(throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.e(throwable, toFile, message)

    public inline fun e(tag: String?, throwable: Throwable?, toFile: Boolean = true, crossinline message: () -> String): Unit = mainLogger.e(tag, throwable, toFile, message)

    @JvmStatic
    public fun e(message: String, toFile: Boolean = true): Unit = mainLogger.e(message, toFile)

    @JvmStatic
    public fun e(tag: String?, message: String, toFile: Boolean = true): Unit = mainLogger.e(tag, message, toFile)

    @JvmStatic
    public fun e(message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.e(message, throwable, toFile)

    @JvmStatic
    public fun e(tag: String?, message: String, throwable: Throwable?, toFile: Boolean = true): Unit = mainLogger.e(tag, message, throwable, toFile)

    @JvmStatic
    public fun e(throwable: Throwable, toFile: Boolean = true): Unit = mainLogger.e(throwable, toFile)

    // --- JSON ---

    @JvmStatic
    public fun json(json: String, toFile: Boolean = true): Unit = mainLogger.json(json, toFile)

    @JvmStatic
    public fun json(json: String, tag: String?, toFile: Boolean = true): Unit = mainLogger.json(json, tag, toFile)

    @PublishedApi
    internal fun shouldLog(level: Int): Boolean = mainLogger.shouldLog(level)

    @PublishedApi
    internal fun logDirect(level: Int, tag: String?, toFile: Boolean, message: String, throwable: Throwable?) {
        mainLogger.logDirect(level, tag, toFile, message, throwable)
    }
}
