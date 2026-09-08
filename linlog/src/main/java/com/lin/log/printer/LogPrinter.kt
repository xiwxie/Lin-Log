package com.lin.log.printer

/**
 * 日志输出管道标准接口
 */
public interface LogPrinter {

    /**
     * 打印一条日志
     * @param entry 结构化日志条目
     */
    public fun print(entry: LogEntry)

    /**
     * 同步将内存中积攒的日志强制刷入底层输出介质（如磁盘/网络）
     */
    public fun flush() {}

    /**
     * 释放打印器所占用的系统资源（如关闭文件流、终止协程作用域与线程池）
     */
    public fun release() {}
}
