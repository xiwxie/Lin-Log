package com.lin.log.internal

import android.os.Process
import com.lin.log.LinLogConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * 全局统一受管日志落盘调度器（默认常驻 1 个后台写线程，用于驱动所有常规日志桶，杜绝多桶线程爆炸）
 */
internal object LinLogDispatcher {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, LinLogConstants.THREAD_NAME_ASYNC_WRITER).apply {
            priority = Process.THREAD_PRIORITY_BACKGROUND
        }
    }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    val writerScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
