#!/usr/bin/env python3
"""MT MCP 直连客户端（streamable HTTP JSON-RPC）。

MT 管理器逆向助手的 MCP 服务跑在手机端（本机回环 127.0.0.1:8787/mcp；
旧值 192.168.3.105:8787 = Mac 侧 LAN 地址，2026-10-27 起 DSH 直接跑在目标机上，已作废）。
ZCode 里已注册为原生 MCP（~/.zcode/cli/config.json mcp.servers.mt，新会话自动连接）；
本脚本用于脚本管道与调试，也是注册失效时的兜底通道。

用法：
  python3 tools/mt_mcp.py list                # 列工具
  python3 tools/mt_mcp.py call <tool> [json]  # 调工具，如 call file_pull '["/proc/version","scratch/v"]'
  python3 tools/mt_mcp.py raw <method> [json] # 裸 JSON-RPC
"""
import json
import sys
import urllib.request

URL = "http://127.0.0.1:8787/mcp"
INIT_PARAMS = {
    "protocolVersion": "2025-03-26",
    "capabilities": {},
    "clientInfo": {"name": "mt-mcp-cli", "version": "0.1.0"},
}


def post(payload, session=None, timeout=60):
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if session:
        headers["Mcp-Session-Id"] = session
    req = urllib.request.Request(URL, json.dumps(payload).encode(), headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        sid = resp.headers.get("Mcp-Session-Id") or session
        body = resp.read().decode("utf-8")
        return sid, resp.headers.get("Content-Type", ""), body


def parse_sse_or_json(ctype, body):
    if "text/event-stream" in ctype:
        for line in body.splitlines():
            if line.startswith("data:"):
                data = line[5:].strip()
                if data:
                    return json.loads(data)
        return None
    return json.loads(body) if body.strip() else None


def session_and(method, params):
    sid, ctype, body = post({"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
    result = parse_sse_or_json(ctype, body)
    if result is None or "error" in result:
        sys.exit(f"initialize 失败: {result}")
    # initialized 通知（规范要求，无响应体）
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    return sid, result


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return
    cmd = args[0]
    sid, init = session_and("initialize", INIT_PARAMS)
    if cmd == "list":
        _, ctype, body = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, sid)
        tools = parse_sse_or_json(ctype, body)["result"]["tools"]
        for t in tools:
            print(f"{t['name']}: {t.get('description', '')[:120]}")
    elif cmd == "call":
        if len(args) < 2:
            sys.exit("用法: call <tool> [json-arguments]")
        tool = args[1]
        call_args = json.loads(args[2]) if len(args) > 2 else {}
        _, ctype, body = post(
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
             "params": {"name": tool, "arguments": call_args}}, sid)
        print(json.dumps(parse_sse_or_json(ctype, body), ensure_ascii=False, indent=1))
    elif cmd == "raw":
        method = args[1]
        params = json.loads(args[2]) if len(args) > 2 else {}
        _, ctype, body = post({"jsonrpc": "2.0", "id": 2, "method": method, "params": params}, sid)
        print(json.dumps(parse_sse_or_json(ctype, body), ensure_ascii=False, indent=1))
    else:
        sys.exit(f"未知子命令 {cmd}")
    print(f"\n# server: {init['result']['serverInfo']}", file=sys.stderr)


if __name__ == "__main__":
    main()
