package com.lin.log.formatter

import java.io.PrintWriter
import java.io.StringWriter
import java.net.UnknownHostException

/**
 * 异常堆栈格式化器接口
 */
public fun interface ThrowableFormatter : Formatter<Throwable>

/**
 * 默认异常堆栈格式化器（针对网络无连接异常进行收敛，减少日志刷屏）
 */
public class DefaultThrowableFormatter : ThrowableFormatter {
    override fun format(data: Throwable): String {
        var t: Throwable? = data
        while (t != null) {
            if (t is UnknownHostException) {
                return "UnknownHostException (network unavailable)"
            }
            t = t.cause
        }
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        data.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}

/**
 * 线程信息格式化器接口
 */
public fun interface ThreadFormatter : Formatter<Thread>

/**
 * 默认线程格式化器
 */
public class DefaultThreadFormatter : ThreadFormatter {
    override fun format(data: Thread): String = "Thread: [${data.name}, id=${data.id}]"
}

/**
 * 调用栈帧数组格式化器接口
 */
public fun interface StackTraceFormatter : Formatter<Array<StackTraceElement>>

/**
 * 默认调用栈格式化器
 */
public class DefaultStackTraceFormatter : StackTraceFormatter {
    override fun format(data: Array<StackTraceElement>): String {
        val sb = StringBuilder()
        for (i in data.indices) {
            if (i > 0) sb.append("\n\t")
            sb.append(data[i].toString())
        }
        return sb.toString()
    }
}

/**
 * 边框格式化器接口
 */
public fun interface BorderFormatter : Formatter<Array<String?>>

/**
 * 默认字符边框格式化器
 */
public class DefaultBorderFormatter : BorderFormatter {
    private val topBorder = "┌────────────────────────────────────────────────────────────────────────────"
    private val middleBorder = "├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄"
    private val bottomBorder = "└────────────────────────────────────────────────────────────────────────────"

    override fun format(data: Array<String?>): String {
        val sb = StringBuilder()
        sb.append(topBorder).append('\n')
        val nonNullSegments = data.filterNotNull().filter { it.isNotEmpty() }
        for (i in nonNullSegments.indices) {
            val lines = nonNullSegments[i].split("\n")
            for (line in lines) {
                sb.append("│ ").append(line).append('\n')
            }
            if (i < nonNullSegments.size - 1) {
                sb.append(middleBorder).append('\n')
            }
        }
        sb.append(bottomBorder)
        return sb.toString()
    }
}
