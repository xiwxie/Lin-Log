package com.lin.log

import com.lin.log.cleaner.SafeLogCleaner
import com.lin.log.formatter.*
import com.lin.log.printer.AndroidLogPrinter
import com.lin.log.printer.LogPrinter
import com.lin.log.printer.file.AsyncFilePrinter
import com.lin.log.printer.file.DefaultFileNameGenerator
import com.lin.log.printer.file.FileNameGenerator
import java.io.File

/**
 * LinLog 全局与局部配置不可变数据对象
 *
 * @property minLevel 打印的最低日志级别（低于此级别的日志完全不处理，详见 [com.lin.log.LogLevel]）
 * @property tag 默认日志 Tag
 * @property enableThreadInfo 是否携带当前线程信息（如 `Thread: [main, id=1]`）
 * @property stackTraceDepth 调用栈跟踪深度（0 代表不打印调用栈；>0 代表打印指定层数的真实业务栈）
 * @property stackTraceOrigin 调用栈裁剪的原点包名前缀（用于跳过日志封装层）
 * @property enableBorder 是否为日志内容添加字符边框
 * @property isEnabled 是否开启日志系统（false 代表彻底关闭所有输出）
 * @property enableConsoleLog 是否启用控制台输出
 * @property enableFileLog 是否启用文件异步写入
 * @property fileLogDirectory 文件日志物理存储根目录
 * @property fileMinLevel 写入磁盘的最低日志级别门槛
 * @property maxFileSize 单个日志文件最大字节容量（超过自动滚动轮转）
 * @property maxDirCapacity 日志目录最大总字节容量（超过自动按 FIFO 淘汰最旧日志）
 * @property maxKeepDaysMillis 日志最长保存时长（毫秒）
 * @property fileNameGenerator 日志文件命名生成策略
 * @property fileBufferCapacity 异步写入通道的内存缓冲区大小
 * @property preInitBufferCapacity 预初始化阶段早鸟日志暂存队列容量
 * @property enablePreInitConsoleLog 预初始化阶段是否向控制台输出日志
 * @property jsonFormatter JSON 美化格式化器
 * @property throwableFormatter 异常堆栈格式化器
 * @property threadFormatter 线程信息格式化器
 * @property stackTraceFormatter 调用栈格式化器
 * @property borderFormatter 边框格式化器
 * @property printers 最终生效的日志输出管道列表
 */
