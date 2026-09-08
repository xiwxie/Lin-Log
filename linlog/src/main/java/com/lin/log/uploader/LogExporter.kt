package com.lin.log.uploader

import android.content.Context
import com.lin.log.LinLog
import com.lin.log.LinLogConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出打包结果封装
 *
 * @property zipFile 最终生成的临时 Zip 压缩包文件
 * @property sourceFiles 本次打包被纳入的原始未压缩日志文件清单
 */
public data class ExportResult(
    val zipFile: File,
    val sourceFiles: List<File>
)

/**
 * 日志文件导出与快照打包器（冷热物理隔离，0 丢日志）
 */
public object LogExporter {

    /**
     * 提取指定多个目录中符合条件的日志并打包为临时 Zip 文件，返回包含 Zip 及源文件清单的 [ExportResult]
     *
     * @param context Context 实例
     * @param logDirs 需要导出的日志物理存储目录列表
     * @param daysCount 导出天数（如 3 代表今天、昨天、前天；若 <= 0 或 [allHistory] 为 true，则全量提取全部历史日志）
     * @param allHistory 是否收取所有历史日志（默认自动判断：当 daysCount <= 0 时为 true）
     * @return 包含临时 Zip 和源文件列表的结果包装
     */
    public suspend fun exportLogsResult(
        context: Context,
        logDirs: List<File>,
        daysCount: Int = 3,
        allHistory: Boolean = (daysCount <= 0),
        excludeFiles: Set<File> = emptySet()
    ): ExportResult? = withContext(Dispatchers.IO) {
        if (logDirs.isEmpty()) return@withContext null

        // 1. 强制落盘，保证内存缓冲完全写入文件
        LinLog.flushAll()

        val targetDateSet = mutableSetOf<String>()
        if (!allHistory) {
            val dayFormat = SimpleDateFormat(LinLogConstants.DATE_PATTERN_DAY, Locale.US)
            val calendar = Calendar.getInstance()
            for (i in 0 until daysCount) {
                targetDateSet.add(dayFormat.format(calendar.time))
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }
        }

        // 2. 收集各目录中符合日期条件的日志文件（主动排除正在写入的热文件）
        val isMultiDir = logDirs.size > 1
        val fileEntryMap = mutableListOf<Pair<String, File>>() // Pair<ZipEntryPath, File>
        val sourceFiles = mutableListOf<File>()

        for (dir in logDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val matchedFiles = dir.listFiles { file ->
                file.isFile && !excludeFiles.contains(file) && (allHistory || targetDateSet.any { file.name.contains(it) })
            } ?: continue

            for (file in matchedFiles) {
                val entryPath = if (isMultiDir) "${dir.name}/${file.name}" else file.name
                fileEntryMap.add(entryPath to file)
                sourceFiles.add(file)
            }
        }

        if (fileEntryMap.isEmpty()) return@withContext null

        // 3. 压缩打包至临时 cache 目录（采用 9 级最高压缩比，极致缩减移动网络上传体积）
        val tempZipDir = File(context.cacheDir, LinLogConstants.UPLOAD_CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
        val zipFile = File(tempZipDir, "${LinLogConstants.UPLOAD_ZIP_PREFIX}${System.currentTimeMillis()}.${LinLogConstants.FILE_EXTENSION_ZIP}")

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION) // 9 级最高压缩
                val buffer = ByteArray(LinLogConstants.ZIP_BUFFER_SIZE_BYTES)
                for ((entryPath, file) in fileEntryMap) {
                    FileInputStream(file).use { fis ->
                        BufferedInputStream(fis).use { bis ->
                            val entry = ZipEntry(entryPath)
                            zos.putNextEntry(entry)
                            var readCount: Int
                            while (bis.read(buffer).also { readCount = it } != -1) {
                                zos.write(buffer, 0, readCount)
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }
            ExportResult(zipFile = zipFile, sourceFiles = sourceFiles)
        } catch (_: Throwable) {
            zipFile.delete()
            null
        }
    }

    /**
     * 提取指定多个目录中最近 N 天的历史冷日志并打包为临时 Zip 文件
     */
    public suspend fun exportRecentLogsZip(
        context: Context,
        logDirs: List<File>,
        daysCount: Int = 3
    ): File? {
        return exportLogsResult(context, logDirs, daysCount)?.zipFile
    }

    /**
     * 安全删除已打包归档的源日志文件（自动排除正在写入的热文件）
     *
     * @param files 需要删除的文件列表
     * @param excludeFiles 强制排除的文件集合（如当前热活跃文件）
     */
    public fun deleteSourceFiles(files: List<File>, excludeFiles: Set<File> = emptySet()) {
        for (file in files) {
            if (excludeFiles.contains(file)) continue
            try {
                file.delete()
            } catch (_: Throwable) {}
        }
    }
}


