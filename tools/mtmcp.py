#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MT MCP（MT管理器）on-device 逆向助手 —— 封装本会话验证过的调用姿势。

为什么存在（2026-10-23 实战）：
- 真机 /storage 是 noexec，jadx 全量反编译 40k+ 类会 OOM（97% 被 LMK 杀）；
  MT MCP 直接读 dex/smali，按类/按锚点查，零全量反编译。
- MT MCP 裸 JSON-RPC 参数苛刻（缺一个必填就 INVALID_ARGUMENT），会话易过期 —— 本文件统一兜底。

依赖：MT 管理器在手机上开着、其 MCP server 监听 MT_MCP_URL（默认 127.0.0.1:8787/mcp）。
用法（子命令）：
  outline  <apk路径> <类描述符|java名>   # 类大纲（自动翻页，防截断假阴性）
  smali    <apk路径> <类描述符>          # 类 smali 全文（window 内）
  strings  <apk路径> <子串> [--limit N]  # dex 字符串搜（定位锚点宿主）
  names    <apk路径> <子串>              # dex 类/成员名搜
  smaliq   <apk路径> <子串> [--prefix Lcom/foo]  # smali 全文搜（签名/调用点反查）
  list     <apk路径> <java前缀>          # 类清单（存在性 + 方法数）
示例：
  python3 tools/mtmcp.py strings system-files/xhs-9.51.0.apk "disableAiWaterMark is"
  python3 tools/mtmcp.py outline system-files/xhs-9.51.0.apk ce8.j
