#!/usr/bin/env bash
# 同步 .claude/skills → .agents/skills（单向）
# 背景（2026-10-23）：Claude Code 读 .claude/skills，DSH 读 .agents/skills（项目级技能根，
# 另有 .dsh/skills 优先级更高）；两份副本手动同步，改完 .claude 跑一次本脚本。
# sdcard 挂载不支持符号链接，无法用软链免同步。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
[ -d "$ROOT/.claude/skills" ] || { echo "ERROR: .claude/skills 不存在" >&2; exit 1; }
mkdir -p "$ROOT/.agents/skills"
cp -r "$ROOT"/.claude/skills/* "$ROOT/.agents/skills/"
echo "已同步 $(ls "$ROOT/.agents/skills" | wc -l) 个技能 → .agents/skills/（新会话生效）"
