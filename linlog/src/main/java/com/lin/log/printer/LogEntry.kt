package com.lin.log.printer

/**
 * 日志传递与处理的数据实体
 *
 * @property timestamp 日志生成的绝对时间戳（毫秒）
 * @property level 日志级别（详见 [com.lin.log.LogLevel]）
 * @property tag 日志标签
 * @property message 日志正文内容
 * @property threadName 产生日志的线程信息（可选）
 * @property stackTrace 异常调用栈或调用者代码行号栈信息（可选）
 * @property writeToFile 是否允许写入磁盘文件（若为 false，则文件打印器会直接短路拦截）
 */
public data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: Int,
    val tag: String,
    val message: String,
    val threadName: String? = null,
    val stackTrace: String? = null,
    val writeToFile: Boolean = true
)
