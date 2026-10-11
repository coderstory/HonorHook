#!/usr/bin/env bash
# HonorHook 开发/调试统一入口
# 目的：所有命令固化于此，禁止在会话里手工拼接 gradle/adb 命令。
# 用法：tools/dev.sh <子命令>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- 工具链：项目内便携版优先，否则本机安装兜底（macOS 迁移后，原 Windows 硬编码路径已不适用）----
# JDK 必须带 jlink（打包 release 需要）；brew 的 openjdk@21 经 libexec 下的 Home 才有 bin/jlink
if [ -x "$ROOT/tools/jdk-21/bin/jlink" ]; then
  export JAVA_HOME="$ROOT/tools/jdk-21"
elif [ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/jlink ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
elif [ -x /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/jlink ]; then
  export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
else
  export JAVA_HOME="$ROOT/tools/jdk-21"   # 均缺失：保留原路径，由 require 报出明确错误
fi
# ---- Android SDK：项目内便携版优先 → brew 前缀 → 本机安装兜底（2026-10-11 加回退）----
# 背景：e76e045 磁盘清理移除了 tools/android-sdk/（1.85GB 回收），原硬编码路径失效。
# 与上面 JDK 的三级回退同构：任一路径存在即用，全缺失时保留原路径由 require 报明确错误。
if [ -d "$ROOT/tools/android-sdk/platforms" ]; then
  ANDROID_SDK_LOCAL="$ROOT/tools/android-sdk"
elif [ -d /opt/homebrew/share/android-sdk/platforms ]; then
  ANDROID_SDK_LOCAL=/opt/homebrew/share/android-sdk
elif [ -d /usr/local/share/android-sdk/platforms ]; then
  ANDROID_SDK_LOCAL=/usr/local/share/android-sdk
elif [ -d "$HOME/Library/Android/sdk/platforms" ]; then
  ANDROID_SDK_LOCAL="$HOME/Library/Android/sdk"
else
  ANDROID_SDK_LOCAL="$ROOT/tools/android-sdk"   # 均缺失：保留原路径，后续 require/die 报出
fi
export ANDROID_HOME="$ANDROID_SDK_LOCAL"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -x "$ROOT/tools/platform-tools/adb" ]; then
  ADB="$ROOT/tools/platform-tools/adb"
elif [ -x "$ROOT/tools/platform-tools/adb.exe" ]; then
  ADB="$ROOT/tools/platform-tools/adb.exe"
else
  ADB="$(command -v adb || echo adb)"     # brew android-platform-tools
fi
# 逆向工具链（decompile/jadx/aapt2 子命令用；找不到不报错，用时才 require）
APKTOOL="$ROOT/tools/apktool.jar"
JADX=""
if [ -x "$ROOT/tools/jadx/bin/jadx" ]; then
  JADX="$ROOT/tools/jadx/bin/jadx"                 # macOS/Linux bundle
elif [ -f "$ROOT/tools/jadx/bin/jadx.bat" ]; then
  JADX="$ROOT/tools/jadx/bin/jadx.bat"             # Windows bundle
fi
# build-tools 里取版本最高的一份（36.0.0 < 36.1.0 < 37.0.0 字典序即版本序）
AAPT2=""
for c in "$ANDROID_HOME"/build-tools/*/aapt2 "$ANDROID_HOME"/build-tools/*/aapt2.exe; do
  if [ -x "$c" ]; then AAPT2="$c"; fi
