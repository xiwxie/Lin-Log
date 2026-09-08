package com.lin.log

/**
 * LinLog 核心常量配置与规则定义
 */
public object LinLogConstants {

    /** 默认主 Tag */
    public const val DEFAULT_TAG: String = "LinLog"

    /** 默认 Debug 本地存储子目录 */
    public const val DEFAULT_DIR_DEBUG: String = "linlog/debug"

    /** 默认 Release 本地存储子目录 */
    public const val DEFAULT_DIR_RELEASE: String = "linlog/release"

    /** 上传临时压缩包缓存目录名 */
    public const val UPLOAD_CACHE_DIR_NAME: String = "linlog_upload_cache"

    /** 统一日志文件扩展名 */
    public const val FILE_EXTENSION_LINLOG: String = "linlog"

    /** 兼容旧版日志扩展名 */
    public const val FILE_EXTENSION_XLOG: String = "xlog"

    /** 日志文件前缀 */
    public const val LOG_FILE_PREFIX: String = "log_"

    /** 上传压缩包文件前缀 */
    public const val UPLOAD_ZIP_PREFIX: String = "linlog_upload_"

    /** 压缩文件扩展名 */
    public const val FILE_EXTENSION_ZIP: String = "zip"

    /** 按天命名的日期格式化模式 */
    public const val DATE_PATTERN_DAY: String = "yyyyMMdd"

    /** 单条日志时间戳精确格式化模式 */
    public const val DATE_PATTERN_TIMESTAMP: String = "yyyy-MM-dd HH:mm:ss.SSS"

    /** 异步写线程名称（用于 Systrace/Perfetto 性能分析追踪） */
    public const val THREAD_NAME_ASYNC_WRITER: String = "LinLog-AsyncWriter"

    /** 单个日志文件默认最大容量：2MB */
    public const val DEFAULT_MAX_FILE_SIZE: Long = 2 * 1024 * 1024L

    /** 日志总目录默认最大容量：30MB */
    public const val DEFAULT_MAX_DIR_CAPACITY: Long = 30 * 1024 * 1024L

    /** 日志默认保留天数（毫秒数）：7 天 */
    public const val DEFAULT_MAX_KEEP_DAYS_MILLIS: Long = 7 * 24 * 60 * 60 * 1000L

    /** 单日异常超前时间判定阈值（毫秒数）：24 小时 */
    public const val FUTURE_TIMESTAMP_THRESHOLD_MILLIS: Long = 24 * 60 * 60 * 1000L

    /** 物理容量超限时缩减的目标安全水位比率（70%） */
    public const val CAPACITY_SAFE_WATERMARK_RATIO: Double = 0.7

    /** 自动清理后台巡检防抖间隔（毫秒）：30 秒 */
    public const val CLEAN_THROTTLE_INTERVAL_MILLIS: Long = 30 * 1000L

    /** 设备物理可用空间紧急告警阈值（低于此值触发紧急熔断清理）：50MB */
    public const val LOW_STORAGE_EMERGENCY_THRESHOLD_BYTES: Long = 50 * 1024 * 1024L

    /** 单次批量聚合最大日志条数（削峰填谷） */
    public const val BATCH_DRAIN_LIMIT: Int = 64

    /** 写入缓冲区默认大小：16KB */
    public const val BUFFER_SIZE_BYTES: Int = 16 * 1024

    /** Zip 压缩流缓冲区大小：8KB */
    public const val ZIP_BUFFER_SIZE_BYTES: Int = 8 * 1024

    /** 控制台单次打印的最大字符分片长度（防止 Logcat 截断） */
    public const val MAX_LOGCAT_CHUNK_SIZE: Int = 4000

    /** 未初始化前预存暂存队列最大条数（早鸟日志防丢） */
    public const val PRE_INIT_BUFFER_MAX_CAPACITY: Int = 256

    /**
     * 按照默认规范构建日志文件名
     *
     * @param dayIndex 格式为 yyyyMMdd 的日期字符串
     * @param fileIndex 当天递增的文件序号
     * @param prefix 文件名前缀，默认为 [LOG_FILE_PREFIX]
     * @param extension 文件扩展名，默认为 [FILE_EXTENSION_LINLOG]
     */
    @JvmStatic
    public fun formatLogFileName(
        dayIndex: String,
        fileIndex: Int,
        prefix: String = LOG_FILE_PREFIX,
        extension: String = FILE_EXTENSION_LINLOG
    ): String = "$prefix${dayIndex}_$fileIndex.$extension"
}
