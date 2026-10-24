# dev.sh Linux/Termux 适配层（含 DSH on-device：Termux 编译链 + 包管理器工具）
# 本文件只做「解析」，不改全局：约定的输出变量见 dev.sh 头部契约注释。
# 平台判定：$PREFIX 指向 Termux 系前缀（com.termux / com.dsharnessmobile.shell）= on-device 环境。

_IS_TERMUX=0
case "${PREFIX:-}" in
  */com.termux|*/com.dsharnessmobile.shell|*/com.termux/*|*/com.dsharnessmobile.shell/*) _IS_TERMUX=1 ;;
esac

# ---- JDK：项目内便携版优先 → Termux openjdk-21（$PREFIX/lib/jvm）→ 便携路径兜底 ----
if [ -x "$ROOT/tools/jdk-21/bin/jlink" ]; then
  export JAVA_HOME="$ROOT/tools/jdk-21"
elif [ -x "${PREFIX:-/data/user/0/com.dsharnessmobile.shell/files/usr}/lib/jvm/java-21-openjdk/bin/jlink" ]; then
  export JAVA_HOME="${PREFIX:-/data/user/0/com.dsharnessmobile.shell/files/usr}/lib/jvm/java-21-openjdk"   # Termux/DSH 包管理器装的 JDK
else
  export JAVA_HOME="$ROOT/tools/jdk-21"   # 均缺失：保留原路径，由 require 报出明确错误
fi

# ---- Android SDK：项目内便携版优先；Linux 桌面机再认 /usr/local/share 兜底 ----
if [ -d "$ROOT/tools/android-sdk/platforms" ]; then
  ANDROID_SDK_LOCAL="$ROOT/tools/android-sdk"
elif [ -d /usr/local/share/android-sdk/platforms ]; then
  ANDROID_SDK_LOCAL=/usr/local/share/android-sdk
else
  ANDROID_SDK_LOCAL="$ROOT/tools/android-sdk"
fi

if [ -x "$ROOT/tools/platform-tools/adb" ]; then
  ADB="$ROOT/tools/platform-tools/adb"
else
  ADB="$(command -v adb || echo adb)"     # Termux android-tools / 发行版 adb
fi

if [ -x "$ROOT/tools/jadx/bin/jadx" ]; then
  JADX="$ROOT/tools/jadx/bin/jadx"                 # Linux bundle
elif [ -x "${PREFIX:-/data/user/0/com.dsharnessmobile.shell/files/usr}/opt/jadx/bin/jadx" ]; then
  JADX="${PREFIX:-/data/user/0/com.dsharnessmobile.shell/files/usr}/opt/jadx/bin/jadx"   # Termux/DSH: 装在 $PREFIX/opt
fi
# AAPT2 留空：dev.sh 公共段先扫 build-tools，再回落 `command -v aapt2`（Termux aapt 包）

if [ "$_IS_TERMUX" = "1" ]; then
  # ---- on-device 资源封顶（2026-10-23：真机 16G 不够 jadx 挥霍，曾 97% 时被 LMK 杀）----
  PLATFORM_JADX_JAVA_OPTS="-Xmx2g"
  PLATFORM_JADX_ARGS="-j 1"

  # ---- Termux 编译的 JDK 硬编码路径修正（两处都指向 com.termux，本环境不存在）----
  # ① java.io.tmpdir → /data/data/com.termux/files/usr/tmp
  # ② user.home      → /data/data/com.termux/files/home（jadx 初始化 $HOME/.cache 时直接炸）
  # → 用 JAVA_TOOL_OPTIONS 覆盖到本环境真实路径（2026-10-23）
  _tmpd="${TMPDIR:-$PREFIX/tmp}"
  mkdir -p "$_tmpd" 2>/dev/null || true
  export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$_tmpd -Duser.home=${HOME:-$PREFIX/home} ${JAVA_TOOL_OPTIONS:-}"
else
  PLATFORM_JADX_JAVA_OPTS=""
  PLATFORM_JADX_ARGS=""
fi
PLATFORM_PATH_PREPEND=""
