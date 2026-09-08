#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
LinLog 智能日志分析与时序因果推导引擎
支持全自动解压 zip、多领域时序对齐、慢请求聚类、APM 丢帧定位与跨域因果推导。
"""

import os
import sys
import re
import json
import zipfile
import hashlib
import shutil
import argparse
from datetime import datetime
from collections import defaultdict

# 正则匹配 1: 标准管道符格式 (真实生产格式: 2026-09-03 11:57:53.788|I|Tag|Message)
PIPE_PATTERN = re.compile(
    r'^(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\|(?P<level>[A-Z]+)\|(?P<tag>[^|]+)\|(?P<msg>.*)$'
)

# 正则匹配 2: 方括号格式 (2026-09-03 11:57:53.788 [INFO] [Thread: main] [Tag]: Message)
BRACKET_PATTERN = re.compile(
    r'^(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\s+\[(?P<level>[A-Z]+)\]\s*(?:\[Thread:\s*(?P<thread>[^\]]+)\])?\s*\[(?P<tag>[^\]]+)\]:\s*(?P<msg>.*)$'
)

# 常见指标提取正则
LATENCY_PATTERN = re.compile(r'(?:cost|latency|time|耗时)[:=\s]*(\d+(?:\.\d+)?)\s*(?:ms|毫秒)', re.IGNORECASE)
FPS_PATTERN = re.compile(r'FPS[:=\s]*(\d+(?:\.\d+)?)', re.IGNORECASE)
SYSTEM_MEM_PATTERN = re.compile(r'totalMemory=(\d+),\s*usedMemory=(\d+),\s*availableMemory=(\d+)', re.IGNORECASE)
JVM_MEM_PATTERN = re.compile(r'totalJvmMemoryMB=(\d+),\s*freeMemory=(\d+),\s*usedMemory=(\d+)', re.IGNORECASE)
SOCKET_STATE_PATTERN = re.compile(r'WebSocketState:\s*([A-Za-z0-9_]+(?:\([^\)]*\))?)', re.IGNORECASE)

# 缓存基目录
CACHE_BASE_DIR = os.path.expanduser("~/.cache/linlog_analyzer")


def get_target_cache_paths(target_path: str):
    """根据文件路径、体积与修改时间生成唯一持久化缓存 Key"""
    abs_path = os.path.abspath(target_path)
    if not os.path.exists(abs_path):
        return None, None, None
    stat = os.stat(abs_path)
    key_str = f"{abs_path}_{stat.st_size}_{stat.st_mtime}"
    cache_id = hashlib.md5(key_str.encode('utf-8')).hexdigest()
    cache_dir = os.path.join(CACHE_BASE_DIR, cache_id)
    cache_index = os.path.join(cache_dir, "parsed_cache.json")
    cache_unzip_dir = os.path.join(cache_dir, "extracted")
    return cache_dir, cache_index, cache_unzip_dir


class LinLogAnalyzer:
    def __init__(self, target_path: str, query: str = None, force_reload: bool = False):
        self.target_path = target_path
        self.query = query.strip() if query else None
        self.force_reload = force_reload
        self.is_zip = zipfile.is_zipfile(target_path) if os.path.isfile(target_path) else False
        self.timeline_events = []
        self.domain_summary = defaultdict(lambda: {"count": 0, "errors": 0, "warnings": 0})
        self.slow_network_events = []
        self.socket_state_events = []
        self.apm_jank_events = []
        self.apm_memory_events = []
        self.exceptions = []
        self.query_matched_events = []

    def load_data(self):
        """智能加载：若存在本地持久化缓存则 0 秒秒级恢复，彻底消除重复解压与正则开销"""
        cache_dir, cache_index, cache_unzip_dir = get_target_cache_paths(self.target_path)

        # 1. 尝试从缓存直接恢复
        if not self.force_reload and cache_index and os.path.isfile(cache_index):
            try:
                with open(cache_index, 'r', encoding='utf-8') as f:
                    cached_data = json.load(f)
                self.timeline_events = cached_data.get("timeline_events", [])
                self.domain_summary = defaultdict(lambda: {"count": 0, "errors": 0, "warnings": 0}, cached_data.get("domain_summary", {}))
                self.slow_network_events = cached_data.get("slow_network_events", [])
                self.socket_state_events = cached_data.get("socket_state_events", [])
                self.apm_jank_events = cached_data.get("apm_jank_events", [])
                self.apm_memory_events = cached_data.get("apm_memory_events", [])
                self.exceptions = cached_data.get("exceptions", [])
                sys.stderr.write(f"[LinLog Cache ⚡] 秒级命中持久化索引，跳过 Zip 解压与正则扫描 (共 {len(self.timeline_events)} 条)\n")
                return
            except Exception as e:
                sys.stderr.write(f"[LinLog Cache ⚠️] 缓存读取失败，将重新全量解析: {e}\n")

        # 2. 缓存未命中或强制重载：执行解压并全量扫描
        base_dir = self.target_path
        if self.is_zip:
            if cache_unzip_dir:
                os.makedirs(cache_unzip_dir, exist_ok=True)
                sys.stderr.write(f"[LinLog Unzip 📦] 正在解压日志归档包至缓存池...\n")
                with zipfile.ZipFile(self.target_path, 'r') as zf:
                    zf.extractall(cache_unzip_dir)
                base_dir = cache_unzip_dir
            else:
                base_dir = tempfile.mkdtemp(prefix="linlog_analysis_")
                with zipfile.ZipFile(self.target_path, 'r') as zf:
                    zf.extractall(base_dir)

        self._scan_and_parse(base_dir)
        self._correlate_causes()

        # 3. 异步持久化到磁盘缓存，以便后续连续提问秒出结果
        if cache_index and cache_dir:
            try:
                os.makedirs(cache_dir, exist_ok=True)
                cache_payload = {
                    "timeline_events": self.timeline_events,
                    "domain_summary": dict(self.domain_summary),
                    "slow_network_events": self.slow_network_events,
                    "socket_state_events": self.socket_state_events,
                    "apm_jank_events": self.apm_jank_events,
                    "apm_memory_events": self.apm_memory_events,
                    "exceptions": self.exceptions
                }
                with open(cache_index, 'w', encoding='utf-8') as f:
                    json.dump(cache_payload, f, ensure_ascii=False)
                sys.stderr.write(f"[LinLog Cache 💾] 已建立本地持久化索引缓存，后续提问将直接 0 秒秒查！\n")
            except Exception as e:
                sys.stderr.write(f"[LinLog Cache ⚠️] 持久化缓存写入失败: {e}\n")

    def run_analysis(self) -> dict:
        if not self.timeline_events:
            self.load_data()
        if self.query:
            self._search_query(self.query)
        return self._build_report()

    def _scan_and_parse(self, base_dir: str):
        for root, _, files in os.walk(base_dir):
            for file in sorted(files):
                if file.startswith('.') or not (file.endswith('.linlog') or file.endswith('.log') or file.endswith('.txt')):
                    continue

                full_path = os.path.join(root, file)
                rel_dir = os.path.relpath(root, base_dir)
                domain = "main" if rel_dir == "." else rel_dir.replace(os.sep, "/")

                self._parse_file(domain, full_path)

        # 按时间戳字符串排序全局事件轴
        self.timeline_events.sort(key=lambda x: x.get("timestamp_str", ""))

    def _parse_file(self, domain: str, file_path: str):
        try:
            with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                last_event = None
                for line_no, raw_line in enumerate(f, 1):
                    line = raw_line.rstrip('\r\n')
                    if not line:
                        continue

                    # 优先匹配管道符格式，其次方括号格式
                    match = PIPE_PATTERN.match(line)
                    thread = ""
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
                            thread = match2.group('thread') or ""
                            tag = match2.group('tag').strip()
                            msg = match2.group('msg')
                        else:
                            # 属于多行异常堆栈 (StackTrace) 或换行内容，自动缝合到上一条事件
                            if last_event:
                                last_event["msg"] += "\n" + line
                                if "Exception" in line or "Error" in line or "at " in line:
                                    last_event["has_stacktrace"] = True
                            continue

                    level_map = {"I": "INFO", "D": "DEBUG", "W": "WARN", "E": "ERROR", "V": "VERBOSE"}
                    level_norm = level_map.get(level, level)

                    event_domain = self._resolve_virtual_domain(domain, os.path.basename(file_path), tag, msg)

                    event = {
                        "domain": event_domain,
                        "timestamp_str": time_str,
                        "level": level_norm,
                        "thread": thread,
                        "tag": tag,
                        "msg": msg,
                        "file": os.path.basename(file_path),
                        "line_no": line_no,
                        "has_stacktrace": False
                    }

                    self._enrich_and_categorize(event_domain, event)
                    self.timeline_events.append(event)
                    last_event = event

                    self.domain_summary[event_domain]["count"] += 1
                    if level_norm in ("WARN", "W"):
                        self.domain_summary[event_domain]["warnings"] += 1
                    elif level_norm in ("ERROR", "E"):
                        self.domain_summary[event_domain]["errors"] += 1
                        self.exceptions.append(event)
                    elif "Exception" in msg or "Fatal" in msg or "Crash" in msg:
                        self.exceptions.append(event)

        except Exception as e:
            sys.stderr.write(f"解析文件出错 {file_path}: {e}\n")

    def _resolve_virtual_domain(self, physical_domain: str, filename: str, tag: str, msg: str) -> str:
        """三级弹性嗅探虚拟领域 (Virtual Domain Sniffing):
        1. 优先读取明确的物理子目录 (非 '.', 'main', 'debug', 'release', 'linlog' 等泛容器)；
        2. 识别文件名特征 (如 network_*.linlog, apm.txt)；
        3. 识别日志 Tag 特征 (如 NetworkLayer, ApmMonitor, WebSocket)；
        4. 识别日志内容关键特征 (耗时指标、FPS 指标、Socket 状态)；
        5. 兜底归类为 main。
        """
        pd = physical_domain.strip("/\\").lower()
        if pd and pd not in (".", "main", "debug", "release", "linlog", "log"):
            last_sub = os.path.basename(pd)
            if last_sub:
                return last_sub

        fn = filename.lower()
        if any(k in fn for k in ("network", "http", "api")):
            return "network"
        if any(k in fn for k in ("socket", "websocket", "tcp", "im")):
            return "socket"
        if any(k in fn for k in ("apm", "perf", "jank", "fps", "memory")):
            return "apm"
        if any(k in fn for k in ("track", "behavior", "event", "click")):
            return "track"
        if any(k in fn for k in ("pay", "billing", "iap")):
            return "payment"

        tl = tag.lower()
        if any(k in tl for k in ("net", "http", "api", "okhttp", "retrofit")):
            return "network"
        if any(k in tl for k in ("socket", "websocket", "channel", "imclient")):
            return "socket"
        if any(k in tl for k in ("apm", "perf", "jank", "fps", "memory", "cpu")):
            return "apm"
        if any(k in tl for k in ("track", "event", "usertrack", "page")):
            return "track"
        if any(k in tl for k in ("pay", "billing", "iap", "cashier")):
            return "payment"

        if LATENCY_PATTERN.search(msg):
            return "network"
        if FPS_PATTERN.search(msg) or SYSTEM_MEM_PATTERN.search(msg) or JVM_MEM_PATTERN.search(msg):
            return "apm"
        if SOCKET_STATE_PATTERN.search(msg):
            return "socket"

        return "main"

    def _enrich_and_categorize(self, domain: str, event: dict):
        msg = event["msg"]
        tag = event["tag"]

        # 1. 网络与长连接领域 (Socket / HTTP)
        if "socket" in domain.lower() or "socket" in tag.lower() or "websocket" in tag.lower():
            sock_match = SOCKET_STATE_PATTERN.search(msg)
            if sock_match:
                event["socket_state"] = sock_match.group(1)
                self.socket_state_events.append(event)
            elif any(k in msg for k in ("重连", "reconnect", "onfailure", "Closed", "Connecting")):
                self.socket_state_events.append(event)

        if "net" in domain.lower() or "http" in tag.lower() or "api" in tag.lower():
            lat_match = LATENCY_PATTERN.search(msg)
            if lat_match:
                cost = float(lat_match.group(1))
                event["network_cost_ms"] = cost
                if cost >= 800:
                    event["is_slow_network"] = True
                    self.slow_network_events.append(event)

            if any(err in msg for err in ("404", "500", "502", "timeout", "failed", "失败")):
                event["is_network_error"] = True

        # 2. APM 性能指标 (丢帧、内存)
        if "apm" in domain.lower() or "performance" in tag.lower():
            fps_match = FPS_PATTERN.search(msg)
            if fps_match:
                fps = float(fps_match.group(1))
                event["fps"] = fps
                if fps < 30.0:
                    event["is_jank"] = True
                    self.apm_jank_events.append(event)

            sys_mem = SYSTEM_MEM_PATTERN.search(msg)
            if sys_mem:
                event["sys_mem_avail_mb"] = int(sys_mem.group(3))
                event["sys_mem_used_mb"] = int(sys_mem.group(2))
                self.apm_memory_events.append(event)

            jvm_mem = JVM_MEM_PATTERN.search(msg)
            if jvm_mem:
                event["jvm_mem_used_mb"] = int(jvm_mem.group(3))
                self.apm_memory_events.append(event)

    def _correlate_causes(self):
        """跨域因果时序对齐：针对每个异常点，寻找同秒内各领域的上下文事件"""
        for exc in self.exceptions:
            exc_time = exc.get("timestamp_str", "")
            if not exc_time:
                continue
            time_prefix = exc_time[:19]
            related = [
                ev for ev in self.timeline_events
                if ev is not exc and ev.get("timestamp_str", "")[:19] == time_prefix
            ]
            exc["related_events"] = related[:5]

    def _search_query(self, query_text: str):
        """针对用户提问的关键词与语义进行搜索与时序上下文切片"""
        self.query_matched_events = []
        keywords = [k.strip() for k in re.split(r'[\s,，、]+', query_text) if k.strip()]
        if not keywords:
            return

        for idx, ev in enumerate(self.timeline_events):
            content = f"{ev['domain']} {ev['tag']} {ev['msg']}"
            if any(k.lower() in content.lower() for k in keywords):
                start_idx = max(0, idx - 5)
                end_idx = min(len(self.timeline_events), idx + 6)
                context_slice = self.timeline_events[start_idx:end_idx]
                ev_copy = dict(ev)
                ev_copy["matched_keyword"] = [k for k in keywords if k.lower() in content.lower()]
                ev_copy["timeline_context"] = context_slice
                self.query_matched_events.append(ev_copy)

    def query_and_format(self, query_text: str) -> str:
        """交互模式专用：在已加载的内存数据中秒级检索单个问题并格式化输出"""
        self._search_query(query_text)
        md = []
        md.append(f"### 🎯 针对提问「{query_text}」的诊断检索结果")
        md.append(f"- **命中事件数**: `{len(self.query_matched_events)}` 条记录\n")

        if not self.query_matched_events:
            md.append("> ℹ️ 未直接命中包含该关键词的日志，请尝试换一个关键词（如类名、方法名或错误码）。")
            return "\n".join(md)

        for i, match in enumerate(self.query_matched_events[:5], 1):
            kw_str = " ".join(match['matched_keyword'])
            md.append(f"#### 现场 #{i}: `[{match['timestamp_str']}]` 命中关键词 `[{kw_str}]`")
            md.append(f"- **核心事件**: `[{match['domain']}]` `[{match['level']}]` **{match['tag']}**: {match['msg']}")
            md.append("- **前后时序流水 (Context Window)**:")
            for ctx in match.get("timeline_context", []):
                marker = "👉" if ctx is match else "  "
                md.append(f"  {marker} `[{ctx['timestamp_str']}]` `[{ctx['domain']}]` `[{ctx['level']}]` {ctx['tag']}: {ctx['msg'][:120]}")
            md.append("")

        return "\n".join(md)

    def _build_report(self) -> dict:
        jvm_mem_usages = [e["jvm_mem_used_mb"] for e in self.apm_memory_events if "jvm_mem_used_mb" in e]
        sys_mem_avails = [e["sys_mem_avail_mb"] for e in self.apm_memory_events if "sys_mem_avail_mb" in e]

        # 面向非技术测试/运营人员的大白话健康度与责任归属定性
        non_tech_summary = {
            "health_level": "🟢 运行健康平稳",
            "headline": "日志健康度良好，未发现严重异常、崩溃堆栈或阻塞性故障。",
            "responsibility": "【暂无事故责任】客户端与网络通信平稳",
            "action_advice_ops": "无需人工介入或业务补发，属于正常用户行为。",
            "action_advice_qa": "常规回归验证即可。"
        }

        if self.exceptions:
            exc_first = self.exceptions[0]
            first_err_line = exc_first['msg'].strip().split('\n')[0]
            non_tech_summary["health_level"] = "🔴 严重异常 / 崩溃闪退"
            non_tech_summary["headline"] = f"检测到 {len(self.exceptions)} 处未捕获异常或崩溃堆栈（首发异常 Tag=[{exc_first['tag']}]，错误: {first_err_line[:80]}），极可能引发 App 闪退或界面卡死退出。"
            non_tech_summary["responsibility"] = "【前端 App 缺陷】发生代码级未捕获异常，需研发介入定位修复"
            non_tech_summary["action_advice_ops"] = "密切关注是否有同类用户投诉；若已闪退可引导用户重启 App 或升级版本。"
            non_tech_summary["action_advice_qa"] = f"根据首发异常 Tag [{exc_first['tag']}] 对应的前后用户操作链路组织复现用例。"
        elif self.slow_network_events or self.apm_jank_events:
            non_tech_summary["health_level"] = "🟠 亚健康 / 存在体验瓶颈 (卡顿或网络慢)"
            details = []
            if self.slow_network_events:
                details.append(f"{len(self.slow_network_events)} 次接口耗时 > 800ms")
            if self.apm_jank_events:
                details.append(f"{len(self.apm_jank_events)} 次严重丢帧(FPS < 30)")
            non_tech_summary["headline"] = f"检测到客户端运行存在体验瓶颈（{'，'.join(details)}），可能导致用户感知转圈、交互迟钝或轻微掉帧。"
            if len(self.slow_network_events) >= len(self.apm_jank_events):
                non_tech_summary["responsibility"] = "【后端服务高延迟 / 用户弱网】接口响应缓慢引发转圈等待"
            else:
                non_tech_summary["responsibility"] = "【前端主线程耗时 / 设备负载高】主线程计算量大或内存紧张引发掉帧"
            non_tech_summary["action_advice_ops"] = "若有用户反馈转圈等待，可引导用户切换稳定的 Wi-Fi / 5G 网络后重试。"
            non_tech_summary["action_advice_qa"] = "建议在网络限速代理 (Network Link Conditioner) 与低端机上进行压力复测。"

        return {
            "summary": {
                "total_events": len(self.timeline_events),
                "total_domains": len(self.domain_summary),
                "total_errors": sum(d["errors"] for d in self.domain_summary.values()),
                "total_warnings": sum(d["warnings"] for d in self.domain_summary.values()),
                "total_slow_requests": len(self.slow_network_events),
                "total_jank_frames": len(self.apm_jank_events),
                "total_exceptions": len(self.exceptions),
                "jvm_mem_max_mb": max(jvm_mem_usages) if jvm_mem_usages else None,
                "sys_mem_min_avail_mb": min(sys_mem_avails) if sys_mem_avails else None,
            },
            "non_tech_summary": non_tech_summary,
            "query": self.query,
            "query_matches_count": len(self.query_matched_events),
            "domain_statistics": dict(self.domain_summary),
            "socket_state_timeline": self.socket_state_events[:15],
            "slow_network_top": sorted(self.slow_network_events, key=lambda x: x.get("network_cost_ms", 0), reverse=True)[:10],
            "apm_jank_events": self.apm_jank_events[:10],
            "critical_exceptions": self.exceptions[:15],
            "query_matched_contexts": self.query_matched_events[:5]
        }

    def generate_markdown(self, data: dict) -> str:
        s = data["summary"]
        nt = data.get("non_tech_summary", {})
        md = []
        md.append("# 🩺 LinLog 智能日志体检与诊断报告\n")

        # 0. 置顶：面向测试/运营等非技术人员的速读卡片
        if nt:
            md.append("## 🟢 【测试 / 运营速读卡片】")
            md.append(f"- **📢 运行体检状态**: `{nt.get('health_level', '未知')}`")
            md.append(f"- **🔍 核心事故定性**: {nt.get('headline', '')}")
            md.append(f"- **🎯 责任归属判定**: **{nt.get('responsibility', '')}**")
            md.append(f"- **💡 运营处置建议**: {nt.get('action_advice_ops', '')}")
            md.append(f"- **💡 测试复测指引**: {nt.get('action_advice_qa', '')}\n")
            md.append("---\n")

        # 1. 问答针对性诊断 (如果带了提问)
        if data.get("query"):
            q = data["query"]
            count = data["query_matches_count"]
            md.append("## 🎯 针对提问的专项诊断")
            md.append(f"- **提问内容**: `{q}`")
            md.append(f"- **相关事件匹配数**: `{count}` 条\n")

            if data["query_matched_contexts"]:
                md.append("### 🔍 命中事件与跨域时序因果现场 (TOP 3 现场)")
                for i, match in enumerate(data["query_matched_contexts"][:3], 1):
                    kw_str = " ".join(match['matched_keyword'])
                    md.append(f"#### 现场 #{i}: `[{match['timestamp_str']}]` 命中关键词 `[{kw_str}]`")
                    md.append(f"- **核心事件**: `[{match['domain']}]` `[{match['level']}]` **{match['tag']}**: {match['msg']}")
                    md.append("- **现场前后时序上下文 (Context Window)**:")
                    for ctx in match.get("timeline_context", []):
                        marker = "👉" if ctx is match else "  "
                        md.append(f"  {marker} `[{ctx['timestamp_str']}]` `[{ctx['domain']}]` `[{ctx['level']}]` {ctx['tag']}: {ctx['msg'][:120]}")
                    md.append("")
            else:
                md.append("> ℹ️ 未在日志中直接匹配到包含提问关键词的记录，请查看下文全景体检大盘。\n")

        # 2. 全景概览
        md.append("## 一、全景概览指标")
        md.append(f"- **日志总行数** : `{s['total_events']}` 条")
        md.append(f"- **覆盖领域数** : `{s['total_domains']}` 个领域")
        md.append(f"- **错误与异常** : `{s['total_errors']}` 个错误 (含 `{s['total_exceptions']}` 处核心堆栈)")
        md.append(f"- **警告信息数** : `{s['total_warnings']}` 个告警")
        if s["jvm_mem_max_mb"] is not None:
            md.append(f"- **JVM 内存峰值**: `{s['jvm_mem_max_mb']} MB` (系统最低可用内存: `{s['sys_mem_min_avail_mb']} MB`)")
        md.append(f"- **慢网络请求** : `{s['total_slow_requests']}` 次 (耗时 > 800ms)")
        md.append(f"- **APM 严重丢帧** : `{s['total_jank_frames']}` 次 (FPS < 30.0)\n")

        # 3. 领域分布
        md.append("## 二、各业务领域分布")
        md.append("| 领域名称 (Domain) | 日志总量 | WARN 告警数 | ERROR 错误数 |")
        md.append("| :--- | :---: | :---: | :---: |")
        for dom, stat in data["domain_statistics"].items():
            err_str = f"**{stat['errors']}**" if stat['errors'] > 0 else "0"
            md.append(f"| `{dom}` | {stat['count']} | {stat['warnings']} | {err_str} |")
        md.append("")

        # 4. Socket 状态演变拓扑
        if data["socket_state_timeline"]:
            md.append("## 三、Socket 长连接状态演变时序 (TOP 10)")
            for i, sock in enumerate(data["socket_state_timeline"][:10], 1):
                state = sock.get("socket_state", "")
                detail = f"【状态: **{state}**】" if state else ""
                md.append(f"{i}. **[{sock['timestamp_str']}]** `[{sock['domain']}]` {detail} {sock['msg']}")
            md.append("")

        # 5. 核心崩溃与异常堆栈
        if data["critical_exceptions"]:
            md.append("## 四、核心异常与错误堆栈 (TOP 10)")
            for i, exc in enumerate(data["critical_exceptions"][:10], 1):
                msg_lines = exc['msg'].strip().split('\n')
                first_line = msg_lines[0]
                stack_preview = f"\n  ```\n  " + "\n  ".join(msg_lines[1:6]) + "\n  ```" if len(msg_lines) > 1 else ""
                md.append(f"{i}. **[{exc['timestamp_str']}]** `[{exc['domain']}]` `[{exc['tag']}]`: {first_line}{stack_preview}")
            md.append("")

        # 6. 慢网络与异常请求
        if data["slow_network_top"]:
            md.append("## 五、慢网络请求排行 (TOP 10)")
            for i, net in enumerate(data["slow_network_top"], 1):
                cost = net.get("network_cost_ms", "未知")
                md.append(f"{i}. **[{net['timestamp_str']}]** 耗时: **{cost}ms** | `{net['msg']}`")
            md.append("")

        return "\n".join(md)


def run_interactive_mode(analyzer: LinLogAnalyzer):
    """交互式多轮问答模式 (REPL)：一次解压加载进常驻内存，后续持续问答 0 耗时"""
    analyzer.load_data()
    result = analyzer.run_analysis()
    print(analyzer.generate_markdown(result))

    print("\n" + "=" * 70)
    print("💬 已进入 LinLog 多轮交互问答会话 (内存常驻模式，无需重复解压)")
    print("💡 使用提示：输入想排查的问题或关键词（例如：'socket重连'、'崩溃'、'耗时'）")
    print("           输入 'q'、'exit' 或按 Ctrl+C 退出会话")
    print("=" * 70 + "\n")

    while True:
        try:
            query = input("[LinLog 问答]> ").strip()
            if not query:
                continue
            if query.lower() in ("exit", "quit", "q"):
                print("👋 已退出 LinLog 交互问答。")
                break
            
            ans = analyzer.query_and_format(query)
            print("\n" + ans + "\n")
        except (KeyboardInterrupt, EOFError):
            print("\n👋 已退出 LinLog 交互问答。")
            break


def main():
    parser = argparse.ArgumentParser(description="LinLog 智能日志分析与时序因果推导引擎")
    parser.add_argument("path", nargs="?", help="待分析的 log_xxx.zip 文件路径或已解压的日志目录路径")
    parser.add_argument("-q", "--query", "--ask", dest="query", help="直接提出你想排查的问题或关键词（例如：'socket断开'、'闪退崩溃'、'为什么加载慢'）")
    parser.add_argument("-i", "--interactive", action="store_true", help="进入持续交互式多轮问答会话（内存常驻，0 重复解压）")
    parser.add_argument("--force-reload", action="store_true", help="强制丢弃本地持久化缓存，重新全量解压扫描")
    parser.add_argument("--clear-cache", action="store_true", help="一键清空本地所有日志持久化缓存池")
    parser.add_argument("--json", action="store_true", help="以标准 JSON 结构输出结果")
    args = parser.parse_args()

    if args.clear_cache:
        if os.path.exists(CACHE_BASE_DIR):
            shutil.rmtree(CACHE_BASE_DIR, ignore_errors=True)
            print(f"🧹 已成功清空 LinLog 持久化缓存目录: {CACHE_BASE_DIR}")
        else:
            print("ℹ️ 缓存目录为空，无需清理。")
        return

    if not args.path:
        parser.print_help()
        sys.exit(1)

    analyzer = LinLogAnalyzer(args.path, query=args.query, force_reload=args.force_reload)

    if args.interactive:
        run_interactive_mode(analyzer)
    else:
        result = analyzer.run_analysis()
        if args.json:
            print(json.dumps(result, indent=2, ensure_ascii=False))
        else:
            print(analyzer.generate_markdown(result))


if __name__ == "__main__":
    main()
