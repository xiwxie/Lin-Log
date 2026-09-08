package com.lin.log.printer.file

import com.lin.log.LinLogConstants

/**
 * 日志文件命名生成器接口
 */
public fun interface FileNameGenerator {

    /**
     * 生成目标日志文件名
     *
     * @param dayIndex 格式为 yyyyMMdd 的日期字符串
     * @param fileIndex 当天递增的文件序号
     * @return 生成的文件名（包含扩展名，如 `log_20260827_0.linlog`）
     */
    public fun generateFileName(dayIndex: String, fileIndex: Int): String
}

/**
 * 默认日志文件命名生成器实现
 *
 * @param prefix 文件名前缀，默认为 [LinLogConstants.LOG_FILE_PREFIX]
 * @param extension 文件扩展名，默认为 [LinLogConstants.FILE_EXTENSION_LINLOG]
 */
public class DefaultFileNameGenerator(
    private val prefix: String = LinLogConstants.LOG_FILE_PREFIX,
    private val extension: String = LinLogConstants.FILE_EXTENSION_LINLOG
) : FileNameGenerator {

    override fun generateFileName(dayIndex: String, fileIndex: Int): String {
        return LinLogConstants.formatLogFileName(dayIndex, fileIndex, prefix, extension)
    }
}
