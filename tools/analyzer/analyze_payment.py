#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
支付专属日志深度诊断与多渠道对账分析引擎
支持：
1. 渠道特征智能自动嗅探 (Google Play Billing / 华为 IAP / 第三方聚合支付)；
2. Google Billing 交易状态机全链路时序对齐 (查价 -> 调起 -> 付款 -> Acknowledge -> Consume)；
3. 致命掉单预警：未确认 (3天退款门禁)、未消耗 (ITEM_ALREADY_OWNED 复购阻断)；
4. 历史单据扫描与掉单自愈追踪；
5. 秒级持久化缓存加载。
"""

import os
import sys
import re
import json
import zipfile
import hashlib
import argparse
from datetime import datetime
from collections import defaultdict

# 正则匹配 1: 标准管道符格式 (2026-09-04 15:25:30.450|I|GoogleBilling|[BillingFlow] ...)
PIPE_PATTERN = re.compile(
    r'^(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\|(?P<level>[A-Z]+)\|(?P<tag>[^|]+)\|(?P<msg>.*)$'
)

# 正则匹配 2: 方括号格式
BRACKET_PATTERN = re.compile(
    r'^(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\s+\[(?P<level>[A-Z]+)\]\s*(?:\[Thread:\s*(?P<thread>[^\]]+)\])?\s*\[(?P<tag>[^\]]+)\]:\s*(?P<msg>.*)$'
)

# 提取 JSON 的正则 (如 [BillingFlow] StageName -> {...})
PAYMENT_JSON_PATTERN = re.compile(r'->\s*(\{.*\})\s*$')

CACHE_BASE_DIR = os.path.expanduser("~/.cache/linlog_payment_analyzer")


def get_cache_paths(target_path: str):
    abs_path = os.path.abspath(target_path)
    if not os.path.exists(abs_path):
        return None, None, None
    stat = os.stat(abs_path)
    key_str = f"{abs_path}_{stat.st_size}_{stat.st_mtime}_payment"
    cache_id = hashlib.md5(key_str.encode('utf-8')).hexdigest()
    cache_dir = os.path.join(CACHE_BASE_DIR, cache_id)
    cache_index = os.path.join(cache_dir, "parsed_payment_cache.json")
    cache_unzip_dir = os.path.join(cache_dir, "extracted")
    return cache_dir, cache_index, cache_unzip_dir


class PaymentLogAnalyzer:
    def __init__(self, target_path: str, force_reload: bool = False):
        self.target_path = target_path
        self.force_reload = force_reload
        self.is_zip = zipfile.is_zipfile(target_path) if os.path.isfile(target_path) else False
        
        # 渠道识别集合
        self.detected_channels = set()
        
        # Google Play 相关数据
        self.google_connection_events = []
        self.google_history_events = []
        self.google_orders = defaultdict(lambda: {
            "hex_order_number": "",
            "hex_account_id": "",
            "google_order_id": "",
            "product_id": "",
            "purchase_token": "",
            "stages": [],
            "status": "UNKNOWN",
            "is_acknowledged": None,
            "is_consumed": False,
            "has_error": False,
            "error_msg": "",
            "first_timestamp": "",
            "last_timestamp": ""
        })
        
        # 华为 IAP 相关数据
        self.huawei_pay_events = []
        self.huawei_orders = defaultdict(lambda: {
            "hex_order_number": "",
            "huawei_order_id": "",
            "product_id": "",
            "purchase_token": "",
            "stages": [],
            "status": "UNKNOWN",
            "is_reported": False,
            "is_consumed": False,
            "has_error": False,
            "error_msg": "",
            "risk_warning": "",
            "first_timestamp": "",
            "last_timestamp": ""
        })
        
        # 其他第三方支付相关数据
        self.other_pay_events = []
        
        # 全部支付日志原始事件
        self.all_payment_events = []

    def load_data(self):
        cache_dir, cache_index, cache_unzip_dir = get_cache_paths(self.target_path)

        if not self.force_reload and cache_index and os.path.isfile(cache_index):
            try:
                with open(cache_index, 'r', encoding='utf-8') as f:
                    cached = json.load(f)
                self.detected_channels = set(cached.get("detected_channels", []))
                self.google_connection_events = cached.get("google_connection_events", [])
                self.google_history_events = cached.get("google_history_events", [])
                self.google_orders = defaultdict(dict, cached.get("google_orders", {}))
                self.huawei_pay_events = cached.get("huawei_pay_events", [])
                self.huawei_orders = defaultdict(dict, cached.get("huawei_orders", {}))
                self.other_pay_events = cached.get("other_pay_events", [])
                self.all_payment_events = cached.get("all_payment_events", [])
                sys.stderr.write(f"[Payment Analyzer ⚡] 秒级命中支付专属缓存，共解析 {len(self.all_payment_events)} 条支付事件\n")
                return
            except Exception as e:
                sys.stderr.write(f"[Payment Analyzer ⚠️] 读取缓存失败: {e}\n")

        base_dir = self.target_path
        if self.is_zip:
            os.makedirs(cache_unzip_dir, exist_ok=True)
            sys.stderr.write(f"[Payment Analyzer 📦] 解压日志归档包中...\n")
            with zipfile.ZipFile(self.target_path, 'r') as zf:
                zf.extractall(cache_unzip_dir)
            base_dir = cache_unzip_dir

        self._scan_and_parse(base_dir)
        self._audit_and_diagnose()

        if cache_index and cache_dir:
            try:
                os.makedirs(cache_dir, exist_ok=True)
                payload = {
                    "detected_channels": list(self.detected_channels),
                    "google_connection_events": self.google_connection_events,
                    "google_history_events": self.google_history_events,
                    "google_orders": dict(self.google_orders),
                    "huawei_pay_events": self.huawei_pay_events,
                    "huawei_orders": dict(self.huawei_orders),
                    "other_pay_events": self.other_pay_events,
                    "all_payment_events": self.all_payment_events
                }
                with open(cache_index, 'w', encoding='utf-8') as f:
                    json.dump(payload, f, ensure_ascii=False)
                sys.stderr.write(f"[Payment Analyzer 💾] 已完成持久化缓存索引\n")
            except Exception as e:
                sys.stderr.write(f"[Payment Analyzer ⚠️] 缓存写入失败: {e}\n")

    def _scan_and_parse(self, base_dir: str):
        for root, _, files in os.walk(base_dir):
            for file in sorted(files):
                if file.startswith('.') or not (file.endswith('.linlog') or file.endswith('.log') or file.endswith('.txt')):
                    continue

                full_path = os.path.join(root, file)
                rel_dir = os.path.relpath(root, base_dir)
                domain = "main" if rel_dir == "." else rel_dir.replace(os.sep, "/")

                # 优先解析 payment 领域，或文件名与路径带 payment / billing / charge 的日志
                is_payment_domain = "payment" in domain.lower() or "billing" in file.lower() or "pay" in file.lower()
                self._parse_file(domain, full_path, is_payment_domain)

    def _parse_file(self, domain: str, file_path: str, is_payment_domain: bool):
        try:
            with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                for line_no, raw_line in enumerate(f, 1):
                    line = raw_line.rstrip('\r\n')
                    if not line:
                        continue

                    match = PIPE_PATTERN.match(line)
                    if match:
                        time_str = match.group('time')
                        level = match.group('level').upper()
                        tag = match.group('tag').strip()
                        msg = match.group('msg')
                    else:
                        match2 = BRACKET_PATTERN.match(line)
                        if match2:
                            time_str = match2.group('time')
                            level = match2.group('level').upper()
                            tag = match2.group('tag').strip()
                            msg = match2.group('msg')
                        else:
                            continue

                    # 判定是否属于支付事件
                    is_pay_event = is_payment_domain or any(
                        k in tag.lower() or k in msg.lower()
                        for k in ("billing", "googlebilling", "huaweipay", "iapclient", "paychannel", "payment")
                    )

                    if not is_pay_event:
                        continue

                    event = {
                        "domain": domain,
                        "timestamp": time_str,
                        "level": level,
                        "tag": tag,
                        "msg": msg,
                        "file": os.path.basename(file_path),
                        "line": line_no
                    }
                    self.all_payment_events.append(event)
                    self._dispatch_channel_event(event)

        except Exception as e:
            sys.stderr.write(f"解析支付日志文件出错 {file_path}: {e}\n")

    def _dispatch_channel_event(self, event: dict):
        tag = event["tag"]
        msg = event["msg"]

        # 1. 优先嗅探 华为支付 (Huawei IAP) - 严禁因 billing 关键词被 Google 误判
        if any(k in tag.lower() or k in msg.lower() for k in ("huaweibilling", "huaweipay", "iapclient", "huaweipaymanager", "hawapayimpl", "zui_iap", "iaprequesthelper", "huaweicons", "hms.iap", "huaweibillingflow")):
            self.detected_channels.add("Huawei IAP (华为支付)")
            self._handle_huawei_event(event)
            return

        # 2. 嗅探 Google Play 官方支付
        if "googlebilling" in tag.lower() or "billingflow" in msg.lower() or "billingconnection" in msg.lower() or "billinghistory" in msg.lower():
            self.detected_channels.add("Google Play (谷歌支付)")
            self._handle_google_event(event)
            return

        # 3. 嗅探 其他第三方支付渠道 (WeChat / AliPay / 聚合支付)
        if any(k in tag.lower() or k in msg.lower() for k in ("wechatpay", "alipay", "chargefragment", "apichargeservice", "paychannel")):
            self.detected_channels.add("Third-party Pay (第三方/其他支付)")
            self.other_pay_events.append(event)

    def _handle_google_event(self, event: dict):
        msg = event["msg"]
        ts = event["timestamp"]

        # 区分连接状态
        if "[BillingConnection]" in msg:
            self.google_connection_events.append(event)
            return

        # 区分历史掉单扫描
        if "[BillingHistory]" in msg:
            self.google_history_events.append(event)
            return

        # 核心交易流转 [BillingFlow]
        json_match = PAYMENT_JSON_PATTERN.search(msg)
        if json_match:
            try:
                payload = json.loads(json_match.group(1))
                stage = payload.get("stage", "UNKNOWN")
                order_num = payload.get("hex_order_number") or payload.get("business_order_id")
                acc_id = payload.get("hex_account_id") or payload.get("profile_id")
                google_oid = payload.get("google_order_id")
                prod_id = payload.get("product_id")
                token = payload.get("purchase_token")
                resp_code = payload.get("response_code")
                is_ack = payload.get("is_acknowledged")

                # 如果无法提取自研单号，尝试以 google_order_id 或 token 聚合
                session_key = order_num or google_oid or (f"token_{token[-10:]}" if token else f"anon_{ts}")

                order = self.google_orders[session_key]
                if order_num:
                    order["hex_order_number"] = order_num
                if acc_id:
                    order["hex_account_id"] = acc_id
                if google_oid:
                    order["google_order_id"] = google_oid
                if prod_id:
                    order["product_id"] = prod_id
                if token:
                    order["purchase_token"] = token
                if is_ack is not None:
                    order["is_acknowledged"] = is_ack

                if not order["first_timestamp"]:
                    order["first_timestamp"] = ts
                order["last_timestamp"] = ts

                order["stages"].append({
                    "stage": stage,
                    "timestamp": ts,
                    "response_code": resp_code,
                    "extra_msg": payload.get("extra_msg"),
                    "raw_json": payload.get("raw_json")
                })

                # 更新单据聚合状态
                if "SUCCESS" in stage or "Result" in stage or "Success" in stage:
                    if stage == "PurchasesUpdated_SUCCESS":
                        order["status"] = "PAID"
                    elif stage == "AcknowledgePurchase_Success" or stage == "AcknowledgeHistory_Success":
                        order["is_acknowledged"] = True
                        if order["status"] != "CONSUMED":
                            order["status"] = "ACKNOWLEDGED"
                    elif "ConsumePurchase" in stage and resp_code == 0:
                        order["is_consumed"] = True
                        order["status"] = "CONSUMED"
                elif "Fail" in stage or "FAIL" in stage:
                    order["has_error"] = True
                    order["error_msg"] = f"Failed at {stage} (code={resp_code})"

            except Exception as e:
                sys.stderr.write(f"解析 Google JSON 失败: {e}\n")

    def _handle_huawei_event(self, event: dict):
        msg = event["msg"]
        ts = event["timestamp"]
        self.huawei_pay_events.append(event)

        # 1. 尝试结构化 JSON 提取 [HuaweiBillingFlow]
        json_match = PAYMENT_JSON_PATTERN.search(msg)
        if json_match:
            try:
                payload = json.loads(json_match.group(1))
                stage = payload.get("stage", "UNKNOWN")
                order_num = payload.get("hex_order_number") or payload.get("orderNumber")
                hw_oid = payload.get("huawei_order_id") or payload.get("huaweiOrderId")
                prod_id = payload.get("product_id") or payload.get("productId")
                token = payload.get("purchase_token") or payload.get("purchaseToken")
                resp_code = payload.get("response_code") or payload.get("responseCode")
                extra_msg = payload.get("extra_msg") or payload.get("extraMsg")

                oid = order_num or hw_oid or f"hw_{ts}"
                hw_order = self.huawei_orders[oid]
                if order_num:
                    hw_order["hex_order_number"] = order_num
                if hw_oid:
                    hw_order["huawei_order_id"] = hw_oid
                if prod_id:
                    hw_order["product_id"] = prod_id
                if token:
                    hw_order["purchase_token"] = token
                if not hw_order["first_timestamp"]:
                    hw_order["first_timestamp"] = ts
                hw_order["last_timestamp"] = ts

                hw_order["stages"].append({
                    "stage": stage,
                    "timestamp": ts,
                    "response_code": resp_code,
                    "extra_msg": extra_msg,
                    "raw_json": payload.get("raw_json") or payload.get("rawJson")
                })

                # 流转状态判定
                if stage == "PurchasesResult_SUCCESS":
                    hw_order["status"] = "PAID"
                elif stage in ("ReportSign_Success", "Delivery_Success"):
                    hw_order["is_reported"] = True
                    hw_order["status"] = "DELIVERED"
                elif "Consume" in stage or stage in ("Replenishment_Consume", "Consume_Success"):
                    hw_order["is_consumed"] = True
                    hw_order["status"] = "CONSUMED"
                elif stage in ("PurchasesResult_CANCELED", "LaunchBillingFlow_Canceled"):
                    hw_order["status"] = "CANCELED"
                elif "Fail" in stage or "FAIL" in stage or stage == "PurchasesResult_FAIL":
                    hw_order["has_error"] = True
                    hw_order["error_msg"] = f"Failed at {stage} (code={resp_code})"
                    if hw_order["status"] not in ("DELIVERED", "CONSUMED"):
                        hw_order["status"] = "FAIL"
                elif stage == "PurchasesResult_PRODUCT_OWNED" or resp_code == 60051:
                    hw_order["has_error"] = True
                    hw_order["error_msg"] = "商品被占用 (ORDER_PRODUCT_OWNED 60051)"
                return
            except Exception:
                pass

        # 2. 文本正则兼容
        order_match = re.search(r'orderId[=:\s]+([A-Za-z0-9_\-]+)', msg, re.IGNORECASE)
        if order_match:
            oid = order_match.group(1)
            hw_order = self.huawei_orders[oid]
            hw_order["hex_order_number"] = oid
            if not hw_order["first_timestamp"]:
                hw_order["first_timestamp"] = ts
            hw_order["last_timestamp"] = ts
            hw_order["stages"].append({"stage": msg, "msg": msg, "timestamp": ts})
            if "success" in msg.lower():
                hw_order["status"] = "SUCCESS"
            elif "fail" in msg.lower():
                hw_order["status"] = "FAIL"

    def _audit_and_diagnose(self):
        """对全量订单进行根因定性与风险审计"""
        for k, order in self.google_orders.items():
            # 1. 致命掉单风险：已付款但未确认 (Acknowledge missing)
            if order["status"] == "PAID" and not order["is_acknowledged"]:
                order["risk_warning"] = "⚠️【严重掉单预警】用户已成功扣款，但未完成 Acknowledge 确认！将在 3 天后被 Google 自动全额退款！"

            # 2. 复购阻断风险：已确认但未消耗 (Consume missing)
            elif order["is_acknowledged"] and not order["is_consumed"]:
                order["risk_warning"] = "⚠️【复购阻断】商品尚未执行 Consume 消耗！用户再次购买将提示 ITEM_ALREADY_OWNED (错误码 7)。"

            # 3. 完美完成
            elif order["is_consumed"] and order["is_acknowledged"]:
                order["risk_warning"] = "✅【交易完结】已完成 确认(Acknowledge) 与 消耗(Consume)，全流程安全发货。"

        # 华为支付风险审计
        for k, order in self.huawei_orders.items():
            if order["status"] == "PAID" and not order["is_reported"]:
                order["risk_warning"] = "⚠️【掉单预警】用户在收银台已完成付款，但自研服务端尚未发货验签成功！"
            elif order["is_reported"] and not order["is_consumed"]:
                order["risk_warning"] = "⚠️【复购阻断风险】已发货但尚未成功消耗华为商品 Token！用户再次购买可能触发 60051 (ORDER_PRODUCT_OWNED)。"
            elif order["is_consumed"] and order["is_reported"]:
                order["risk_warning"] = "✅【交易完结】已完成发货验签与商品 Token 消耗，全流程成功对账。"
            elif order["status"] == "CANCELED":
                order["risk_warning"] = "ℹ️【用户取消】用户在华为官方收银台主动放弃付款。"
            elif "60051" in str(order.get("error_msg", "")):
                order["risk_warning"] = "🔄【商品被占用自愈】命中 60051，需检查是否触发 replenishmentConsumerPurchase 自动解锁。"

    def generate_report(self) -> str:
        md = []
        md.append("# 💳 LinLog 支付专属深度诊断与全链路对账报告\n")

        # 1. 渠道嗅探概览
        channels_str = "、".join(self.detected_channels) if self.detected_channels else "未明确识别到已知支付渠道"
        md.append("## 一、支付渠道嗅探概览")
        md.append(f"- **激活支付渠道** : `{channels_str}`")
        md.append(f"- **支付日志总数** : `{len(self.all_payment_events)}` 条记录\n")

        # 2. Google Play 支付专项深度诊断
        if "Google Play (谷歌支付)" in self.detected_channels or self.google_orders:
            md.append("## 二、Google Play 官方支付对账大盘")
            md.append(f"- **Google 订单总数** : `{len(self.google_orders)}` 笔")
            md.append(f"- **底层连接事件数** : `{len(self.google_connection_events)}` 次")
            md.append(f"- **历史掉单扫描数** : `{len(self.google_history_events)}` 次\n")

            # 2.1 订单明细大表
            if self.google_orders:
                md.append("### 📋 订单全链路生命周期对账表")
                md.append("| 自研订单号 (hex) | 商品规格 | Google流水号 | 最终状态 | 确认(Ack) | 消耗(Consume) | 诊断判定 |")
                md.append("| :--- | :--- | :--- | :---: | :---: | :---: | :--- |")
                for k, order in self.google_orders.items():
                    b_id = order['hex_order_number'] or k
                    p_id = order['hex_account_id'] or order['product_id'] or "-"
                    g_id = order['google_order_id'] or "-"
                    status = order['status']
                    ack_str = "✅ 是" if order['is_acknowledged'] else "❌ 否"
                    cons_str = "✅ 是" if order['is_consumed'] else "❌ 否"
                    diag = order.get('risk_warning', '正常流转')
                    md.append(f"| `{b_id}` | `{p_id}` | `{g_id}` | **{status}** | {ack_str} | {cons_str} | {diag} |")
                md.append("")

                # 2.2 订单详细时序展开
                md.append("### 🔍 订单时序推进详细证据链")
                for k, order in self.google_orders.items():
                    b_id = order['hex_order_number'] or k
                    md.append(f"#### 订单: `{b_id}` (商品: `{order['hex_account_id'] or order['product_id']}`)")
                    if order.get("risk_warning"):
                        md.append(f"> **诊断结论**: {order['risk_warning']}")
                    md.append("- **流转时间轴 (Timeline)**:")
                    for s in order["stages"]:
                        code_str = f" [code={s['response_code']}]" if s['response_code'] is not None else ""
                        extra = f" | {s['extra_msg']}" if s.get('extra_msg') else ""
                        md.append(f"  - `[{s['timestamp']}]` **{s['stage']}**{code_str}{extra}")
                    md.append("")

            # 2.3 底层连接健康度
            if self.google_connection_events:
                md.append("### 🔌 Google Play 底层 IPC 连接事件 (TOP 5)")
                for ev in self.google_connection_events[:5]:
                    md.append(f"- `[{ev['timestamp']}]` `{ev['level']}` {ev['msg']}")
                md.append("")

            # 2.4 历史单据扫描与掉单补偿
            if self.google_history_events:
                md.append("### 🔄 历史单据扫描与掉单自愈 (TOP 5)")
                for ev in self.google_history_events[:5]:
                    md.append(f"- `[{ev['timestamp']}]` `{ev['level']}` {ev['msg']}")
                md.append("")

        # 3. 华为 IAP 专项
        if "Huawei IAP (华为支付)" in self.detected_channels or self.huawei_orders:
            md.append("## 三、Huawei IAP 华为支付对账大盘")
            md.append(f"- **华为支付订单数**: `{len(self.huawei_orders)}` 笔")
            md.append(f"- **华为支付相关事件**: `{len(self.huawei_pay_events)}` 条\n")
            if self.huawei_orders:
                md.append("### 📋 华为订单全链路生命周期对账表")
                md.append("| 自研订单号 (hex) | 商品规格 | 华为流水号 | 最终状态 | 服务端发货 | 商品消耗 | 诊断判定 |")
                md.append("| :--- | :--- | :--- | :---: | :---: | :---: | :--- |")
                for oid, ho in self.huawei_orders.items():
                    b_id = ho.get('hex_order_number') or oid
                    p_id = ho.get('product_id') or "-"
                    hw_id = ho.get('huawei_order_id') or "-"
                    status = ho.get('status', 'UNKNOWN')
                    rep_str = "✅ 是" if ho.get('is_reported') else "❌ 否"
                    cons_str = "✅ 是" if ho.get('is_consumed') else "❌ 否"
                    diag = ho.get('risk_warning') or ho.get('error_msg') or '正常流转'
                    md.append(f"| `{b_id}` | `{p_id}` | `{hw_id}` | **{status}** | {rep_str} | {cons_str} | {diag} |")
                md.append("")

                md.append("### 🔍 华为订单时序推进详细证据链")
                for oid, ho in self.huawei_orders.items():
                    b_id = ho.get('hex_order_number') or oid
                    md.append(f"#### 订单: `{b_id}` (商品: `{ho.get('product_id', '-')}`)")
                    if ho.get("risk_warning"):
                        md.append(f"> **诊断结论**: {ho['risk_warning']}")
                    md.append("- **流转时间轴 (Timeline)**:")
                    for s in ho["stages"]:
                        code_str = f" [code={s['response_code']}]" if s.get('response_code') is not None else ""
                        extra = f" | {s['extra_msg']}" if s.get('extra_msg') else ""
                        md.append(f"  - `[{s['timestamp']}]` **{s.get('stage', 'EVENT')}**{code_str}{extra}")
                    md.append("")

        # 4. 其他支付渠道
        if self.other_pay_events:
            md.append("## 四、其他三方/聚合支付事件 (TOP 10)")
            for ev in self.other_pay_events[:10]:
                md.append(f"- `[{ev['timestamp']}]` `[{ev['tag']}]`: {ev['msg']}")
            md.append("")

        return "\n".join(md)


def main():
    parser = argparse.ArgumentParser(description="LinLog 支付专属深度诊断引擎")
    parser.add_argument("path", help="待分析的 log.zip 或日志目录")
    parser.add_argument("--force-reload", action="store_true", help="强制丢弃持久化缓存重新解析")
    parser.add_argument("--json", action="store_true", help="以 JSON 格式输出")
    args = parser.parse_args()

    analyzer = PaymentLogAnalyzer(args.path, force_reload=args.force_reload)
    analyzer.load_data()

    if args.json:
        result = {
            "detected_channels": list(analyzer.detected_channels),
            "google_orders": dict(analyzer.google_orders),
            "huawei_orders": dict(analyzer.huawei_orders),
            "total_payment_events": len(analyzer.all_payment_events)
        }
        print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        print(analyzer.generate_report())


if __name__ == "__main__":
    main()