public data class LinLogConfiguration internal constructor(
    val minLevel: Int = LogLevel.ALL,
    val tag: String = LinLogConstants.DEFAULT_TAG,
    val enableThreadInfo: Boolean = false,
    val stackTraceDepth: Int = 0,
    val stackTraceOrigin: String? = null,
    val enableBorder: Boolean = false,
    val isEnabled: Boolean = true,
    val enableConsoleLog: Boolean = true,
    val enableFileLog: Boolean = true,
    val fileLogDirectory: File? = null,
    val fileMinLevel: Int = LogLevel.ALL,
    val maxFileSize: Long = LinLogConstants.DEFAULT_MAX_FILE_SIZE,
    val maxDirCapacity: Long = LinLogConstants.DEFAULT_MAX_DIR_CAPACITY,
    val maxKeepDaysMillis: Long = LinLogConstants.DEFAULT_MAX_KEEP_DAYS_MILLIS,
    val fileNameGenerator: FileNameGenerator = DefaultFileNameGenerator(),
    val fileBufferCapacity: Int = 2048,
    val preInitBufferCapacity: Int = LinLogConstants.PRE_INIT_BUFFER_MAX_CAPACITY,
    val enablePreInitConsoleLog: Boolean = true,
    val jsonFormatter: JsonFormatter = DefaultJsonFormatter(),
    val throwableFormatter: ThrowableFormatter = DefaultThrowableFormatter(),
    val threadFormatter: ThreadFormatter = DefaultThreadFormatter(),
    val stackTraceFormatter: StackTraceFormatter = DefaultStackTraceFormatter(),
    val borderFormatter: BorderFormatter = DefaultBorderFormatter(),
    val isolateThread: Boolean = false,
    val printers: List<LogPrinter> = emptyList()
) {
    /**
     * [LinLogConfiguration] 的链式构建器
     */
    public class Builder {
        public var minLevel: Int = LogLevel.ALL
        public var tag: String = LinLogConstants.DEFAULT_TAG
        public var enableThreadInfo: Boolean = false
        public var stackTraceDepth: Int = 0
        public var stackTraceOrigin: String? = null
        public var enableBorder: Boolean = false
        public var isEnabled: Boolean = true
        public var enableConsoleLog: Boolean = true
        public var enableFileLog: Boolean = true
        public var fileLogDirectory: File? = null
        public var fileMinLevel: Int = LogLevel.ALL
        public var maxFileSize: Long = LinLogConstants.DEFAULT_MAX_FILE_SIZE
        public var maxDirCapacity: Long = LinLogConstants.DEFAULT_MAX_DIR_CAPACITY
        public var maxKeepDaysMillis: Long = LinLogConstants.DEFAULT_MAX_KEEP_DAYS_MILLIS
        public var fileNameGenerator: FileNameGenerator = DefaultFileNameGenerator()
        public var fileBufferCapacity: Int = 2048
        public var preInitBufferCapacity: Int = LinLogConstants.PRE_INIT_BUFFER_MAX_CAPACITY
        public var enablePreInitConsoleLog: Boolean = true
        public var jsonFormatter: JsonFormatter = DefaultJsonFormatter()
        public var throwableFormatter: ThrowableFormatter = DefaultThrowableFormatter()
        public var threadFormatter: ThreadFormatter = DefaultThreadFormatter()
        public var stackTraceFormatter: StackTraceFormatter = DefaultStackTraceFormatter()
        public var borderFormatter: BorderFormatter = DefaultBorderFormatter()

        /**
         * 是否为当前日志通道独占开辟独立的后台写线程（默认 false，走全局共享受管调度器）
         *
         * - false (共享池模式)：与其他常规领域共用全局单线程后台调度器，节省系统线程与栈内存；
         * - true (独立专线模式)：独占独立命名的写线程，彻底物理隔离高频 APM 采样或极端慢 I/O，杜绝内存积压与调度饥饿。
         */
        public var isolateThread: Boolean = false
        private val customPrinters = mutableListOf<LogPrinter>()

        /**
         * 自定义添加额外的日志输出打印器
         */
        public fun addPrinter(printer: LogPrinter): Builder = apply {
            this.customPrinters.add(printer)
        }

        /**
         * 覆盖设置日志输出打印器列表
         */
        public fun setPrinters(vararg printers: LogPrinter): Builder = apply {
            this.customPrinters.clear()
            this.customPrinters.addAll(printers)
        }

        /**
         * 构建不可变的 [LinLogConfiguration] 实例
         */
        public fun build(): LinLogConfiguration {
            val resolvedPrinters = mutableListOf<LogPrinter>()

            if (customPrinters.isNotEmpty()) {
                resolvedPrinters.addAll(customPrinters)
            } else {
                if (enableConsoleLog) {
                    resolvedPrinters.add(AndroidLogPrinter())
                }
                if (enableFileLog && fileLogDirectory != null) {
                    val cleaner = SafeLogCleaner(
                        maxDirectoryBytes = maxDirCapacity,
                        maxKeepDaysMillis = maxKeepDaysMillis
                    )
                    resolvedPrinters.add(
                        AsyncFilePrinter(
                            logDirectory = fileLogDirectory ?: File(""),
                            minLevel = fileMinLevel,
                            maxFileSize = maxFileSize,
                            fileNameGenerator = fileNameGenerator,
                            cleaner = cleaner,
                            bufferCapacity = fileBufferCapacity,
                            isolateThread = isolateThread,
                            domainTag = tag
                        )
                    )
                }
            }

            return LinLogConfiguration(
                minLevel = minLevel,
                tag = tag,
                enableThreadInfo = enableThreadInfo,
                stackTraceDepth = stackTraceDepth,
                stackTraceOrigin = stackTraceOrigin,
                enableBorder = enableBorder,
                isEnabled = isEnabled,
                enableConsoleLog = enableConsoleLog,
                enableFileLog = enableFileLog,
                fileLogDirectory = fileLogDirectory,
                fileMinLevel = fileMinLevel,
                maxFileSize = maxFileSize,
                maxDirCapacity = maxDirCapacity,
                maxKeepDaysMillis = maxKeepDaysMillis,
                fileNameGenerator = fileNameGenerator,
                fileBufferCapacity = fileBufferCapacity,
                preInitBufferCapacity = preInitBufferCapacity,
                enablePreInitConsoleLog = enablePreInitConsoleLog,
                jsonFormatter = jsonFormatter,
                throwableFormatter = throwableFormatter,
                threadFormatter = threadFormatter,
                stackTraceFormatter = stackTraceFormatter,
                borderFormatter = borderFormatter,
                isolateThread = isolateThread,
                printers = resolvedPrinters.toList()
            )
        }
    }
}