done
# ---- PATH 暴露（让按 PATH 探测的工具/脚本认得出本项目的工具链）----
# 顺序：项目垫片 > jadx bundle > SDK build-tools（取最高版本，aapt2/apksigner/zipalign/dexdump）> JDK > platform-tools
PATH="$ROOT/tools/bin:$PATH"
[ -x "$ROOT/tools/jadx/bin/jadx" ] && PATH="$ROOT/tools/jadx/bin:$PATH"
BT=""
for d in "$ANDROID_HOME"/build-tools/*/; do [ -d "$d" ] && BT="${d%/}"; done
[ -n "$BT" ] && PATH="$BT:$PATH"
[ -x "$JAVA_HOME/bin/java" ] && PATH="$JAVA_HOME/bin:$PATH"
[ -d "$ROOT/tools/platform-tools" ] && PATH="$ROOT/tools/platform-tools:$PATH"
export PATH

GRADLEW="$ROOT/app/gradlew"
APK_DEBUG="$ROOT/app/app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE="$ROOT/app/app/build/outputs/apk/release/app-release.apk"
LOG_TAG="HonorHook"
LOG_OUT="$ROOT/.buildtmp"

die()  { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }
# 落盘文件名统一：类型_YYYYMMDD-HHMMSS，便于按时间与类型辨识归档
stamp() { date +%Y%m%d-%H%M%S; }

require() { [ -x "$1" ] || [ -f "$1" ] || die "缺少文件: $1（工具链未就位？）"; }

case "${1:-help}" in

  help|"")
    cat <<'EOF'
HonorHook 开发工具

环境要求：macOS（JDK 21 / adb / python3-Pillow 由 tools/ 或本机安装提供，缺失时脚本自行兜底）

子命令：
  build            编译 debug APK
  build-release    编译 release APK（混淆+收缩）
  install          安装 debug APK 到已连接设备（-r 覆盖）
  install-release  安装 release APK
  enable-module    重装后自动重新启用 LSPosed 模块（点通知+开关，含作用域进程清理）
  deploy-release   一键全流程：build-release + install-release + enable-module
  devices          列出已连接设备与授权状态
  launch           冷启动模块 App（主 Activity）
  log              实时日志：宿主阻断 + HonorHook tag
  log-hook         实时日志：仅 HonorHook tag（hook 侧 installed N/M 判据）
  log-tag          一次性 dump：TAG=HonorHook 的全部缓冲
  log-grep         全量缓冲按内容匹配 HonorHook / lspd / LSPosed（无视 tag）
  grep-log <正则>   按正则过滤当前 logcat 缓冲
  grep-logd <正则>  按正则过滤全 buffer（-b all）
  modlog [tag] [行数]   LSPosed 框架日志中匹配 tag 的最后 N 行（默认 HonorHook / 60）
  lspd-log         LSPosed 框架日志目录与最近内容（模块未被加载时先看这里）
  crash            抓取崩溃堆栈（一次性 dump，不阻塞）
  logcrash         读崩溃缓冲区（-b crash）头部
  diag-start       清 logcat 后启动目标并抓首轮诊断
  diag-module      模块自检三步：包安装 / 宿主进程存活 / LSPosed 模块记录
  diag-capture     收集 [diag] 取证日志并按 tag 分组
  wait-hook        清空日志后轮询等待 installed 判据（最长 3 分钟）
  hook-test        清日志 → 强停并拉起主题商店 → 抓挂载判据
  logcat           原始全量 logcat dump（最近缓冲）
  clear-log        清空设备 logcat 缓冲
  log-file         拉取 hook 侧判据日志文件 honorhook.log
  scopes           打印模块声明的作用域清单
  targets          打印代码里注册的目标包名
  shot             截屏到 .buildtmp/shot_*.png
  ui               UI 层级 dump（含文本/描述节点），定位界面元素用
  viewdump         当前前台 Activity / 焦点窗口
  tap <x> <y>      点击屏幕坐标
  swipe <x1> <y1> <x2> <y2> [ms]   滑动
  text <文本>      输入文本
  key <keycode>    发送按键（4=返回 3=Home）
  launch-theme     冷启动主题商店（宿主，触发 hook）并 dump 栈顶
  top              当前前台/栈顶 Activity + 本模块包存活状态
  size             debug/release APK 体积与最大成分
  dexstat          release classes.dex 按顶层包统计类数
  test             运行 JVM 单元测试（表驱动决策回归）
  verify           一键：编译 → 安装 → 启动 → 抓判据日志
  clean            清理 Gradle 构建产物
  env              打印工具链解析结果（java/adb/apktool/jadx/aapt2/python 都在哪，先跑这个）
  decompile <apk> [名称]   apktool 反编译 → system-files/<名称>-apktool（smali + 明文 manifest + res）
  jadx <apk> [名称]        jadx 反编译（--show-bad-code）→ system-files/<名称>-jadx
  aapt2 <参数...>          aapt2 透传（自动选 build-tools 里最高版本）
  apkinfo <apk>            aapt2 dump badging 摘要（包名/版本/权限）
  exec <命令...>           带本项目工具链环境（PATH/JAVA_HOME/ANDROID_HOME）执行任意命令
EOF
    ;;

  build)
    # 不 require jlink：debug 编译不需要，缺 jlink 的 JDK 也应能编 debug（release 才要）
    require "$GRADLEW"
    info "编译 debug APK（JAVA_HOME=${JAVA_HOME}）"
    (cd "$ROOT/app" && "$GRADLEW" :app:assembleDebug --console=plain)
    ls -lh "$APK_DEBUG"
    ;;

  lspd-log)
    require "$ADB"
    info "LSPosed 框架自身日志目录（模块未被加载时看这里找原因）"
    "$ADB" shell su -c "ls -la /data/adb/lspd/log/ 2>/dev/null | head -10" 2>&1 | tr -d '\r'
    info "最近日志内容（含 HonorHook / error / module 关键字优先）"
    "$ADB" shell su -c "tail -n 200 /data/adb/lspd/log/*.log 2>/dev/null" 2>&1 | tr -d '\r' \
      | grep -iE "honorhook|error|fail|module|scope|init" | tail -30
    ;;

  log-grep)
    require "$ADB"
    info "全量缓冲中按内容匹配 HonorHook / lspd / LSPosed（无视 tag）"
    "$ADB" logcat -d -v brief 2>/dev/null | tr -d '\r' \
      | grep -iE "honorhook|lspd|lsposed" | grep -viE "com\.hihonor" | tail -40
    ;;

  log-tag)
    require "$ADB"
    info "一次性 dump：TAG=HonorHook 的全部缓冲"
    "$ADB" logcat -d -v brief "$LOG_TAG:*" '*:S' 2>/dev/null | tr -d '\r' | tail -80
    info "—— 缓冲内 HonorHook 行数：$("$ADB" logcat -d 2>/dev/null | grep -c "HonorHook" || echo 0)"
    ;;

  diag-module)
    require "$ADB"
    info "① 包是否安装"
    "$ADB" shell dumpsys package top.honorhook 2>/dev/null | tr -d '\r' | grep -m2 -E "versionName|firstInstallTime" || echo "  未安装"
    info "② 主题商店进程是否存活"
    "$ADB" shell pidof com.hihonor.android.thememanager 2>/dev/null | tr -d '\r' || echo "  未运行（需打开主题商店触发挂载）"
    info "③ LSPosed 框架内的模块记录"
    "$ADB" shell su -c "ls /data/adb/lspd/config 2>/dev/null; echo '--- modules ---'; ls /data/adb/lspd/modules 2>/dev/null" 2>&1 | tr -d '\r' | head -12
    info "④ 全量日志中含 xposed/lsposed/HonorHook 的行（不按 tag 过滤，按内容匹配）"
    "$ADB" logcat -d -v brief 2>/dev/null | tr -d '\r' \
      | grep -iE "honorhook|lsposed|lspd|xposed" | grep -viE "com\.hihonor|HwTheme|honor\.android" | tail -25
    ;;

  wait-hook)
    require "$ADB"
    info "清空日志后等待模块挂载判据（最长 3 分钟，每 5s 轮询一次）"
    "$ADB" logcat -c
    i=0
    while [ $i -lt 36 ]; do
      out="$("$ADB" shell su -c "grep -h 'HonorHook' /data/adb/lspd/log/modules_*.log 2>/dev/null" 2>&1 | tr -d '\r' || true)"
      if printf '%s' "$out" | grep -q "installed"; then
        info "捕获到挂载日志："
        printf '%s\n' "$out" | tail -40
        exit 0
      fi
      i=$((i + 1))
      sleep 5
    done
    info "3 分钟内未捕获 installed 判据 —— 可能模块未激活 / 作用域未勾选 / 主题商店进程未重启"
    info "当前 HonorHook 相关日志："
    "$ADB" logcat -d -v brief "$LOG_TAG:*" '*:S' 2>/dev/null | tr -d '\r' | tail -20
    ;;

  diag-capture)
    require "$ADB"
    mkdir -p "$LOG_OUT"
    out="$LOG_OUT/diag_$(stamp).log"
    info "收集 [diag] 取证日志 → $out"
    "$ADB" shell su -c "grep -h '\[diag\]' /data/adb/lspd/log/modules_*.log 2>/dev/null" 2>&1 | tr -d '\r' > "$out"
    info "共 $(wc -l < "$out") 行"
    info "按 tag 分组："
    grep -oE "\[diag\]\[[a-z-]+\]" "$out" | sort | uniq -c | sort -rn | head
    ;;

  dexstat)
    require "$APK_RELEASE"
    mkdir -p "$LOG_OUT/dex"
    unzip -o -q "$APK_RELEASE" classes.dex -d "$LOG_OUT/dex"
    DEXDUMP="$(ls "$ANDROID_HOME"/build-tools/*/dexdump* 2>/dev/null | head -1)"
    [ -n "$DEXDUMP" ] || die "未找到 dexdump（build-tools 未安装？）"
    info "release classes.dex 内各类按顶层包统计："
    "$DEXDUMP" "$LOG_OUT/dex/classes.dex" 2>/dev/null \
      | grep -oE "'L[^']+'" | tr -d "'L" \
      | awk -F/ '{print $1}' | sort | uniq -c | sort -rn | head -15
    info "类总数：$("$DEXDUMP" "$LOG_OUT/dex/classes.dex" 2>/dev/null | grep -c "Class descriptor" || true)"
    ;;

  hook-test)
    require "$ADB"
    info "① 清空日志"
    "$ADB" logcat -c
    info "② 强制重启主题商店进程（触发 hook 挂载）"
    "$ADB" shell am force-stop com.hihonor.android.thememanager 2>/dev/null || true
    "$ADB" shell monkey -p com.hihonor.android.thememanager -c android.intent.category.LAUNCHER 1 \
      >/dev/null 2>&1 || true
    sleep 8
    info "③ 模块挂载判据（在 LSPosed 框架日志里，不在 logcat）"
    "$ADB" shell su -c "grep -h 'HonorHook' /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -25" 2>&1 | tr -d '\r'
    ;;

  size)
    for apk in "$APK_DEBUG" "$APK_RELEASE"; do
      [ -f "$apk" ] || continue
      info "$(basename "$apk")：$(ls -lh "$apk" | awk '{print $5}')"
      unzip -l "$apk" 2>/dev/null | awk 'NR>3 && $1!="" {print $1, $4}' | sort -rn | head -8
      echo "  —— 合计（未压缩）: $(unzip -l "$apk" 2>/dev/null | awk '{s+=$1} END {printf "%.0f KB", s/1024}')"
    done
    ;;

  build-release)
    require "$GRADLEW"; require "$JAVA_HOME/bin/jlink"
    (cd "$ROOT/app" && "$GRADLEW" :app:assembleRelease --console=plain)
    ls -lh "$APK_RELEASE"
    ;;

  test)
    require "$GRADLEW"
    info "运行 JVM 单元测试（:app:testDebugUnitTest）"
    (cd "$ROOT/app" && "$GRADLEW" :app:testDebugUnitTest --console=plain)
    ;;

  install)
    require "$ADB"; require "$APK_DEBUG"
    info "先卸载旧包（用户约定：每次安装前必须卸载，避免覆盖安装残留）"
    "$ADB" uninstall top.honorhook 2>&1 | tr -d '\r' || true
    "$ADB" install "$APK_DEBUG" | tr -d '\r'
    info "校验已安装包版本："
    "$ADB" shell dumpsys package top.honorhook 2>/dev/null | tr -d '\r' | grep -m1 versionName || true
    ;;

  install-release)
    require "$ADB"; require "$APK_RELEASE"
    info "先卸载旧包（用户约定：每次安装前必须卸载）"
    "$ADB" uninstall top.honorhook 2>&1 | tr -d '\r' || true
    "$ADB" install "$APK_RELEASE" | tr -d '\r'
    ;;

  enable-module)
    # 重装后 LSPosed 停用模块并发"尚未激活"通知（深链直达模块页）——模拟点击通知+开关全自动重新启用。
    # 通知栏与寄生管理器对 uiautomator 均不可见 ⇒ 全程 screencap + PIL：
    #   通知定位 = 图标列的 LSPosed 深灰蓝像素带 (85,98,105)+白色并存；
    #   开关状态 = 模块页固定位置取色（ON=蓝 74,128,247 / OFF=白底+灰边 230）。
    # 注意：先跑 install-release（触发通知）再跑本命令；deploy-release 已串好。
    require "$ADB"
    # 像素分析用解释器：HONORHOOK_PYTHON 覆盖 > 工程内 venv > PATH（Windows Git Bash 常见 python 而非 python3）
    PYVENV="${HONORHOOK_PYTHON:-}"
    if [ -z "$PYVENV" ]; then
      for c in "$ROOT/.venv/bin/python" "$ROOT/.venv/Scripts/python.exe" \
               "$(command -v python3 || true)" "$(command -v python || true)"; do
        if [ -n "$c" ] && [ -x "$c" ]; then PYVENV="$c"; break; fi
      done
    fi
    [ -n "$PYVENV" ] && [ -x "$PYVENV" ] || die "缺少 python3（enable-module 需 Pillow 做截屏取色）"
    "$PYVENV" -c 'import PIL' 2>/dev/null || die "python3 缺少 Pillow：${PYVENV}（pip3 install pillow）"
    LSP_FOCUS="com.android.shell/org.lsposed.manager"

    SZ=$("$ADB" shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+')
    W=${SZ%x*}; H=${SZ#*x}
    SX=$((W * 1000 / 1272)); SY=$((H * 1000 / 2800))   # 千分比缩放（基准 1272x2800）
    SW_X=$((1054 * SX / 1000)); SW_Y=$((604 * SY / 1000))

    shade_classify() {  # 截屏模块页开关区 → stdout: on / off / other
      "$ADB" exec-out screencap -p > "$LOG_OUT/enable_page.png" 2>/dev/null
      "$PYVENV" - "$LOG_OUT/enable_page.png" "$SW_X" "$SW_Y" << 'PYEOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB')
sx, sy = int(sys.argv[2]), int(sys.argv[3])
blue = gray_edge = white = 0
for dx in range(-40, 41, 10):
    r, g, b = im.getpixel((sx + dx, sy))[:3]
    if abs(r-74) < 40 and abs(g-128) < 40 and abs(b-247) < 40: blue += 1
    elif r > 245 and g > 245 and b > 245: white += 1
    if abs(r-230) < 12 and abs(g-230) < 12 and abs(b-230) < 12: gray_edge += 1
print("on" if blue >= 6 else ("off" if white >= 5 and gray_edge >= 1 else "other"))
PYEOF
    }

    shade_candidates() {  # 展开通知栏 → 截屏 → 输出 LSPosed 图标候选 y（自上而下）
      "$ADB" shell "cmd statusbar expand-notifications" >/dev/null 2>&1
      sleep 2
      "$ADB" exec-out screencap -p > "$LOG_OUT/enable_shade.png" 2>/dev/null
      "$PYVENV" - "$LOG_OUT/enable_shade.png" << 'PYEOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB')
W, H = im.size
px = im.load()
counts = {}
for y in range(300, H, 3):
    slate = white = 0
    for x in range(80, 250, 4):   # 通知图标列
        r, g, b = px[x, y]
        if abs(r-85) < 25 and abs(g-98) < 25 and abs(b-105) < 25: slate += 1
        elif r > 240 and g > 240 and b > 240: white += 1
    if slate >= 8 and white >= 2:
        counts[y] = True
rows = sorted(counts)
bands = []
for y in rows:
    if bands and y - bands[-1][1] <= 40: bands[-1][1] = y
    else: bands.append([y, y])
print(" ".join(str((a + b) // 2) for a, b in bands))
PYEOF
    }

    info "展开通知栏，扫描 LSPosed 通知…"
    BANDS=$(shade_candidates)
    [ -n "$BANDS" ] || die "通知栏里没找到 LSPosed 通知（先 install-release 重装触发）"
    info "候选: $BANDS"

    done_flag=0
    for y in $BANDS; do
      "$ADB" shell "input tap $((W / 2)) $y" 2>/dev/null
      sleep 3
      FOCUS=$("$ADB" shell "dumpsys window | grep mCurrentFocus" 2>/dev/null | tr -d '\r')
      case "$FOCUS" in
        *"$LSP_FOCUS"*) ;;
        *NotificationShade*) "$ADB" shell "cmd statusbar expand-notifications" >/dev/null 2>&1; sleep 1; continue ;;
        *) "$ADB" shell "input keyevent 4" >/dev/null 2>&1; "$ADB" shell "cmd statusbar expand-notifications" >/dev/null 2>&1; sleep 2; continue ;;
      esac
      # 管理器已打开：判别落在哪个页
      STATE=$(shade_classify)
      case "$STATE" in
        on)  info "模块页已打开且模块为启用状态"; done_flag=1; break ;;
        off) info "模块页已打开（开关 OFF），点击启用…"
             "$ADB" shell "input tap $SW_X $SW_Y"; sleep 2
             STATE2=$(shade_classify)
             [ "$STATE2" = "on" ] || die "点击开关后未变蓝（截屏 $LOG_OUT/enable_page.png 人工核对）"
             info "模块已重新启用 ✓"; done_flag=1; break ;;
        *)   info "落点非模块页（管理器首页），返回重试下一候选"
             "$ADB" shell "input keyevent 4" >/dev/null 2>&1
             "$ADB" shell "cmd statusbar expand-notifications" >/dev/null 2>&1; sleep 2 ;;
      esac
    done
    [ "$done_flag" = 1 ] || die "未能进入模块页完成启用（通知可能失效，重跑 install-release 再试）"
    "$ADB" shell "cmd statusbar collapse" >/dev/null 2>&1
    # 作用域进程**全部**强停（hook 必须强停重启才生效），下次拉起即带 hook。
    # 必须与 META-INF/xposed/scope.list 的 6 个包一一对应，漏一个就会出现
    # "部署报成功但该目标 hook 没生效"的假成功。
    for pkg in com.hihonor.android.thememanager com.android.packageinstaller com.android.settings \
               com.android.systemui com.hihonor.android.launcher com.exam8.chengrenGK; do
      "$ADB" shell "am force-stop $pkg" >/dev/null 2>&1
    done
    info "作用域进程已强停（scope.list 全部 6 个包）"
    ;;

  deploy-release)
    # 全流程一键：编译 → 卸载重装（触发 LSPosed 停用）→ 自动重新启用 → 清作用域进程
    "$0" build-release
    "$0" install-release
    sleep 4   # 等 LSPosed 发"尚未激活"通知
    "$0" enable-module
    ;;

  devices)
    require "$ADB"
    "$ADB" start-server >/dev/null 2>&1 || true
    "$ADB" devices -l | tr -d '\r'
    ;;

  launch)
    require "$ADB"
    info "冷启动 top.honorhook（明确 LAUNCHER intent，不用 monkey 避免干扰）"
    "$ADB" shell am force-stop top.honorhook 2>/dev/null || true
    "$ADB" shell am start -a android.intent.action.MAIN \
      -c android.intent.category.LAUNCHER -n top.honorhook/.ui.MainActivity 2>&1 | tr -d '\r'
    sleep 3
    "$0" top
    ;;

  top)
    require "$ADB"
    info "当前前台/栈顶 Activity："
    "$ADB" shell dumpsys activity activities 2>/dev/null | tr -d '\r' \
      | grep -m8 -E "mResumedActivity|topResumedActivity|^ *Hist " || echo "（未取到栈顶，可能 Activity 未存活）"
    info "包存活状态："
    "$ADB" shell dumpsys activity processes 2>/dev/null | tr -d '\r' \
      | grep -A2 -m1 "top.honorhook" || true
    ;;

  log)
    require "$ADB"
    info "实时日志（Ctrl+C 退出）"
    "$ADB" logcat -v brief 'AndroidRuntime:E' "$LOG_TAG:*" '*:S' | tr -d '\r'
    ;;

  log-hook)
    require "$ADB"
    "$ADB" logcat -v brief "$LOG_TAG:*" '*:S' | tr -d '\r'
    ;;

  logcrash)
    require "$ADB"
    info "崩溃缓冲区（-b crash）：先看异常原因头部"
    "$ADB" logcat -d -b crash -v threadtime 2>/dev/null | tr -d '\r' | head -45
    ;;

  crash|diag-start)
    require "$ADB"
    info "① 清空设备日志缓冲"
    "$ADB" logcat -c
    info "② 冷启动 top.honorhook"
    "$ADB" shell am force-stop top.honorhook 2>/dev/null || true
    "$ADB" shell monkey -p top.honorhook -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
      "$ADB" shell am start -n top.honorhook/.ui.MainActivity >/dev/null 2>&1 || true
    sleep 5
    info "③ 进程存活检查"
    pid="$("$ADB" shell pidof top.honorhook 2>/dev/null | tr -d '\r' || true)"
    [ -n "$pid" ] && echo "进程存活 pid=$pid" || echo "进程不存在（已崩溃或退后台）"
    info "④ 本次启动日志（崩溃/异常优先）"
    "$ADB" logcat -d -v threadtime 2>/dev/null | tr -d '\r' \
      | grep -E "FATAL|AndroidRuntime|top\.honorhook|Exception|Error caused|am_proc_died|ActivityManager: (Force|Process)" \
      | tail -50
    ;;

  logcat)
    require "$ADB"
    mkdir -p "$LOG_OUT"
    local_out="$LOG_OUT/logcat_$(stamp).txt"
    "$ADB" logcat -d -v threadtime 2>/dev/null | tr -d '\r' > "$local_out"
    info "已落盘: $local_out"
    tail -20 "$local_out"
    ;;

  clear-log)
    require "$ADB"
    "$ADB" logcat -c
    info "设备日志缓冲已清空"
    ;;

  shot)
    require "$ADB"
    mkdir -p "$LOG_OUT"
    out="$LOG_OUT/shot_$(stamp).png"
    "$ADB" exec-out screencap -p > "$out"
    info "截图已落盘: $out"
    ;;

  ui)
    require "$ADB"
    mkdir -p "$LOG_OUT"
    out="$LOG_OUT/ui_$(stamp).xml"
    # MSYS_NO_PATHCONV：Git Bash 会把 /sdcard/... 误转为 Windows 路径
    MSYS_NO_PATHCONV=1 "$ADB" shell rm -f /sdcard/ui_dump.xml >/dev/null 2>&1 || true
    MSYS_NO_PATHCONV=1 "$ADB" shell uiautomator dump /sdcard/ui_dump.xml 2>&1 | tr -d '\r' || true
    pull_dst="$out"   # Git Bash 需转 Windows 路径；macOS 直接用原路径
    if command -v cygpath >/dev/null 2>&1; then pull_dst="$(cygpath -w "$out")"; fi
    MSYS_NO_PATHCONV=1 "$ADB" pull /sdcard/ui_dump.xml "$pull_dst" 2>&1 | tr -d '\r' | tail -1 || true
    if [ ! -s "$out" ]; then
      info "UI dump 失败（文件为空）——检查屏幕是否亮屏/解锁"
      exit 1
    fi
    info "UI 层级已落盘: $out"
    # 打印含文本/描述的非空节点（text/desc/resource-id/bounds），便于定位 UI 元素
    tr '>' '>\n' < "$out" | grep -E "text=\"[^\"]+\"|content-desc=\"[^\"]+\"" | sed 's/^ *//' | head -120
    ;;

  viewdump)
    require "$ADB"
    info "当前焦点窗口 / 前台 Activity"
    "$ADB" shell dumpsys window 2>/dev/null | tr -d '\r' \
      | grep -m4 -E "mCurrentFocus|mFocusedApp" || true
    info "屏幕/锁屏状态"
    "$ADB" shell dumpsys power 2>/dev/null | tr -d '\r' | grep -m2 -E "mWakefulness|Display Power" || true
    "$ADB" shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 -E "mDreamingLockscreen|isStatusBarKeyguard" || true
    ;;

  launch-theme)
    require "$ADB"
    info "冷启动主题商店（宿主，触发 hook）"
    "$ADB" shell am force-stop com.hihonor.android.thememanager 2>/dev/null || true
    "$ADB" shell monkey -p com.hihonor.android.thememanager -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 5
    "$0" viewdump
    ;;

  tap)
    require "$ADB"
    [ $# -ge 3 ] || die "用法: dev.sh tap <x> <y>"
    "$ADB" shell input tap "$2" "$3"
    info "已点击 ($2,$3)"
    ;;

  swipe)
    require "$ADB"
    [ $# -ge 5 ] || die "用法: dev.sh swipe <x1> <y1> <x2> <y2> [duration_ms]"
    "$ADB" shell input swipe "$2" "$3" "$4" "$5" "${6:-300}"
    info "已滑动 ($2,$3)->($4,$5)"
    ;;

  text)
    require "$ADB"
    [ $# -ge 2 ] || die "用法: dev.sh text <要输入的文本>"
    "$ADB" shell input text "$2"
    info "已输入: $2"
    ;;

  key)
    require "$ADB"
    [ $# -ge 2 ] || die "用法: dev.sh key <keycode，如 4=返回 3=Home>"
    "$ADB" shell input keyevent "$2"
    info "已发送 keyevent $2"
    ;;

  modlog)
    require "$ADB"
    pat="${2:-HonorHook}"
    lines="${3:-60}"
    info "LSPosed 框架日志（模块 log 输出落点）中匹配 $pat 的最后 $lines 行"
    "$ADB" shell su -c "grep -h '$pat' /data/adb/lspd/log/modules_*.log 2>/dev/null" 2>&1 | tr -d '\r' | tail -"$lines"
    ;;

  grep-log)
    require "$ADB"
    [ $# -ge 2 ] || die "用法: dev.sh grep-log <正则>（在当前 logcat 缓冲里按 tag/内容匹配）"
    "$ADB" logcat -d -v brief 2>/dev/null | tr -d '\r' | grep -E "$2" | tail -40
    ;;

  grep-logd)
    require "$ADB"
    [ $# -ge 2 ] || die "用法: dev.sh grep-logd <正则>（全 buffer dump 后匹配，含 -b all 缓冲）"
    "$ADB" logcat -d -b all -v brief 2>/dev/null | tr -d '\r' | grep -E "$2" | tail -60
    ;;

  log-file)
    require "$ADB"
    mkdir -p "$LOG_OUT"
    out="$LOG_OUT/honorhook_$(stamp).log"
    "$ADB" shell su -c "cat /data/user/0/top.honorhook/files/honorhook.log" 2>/dev/null | tr -d '\r' > "$out" || true
    if [ -s "$out" ]; then info "已落盘: $out"; cat "$out"; else
      info "未在应用私有目录取到（日志文件由 XposedService.openRemoteFile 提供，走 App 内日志页查看）"
    fi
    ;;

  scopes)
    cat "$ROOT/app/app/src/main/resources/META-INF/xposed/scope.list"
    ;;

  targets)
    grep -rn "register(" "$ROOT/app/app/src/main/kotlin/top/honorhook/xposed/target/TargetRegistry.kt" \
      | grep -v "fun register"
    ;;

  verify)
    require "$ADB"
    "$0" build
    "$0" install
    "$0" enable-module
    "$0" launch
    sleep 3
    info "启动后判据日志（LSPosed 模块日志，非 logcat）："
    "$ADB" shell "cat /data/adb/lspd/log/modules_*.log 2>/dev/null | grep -E 'installed [0-9]+/[0-9]+' | tail -20" 2>/dev/null || \
      warn "未找到 LSPosed 模块日志，确认模块已启用且目标进程已启动"
    ;;

  clean)
    (cd "$ROOT/app" && "$GRADLEW" :app:clean --console=plain)
    ;;

  env)
    info "工具链解析结果（别在会话里问路径，跑这个）"
    printf '  %-12s %s\n' JAVA_HOME "$JAVA_HOME"
    printf '  %-12s %s\n' java "$JAVA_HOME/bin/java"
    printf '  %-12s %s\n' ADB "$ADB"
    printf '  %-12s %s\n' ANDROID_HOME "$ANDROID_HOME"
    printf '  %-12s %s\n' aapt2 "${AAPT2:-MISSING}"
    printf '  %-12s %s\n' apktool "$APKTOOL"
    printf '  %-12s %s\n' jadx "${JADX:-MISSING}"
    printf '  %-12s %s\n' python3 "$(command -v python3 || echo MISSING)"
    printf '  %-12s %s\n' gradlew "$GRADLEW"
    info "按 PATH 解析到的（本项目垫片 + SDK build-tools 已注入）"
    for t in apktool jadx aapt2 apksigner zipalign dexdump java adb; do
      printf '  %-12s %s\n' "$t" "$(command -v "$t" 2>/dev/null || echo MISSING)"
    done
    ;;

  decompile)
    # 反编译产物落 system-files/<名称>-apktool（smali + 明文 AndroidManifest + res）
    require "$APKTOOL"; require "$JAVA_HOME/bin/java"
    [ -n "${2:-}" ] || die "用法: tools/dev.sh decompile <apk> [名称]（默认取 apk 文件名）"
    apk="$2"; name="${3:-$(basename "$apk" .apk)}"
    out="$ROOT/system-files/$name-apktool"
    info "apktool 反编译 → $out"
    "$JAVA_HOME/bin/java" -jar "$APKTOOL" d -f "$apk" -o "$out"
    info "完成（smali/ 在 $out/smali，META-INF 在 $out/unknown/META-INF）"
    ;;

  jadx)
    [ -n "$JADX" ] || die "未找到 jadx（tools/jadx/bin/jadx）"
    [ -n "${2:-}" ] || die "用法: tools/dev.sh jadx <apk> [名称]"
    apk="$2"; name="${3:-$(basename "$apk" .apk)}"
    out="$ROOT/system-files/$name-jadx"
    info "jadx（--show-bad-code）→ $out"
    JAVA_OPTS="${JAVA_OPTS:-}" "$JADX" --show-bad-code -d "$out" "$apk"
    ;;

  aapt2)
    [ -n "$AAPT2" ] || die "未找到 aapt2（tools/android-sdk/build-tools/*/aapt2）"
    if [ $# -gt 1 ]; then shift; fi
    "$AAPT2" "$@"
    ;;

  exec)
    # 带本项目工具链环境执行任意命令（PATH/JAVA_HOME/ANDROID_HOME 已注入）
    if [ $# -gt 1 ]; then shift; fi
    [ $# -gt 0 ] || die "用法: tools/dev.sh exec <命令...>（例如 exec python3 <脚本> --json）"
    exec "$@"
    ;;

  apkinfo)
    [ -n "$AAPT2" ] || die "未找到 aapt2（tools/android-sdk/build-tools/*/aapt2）"
    [ -n "${2:-}" ] || die "用法: tools/dev.sh apkinfo <apk>"
    "$AAPT2" dump badging "$2" | head -14
    ;;

  *)
    die "未知子命令: $1（用 tools/dev.sh help 查看）"
    ;;
esac