"""
import argparse
import json
import re
import sys
import urllib.request

MT_URL = None  # 延迟取环境变量


def _post(method, params=None, timeout=300):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method,
                       "params": params or {}}).encode()
    req = urllib.request.Request(MT_URL, data=body, headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        outer = json.loads(resp.read().decode())
    if "result" not in outer:
        raise RuntimeError(f"RPC error: {str(outer)[:200]}")
    return json.loads(outer["result"]["content"][0]["text"])


class Apk:
    """一个 APK 的 MT 工作区（workspace + editSession，会话过期自动重建）。"""

    def __init__(self, apk_path):
        global MT_URL
        MT_URL = __import__('os').environ.get('MT_MCP_URL', 'http://127.0.0.1:8787/mcp')
        import os
        import time
        apk_path = os.path.abspath(apk_path)  # MT 按它自己的 Home 解析相对路径 → 必须绝对化
        r = None
        for attempt in range(3):  # PATH_NOT_FOUND 偶发瞬时（MT 忙），退避重试
            r = _post("tools/call", {"name": "mt_apk_workspace_open",
                                     "arguments": {"path": apk_path, "temporary": False}})
            if r.get("ok"):
                break
            time.sleep(1.5 * (attempt + 1))
        if not r or not r.get("ok"):
            raise SystemExit(f"workspace_open 失败: {(r or {}).get('error')}")
        self.wid = r["data"]["workspaceId"]
        self.apk = r["data"].get("apkFileName", apk_path)
        self._session()

    def _session(self):
        r = _post("tools/call", {"name": "mt_apk_session_create",
                                 "arguments": {"workspaceId": self.wid}})
        if not r.get("ok"):
            raise SystemExit(f"session_create 失败: {r.get('error')}")
        self.eid = r["data"]["editSessionId"]

    def _call(self, tool, args):
        args = dict(args, workspaceId=self.wid, editSessionId=self.eid)
        r = _post("tools/call", {"name": tool, "arguments": args})
        if not r.get("ok"):
            err = r.get("error") or {}
            msg = err.get("message") or json.dumps(r)[:200]
            if "session" in msg.lower():
                self._session()          # 会话过期：重建重试一次
                return self._call(tool, args)
            raise RuntimeError(f"{tool}: {msg}")
        return r.get("data", {})

    # ---- 高层操作 ----

    def outline(self, cls):
        """类大纲，自动翻页合并（outline 有 window 截断，曾造成 shouldBlockDetailAd 假阴性）。"""
        loc = cls if cls.startswith("dex_class:") else (
            "dex_class:L%s;" % cls.replace(".", "/"))
        data = self._call("mt_apk_dex_read_class_outline",
                          {"locator": loc, "limit": 400})
        while data.get("pagination", {}).get("hasMore"):
            cur = data["pagination"]["nextCursor"]
            more = self._call("mt_apk_continue",
                              {"nextCursor": cur, "limit": 400})
            data["methods"].extend(more.get("methods", []))
            data["pagination"] = more.get("pagination", {"hasMore": False})
        return data

    def smali(self, cls, max_chars=40000):
        loc = cls if cls.startswith("dex_class:") else (
            "dex_class:L%s;" % cls.replace(".", "/"))
        out = self._call("mt_apk_read_text",
                         {"locator": loc, "limit": 2000, "maxChars": max_chars})
        return out["textWindow"]["text"]

    def search(self, target, query, limit=50, prefix="", case_sensitive=True):
        """统一搜素：target ∈ dex_strings / dex_names / smali / resource_table_names…"""
        out = self._call("mt_apk_search", {
            "target": target, "query": query, "queryType": "literal",
            "caseSensitive": case_sensitive, "matchMode": "contains",
            "prefix": prefix, "includeMatchOffsets": False,
            "limit": limit, "snippetMaxChars": 240,
        })
        hits = out.get("hits", [])
        return sorted({h["locator"] for h in hits})

    def list_classes(self, java_prefix, limit=100):
        out = self._call("mt_apk_list",
                         {"view": "dex_classes", "prefix": java_prefix, "limit": limit})
        return [(i["javaName"], i["methodCount"]) for i in out.get("items", [])]


def param_count(smali_sig):
    """smali 方法签名 → 参数个数。注意：描述符无逗号分隔（'()Z'、'(II)…'），数 token 不数逗号。"""
    inner = smali_sig[smali_sig.index("(") + 1:smali_sig.rindex(")")]
    return len(re.findall(r"\[*L[^;]+;|\[*[BCDFIJSZ]", inner))


def main():
    global MT_URL
    MT_URL = __import__('os').environ.get('MT_MCP_URL', 'http://127.0.0.1:8787/mcp')
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, help_, args in [
        ("outline", "类大纲（自动翻页）", [("apk", None), ("cls", None)]),
        ("smali", "类 smali 全文", [("apk", None), ("cls", None)]),
        ("strings", "dex 字符串搜", [("apk", None), ("query", None)]),
        ("names", "dex 名字搜", [("apk", None), ("query", None)]),
        ("smaliq", "smali 全文搜", [("apk", None), ("query", None)]),
        ("list", "类清单", [("apk", None), ("prefix", None)]),
    ]:
        p = sub.add_parser(name, help=help_)
        for an, _ in args:
            p.add_argument(an)
        if name in ("strings", "names", "smaliq"):
            p.add_argument("--limit", type=int, default=30)
            if name != "strings":
                p.add_argument("--prefix", default="")
    a = ap.parse_args()
    apk = Apk(a.apk)
    if a.cmd == "outline":
        d = apk.outline(a.cls)
        print("source:", d.get("source"), "| super:", d.get("super"))
        for m in d["methods"]:
            print(" ", m["name"], "|", m.get("access"), "|", m.get("sig"))
    elif a.cmd == "smali":
        print(apk.smali(a.cls))
    elif a.cmd == "strings":
        for loc in apk.search("dex_strings", a.query, limit=a.limit):
            print(loc)
    elif a.cmd == "names":
        for loc in apk.search("dex_names", a.query, limit=a.limit):
            print(loc)
    elif a.cmd == "smaliq":
        for loc in apk.search("smali", a.query, limit=a.limit,
                              prefix=getattr(a, "prefix", "")):
            print(loc)
    elif a.cmd == "list":
        for name, mc in apk.list_classes(a.prefix):
            print(name, mc)


if __name__ == "__main__":
    main()
