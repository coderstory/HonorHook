# dev.sh Windows 适配层（Git-Bash / MSYS2 / Cygwin）
# 本文件只做「解析」，不改全局：约定的输出变量见 dev.sh 头部契约注释。

# ---- JDK：项目内便携版优先（原 Windows 硬编码路径已随 macOS 迁移移除，保留便携版口径）----
# 便携版缺失 → 探测系统 JDK（须含 bin/jlink，release 打包依赖）
if [ -d "$ROOT/tools/jdk-21" ]; then
  export JAVA_HOME="$ROOT/tools/jdk-21"
else
  for _cand in "${SYSTEM_JDK_HOME:-}" "/d/software/jdk-21" "/c/Program Files/Java/jdk-21" "/c/Program Files/Eclipse Adoptium/jdk-21"; do
    if [ -n "$_cand" ] && [ -x "$_cand/bin/jlink.exe" ]; then
      export JAVA_HOME="$_cand"
      break
    fi
  done
  [ -d "$JAVA_HOME" ] || export JAVA_HOME="$ROOT/tools/jdk-21"
fi

# ---- Android SDK：项目内便携版优先，均缺失保留原路径由 require 报出 ----
if [ -d "$ROOT/tools/android-sdk/platforms" ]; then
  ANDROID_SDK_LOCAL="$ROOT/tools/android-sdk"
else
  ANDROID_SDK_LOCAL="$ROOT/tools/android-sdk"
fi

if [ -x "$ROOT/tools/platform-tools/adb.exe" ]; then
  ADB="$ROOT/tools/platform-tools/adb.exe"
else
  ADB="$(command -v adb.exe || command -v adb || echo adb)"
fi

if [ -f "$ROOT/tools/jadx/bin/jadx.bat" ]; then
  JADX="$ROOT/tools/jadx/bin/jadx.bat"             # Windows bundle
fi
# AAPT2 留空：dev.sh 公共段扫 build-tools（含 aapt2.exe）

# 平台附加：Windows 侧无 on-device 限流需求，jadx 参数交由默认
PLATFORM_JADX_JAVA_OPTS=""
PLATFORM_JADX_ARGS=""
PLATFORM_PATH_PREPEND=""
