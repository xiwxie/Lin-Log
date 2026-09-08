---
name: linlog-analyzer
description: 智能解析、诊断与分析 LinLog 日志归档包 (.zip 或解压目录)。当用户提供日志文件、反馈 App 掉线/重连/卡顿/崩溃/业务失败，或要求排查用户行为链路与 APM 根因时自动激活此 Skill。
---

# LinLog 智能日志诊断与根因分析 Skill (AI-Native 规范)

## 1. 触发时机 (Trigger Scenarios)
当用户在对话中满足以下任一条件时，Agent 应自主激活此 Skill（**无需用户要求输入命令**）：
- 用户提供了一个 `.zip` 文件路径或解压目录，并提出排查需求；
- 用户反馈：“为什么又断开了”、“排查崩溃”、“分析卡顿”、“为什么这个接口失败了”；
- 用户要求还原特定用户或时间段内的多领域行为流水（Socket、网络、业务、APM）。

## 2. 自动化分析工作流 (Workflow)

### 步骤一：自主执行日志特征抽取 (Zero-User-Friction)
Agent 应通过后台命令调用工程内置的诊断引擎，**自动利用持久化缓存，无需重复解压**：

1. **若用户有具体的问题或排查方向**（如排查重连、崩溃、卡顿）：
   ```bash
   python3 tools/analyzer/analyze_log.py <path_to_zip> -q "<用户提问的关键词>"
   ```
2. **若用户需要全盘体检概览**：
   ```bash
   python3 tools/analyzer/analyze_log.py <path_to_zip>
   ```

### 步骤二：跨域时序因果推导 (Cross-Domain Root Cause Analysis)
读取脚本返回的结构化时序流水：
- **`socket/` 领域**：检查 WebSocketState 切换（Connecting -> Connected -> Closed）及重连原因；
- **`network/` 领域**：检查网络状态变化（Wifi/Cellular/NoNet）、API 慢请求（>800ms）及 4xx/5xx 错误；
- **`apm/` 领域**：检查 JVM 内存突增、系统可用内存告急、卡顿丢帧点；
- **`log/` 领域**：检查主业务逻辑、用户鉴权 Token 刷新、Activity/Fragment 生命周期与异常堆栈。

### 步骤三：代码库联动与源码跳转 (Codebase Cross-Reference)
根据日志中提取到的核心 `Tag` 和异常堆栈，在当前工程中找到对应的 Kotlin 源码文件（例如根据 `HexWebSocket` 找到对应 WebSocket 客户端，根据 `HWUserInfoController` 找到用户控制器），建立因果证据链。

### 步骤四：输出《LinLog 架构级诊断报告》
严格遵循以下标准结构输出结论：
1. **🩺 核心定性结论**：一句话明确故障性质与根本原因（Root Cause）；
2. **⏱️ 时序因果证据链**：按时间戳罗列【现场前操作 $\to$ 触发点 $\to$ 连锁副反应】的各领域日志；
3. **🔍 源码深潜分析**：指出工程中哪一行代码、哪一个监听器逻辑存在漏洞或竞态；
4. **🛠️ 修复建议与代码 Diff**：直接提供针对该源码的防重/超时/自愈/异常捕获优化 Kotlin 代码。
