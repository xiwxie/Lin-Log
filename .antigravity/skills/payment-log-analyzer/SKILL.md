---
name: payment-log-analyzer
description: 专注诊断与对账 Android 跨渠道支付日志 (Google Play Billing、华为 IAP、第三方/Web 聚合支付)。当用户提供支付日志包、反馈“掉单”、“扣款未到账”、“Google确认超时(Acknowledge)”、“未消耗商品(Consume)”、“ITEM_ALREADY_OWNED”、“支付失败”或要求排查充值链路时自动激活。
---

# Android 跨渠道支付全链路对账与根因诊断 Skill (AI-Native 规范)

## 1. 触发时机 (Trigger Scenarios)
当用户在对话中满足以下任一条件时，Agent 应自主激活此 Skill（**无需用户要求输入命令**）：
- 用户提供了一个包含 `payment/` 领域或支付日志的 `.zip` 文件路径或解压目录；
- 用户反馈支付业务故障：“为什么用户扣了款没到账”、“这笔订单怎么又掉单了”、“为什么提示 ITEM_ALREADY_OWNED 无法充值”、“查一下订单号 ORD2026xxxx”；
- 用户要求排查 Google Play 原生支付收银台、华为 HMS IAP、或者三方支付（微信/支付宝/聚合支付）链路。

## 2. 自动化分析工作流 (Workflow)

### 步骤一：自主执行支付专属对账引擎 (Zero-User-Friction)
Agent 应通过后台命令调用工程内置的支付诊断引擎，**自动利用持久化缓存，无需重复解压**：

```bash
python3 core/linlog/analyzer/analyze_payment.py <path_to_zip_or_dir>
```

若需获取纯结构化 JSON 进行深入推导：
```bash
python3 core/linlog/analyzer/analyze_payment.py <path_to_zip_or_dir> --json
```

### 步骤二：多渠道状态机深度推导与对账审计 (Cross-Channel Audit)
读取分析引擎生成的各渠道对账大盘，重点执行以下规则审计：

#### A. Google Play 官方渠道审计 (Google Billing)
1. **核对 5 步状态机闭环**：
   - `QuerySkuDetails_Start`：查价规格与客户端 Ready 状态；
   - `LaunchBillingFlow_Result`：收银台是否正常调起 (Launch code = 0)；
   - `PurchasesUpdated_SUCCESS`：收银台付款是否成功，获取 `google_order_id` 与 `purchase_token`；
   - `AcknowledgePurchase_Success`：**【致命退款门禁】** 商家是否已向 Google 确认发货。若未确认，**3 天后将被 Google 自动全额退款给用户！**
   - `ConsumePurchase_Normal_Result`：**【复购阻断门禁】** 是否完成消耗。若未消耗，用户再次购买同规格商品将直接阻断在 `ITEM_ALREADY_OWNED` (code=7)。
2. **底层 Binder IPC 连接与掉单自愈审计**：
   - 检查 `[BillingConnection]` 是否有反复断开 (`Disconnected`) 或无法建立连接；
   - 检查 `[BillingHistory]` 是否有冷启掉单补扫日志 (`Found unconsumed purchases count: X`)。

#### B. 华为 IAP 渠道审计 (Huawei HMS IAP)
1. **核对 5 步状态机闭环**：
   - `LaunchBillingFlow_Start`：自研订单号 `developerPayload` 透传与协程挂起登记；
   - `LaunchBillingFlow_IntentReady`：HMS 支付意图创建成功并调起官方收银台；
   - `PurchasesResult_SUCCESS`：官方收银台付款成功，获取华为流水号与 `purchaseToken`；
   - `ReportSign_Success`：**【自研发货门禁】** 向自研服务端验签发货 (`reportHuaweiPayInfo`) 是否成功；
   - `Consume_Success` / `Replenishment_Consume`：**【复购阻断门禁】** 执行 `consumeOwnedPurchase` 释放 PMS 商品所有权。若未消耗，再次购买该档位将被华为直接阻断在 `ORDER_PRODUCT_OWNED` (code=60051)。
2. **掉单补偿与解卡自愈审计**：
   - **商品占用自愈**：若日志出现 `ORDER_PRODUCT_OWNED (60051)`，核查是否触发 `recoverOwnedProduct` 联动 `replenishmentConsumerPurchase` 强制消耗解锁并自动重新调起收银台；
   - **历史补单扫描**：检查 `[HuaweiHistory]` 是否扫描出 `unconsumed purchases` 并补报发货。

#### C. 其他三方 / Web 聚合支付渠道审计
- 检查客户端发起下单参数 `payChannel`、服务端预下单结果、拉起三方 App Scheme 是否成功。

### 步骤三：代码库联动与源码深潜 (Codebase Cross-Reference)
根据日志中提取到的订单号、渠道标识与异常阶段，联动源码：
- Google 渠道：定位 [BillingClientLifecycle.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/main/java/com/hawatalk/live/billing/BillingClientLifecycle.kt)、[BillingSettlementDelegate.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/main/java/com/hawatalk/live/billing/delegate/BillingSettlementDelegate.kt)、[BillingOrderFlowDelegate.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/main/java/com/hawatalk/live/billing/delegate/BillingOrderFlowDelegate.kt)；
- 华为渠道：定位 [HuaWeiPayManager.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/huawei/java/com/pay/huawei/HuaWeiPayManager.kt)、[HuaweiPayCoordinator.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/huawei/java/com/pay/huawei/HuaweiPayCoordinator.kt)、[HuaweiOrderFlowDelegate.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/huawei/java/com/pay/huawei/delegate/HuaweiOrderFlowDelegate.kt)、[HuaweiSettlementDelegate.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/huawei/java/com/pay/huawei/delegate/HuaweiSettlementDelegate.kt)、[HuaweiHistoryDelegate.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/huawei/java/com/pay/huawei/delegate/HuaweiHistoryDelegate.kt)、[HuaweiPayReporter.kt](file:///Users/pengshilin/StudioProjects/dollar_android2/erban_client/src/huawei/java/com/pay/huawei/HuaweiPayReporter.kt)。

### 步骤四：输出《LinLog 支付全链路对账与诊断报告》
严格遵循以下标准结构输出结论：
1. **💳 核心对账结论**：明确指出本次排查发现的订单数量、成功数量、掉单/异常数量及最终定性；
2. **📋 订单生命周期对账表**：包含自研订单号、商品、渠道流水号、最终状态、Acknowledge 状态、Consume 状态；
3. **🚨 致命风险预警 (如有)**：
   - 是否存在未 Acknowledge 的即将退款单据；
   - 是否存在未 Consume 阻断复购的单据；
4. **🛠️ 修复与处理建议**：对于掉单提供手动补单 API 调用指引或客户端重试建议。
