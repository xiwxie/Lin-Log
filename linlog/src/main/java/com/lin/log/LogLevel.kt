package com.lin.log

/**
 * 日志输出级别常量定义
 */
public object LogLevel {
    /** 输出所有级别日志 */
    public const val ALL: Int = 0

    /** Verbose 级别 */
    public const val VERBOSE: Int = 2

    /** Debug 级别 */
    public const val DEBUG: Int = 3

    /** Info 级别 */
    public const val INFO: Int = 4

    /** Warn 级别 */
    public const val WARN: Int = 5

    /** Error 级别 */
    public const val ERROR: Int = 6

    /** 关闭日志输出 */
    public const val NONE: Int = 8

    /**
     * 获取日志级别的单字母缩写（用于磁盘扁平化存储与控制台紧凑显示）
     */
    public fun getShortName(level: Int): String = when (level) {
        VERBOSE -> "V"
        DEBUG -> "D"
        INFO -> "I"
        WARN -> "W"
        ERROR -> "E"
        else -> "U"
    }

    /**
     * 获取日志级别的完整名称
     */
    public fun getLevelName(level: Int): String = when (level) {
        VERBOSE -> "VERBOSE"
        DEBUG -> "DEBUG"
        INFO -> "INFO"
        WARN -> "WARN"
        ERROR -> "ERROR"
        else -> "UNKNOWN"
    }
}
