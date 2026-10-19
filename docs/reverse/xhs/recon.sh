#!/usr/bin/env bash
# xhs 逆向专用脚本（2026-10-20 沉淀；流程入口 = skill xhs-recon + docs/reverse/xhs-anchors.md）
# 通用操作走 tools/dev.sh（构建/安装/日志/反编译）；这里只收 xhs 高频专用链：
#   dex     <classesN...>  从 .buildtmp/xhs-*.apk 抽 dex → jadx 定向反编译（禁全量：183MB 卡死实证）
#   grep    <pattern>      全部 xhs 反编译产物搜串/类/资源 id（十进制）
#   apk                    拉设备宿主 APK → .buildtmp/xhs-<version>.apk
#   dexes                   列出 APK 内全部 classesN.dex（选编号用）
# 用法示例：
#   docs/reverse/xhs-recon.sh dex 2 20        # classes2+classes20 → .buildtmp/xhs<ver>-c2 / -c20
#   docs/reverse/xhs-recon.sh grep "save video failed:"
#   docs/reverse/xhs-recon.sh grep "$(printf '%d' 0x7f112348)"   # 资源 id 先转十进制
set -u

# ---- 自定位与工具链（与 tools/dev.sh 同源规则；dev.sh 优先，缺失走 brew/PATH）----
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
JADX="$ROOT/tools/jadx/bin/jadx"
ADB="$(command -v adb || true)"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then :; else
  JAVA_HOME="$(ls -d /opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home 2>/dev/null | head -1 || true)"
  [ -n "$JAVA_HOME" ] || JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
fi
export JAVA_HOME

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }
warn() { echo "WARN: $*" >&2; }

latest_apk() { ls -t "$ROOT"/.buildtmp/xhs-*.apk 2>/dev/null | head -1; }

case "${1:-}" in
  apk)
    [ -n "$ADB" ] || die "未找到 adb"
    ver="$("$ADB" shell dumpsys package com.xingin.xhs 2>/dev/null | tr -d '\r' | awk -F'= ' '/versionName/{print $2; exit}')"
    [ -n "$ver" ] || die "设备未装 com.xingin.xhs 或未连接"
    path="$("$ADB" shell pm path com.xingin.xhs 2>/dev/null | tr -d '\r' | head -1 | sed 's/^package://')"
    [ -n "$path" ] || die "pm path 失败"
    out="$ROOT/.buildtmp/xhs-$ver.apk"
    mkdir -p "$ROOT/.buildtmp"
    "$ADB" pull "$path" "$out" >/dev/null || die "pull 失败"
    info "→ $out"
    ;;

  dexes)
    apk="$(latest_apk)" || true
    [ -n "${apk:-}" ] || die "未找到 .buildtmp/xhs-*.apk（先跑: $(basename "$0") apk）"
    unzip -l "$apk" 'classes*.dex' | awk 'NR>3 && /classes/ {print $4, "("int($1/1048576)"MB)"}'
    ;;

  dex)
    [ -x "$JADX" ] || die "未找到 jadx：$JADX"
    [ -n "${2:-}" ] || die "用法: $(basename "$0") dex <classesN...>（先跑 dexes 选编号）"
    apk="$(latest_apk)" || true
    [ -n "${apk:-}" ] || die "未找到 .buildtmp/xhs-*.apk（先跑: $(basename "$0") apk）"
    ver="$(basename "$apk" .apk | sed 's/^xhs-//')"
    dexdir="$ROOT/.buildtmp/xhs${ver}-dex"
    shift
    info "APK=$apk 抽 $* → $dexdir"
    unzip -o "$apk" $(for n in "$@"; do echo "classes$n.dex"; done) -d "$dexdir" >/dev/null || die "unzip 失败"
    for n in "$@"; do
      out="$ROOT/.buildtmp/xhs${ver}-c$n"
      info "jadx classes$n → $out（--no-res，约 5 分钟/dex）"
      "$JADX" --no-res -j 8 -d "$out" "$dexdir/classes$n.dex" >/dev/null 2>&1 || warn "classes$n jadx 有错误项（正常，看产物）"
    done
    info "完成。搜串: $(basename "$0") grep <模式>"
    ;;

  grep)
    [ -n "${2:-}" ] || die "用法: $(basename "$0") grep <pattern>"
    pat="$2"; found=0
    for d in "$ROOT"/.buildtmp/xhs*-partial "$ROOT"/.buildtmp/xhs*-main "$ROOT"/.buildtmp/xhs*-c[0-9]* \
             "$ROOT"/system-files/xhs-partial "$ROOT"/system-files/xhs-jadx; do
      [ -d "$d/sources" ] || continue
      hits="$(grep -rl -- "$pat" "$d/sources" 2>/dev/null | head -20)"
      if [ -n "$hits" ]; then found=1; echo "== $d =="; echo "$hits"; fi
    done
    [ "$found" = "0" ] && warn "代码产物无命中。资源串另查：system-files/xhs-jadx/resources/res/values-zh-rCN/strings.xml"
    exit 0
    ;;

  *)
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    die "未知子命令：${1:-（空）}"
    ;;
esac
