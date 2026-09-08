# LinLog 智能日志分析引擎与主流 AI 协同诊断实战指南

`tools/analyzer/` 是随 `LinLog` 基础设施一体化发布的智能诊断与时序因果推导工具。配合现代主流 LLM（Google Antigravity / Gemini、Cursor/Codex、Claude、DeepSeek），实现从 **“原始 Zip 日志” 到 “非技术大白话归因 + 研发根因定位与修复建议”** 的秒级自动化闭环。

---

## 一、核心设计哲学：虚拟领域自适应与双重视角服务

### 1. 虚拟领域自适应 (Virtual Domain Sniffing)
接入方在打日志时拥有极高的自由度，**不强制建立固定的物理子目录**：
- **物理目录**：如 `/linlog/network/`、`/linlog/apm/`；
- **单文件/平铺文件**：如 `network_20260908.linlog`、`app_apm.log`；
- **纯 Tag / 前缀逻辑流**：所有日志平铺在同一文件，通过 Tag（`NetworkLayer`、`ApmMonitor`、`WebSocket`）逻辑区分。

分析引擎支持 **【物理目录 $\to$ 文件名模式 $\to$ Tag/内容特征】** 三级弹性嗅探，自动在内存中聚类出网络（`network`）、长连接（`socket`）、性能（`apm`）、用户流水（`track`/`main`）等逻辑领域。

### 2. 双重视角报告体系 (Dual-Layer Diagnostics)
- **第一层（面向测试、运营、客服）**：
  - 纯自然语言与大白话事故定性；
  - 明确判定责任归属（【前端 App 问题】/【后端接口故障】/【用户弱网环境】/【第三方平台微信/华为故障】）；
  - 用户操作时间线还原与业务补单/测试复现指令。
- **第二层（面向研发架构师）**：
  - 跨域时序因果拓扑、慢请求耗时、Jank 丢帧指标、代码漏洞分析与 Kotlin 修复 Diff。

---

## 二、各大主流 AI 工具操作示范与 Prompt 模板

### 1. Google Gemini / Antigravity (原生内置 Agent 模式)

在 Antigravity 或搭载 Gemini 的智能编程环境中，已内置 `.agents/skills/linlog-analyzer/` 与 `.agents/skills/payment-analyzer/`，AI 可直接自主执行分析：

* **操作方式**：测试、运营或研发直接在对话框粘贴日志文件路径并用自然语言提问。
* **输入 Prompt 示范（测试/运营人员）**：
  > “收到线上用户投诉，日志在 `/Users/pengshilin/Downloads/log_20260831.zip`。用户说付款成功了但没拿到钻石，一直提示商品已拥有无法再充值，请帮我分析是谁的责任，我需要给用户补发吗？”
* **Gemini / Antigravity 内部流转**：
  1. 自动触发 `payment-analyzer` 或 `linlog-analyzer` Skill；
  2. 后台无感调用 `python3 tools/analyzer/analyze_payment.py /Users/pengshilin/Downloads/log_20260831.zip`；
  3. 输出包含 **【测试/运营速读卡片（大白话定性 + 责任判定 + 补单建议）】** 与 **【研发深潜修复】** 的完整报告。

---

### 2. Cursor / OpenAI Codex / ChatGPT (IDE 协作模式)

在 Cursor（基于 Claude / GPT-4o / Codex）中进行排查：

* **第一步：在终端一键提取拓扑特征**
  ```bash
  python3 tools/analyzer/analyze_log.py /path/to/user_log.zip > log_summary.md
  ```
* **第二步：在 Cursor Chat / ChatGPT 中使用 `@log_summary.md` 提问**
* **输入 Prompt 模板**：
  > `@log_summary.md` 这是从用户日志中提取的多领域时序因果报告。  
  > 请分别以【测试/运营大白话总结】和【资深 Android 架构师技术修复】双重视角输出分析：  
  > 1. 大白话说明发生了什么问题、是谁的责任（前端/后端/网络/三方）；  
  > 2. 结合 `[network]` 与 `[apm]` 数据，分析主线程卡顿与异常；  
  > 3. 给出针对性的 Kotlin 架构优化与防御代码。

---

### 3. Claude 3.5 Sonnet / Opus (深度因果分析模式)

Claude 在复杂时序因果推导与代码审查上表现极佳，推荐使用 JSON 模式作为上下文：

* **第一步：生成结构化 JSON 特征**
  ```bash
  python3 tools/analyzer/analyze_log.py /path/to/user_log.zip --json > log_metric.json
  ```
* **第二步：输入 System Prompt & 任务指令**
  > 请分析附带的 `log_metric.json`，首先面向非技术运营输出易读的业务总结与责任归属，其次面向研发输出跨域时序因果链与 Kotlin 修复方案。

---

## 三、命令行核心参数一览

```bash
# 1. 通用日志全量分析（自动识别虚拟领域并生成双重视角报告）
python3 tools/analyzer/analyze_log.py /path/to/log_xxx.zip

# 2. 🎯 自然语言问答模式（针对特定非技术或技术问题切片排查）
python3 tools/analyzer/analyze_log.py /path/to/log_xxx.zip -q "socket重连"
python3 tools/analyzer/analyze_log.py /path/to/log_xxx.zip --ask "为什么卡顿闪退"
python3 tools/analyzer/analyze_log.py /path/to/log_xxx.zip -q "pay"

# 3. 💳 支付专项全链路对账与掉单审计
python3 tools/analyzer/analyze_payment.py /path/to/log_xxx.zip

# 4. 输出标准 JSON 格式（供 CI/CD 或自动化流水线消费）
python3 tools/analyzer/analyze_log.py /path/to/log_xxx.zip --json
python3 tools/analyzer/analyze_payment.py /path/to/log_xxx.zip --json
```

---

## 四、标准交付报告样例

```markdown
# 🩺 LinLog 智能日志体检与诊断报告

## 🟢 【测试 / 运营速读卡片】
1. **📢 事故大白话定性**：用户在点击支付后，因手机网络瞬间中断导致请求超时卡在转圈页；但微信扣款实际已成功，前端因超时断开未能接收到发货通知。
2. **🎯 责任归属判定**：【用户弱网环境 + 客户端缺少未决订单补单自愈】（非后端接口挂死，亦非微信通道异常）。
3. **🚶 用户操作链路还原**：[14:20:01] 浏览商品 -> [14:20:05] 点击立即购买 -> [14:20:08] 微信付款成功 -> [14:20:10] 界面提示请求超时。
4. **💡 运营/测试处置建议**：
   - **运营**：核实流水号后可在后台对订单 `ORD20260908xxx` 执行手动补发；
   - **测试**：在支付完成后切断 Wi-Fi 验证客户端冷启自动补单逻辑。

---

## 🔍 【研发级技术深潜与证据链】
1. **[14:20:05.125] [apm]** 主线程遭遇 3 次 Jank 严重丢帧 (FPS=11.2)；
2. **[14:20:05.540] [network]** `POST /api/v2/pay` 遭遇超时（耗时 2850ms）；
3. **缺陷定位与修复代码**：`BillingOrderFlowDelegate.kt` 缺少超时重试兜底状态机，需补充协程超时捕获与本地补单队列保存。
```
