#!/bin/bash
# UI 点击自动化：按文本/content-desc 找节点，点其中心。
# 用法：
#   tools/touch.sh <文本> [第几个匹配，从 1 起，默认 1]
#   tools/touch.sh --list          # 列出当前屏全部可见文本节点及坐标
# 依赖 dev.sh ui / dev.sh tap；界面未就绪时自旋等待文本出现（默认 15s）。

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UI_DIR="$ROOT/.buildtmp"

latest_ui() { ls -t "$UI_DIR"/ui_*.xml 2>/dev/null | head -1; }

dump_ui() { bash "$ROOT/tools/dev.sh" ui >/dev/null 2>&1; latest_ui; }

xml_center() {
  # $1=xml $2=text $3=index → "x y" 或空
  python3 - "$1" "$2" "${3:-1}" <<'PY'
import re, sys
xml, text, idx = open(sys.argv[1], encoding='utf-8').read(), sys.argv[2], int(sys.argv[3])
hits = []
for m in re.finditer(r'<node[^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n).group(1)
    d = re.search(r'content-desc="([^"]*)"', n).group(1)
    b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', n)
    if b is None:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    if (t == text or d == text) and (x2 > x1) and (y2 > y1):
        hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if len(hits) >= idx:
    print(hits[idx - 1][0], hits[idx - 1][1])
PY
}

if [[ "${1:-}" == "--list" ]]; then
  xml="$(dump_ui)"
  python3 - "$xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n).group(1)
    d = re.search(r'content-desc="([^"]*)"', n).group(1)
    b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', n)
    if b is None or not (t or d):
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    if x2 > x1 and y2 > y1:
        print(f'{t or d}\t[{x1},{y1}][{x2},{y2}]')
PY
  exit 0
fi

[[ $# -ge 1 ]] || { echo "用法: tools/touch.sh <文本> [序号] | --list" >&2; exit 2; }
TEXT="$1"; IDX="${2:-1}"

xml=""
for _ in $(seq 1 15); do
  xml="$(dump_ui)"
  xy="$(xml_center "$xml" "$TEXT" "$IDX")"
  [[ -n "$xy" ]] && break
  sleep 1
done
[[ -n "${xy:-}" ]] || { echo "touch.sh: 未找到节点「${TEXT}」（第 ${IDX} 个）" >&2; exit 1; }

bash "$ROOT/tools/dev.sh" tap $xy >/dev/null 2>&1
echo "touch.sh: 已点「${TEXT}」@ ($xy)"
