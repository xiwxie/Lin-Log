package com.lin.log.internal

/**
 * 内部调用栈安全裁剪工具
 */
internal object StackTraceUtil {

    private const val LINLOG_PACKAGE_PREFIX = "com.lin.log"

    /**
     * 从栈顶自顶向下正序扫描，精准跳过 LinLog 自身及指定的顶层封装门面
     *
     * @param stackTrace 完整调用栈
     * @param customOrigin 自定义跳过的封装层包名前缀（可选）
     * @param maxDepth 裁剪保留的最大深度
     */
    fun getCroppedRealStackTrace(
        stackTrace: Array<StackTraceElement>,
        customOrigin: String?,
        maxDepth: Int
    ): Array<StackTraceElement> {
        val totalSize = stackTrace.size
        if (totalSize == 0) return emptyArray()

        var startIndex = 0
        while (startIndex < totalSize) {
            val className = stackTrace[startIndex].className
            val isFramework = className.startsWith(LINLOG_PACKAGE_PREFIX) ||
                (customOrigin != null && className.startsWith(customOrigin))
            if (!isFramework) {
                break
            }
            startIndex++
        }

        val availableDepth = totalSize - startIndex
        if (availableDepth <= 0) return emptyArray()

        val actualDepth = if (maxDepth in 1 until availableDepth) maxDepth else availableDepth
        val result = arrayOfNulls<StackTraceElement>(actualDepth)
        System.arraycopy(stackTrace, startIndex, result, 0, actualDepth)

        @Suppress("UNCHECKED_CAST")
        return result as Array<StackTraceElement>
    }
}

