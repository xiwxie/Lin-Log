package com.lin.log

/**
 * 控制台专用日志子门面（语义化调用，日志仅在 Logcat 输出，绝不写入磁盘，适合高频 UI、动画或局部调试）
 *
 * @author LinLog Team
 * @since 1.0.0
 */
public object ConsoleFacade {

    // ==================== VERBOSE 级别多态重载 ====================

    /**
     * 输出 VERBOSE 级别控制台日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.VERBOSE)) {
            LinLog.logDirect(LogLevel.VERBOSE, null, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 VERBOSE 级别控制台日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(tag: String?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.VERBOSE)) {
            LinLog.logDirect(LogLevel.VERBOSE, tag, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 VERBOSE 级别控制台日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.VERBOSE)) {
            LinLog.logDirect(LogLevel.VERBOSE, null, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 VERBOSE 级别控制台日志（内联高阶函数，支持自定义 Tag、携带异常与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun v(tag: String?, throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.VERBOSE)) {
            LinLog.logDirect(LogLevel.VERBOSE, tag, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 VERBOSE 级别普通字符串控制台日志（直通 Fast-Path）
     *
     * @param message 日志内容
     */
    @JvmStatic
    public fun v(message: String) {
        v(null, message)
    }

    /**
     * 输出 VERBOSE 级别普通字符串控制台日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容
     */
    @JvmStatic
    public fun v(tag: String?, message: String) {
        if (LinLog.shouldLog(LogLevel.VERBOSE)) {
            LinLog.logDirect(LogLevel.VERBOSE, tag, toFile = false, message = message, throwable = null)
        }
    }

    /**
     * 输出 VERBOSE 级别异常控制台日志（带说明与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun v(message: String, throwable: Throwable?) {
        v(null, message, throwable)
    }

    /**
     * 输出 VERBOSE 级别异常控制台日志（带自定义 Tag、说明与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun v(tag: String?, message: String, throwable: Throwable?) {
        if (LinLog.shouldLog(LogLevel.VERBOSE)) {
            LinLog.logDirect(LogLevel.VERBOSE, tag, toFile = false, message = message, throwable = throwable)
        }
    }

    /**
     * 输出 VERBOSE 级别纯异常控制台日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun v(throwable: Throwable) {
        v(null, throwable.message ?: throwable.javaClass.simpleName, throwable)
    }

    // ==================== DEBUG 级别多态重载 ====================

    /**
     * 输出 DEBUG 级别控制台日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.DEBUG)) {
            LinLog.logDirect(LogLevel.DEBUG, null, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 DEBUG 级别控制台日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(tag: String?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.DEBUG)) {
            LinLog.logDirect(LogLevel.DEBUG, tag, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 DEBUG 级别控制台日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.DEBUG)) {
            LinLog.logDirect(LogLevel.DEBUG, null, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 DEBUG 级别控制台日志（内联高阶函数，支持自定义 Tag、携带异常与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun d(tag: String?, throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.DEBUG)) {
            LinLog.logDirect(LogLevel.DEBUG, tag, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 DEBUG 级别普通字符串控制台日志（直通 Fast-Path）
     *
     * @param message 日志内容
     */
    @JvmStatic
    public fun d(message: String) {
        d(null, message)
    }

    /**
     * 输出 DEBUG 级别普通字符串控制台日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容
     */
    @JvmStatic
    public fun d(tag: String?, message: String) {
        if (LinLog.shouldLog(LogLevel.DEBUG)) {
            LinLog.logDirect(LogLevel.DEBUG, tag, toFile = false, message = message, throwable = null)
        }
    }

    /**
     * 输出 DEBUG 级别异常控制台日志（带说明与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun d(message: String, throwable: Throwable?) {
        d(null, message, throwable)
    }

    /**
     * 输出 DEBUG 级别异常控制台日志（带自定义 Tag、说明与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun d(tag: String?, message: String, throwable: Throwable?) {
        if (LinLog.shouldLog(LogLevel.DEBUG)) {
            LinLog.logDirect(LogLevel.DEBUG, tag, toFile = false, message = message, throwable = throwable)
        }
    }

    /**
     * 输出 DEBUG 级别纯异常控制台日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun d(throwable: Throwable) {
        d(null, throwable.message ?: throwable.javaClass.simpleName, throwable)
    }

    // ==================== INFO 级别多态重载 ====================

    /**
     * 输出 INFO 级别控制台日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.INFO)) {
            LinLog.logDirect(LogLevel.INFO, null, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 INFO 级别控制台日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(tag: String?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.INFO)) {
            LinLog.logDirect(LogLevel.INFO, tag, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 INFO 级别控制台日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.INFO)) {
            LinLog.logDirect(LogLevel.INFO, null, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 INFO 级别控制台日志（内联高阶函数，支持自定义 Tag、携带异常与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun i(tag: String?, throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.INFO)) {
            LinLog.logDirect(LogLevel.INFO, tag, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 INFO 级别普通字符串控制台日志（直通 Fast-Path）
     *
     * @param message 日志内容
     */
    @JvmStatic
    public fun i(message: String) {
        i(null, message)
    }

    /**
     * 输出 INFO 级别普通字符串控制台日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容
     */
    @JvmStatic
    public fun i(tag: String?, message: String) {
        if (LinLog.shouldLog(LogLevel.INFO)) {
            LinLog.logDirect(LogLevel.INFO, tag, toFile = false, message = message, throwable = null)
        }
    }

