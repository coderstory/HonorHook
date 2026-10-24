# dev.sh macOS 适配层（brew 工具链解析）
# 本文件只做「解析」，不改全局：约定的输出变量见 dev.sh 头部契约注释。
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

if [ -x "$ROOT/tools/platform-tools/adb" ]; then
  ADB="$ROOT/tools/platform-tools/adb"
else
  ADB="$(command -v adb || echo adb)"     # brew android-platform-tools
fi

if [ -x "$ROOT/tools/jadx/bin/jadx" ]; then
  JADX="$ROOT/tools/jadx/bin/jadx"                 # macOS bundle
fi
# AAPT2 留空：dev.sh 公共段扫 build-tools（macOS 走 SDK build-tools）

# 平台附加：macOS 内存充裕，jadx 不限堆、不限线程（2026-10-23 拆分时定：限流是 on-device 需求）
PLATFORM_JADX_JAVA_OPTS=""
PLATFORM_JADX_ARGS=""
PLATFORM_PATH_PREPEND=""
