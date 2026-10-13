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
# warn 走 stderr 不退出：诊断类子命令「查无结果」属正常分支，不能像 die 一样杀脚本
warn() { echo "WARN: $*" >&2; }
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
  enable-module    重装后重新启用 LSPosed 模块（lspctl CLI 通道，含作用域进程强停）
  deploy-release   一键全流程：build-release + install-release + enable-module
  connect <ip[:port]> 无线 adb 连接（省端口默认 5555）
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
  relaunch <pkg>   强停指定包并冷启动，随后抓 installed 判据（验证指定宿主的挂载）
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
  pull-apk <包名>          从设备拉取指定包的 APK（含 split）到 system-files/<包名>/
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
      | grep -iE "honorhook|error|fail|module|scope|init" | tail -30 || true
    ;;

  log-grep)
    require "$ADB"
    info "全量缓冲中按内容匹配 HonorHook / lspd / LSPosed（无视 tag）"
    "$ADB" logcat -d -v brief 2>/dev/null | tr -d '\r' \
      | grep -iE "honorhook|lspd|lsposed" | grep -viE "com\.hihonor" | tail -40 || true
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
    "$ADB" shell dumpsys package io.github.coderstory.honorhook 2>/dev/null | tr -d '\r' | grep -m2 -E "versionName|firstInstallTime" || echo "  未安装"
    info "② 主题商店进程是否存活"
    "$ADB" shell pidof com.hihonor.android.thememanager 2>/dev/null | tr -d '\r' || echo "  未运行（需打开主题商店触发挂载）"
    info "③ LSPosed 框架内的模块记录"
    "$ADB" shell su -c "ls /data/adb/lspd/config 2>/dev/null; echo '--- modules ---'; ls /data/adb/lspd/modules 2>/dev/null" 2>&1 | tr -d '\r' | head -12
    info "④ 全量日志中含 xposed/lsposed/HonorHook 的行（不按 tag 过滤，按内容匹配）"
    "$ADB" logcat -d -v brief 2>/dev/null | tr -d '\r' \
      | grep -iE "honorhook|lsposed|lspd|xposed" | grep -viE "com\.hihonor|HwTheme|honor\.android" | tail -25 || true
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
    grep -oE "\[diag\]\[[a-z-]+\]" "$out" | sort | uniq -c | sort -rn | head || true
    ;;

  dexstat)
    require "$APK_RELEASE"
    mkdir -p "$LOG_OUT/dex"
    unzip -o -q "$APK_RELEASE" classes.dex -d "$LOG_OUT/dex"
    DEXDUMP=""
    # 与 aapt2 同式：遍历 glob 取版本最高一份（ls | head -1 会取到最低版本，d9）
    for c in "$ANDROID_HOME"/build-tools/*/dexdump "$ANDROID_HOME"/build-tools/*/dexdump.exe; do
      if [ -x "$c" ]; then DEXDUMP="$c"; fi
    done
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

  relaunch)
    require "$ADB"
    [ -n "$2" ] || die "用法：tools/dev.sh relaunch <包名>"
    pkg="$2"
    info "① 强停 ${pkg} 并冷启动（触发 hook 挂载）"
    "$ADB" shell am force-stop "$pkg" 2>/dev/null || true
    "$ADB" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 8
    info "② 挂载判据（LSPosed 框架日志，过滤 ${pkg}）："
    "$ADB" shell su -c "grep -h 'HonorHook' /data/adb/lspd/log/modules_*.log 2>/dev/null | grep \"$pkg\" | tail -30" 2>&1 | tr -d '\r'
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
    "$ADB" uninstall io.github.coderstory.honorhook 2>&1 | tr -d '\r' || true
    "$ADB" install "$APK_DEBUG" | tr -d '\r'
    info "校验已安装包版本："
    "$ADB" shell dumpsys package io.github.coderstory.honorhook 2>/dev/null | tr -d '\r' | grep -m1 versionName || true
    ;;

  install-release)
    require "$ADB"; require "$APK_RELEASE"
    info "先卸载旧包（用户约定：每次安装前必须卸载）"
    "$ADB" uninstall io.github.coderstory.honorhook 2>&1 | tr -d '\r' || true
    # -g：设备侧确认弹窗会以 INSTALL_FAILED_ABORTED 打断纯 adb install（2026-10-11 实证）
    "$ADB" install -g "$APK_RELEASE" | tr -d '\r'
    info "校验已安装包版本："
    "$ADB" shell dumpsys package io.github.coderstory.honorhook 2>/dev/null | tr -d '\r' | grep -m1 versionName || true
    ;;

  enable-module)
    # 重装后重新启用模块 + 强停作用域进程（hook 必须强停重启才生效）。
    # 走 LSPosed 官方 CLI lspctl（/data/adb/modules/zygisk_lsposed/lspctl，2.2+ 自带），
    # 不再模拟点击——旧实现截屏取色+点通知，扰动通知栏/热点开关且依赖 Pillow，脆弱。
    LSPCTL="/data/adb/modules/zygisk_lsposed/lspctl"
    require "$ADB"
    "$ADB" shell "su 0 $LSPCTL module enable --user=0 io.github.coderstory.honorhook" 2>&1 | tr -d '\r'
    "$ADB" shell "su 0 $LSPCTL module show io.github.coderstory.honorhook" 2>&1 | tr -d '\r' | grep -E 'User'
    # 包名清单直接读 scope.list（单一事实源，跳过空行/注释行）——漏强停的宿主会出现
    # "部署报成功但该目标 hook 没生效"的假成功。
    # scope set 全量替换：卸载重装会抹掉作用域绑定（2026-10-13 实证，静态 scope 不会自动恢复），
    # 每次启用按 scope.list 重铺 = 幂等自愈。先按「本机已安装」过滤——lspctl scope set 遇到
    # 未安装包会整体报错（scope.list 里的包不必都装在当前设备）。
    SCOPE_PKGS="$(tr -d '\r' < "$ROOT/app/app/src/main/resources/META-INF/xposed/scope.list" | grep -vE '^[[:space:]]*(#|$)' || true)"
    [ -n "$SCOPE_PKGS" ] || die "scope.list 读取为空（$ROOT/app/app/src/main/resources/META-INF/xposed/scope.list）"
    INSTALLED="$("$ADB" shell pm list packages --user 0 2>/dev/null | tr -d '\r' | sed 's/^package://' | sort)"
    VALID="$(comm -12 <(printf '%s\n' "$INSTALLED") <(printf '%s\n' $SCOPE_PKGS | sort))"
    [ -n "$VALID" ] || die "scope.list 与已安装包无交集"
    "$ADB" shell "su 0 $LSPCTL scope set --user=0 io.github.coderstory.honorhook $(printf '%s ' $VALID)" 2>&1 | tr -d '\r' | tail -1
    for pkg in $VALID; do
      "$ADB" shell "am force-stop $pkg" >/dev/null 2>&1
    done
    info "作用域进程已强停（scope.list 全部 $(printf '%s\n' $SCOPE_PKGS | wc -l | tr -d ' ') 个包）"
    ;;

  deploy-release)
    # 全流程一键：编译 → 卸载重装 → lspctl 重新启用 → 清作用域进程
    "$0" build-release
    "$0" install-release
    sleep 4   # 等包管理器落盘（lspctl 直启用，无需等 LSPosed 通知）
    "$0" enable-module
    ;;

  # 无线 adb：dev.sh connect <ip[:port]>（省端口默认 5555）。连上后其余子命令照常（多设备时 adb 需 -s，见 devices 输出）
  connect)
    require "$ADB"
    [ -n "$2" ] || die "用法：tools/dev.sh connect <ip[:port]>"
    target="$2"
    case "$target" in *:*) ;; *) target="${target}:5555" ;; esac
    "$ADB" start-server >/dev/null 2>&1 || true
    info "连接 ${target}"
    "$ADB" connect "$target" | tr -d '\r'
    "$ADB" devices -l | tr -d '\r'
    ;;

  devices)
    require "$ADB"
    "$ADB" start-server >/dev/null 2>&1 || true
    "$ADB" devices -l | tr -d '\r'
    ;;

  # root 直装（su 0 pm install -r）+ 自动点 ROM 安装确认弹窗（荣耀安装器两段式：继续安装 → 确认）。
  # ROM 对 adb/su 安装都弹确认（纯净模式/USB 安装管控），本命令轮询 UI 找确认按钮代点，最多 ~20s。
  install-root)
    require "$ADB"
    APK="app/app/build/outputs/apk/release/app-release.apk"
    [ -f "$APK" ] || die "release APK 不存在，先 build-release"
    # 弹窗按钮解析用解释器：HONORHOOK_PYTHON 覆盖 > 工程 venv > PATH 探测链（d5，原裸 python3 在无 python3 PATH 的机器上挂）
    pick_python() {
      local c
      if [ -n "${HONORHOOK_PYTHON:-}" ] && [ -x "${HONORHOOK_PYTHON}" ]; then printf '%s\n' "$HONORHOOK_PYTHON"; return 0; fi
      for c in "$ROOT/.venv/bin/python" "$ROOT/.venv/Scripts/python.exe" \
               "$(command -v python3 || true)" "$(command -v python || true)"; do
        if [ -n "$c" ] && [ -x "$c" ]; then printf '%s\n' "$c"; return 0; fi
      done
      return 1
    }
    PARSER="$(pick_python)" || die "缺少 python3（install-root 安装弹窗解析需要）"
    "$ADB" push "$APK" /data/local/tmp/honorhook-install.apk >/dev/null 2>&1 || die "push 失败"
    "$ADB" shell "nohup su 0 sh -c 'pm install -r /data/local/tmp/honorhook-install.apk' >/dev/null 2>&1 &"
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18; do
      sleep 1
      # uiautomator dump 走文件再 cat（/dev/tty 直排在安装弹窗上常返回空，2026-10-12 实证）
      "$ADB" shell uiautomator dump /sdcard/hh_dlg.xml >/dev/null 2>&1
      XY=$("$ADB" shell cat /sdcard/hh_dlg.xml 2>/dev/null | tr -d '\r' | "$PARSER" -c '
import sys, re
xml = sys.stdin.read()
for m in re.finditer(r"text=\"([^\"]+)\"[^>]*clickable=\"true\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"", xml):
    t = m.group(1)
    if t in ("继续", "继续安装", "仍要安装", "确定", "安装", "允许"):
        x1, y1, x2, y2 = (int(m.group(i)) for i in range(2, 6))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break')
      if [ -n "$XY" ]; then
        info "点安装确认: $XY（第 $i 轮）"
        "$ADB" shell input tap $XY >/dev/null 2>&1
      fi
    done
    "$ADB" shell dumpsys package io.github.coderstory.honorhook 2>/dev/null | tr -d '\r' | grep -m1 lastUpdateTime || echo "  未安装"
    "$ADB" shell rm -f /data/local/tmp/honorhook-install.apk >/dev/null 2>&1
    ;;

  # 重启作用域进程：root kill（TERM，干净，无 crash 记录不计 LSPosed 安全模式频次）——
  # 与模块 App「强制重启作用域应用」同机制。用法：restart-scope [pkg...]（缺省 = scope.list 全部）。
  # 进程被 kill 后由系统自动拉起（persistent）。禁用 am crash（用户 2026-10-12 永久禁令）。
  restart-scope)
    require "$ADB"
    pkgs="${*:-$(tr -d '\r' < "$ROOT/app/app/src/main/resources/META-INF/xposed/scope.list" | tr '\n' ' ')}"
    for p in $pkgs; do
      # pidof 无匹配时设备侧 exit 1 → 管道非零：|| true 防 pipefail 杀脚本
      pids=$("$ADB" shell "su 0 pidof $p" 2>/dev/null | tr -d '\r' | tr ' ' '\n' || true)
      if [ -n "$pids" ]; then
        # pidof 可能返回多个 pid（多进程宿主）——全部杀，只杀首个杀不干净
        for pid in $pids; do
          "$ADB" shell "su 0 kill $pid" >/dev/null 2>&1 || true
        done
        info "$p (pid $(echo "$pids" | tr '\n' ' ')) 已 TERM，等系统拉起"
      else
        info "$p 未在运行"
      fi
    done
    ;;

  # 判据金标准：直接 grep LSPosed 框架日志文件（modlog 的 60 行窗口会漏早期判据行，2026-10-12 教训）
  modlog-f)
    TAG="${2:-HonorHook}"   # $1 = 子命令名（dispatch 无 shift），tag 落在 $2
    # tag 进 su -c 双引号内层 grep：先校验字符集，杜绝引号/分号注入碎 shell（d3）
    [[ "$TAG" =~ ^[A-Za-z0-9_.:-]+$ ]] || die "tag 仅限字母/数字/._:-（防注入碎 shell）"
    require "$ADB"
    "$ADB" shell "su 0 grep -h $TAG /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -30" 2>&1 | tr -d '\r'
    ;;

  # LSPosed 官方 CLI 启用/查询模块（enable-module = 本命令 + 作用域进程强停）。
  # 依赖设备端 /data/adb/modules/zygisk_lsposed/lspctl（LSPosed 2.2+ 自带）。scope 是 static（随 APK
  # scope.list 自动生效），无需手动同步；重装（install -r）不清启用态，只有卸载重装才需要本命令。
  enable-cli)
    LSPCTL="/data/adb/modules/zygisk_lsposed/lspctl"
    require "$ADB"
    "$ADB" shell "su 0 $LSPCTL module enable --user=0 io.github.coderstory.honorhook" 2>&1 | tr -d '\r'
    "$ADB" shell "su 0 $LSPCTL module show io.github.coderstory.honorhook" 2>&1 | tr -d '\r' | grep -E 'User'
    ;;

  # LSPosed CLI 查询：模块启用态与作用域（只读）
  modctl)
    LSPCTL="/data/adb/modules/zygisk_lsposed/lspctl"
    require "$ADB"
    "$ADB" shell "su 0 $LSPCTL module show io.github.coderstory.honorhook" 2>&1 | tr -d '\r'
    "$ADB" shell "su 0 $LSPCTL scope list io.github.coderstory.honorhook" 2>&1 | tr -d '\r' | head -20
    ;;

  launch)
    require "$ADB"
    info "冷启动 io.github.coderstory.honorhook（明确 LAUNCHER intent，不用 monkey 避免干扰）"
    "$ADB" shell am force-stop io.github.coderstory.honorhook 2>/dev/null || true
    "$ADB" shell am start -a android.intent.action.MAIN \
      -c android.intent.category.LAUNCHER -n io.github.coderstory.honorhook/.ui.MainActivity 2>&1 | tr -d '\r'
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
      | grep -A2 -m1 "io.github.coderstory.honorhook" || true
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
    info "② 冷启动 io.github.coderstory.honorhook"
    "$ADB" shell am force-stop io.github.coderstory.honorhook 2>/dev/null || true
    "$ADB" shell monkey -p io.github.coderstory.honorhook -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
      "$ADB" shell am start -n io.github.coderstory.honorhook/.ui.MainActivity >/dev/null 2>&1 || true
    sleep 5
    info "③ 进程存活检查"
    pid="$("$ADB" shell pidof io.github.coderstory.honorhook 2>/dev/null | tr -d '\r' || true)"
    [ -n "$pid" ] && echo "进程存活 pid=$pid" || echo "进程不存在（已崩溃或退后台）"
    info "④ 本次启动日志（崩溃/异常优先）"
    "$ADB" logcat -d -v threadtime 2>/dev/null | tr -d '\r' \
      | grep -E "FATAL|AndroidRuntime|io\.github\.coderstory\.honorhook|Exception|Error caused|am_proc_died|ActivityManager: (Force|Process)" \
      | tail -50 || true
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
    # adb input text 不支持空格（需 %s）与非 ASCII（中文进不去）——提示后仍原样执行（d6）
    if [[ "$2" == *" "* ]] || printf '%s' "$2" | LC_ALL=C grep -q '[^ -~]'; then
      info "input text 不支持空格/中文，空格用 %s"
    fi
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
    # pat 进 su -c 单引号内层 grep：先校验字符集，杜绝引号注入碎 shell（d3）
    [[ "$pat" =~ ^[A-Za-z0-9_.:-]+$ ]] || die "tag 仅限字母/数字/._:-（防注入碎 shell）"
    lines="${3:-60}"
    info "LSPosed 框架日志（模块 log 输出落点）中匹配 $pat 的最后 $lines 行"
    "$ADB" shell su -c "grep -h '$pat' /data/adb/lspd/log/modules_*.log 2>/dev/null" 2>&1 | tr -d '\r' | tail -"$lines" || true
    ;;

  grep-log)
    require "$ADB"
    [ $# -ge 2 ] || die "用法: dev.sh grep-log <正则>（在当前 logcat 缓冲里按 tag/内容匹配）"
    "$ADB" logcat -d -v brief 2>/dev/null | tr -d '\r' | grep -E "$2" | tail -40 || true
    ;;

  grep-logd)
    require "$ADB"
    [ $# -ge 2 ] || die "用法: dev.sh grep-logd <正则>（全 buffer dump 后匹配，含 -b all 缓冲）"
    "$ADB" logcat -d -b all -v brief 2>/dev/null | tr -d '\r' | grep -E "$2" | tail -60 || true
    ;;

  log-file)
    require "$ADB"
    mkdir -p "$LOG_OUT"
    out="$LOG_OUT/honorhook_$(stamp).log"
    "$ADB" shell su -c "cat /data/user/0/io.github.coderstory.honorhook/files/honorhook.log" 2>/dev/null | tr -d '\r' > "$out" || true
    if [ -s "$out" ]; then info "已落盘: $out"; cat "$out"; else
      info "未在应用私有目录取到（日志文件由 XposedService.openRemoteFile 提供，走 App 内日志页查看）"
    fi
    ;;

  scopes)
    cat "$ROOT/app/app/src/main/resources/META-INF/xposed/scope.list"
    ;;

  targets)
    grep -rn "register(" "$ROOT/app/app/src/main/kotlin/top/honorhook/xposed/target/TargetRegistry.kt" \
      | grep -v "fun register" || true
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
    # JADX_ARGS = 透传给 jadx CLI 的参数（如 "-j 4 -r --no-res"）。
    # 注意：shim 把 JAVA_OPTS/JADX_OPTS 都喂给 JVM（bin/jadx 注释自证），CLI 参数只能走这里。
    # shellcheck disable=SC2086
    JAVA_OPTS="${JAVA_OPTS:-}" "$JADX" --show-bad-code ${JADX_ARGS:-} -d "$out" "$apk"
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

  pull-apk)
    # 从设备拉取指定包名的 APK（多 split 时全部拉齐）到 system-files/<包名>/（先打 apkinfo 摘要）
    [ -n "${2:-}" ] || die "用法: tools/dev.sh pull-apk <包名>"
    "$ADB" shell pm path "$2" 2>/dev/null | tr -d '\r' | sed 's/^package://' | grep -v '^$' > .buildtmp/pull-apk-paths.txt \
      || die "设备未连接或包不存在: $2"
    [ -s .buildtmp/pull-apk-paths.txt ] || die "设备上未找到包: $2"
    outdir="$ROOT/system-files/$2"
    mkdir -p "$outdir"
    while IFS= read -r apk_path; do
      name="$(basename "$apk_path")"
      echo "pull: $apk_path"
      "$ADB" pull "$apk_path" "$outdir/$name" 2>&1 | tail -1
    done < .buildtmp/pull-apk-paths.txt
    rm -f .buildtmp/pull-apk-paths.txt
    echo "— apkinfo 摘要（base）:"
    [ -f "$outdir/base.apk" ] && "$AAPT2" dump badging "$outdir/base.apk" 2>/dev/null | head -6 || true
    echo "输出目录: $outdir"
    ;;

  *)
    die "未知子命令: $1（用 tools/dev.sh help 查看）"
    ;;
esac