    /**
     * 输出 INFO 级别异常控制台日志（带说明与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun i(message: String, throwable: Throwable?) {
        i(null, message, throwable)
    }

    /**
     * 输出 INFO 级别异常控制台日志（带自定义 Tag、说明与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun i(tag: String?, message: String, throwable: Throwable?) {
        if (LinLog.shouldLog(LogLevel.INFO)) {
            LinLog.logDirect(LogLevel.INFO, tag, toFile = false, message = message, throwable = throwable)
        }
    }

    /**
     * 输出 INFO 级别纯异常控制台日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun i(throwable: Throwable) {
        i(null, throwable.message ?: throwable.javaClass.simpleName, throwable)
    }

    // ==================== WARN 级别多态重载 ====================

    /**
     * 输出 WARN 级别控制台日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.WARN)) {
            LinLog.logDirect(LogLevel.WARN, null, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 WARN 级别控制台日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(tag: String?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.WARN)) {
            LinLog.logDirect(LogLevel.WARN, tag, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 WARN 级别控制台日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.WARN)) {
            LinLog.logDirect(LogLevel.WARN, null, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 WARN 级别控制台日志（内联高阶函数，支持自定义 Tag、携带异常与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun w(tag: String?, throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.WARN)) {
            LinLog.logDirect(LogLevel.WARN, tag, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 WARN 级别普通字符串控制台日志（直通 Fast-Path）
     *
     * @param message 日志内容
     */
    @JvmStatic
    public fun w(message: String) {
        w(null, message)
    }

    /**
     * 输出 WARN 级别普通字符串控制台日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容
     */
    @JvmStatic
    public fun w(tag: String?, message: String) {
        if (LinLog.shouldLog(LogLevel.WARN)) {
            LinLog.logDirect(LogLevel.WARN, tag, toFile = false, message = message, throwable = null)
        }
    }

    /**
     * 输出 WARN 级别异常控制台日志（带说明与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun w(message: String, throwable: Throwable?) {
        w(null, message, throwable)
    }

    /**
     * 输出 WARN 级别异常控制台日志（带自定义 Tag、说明与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun w(tag: String?, message: String, throwable: Throwable?) {
        if (LinLog.shouldLog(LogLevel.WARN)) {
            LinLog.logDirect(LogLevel.WARN, tag, toFile = false, message = message, throwable = throwable)
        }
    }

    /**
     * 输出 WARN 级别纯异常控制台日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun w(throwable: Throwable) {
        w(null, throwable.message ?: throwable.javaClass.simpleName, throwable)
    }

    // ==================== ERROR 级别多态重载 ====================

    /**
     * 输出 ERROR 级别控制台日志（内联高阶函数，支持短路懒求值）
     *
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.ERROR)) {
            LinLog.logDirect(LogLevel.ERROR, null, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 ERROR 级别控制台日志（内联高阶函数，支持自定义 Tag 与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(tag: String?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.ERROR)) {
            LinLog.logDirect(LogLevel.ERROR, tag, toFile = false, message = message(), throwable = null)
        }
    }

    /**
     * 输出 ERROR 级别控制台日志（内联高阶函数，支持携带异常与短路懒求值）
     *
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.ERROR)) {
            LinLog.logDirect(LogLevel.ERROR, null, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 ERROR 级别控制台日志（内联高阶函数，支持自定义 Tag、携带异常与短路懒求值）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param throwable 异常堆栈信息
     * @param message 日志内容构建 Lambda
     */
    public inline fun e(tag: String?, throwable: Throwable?, crossinline message: () -> String) {
        if (LinLog.shouldLog(LogLevel.ERROR)) {
            LinLog.logDirect(LogLevel.ERROR, tag, toFile = false, message = message(), throwable = throwable)
        }
    }

    /**
     * 输出 ERROR 级别普通字符串控制台日志（直通 Fast-Path）
     *
     * @param message 日志内容
     */
    @JvmStatic
    public fun e(message: String) {
        e(null, message)
    }

    /**
     * 输出 ERROR 级别普通字符串控制台日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志内容
     */
    @JvmStatic
    public fun e(tag: String?, message: String) {
        if (LinLog.shouldLog(LogLevel.ERROR)) {
            LinLog.logDirect(LogLevel.ERROR, tag, toFile = false, message = message, throwable = null)
        }
    }

    /**
     * 输出 ERROR 级别异常控制台日志（带说明与异常）
     *
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun e(message: String, throwable: Throwable?) {
        e(null, message, throwable)
    }

    /**
     * 输出 ERROR 级别异常控制台日志（带自定义 Tag、说明与异常）
     *
     * @param tag 自定义 Tag，传 null 则使用默认 Tag
     * @param message 日志说明文本
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun e(tag: String?, message: String, throwable: Throwable?) {
        if (LinLog.shouldLog(LogLevel.ERROR)) {
            LinLog.logDirect(LogLevel.ERROR, tag, toFile = false, message = message, throwable = throwable)
        }
    }

    /**
     * 输出 ERROR 级别纯异常控制台日志（自动提取异常描述作为消息体）
     *
     * @param throwable 异常堆栈信息
     */
    @JvmStatic
    public fun e(throwable: Throwable) {
        e(null, throwable.message ?: throwable.javaClass.simpleName, throwable)
    }

    // ==================== JSON 控制台格式化输出 ====================

    /**
     * 格式化并输出 JSON 控制台日志（级别为 DEBUG，使用默认 Tag）
     *
     * @param json 原始 JSON 文本
     */
    @JvmStatic
    public fun json(json: String) {
        json(null, json)
    }

    /**
     * 格式化并输出 JSON 控制台日志（带自定义 Tag）
     *
     * @param tag 自定义 Tag
     * @param json 原始 JSON 文本
     */
    @JvmStatic
    public fun json(tag: String?, json: String) {
        if (!LinLog.shouldLog(LogLevel.DEBUG)) return
        val formatted = LinLog.configuration.jsonFormatter.format(json)
        d(tag, formatted)
    }
}
