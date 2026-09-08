# LinLog AI-Native 架构规范与智能体协同总纲 (AGENTS.md)

本项目内建了工业级全异步、零侵入、多领域物理隔离的日志基础设施 **LinLog (`:linlog`)**。
任何接入本代码库的 AI Agent（Google Antigravity、Cursor、OpenAI Codex、Claude Code、GitHub Copilot）在面对日志排查、故障分析或架构调优任务时，必须严格遵循本规范。

---

## 一、AI 智能体日志诊断协议 (Agentic Diagnosis Protocol)

### 1. 零人工命令行摩擦原则 (Zero-Command Automation)
当用户提供包含 `.zip` 或解压目录的日志路径，或提出“帮我分析这个日志”、“为什么又掉线了”、“排查崩溃原因”时：
- **严禁**要求用户在终端手动敲击命令；
- **严禁**直接用 cat/head 暴力倾倒上万行日志淹没上下文；
- **必须**在后台自主激活项目内置的 Skill：`.agents/skills/linlog-analyzer/SKILL.md`；
- **必须**调用工程内置的诊断引擎：
  - 用户有明确问题时：`python3 tools/analyzer/analyze_log.py <path_to_zip> -q "<问题关键词>"`
  - 用户需要全盘概览时：`python3 tools/analyzer/analyze_log.py <path_to_zip>`

### 2. 跨域时序因果推导 (Cross-Domain Causality)
日志包内置四大核心物理领域，排查时必须进行四维时序交叉验证：
- **`socket/`**：WebSocket 连接状态拓扑（`Connecting` -> `Connected` -> `Closed`）与收发心跳；
- **`network/`**：网络状态变化（Wifi/Cellular/NoNet）与 HTTP API 耗时（>800ms 视为慢接口）；
- **`apm/`**：JVM 内存峰值与系统可用内存、卡顿丢帧点（FPS < 30）；
- **`log/`**：主业务生命周期（Activity/Fragment/Room）、用户鉴权、未捕获异常。

### 3. 代码库联动溯源 (Codebase Cross-Reference)
定位到核心日志 `Tag` 或崩溃点后，必须在当前代码库中检索对应源码：
- 例如：`Tag=HexWebSocket` 对应长连接层源码；`Tag=HWUserInfoController` 对应鉴权模块；
- 输出报告时，必须直接附带对应 Kotlin 源码位置的链接与修复代码 Diff。

---

## 二、标准诊断报告输出模板

AI 完成推导后，必须以如下结构输出中文结论：
1. **🩺 核心定性结论**：一句话直击故障根因（如：“弱网触发网络重连检测，但因 IM Token 刷新竞态导致长连接延迟5秒重连”）；
2. **⏱️ 时序因果证据链**：按时间轴精准列出关键事件；
3. **🔍 源码深潜分析**：指出工程中具体类、方法的缺陷；
4. **🛠️ 修复建议与代码 Diff**：提供可落地的防重/异常防御 Kotlin 代码。
