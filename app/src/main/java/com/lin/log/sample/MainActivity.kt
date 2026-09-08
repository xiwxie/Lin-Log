package com.lin.log.sample

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lin.log.LinLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 官方演示页面，涵盖主业务、多领域多态输出、异常上报与日志导出回收全流程
 *
 * @author LinLog Team
 * @since 1.0.0
 */
class MainActivity : AppCompatActivity() {

    // 属性委托绑定特定领域（0 运行时对象分配）
    private val liveRoomLogger by LinLog.domain("live_room")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_run_sample).setOnClickListener {
            runSampleLogs()
        }

        // 默认自动触发一次测试
        runSampleLogs()
    }

    private fun runSampleLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. 【主业务常规日志】：支持内联 Lambda 短路懒求值
            LinLog.i("MainActivity") { "================== [LinLog 全功能演示案例] ==================" }
            LinLog.d { "用户成功打开 MainActivity 页面" }

            // 2. 【多态支持与异常多重重载】：彻底告别位置参数传参失败
            val watcherId = "MemoryWatcher"
            LinLog.apm.w(watcherId, "Watcher 远程/本地配置为 null，跳过初始化", true)
            LinLog.apm.w("直接纯文本警告，无异常也能顺利指定落盘", true)
            LinLog.apm.w(watcherId, "配置解析异常", IllegalArgumentException("Invalid Watcher Config"), true)
            LinLog.apm.w(IllegalStateException("APM 探针直接异常上报"))

            // 3. 【APM 领域性能采样】：0 栈回溯
            LinLog.apm.d { "帧渲染耗时 16.2ms, PSS 内存: 45.3MB, CPU 占用率: 4.5%" }

            // 4. 【网络领域日志】：自动落盘到 /linlog/network/
            LinLog.net.i { "--> POST https://api.example.com/v1/user/profile (256 bytes)" }
            LinLog.net.d { "Socket 心跳发送: ping -> pong (RTT = 28ms)" }

            // 5. 【埋点领域日志】：自动落盘到 /linlog/track/
            LinLog.track.i { "[Event: click_banner] | banner_id: 101 | position: home_top" }
            LinLog.track.i { "[Event: page_view] | page: MainActivity | duration: 3500ms" }

            // 6. 【属性委托领域实战】
            liveRoomLogger.i { "进入房间 8888, 连麦主播: Linda" }

            // 7. 【控制台专属门面】：绝不落盘、0 磁盘 I/O
            LinLog.console.d { "动画每一帧插值进度: fraction = 0.85" }

            // 8. 【JSON 美化输出】
            LinLog.json("""{"status":200,"msg":"success","data":{"userId":8848,"nickname":"MisterPeng","vip":true}}""", tag = "UserApi")

            // 9. 【安全日志导出与自动清理】：导出最近 3 天日志至 Zip，使用完毕自动删除临时包
            LinLog.exportScope(this@MainActivity, daysCount = 3) { allInOneZip ->
                LinLog.i("LogExporter") { "全量日志 Zip 导出成功: ${allInOneZip.name}, 大小: ${allInOneZip.length()} 字节" }
            }
        }
    }
}
