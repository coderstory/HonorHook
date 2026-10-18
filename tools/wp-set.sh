#!/bin/bash
# 壁纸设置全流程自动化（图库 → 编辑器 → 弹窗选项 → 应用）。
# 用法：tools/wp-set.sh <video_id> <desktop|both|lock>
#   desktop = 本模块第三项（原生 both 链）；both = 原生「设为锁屏和桌面」；lock = 原生「设为锁屏」
# 依赖：dev.sh（am/uiautomator/tap）、touch.sh 的 dump 解析逻辑（内联实现，避免跨脚本 source）。

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VID="${1:?用法: tools/wp-set.sh <video_id> <desktop|both|lock>}"
MODE="${2:?用法: tools/wp-set.sh <video_id> <desktop|both|lock>}"

case "$MODE" in
  desktop) OPTION="设为桌面" ;;
  both) OPTION="设为锁屏和桌面" ;;
  lock) OPTION="设为锁屏" ;;
  *) echo "mode 只能是 desktop|both|lock" >&2; exit 2 ;;
esac

DEV() { bash "$ROOT/tools/dev.sh" exec adb shell "$@" 2>/dev/null; }

# 节点中心坐标（xml 文件 + 文本）→ "x y" 或空
center_of() {
  python3 - "$1" "$2" <<'PY'
import re, sys
xml, text = open(sys.argv[1], encoding='utf-8').read(), sys.argv[2]
for m in re.finditer(r'<node[^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n).group(1)
    d = re.search(r'content-desc="([^"]*)"', n).group(1)
    b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', n)
    if b and (t == text or d == text):
        x1, y1, x2, y2 = map(int, b.groups())
        if x2 > x1 and y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
}

tap_text() { # $1=文本 $2=超时秒 $3=dump 文件名前缀
  local deadline=$(( $(date +%s) + ${2:-10} )) dump="" xy=""
  while [ "$(date +%s)" -lt "$deadline" ]; do
    dump="/sdcard/wp_set_${3}.xml"
    DEV "uiautomator dump $dump >/dev/null 2>&1" || { sleep 1; continue; }
    DEV "cat $dump" > /tmp/wp_set_dump.xml 2>/dev/null || { sleep 1; continue; }
    xy="$(center_of /tmp/wp_set_dump.xml "$1")"
    [ -n "$xy" ] && { bash "$ROOT/tools/dev.sh" tap $xy >/dev/null 2>&1; echo "tap「${1}」@ ($xy)"; return 0; }
    sleep 1
  done
  echo "tap「${1}」超时未找到" >&2
  return 1
}

dump_has() { # $1=文本 → 0/1（本轮 dump 查找）
  local dump="/sdcard/wp_set_chk.xml"
  DEV "uiautomator dump $dump >/dev/null 2>&1" || return 1
  DEV "cat $dump" > /tmp/wp_set_dump.xml 2>/dev/null || return 1
  grep -q "text=\"$1\"" /tmp/wp_set_dump.xml && return 0
  return 1
}

echo "==> 1/4 冷启编辑器弹窗（video ${VID}, ${MODE}）"
DEV "am force-stop com.hihonor.wallpapereditor"
sleep 1
DEV "am start --grant-read-uri-permission -n com.hihonor.wallpapereditor/com.android.gallery3d.app.Wallpaper -a com.hihonor.wallpaper.action.SET_WALLPAPER -d 'content://media/external/video/media/$VID' -t 'video/*'"

echo "==> 2/4 点选项「${OPTION}」"
tap_text "${OPTION}" 15 opt

echo "==> 3/4 关时间轴弹窗（点上部）"
sleep 8
bash "$ROOT/tools/dev.sh" tap 636 400 >/dev/null 2>&1
sleep 3

echo "==> 4/4 点「应用」"
# 应用按钮有时因动画 uiautomator 拿不到 idle，重试若干轮
applied=0
for i in 1 2 3 4 5; do
  if dump_has "应用"; then
    xy="$(center_of /tmp/wp_set_dump.xml "应用")"
    [ -n "$xy" ] && { bash "$ROOT/tools/dev.sh" tap $xy >/dev/null 2>&1; echo "tap「应用」@ ($xy)"; applied=1; break; }
  fi
  sleep 3
done
[ "$applied" = 1 ] || { echo "未找到应用按钮（流程失败，看 /tmp/wp_set_dump.xml）" >&2; exit 1; }

echo "==> 等待转码/应用完成（20s）"
sleep 20
echo "完成。判据：bash tools/dev.sh modlog HonorHook 100"
