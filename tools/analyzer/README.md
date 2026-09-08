# LinLog 智能日志分析引擎与主流 AI 协同诊断实战指南

`core/linlog/analyzer/` 是随 `LinLog` 基础设施一体化发布的智能诊断与时序因果推导工具。配合现代主流 LLM（Google Gemini、Cursor/Codex、Claude、DeepSeek），实现从 **“原始 Zip 日志” 到 “根因定位与修复建议”** 的秒级自动化闭环。

---

## 一、为什么主流 AI 需要搭配 LinLog Analyzer？

传统方式直接将动辄几万行的原始 `.log` 文本全部丢给大模型，存在两大致命缺陷：
1. **Token 爆炸与上下文截断**：海量常规日志冲爆上下文窗口，且单次提问 Token 成本极高；
2. **缺乏多维时序对齐**：大模型在乱序文本中很难精准计算同一毫秒内 `network/` 与 `apm/` 的时序因果。

`LinLog Analyzer` 底层执行 **“预降噪 + 领域分流提取 + 跨域时序因果对齐”**，将数万行日志压缩为高信息密度的时序拓扑矩阵（Markdown / JSON），再交由 AI 深度推导，实现 **100% 命中根因、0 幻觉、极低 Token 消耗**。

---

## 二、各大主流 AI 工具操作示范与 Prompt 模板

### 1. Google Gemini / Antigravity (原生内置 Agent 模式)

在 Antigravity 或搭载 Gemini 的智能编程环境中，已内置 `.antigravity/skills/linlog-analyzer/`，AI 可直接自主执行分析：

* **操作方式**：直接在对话框粘贴日志文件路径并描述业务现象。
* **输入 Prompt 示范**：
  > “收到线上用户反馈，日志在 `/Users/pengshilin/Downloads/log_20260831.zip`。用户反馈在支付确认页非常卡顿，点击后数秒无反应，请帮我分析卡顿根因并排查该时段网络质量。”
* **Gemini 内部流转**：
  1. 自动触发 `linlog-analyzer` Skill；
  2. 调用 `python3 core/linlog/analyzer/analyze_log.py /Users/pengshilin/Downloads/log_20260831.zip`；
  3. 读取输出的 Markdown 时序拓扑，输出包含 **【结论 $\to$ 证据链 $\to$ 源码级修复】** 的体检报告。

---

### 2. Cursor / OpenAI Codex / ChatGPT (IDE 协作模式)

在 Cursor（基于 Claude / GPT-4o / Codex）中进行排查：

* **第一步：在终端一键提取拓扑特征**
  ```bash
  python3 core/linlog/analyzer/analyze_log.py /path/to/user_log.zip > log_summary.md
  ```
* **第二步：在 Cursor Chat / ChatGPT 中使用 `@log_summary.md` 提问**
* **输入 Prompt 模板**：
  > `@log_summary.md` 这是从用户崩溃/卡顿日志中提取的多领域时序因果报告。  
  > 用户反馈现象：**支付流程耗时极长且频繁掉单**。  
  > 请扮演资深 Android 架构师，完成以下诊断：  
  > 1. 结合 `[network]` 慢请求与 `[apm]` 丢帧数据，定性卡顿的根本诱因是 UI 主线程耗时、网络延迟还是系统 GC 抖动？  
  > 2. 给出具体的时间点证据链；  
  > 3. 给出针对性的 Kotlin 架构优化与防御代码。

---

### 3. Claude 3.5 Sonnet / Opus (深度因果分析模式)

Claude 在复杂时序因果推导与代码审查上表现极佳，推荐使用 JSON 模式作为上下文：

* **第一步：生成结构化 JSON 特征**
  ```bash
  python3 core/linlog/analyzer/analyze_log.py /path/to/user_log.zip --json > log_metric.json
  ```
* **第二步：输入 System Prompt & 任务指令**
* **System Prompt 设定**：
  > 你是移动端稳定性与性能调优专家。输入内容是由 LinLog 生成的移动端多领域日志结构化数据（含 apm 性能采样、network 网络请求、track 用户轨迹）。
* **用户提问 Prompt**：
  > 请分析附带的 `log_metric.json`：  
  > 1. 提取所有耗时大于 800ms 的网络请求及其触发时的并发用户行为；  
  > 2. 分析每次发生严重 Jank 丢帧前夕，主线程是否有耗时任务或异常堆栈；  
  > 3. 总结输出《线上质量诊断报告》。

---

### 4. DeepSeek (R1 / V3) / Qwen (通义千问) (开源模型推理模式)

使用 DeepSeek-R1 强大的逻辑推理链（Chain of Thought）进行深层次因果排查：

* **推荐提问 Prompt 模板**：
  ```markdown
  【任务背景】
  我正在排查 Android 客户端的一个复杂卡顿兼接口失败问题。以下是分析脚本提取的结构化日志时序片断：
  
  ```
  <将 analyze_log.py 生成的 Markdown 直接粘贴在此处>
  ```
  
  【推导要求】
  请运用深度推理链（CoT），逐步拆解：
  Step 1. 时间轴复盘：按时序对齐用户操作、网络 I/O、主线程卡顿这三条平行线；
  Step 2. 因果隔离：严格区分“导致卡顿的原因”与“卡顿引发的并发副反应”；
  Step 3. 修复方案：输出修改前后的 Kotlin 代码 Diff。
  ```

---

## 三、命令行核心参数一览

```bash
# 1. 基础用法：分析 Zip 压缩包（自动全量解析四大领域并生成 Markdown 报告）
python3 core/linlog/analyzer/analyze_log.py /path/to/log_xxx.zip

# 2. 🎯 自然语言问答模式（针对具体问题直接提取前后时序上下文）
python3 core/linlog/analyzer/analyze_log.py /path/to/log_xxx.zip -q "socket重连"
python3 core/linlog/analyzer/analyze_log.py /path/to/log_xxx.zip --ask "为什么闪退崩溃"
python3 core/linlog/analyzer/analyze_log.py /path/to/log_xxx.zip -q "loginFailConnection"

# 3. 输出标准 JSON 格式（供流水线自动化消费）
python3 core/linlog/analyzer/analyze_log.py /path/to/log_xxx.zip --json

# 4. 一键分析并直接复制到系统剪贴板（Mac 专属极客习惯，可直接 Cmd+V 发给任意 AI）
python3 core/linlog/analyzer/analyze_log.py /path/to/log_xxx.zip -q "断开" | pbcopy
```

---

## 四、AI 诊断报告标准交付范例

```markdown
# 🩺 LinLog 智能日志体检与诊断报告

## 一、体检综合结论
🚨 **主线程大 JSON 反序列化阻塞 + 支付接口高延迟复合引发卡顿**

## 二、时序因果证据链
1. **[14:20:05.100] [track]** 用户在 `LiveRoomActivity` 点击 [立即支付] (`btn_pay`)；
2. **[14:20:05.120] [main]** 主线程发起 `OrderDetail.toJson()` 耗时 420ms ❌（严重违规阻塞主线程）；
3. **[14:20:05.125] [apm]** FPS 骤降至 11.2，触发 3 次严重 Jank 丢帧 🔴；
4. **[14:20:05.540] [network]** 发起 `POST /api/v2/pay`，遭遇弱网耗时 2850ms 收到响应。

## 三、网络慢请求时间轴清单
- **[14:20:05]** `POST /api/v2/pay` ── 耗时 **2850ms** 🔴
- **[14:20:15]** `GET  /api/v1/conf` ── 耗时 **1200ms** 🔴

```
