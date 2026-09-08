# LinLog AI-Native 架构规范与智能体协同总纲 (AGENTS.md)

本项目内建了工业级全异步、零侵入、弹性多领域的日志基础设施 **LinLog (`:linlog`)**。
任何接入本代码库的 AI Agent（Google Antigravity、Cursor、OpenAI Codex、Claude Code、GitHub Copilot）在面对日志排查、故障分析或架构调优任务时，必须严格遵循本规范。

---

## 一、AI 智能体日志诊断协议 (Agentic Diagnosis Protocol)

### 1. 零人工命令行摩擦原则 (Zero-Command Automation)
当测试、运营或研发提供 `.zip` 日志包、解压目录或提出问题（如“帮我分析这个日志”、“为什么又掉线了”、“这笔订单怎么没到账”、“为什么卡死”）时：
- **严禁**要求非技术用户在终端手动敲击命令；
- **严禁**直接用 cat/head 暴力倾倒成千上万行日志淹没对话；
- **必须**在后台自主激活项目内置的 Skill：
  - 通用日志诊断（网络/长连接/性能/崩溃/生命周期）：激活 [`.agents/skills/linlog-analyzer/SKILL.md`](file:///.agents/skills/linlog-analyzer/SKILL.md)，执行 `python3 tools/analyzer/analyze_log.py <path_to_zip>`；
  - 支付与对账专项（Google Play/华为IAP/第三方聚合支付）：激活 [`.agents/skills/payment-analyzer/SKILL.md`](file:///.agents/skills/payment-analyzer/SKILL.md)，执行 `python3 tools/analyzer/analyze_payment.py <path_to_zip>`。

### 2. 虚拟领域自适应推导 (Virtual Domain Adaptation)
LinLog 遵循业务 0 侵入设计，接入方可自由定义领域（Domain）的组织形态。分析引擎与 Agent 应自动执行弹性识别，**严禁将领域假设为固定的物理子目录**：
- **组织形态自由**：领域可以映射为物理子目录（如 `linlog/network/`）、独立日志文件（如 `network_20260908.linlog`）、亦可全量平铺在同一日志中由 `Tag` 或特定前缀逻辑隔离；
- **四大逻辑因果维度**：无论物理存储如何，Agent 均需按以下四大逻辑维度进行时序对齐：
  1. **网络通信 (Network)**：HTTP API 状态、耗时慢请求（>800ms）、网络环境切换（Wifi/Cellular/NoNet）；
  2. **长连接交互 (Socket)**：WebSocket / TCP 状态机（Connecting -> Connected -> Closed）、收发心跳与重连机制；
  3. **运行性能 (APM)**：JVM 内存突增、系统可用内存告急、UI 卡顿与丢帧（FPS < 30）；
  4. **主业务行为 (Main/Track)**：用户操作流水、页面生命周期、核心业务状态机与未捕获异常。

### 3. 代码库联动溯源 (Codebase Cross-Reference)
定位到核心日志 `Tag` 或崩溃异常堆栈后，必须联动当前代码库搜索关联源码：
- 结合异常堆栈中的类名与方法定位具体缺陷；
- 输出报告时附带对应源码位置链接与修复代码建议。

---

## 二、双重视角诊断报告标准 (Dual-Layer Report Architecture)

针对**测试、运营、产品等非技术人员**与**研发架构师**的不同需求，输出必须严格遵循以下双层结构：

### 🟢 第一层：非技术大白话速读卡片 (面向测试 / 运营 / 产品)
必须放在报告最开头，使用纯自然语言与直观结论，严禁充斥无解释的技术堆栈与晦涩术语：
1. **📢 事故大白话定性**：用一两句话讲清发生了什么（例：“用户在支付完成后，因本地网络瞬间断开导致没能及时拿到发货结果，但钱已扣除”）；
2. **🎯 责任归属明确判定**：明确划分事故责任方（【前端 App 缺陷】/【后端接口故障】/【用户弱网/系统环境】/【三方平台服务异常】）；
3. **🚶 用户操作链路还原**：以日常语言还原用户操作路径（例：“进入商品页 $\to$ 点击购买 $\to$ 微信付款成功 $\to$ 返回 App 发生黑屏”）；
4. **💡 运营 / 测试行动建议**：
   - **对运营**：明确告知是否需要人工补单、退款或安抚用户；
   - **对测试**：明确指出复现步骤与触发环境边界（如“在 Airplane 模式切换瞬间容易复现”）。

### 🔍 第二层：研发级技术深潜与代码修复 (面向研发)
1. **⏱️ 时序因果证据链**：按时间轴精准列出跨域日志事件；
2. **💻 源码级深潜剖析**：指出代码库中具体类、方法的竞态条件、内存泄露或未捕获异常；
3. **🛠️ 修复建议与代码 Diff**：提供可落地的防重/异常防御 Kotlin 源码。
