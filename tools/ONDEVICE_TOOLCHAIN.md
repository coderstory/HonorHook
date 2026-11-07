# DSH 设备工具链台账（2026-11-06 实测盘点，勿重复下载/安装）

> 本文件是设备侧已装工具的唯一清单。新会话动手前先查这里；缺什么先来这里找，找不到再问用户，最后才考虑安装。

## 已安装（全部就位，别再动）

| 工具 | 位置 | 说明 |
|---|---|---|
| jadx 1.5.1 | `tools/jadx/bin/jadx`（启动器）+ `tools/jadx/lib/`（108 jar，Maven Central 组装） | **经 `bash tools/jadx/bin/jadx` 调用**（/storage noexec，不能直接 exec）；主类 `jadx.cli.JadxCLI`；JAVA_HOME 固定指向 DSH 内置 JDK21；版本快照 `tools/jadx/INSTALL_NOTE.json` |
| jadx 依赖解析工程 | `tools/jadx-mvn/`（build.gradle.kts + settings） | 一次性辅助工程：`collectJadx` task 经 Gradle（阿里云 central/google 镜像）把 jadx-cli 1.5.1 全量 runtime classpath 拷到 `tools/jadx/lib/`。升级 jadx 版本时改版本号重跑；平时不删 |
| apktool | `tools/apktool.jar`（bin 垫片 `tools/bin/apktool`） | JDK21 跑 |
| JDK 21 | `/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-21-openjdk`（21.0.13-1） | `dev.sh env` 会解析；**2026-11-07 由 deb 解包重建**（原「DSH 内置」环境已不存在，见下節实录）；`java`/`javac`/`jlink` 自检通过 |
| Gradle + 依赖缓存 | `tools/gradle-home`（GRADLE_USER_HOME）+ `tools/android-sdk`（SDK，`app/local.properties` 指向） | 构建唯一入口 `tools/dev.sh build`；gradlew 必须经 `bash gradlew` 调（noexec） |
| aapt2 | `/data/data/com.dsharnessmobile.shell/files/usr/bin/aapt2`（2.20-android-16.0.0_r4） | **2026-11-07 从 Termux `aapt2` 包重建**；AGP 经 `tools/gradle-home/gradle.properties` 的 `android.aapt2FromMavenOverride` 指向它（该 properties 已 gitignore）。见下节实录 |
| python3 / bash | DSH Termux 环境（`/data/data/com.dsharnessmobile.shell/files/usr`） | python3.14 |
| MT 管理器 | 包名 `bin.mt.plus` | APK 安装面板（am VIEW intent 路，见 CLAUDE.md §工具清单） |

## 没有 / 不可用（别再尝试这些路）

| 项 | 结论 |
|---|---|
| adb | SDK platform-tools 的是 x86-64 二进制，本机 arm64 跑不了；本机=目标机，无 adb 需求 |
| `pm install` | Shizuku 特权通道危险检查黑名单拦截（权限写面），永不放行 |
| GitHub release 大文件直连 | 本机网络对 `github.com/releases/download` 不稳（两次截断、一次 -C 续传错乱）；**大文件改走 Maven Central / codeload**（均实测通且快） |
| apt/pkg 装 jadx | 依赖损坏（openjdk-21/ccache 冲突），勿再试 |
| `apt-get install` 装任何包 | 源里 deb 是 **com.termux 前缀**（`dpkg-deb -c` 可见 `./data/data/com.termux/files/usr/...`），本机前缀是 `com.dsharnessmobile.shell` ⇒ dpkg 前端直接报 `6 expected programs not found in PATH`（误导性报错，实为前缀/权限不匹配）。**可行路 = 下载后解包搬迁**（见下节） |

## 2026-11-07 JDK 重建实录（环境重置后照抄即可）

**已脚本化：`bash tools/dev.sh env-bootstrap` 一条命令搞定本节 + 下节（`--check` 只体检不下载）。**
下面的命令就是它内部的实现，留着是为了脚本本身也坏了时能手抄。

背景：DSH 重装后 `$PREFIX/lib/jvm` 整目录消失，`dev.sh build` 因 `JAVA_HOME/bin/java` 不存在而失败；`tools/jdk-21` 也从来不存在（`dev-env-linux.sh:11` 的首选分支是死分支，实际命中第二分支 = `$PREFIX/lib/jvm/java-21-openjdk`）。

```bash
# 1) 只下载不安装（apt 的 download 正常，install 会因前缀不匹配失败）
apt-get update
apt-get install -y --no-install-recommends openjdk-21   # 会下到 106MB deb 后失败，属预期
D=$PREFIX/var/cache/apt/archives/openjdk-21_21.0.13-1_aarch64.deb

# 2) 解包 → 把 com.termux 前缀的 jvm 目录搬到本机前缀
dpkg-deb -x "$D" "$PREFIX/tmp/oj21"
mkdir -p "$PREFIX/lib/jvm"
cp -a "$PREFIX/tmp/oj21/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk" "$PREFIX/lib/jvm/"

# 3) libjvm.so 的运行时依赖（同为 apt 缓存里的 deb，解包取 .so 放 $PREFIX/lib）
#    缺 libandroid-shmem.so 时 java 报 "dl failure ... libjvm.so ... libandroid-shmem.so not found"
for p in libandroid-shmem_0.7 libandroid-sysv-semaphore_0.1-1 libandroid-spawn_0.3; do
  dpkg-deb -x "$PREFIX/var/cache/apt/archives/${p}_aarch64.deb" "$PREFIX/tmp/ojlibs/$p"
done
find "$PREFIX/tmp/ojlibs" -name '*.so*' -exec cp -av {} "$PREFIX/lib/" \;
```

- 搬迁后无需改仓库：`tools/dev-env-linux.sh:13` 本来就优先认 `$PREFIX/lib/jvm/java-21-openjdk/bin/jlink`
- 二进制里的 RUNPATH 含 `$ORIGIN:$ORIGIN/../lib`，故搬路径后仍能自解析 `libjvm.so`（那条写死的 com.termux 路径只是多余项，缺失无害）
- 走动验证：`bash tools/dev.sh build` → `BUILD SUCCESSFUL`；`bash tools/dev.sh test` → 164 tests / 0 failures

## 2026-11-07 aapt2 重建实录（AGP 资源任务的唯一外部二进制）

**同样由 `bash tools/dev.sh env-bootstrap` 自动完成**（含 `gradle.properties` 覆盖路径的回填）。

背景：环境重置后 `$PREFIX/bin/aapt2` 消失，而 `tools/gradle-home/gradle.properties` 里的 `android.aapt2FromMavenOverride` 仍指向它 ⇒ 资源任务直接失败。**注意 `tools/android-sdk/build-tools/36.0.0/aapt2` 不能用**：它是 x86-64 glibc（`file` 显示 `ELF ... x86-64`），复制到 `$PREFIX/bin` 后跑起来报 `error: "..." is for EM_X86_64 (62) instead of EM_AARCH64 (183)`。

关键事实：**Termux 的 `aapt` 包 ≠ aapt2**（只含 `bin/aapt` v1 + `bin/zipalign`）。aapt2 是**独立包 `aapt2`**（16.0.0.4-4，964KB），依赖 `fmt`/`libpng`/`libprotobuf`/`abseil-cpp`。

```bash
cd $PREFIX/tmp
apt-get download aapt2 fmt libpng libprotobuf abseil-cpp     # apt-cache 已废，见下条坑
mkdir -p aapt2x && dpkg-deb -x aapt2_16.0.0.4-4_aarch64.deb aapt2x
install -m 755 aapt2x/data/data/com.termux/files/usr/bin/aapt2 $PREFIX/bin/aapt2

# 依赖 .so：解包后**只补 $PREFIX/lib 里没有的**（别覆盖既有 libc++/z 等同名库）
mkdir -p deps && for f in fmt_*.deb libpng_*.deb libprotobuf_*.deb abseil-cpp_*.deb; do dpkg-deb -x "$f" deps/; done
find deps -path '*/usr/lib/*' -name 'lib*.so*' | while read f; do
  b=$(basename "$f"); [ -e "$PREFIX/lib/$b" ] || cp -a "$f" "$PREFIX/lib/"
done
```

- 验证：`aapt2 version` → `Android Asset Packaging Tool (aapt) 2.20-android-16.0.0_r4`
- 真验证（判据）：`bash tools/dev.sh exec bash ./app/gradlew -p app :app:processDebugResources --rerun-tasks --no-build-cache --info` 日志里必须出现 `AAPT2 bin Daemon #0` 且 `BUILD SUCCESSFUL`（普通 build 会命中 build cache 走 `FROM-CACHE`，**证不了 aapt2 能跑**）
- 该任务会顺带打一堆 `E aapt2 : No package ID 7f found for resource ID 0x7f02xxxx`，属噪声：成品 APK 里 `aapt2 dump resources` 有 1541 条 `resource 0x7f...`、`resources.arsc` 正常
- 依赖排查别信 `ldd`：本机 ldd 对 aapt2 报「无缺失」，实际缺 `libfmt.so`。用 `readelf -d <bin> | grep NEEDED` 再逐个 `[ -e $PREFIX/lib/<lib> ]` 判定
- AGP 不读 PATH，只认 `android.aapt2FromMavenOverride` 或 SDK build-tools 路径 ⇒ 二进制放 `$PREFIX/bin` 后**必须**确认 properties 指向它

## 环境调用坑（每次都踩过的记这）

- `/storage/emulated/0` 挂载 **noexec**：一切脚本（gradlew、jadx 启动器）必须经 `bash <脚本>` 调用；`adapt_new_version.py` 已打补丁（os.access X_OK 为假时自动经 bash 调 jadx）
- **`tools/dev.sh` 本身也没有执行位**（`-rw-rw----`），裸写 `tools/dev.sh build` 直接 `Permission denied` ⇒ 本机所有命令一律 `bash tools/dev.sh <子命令>`
- 本机 JAVA 临时目录：调 java 前设 `JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/data/user/0/com.dsharnessmobile.shell/files/home/tmp -Duser.home=/data/user/0/com.dsharnessmobile.shell/files/home"`（jadx 启动器已内置）
- `$HOME` 在裸 bash 里可能是 `/data/data/com.termux/...`（不存在）→ gradlew 必须带 `GRADLE_USER_HOME=tools/gradle-home`
- **`apt-cache` / `apt search` 全废**：报 `E: Unable to determine a suitable packaging system type`（`/data/data/com.termux/files/usr/etc/apt/apt.conf.d` 不存在）。`apt-get download <pkg>` 照常可用。查包名直接 curl 索引：`curl -s https://mirrors.ustc.edu.cn/termux/termux-main/dists/stable/main/binary-aarch64/Packages.gz | zcat | grep -A12 '^Package: <名字>'`
- **`dev.sh exec` 不经 bash 执行参数**：跑 gradlew 必须 `bash tools/dev.sh exec bash ./app/gradlew -p app <任务>`（否则 `Permission denied`）；`dev.sh build/test/build-release` 内部已处理，无需操心
- **`java` 不在 PATH 上**（JDK 是搬迁装的，没有往 `$PREFIX/bin` 放 java）⇒ 命令行工具会报 `exec: java: not found`（如 `apksigner verify`）。跑法：`JAVA_HOME=$PREFIX/lib/jvm/java-21-openjdk PATH=$JAVA_HOME/bin:$PATH bash tools/android-sdk/build-tools/36.0.0/apksigner verify <apk>`。Gradle 不受影响（认 JAVA_HOME）
- 本机**没有** `app/app/signing.properties`、也没有 `tools/signing/` ⇒ `build-release` 按设计回落 debug keystore（`apksigner --print-certs` 显示 `CN=Android Debug`）。要出正式包需补 keystore 或走 CI `-Phh*`
- 判据：装完即写 INSTALL_NOTE.json（jadx 同款），并更新本文件

## on-device 设备操作子命令（2026-11-07 新增）

本机既是开发机又是目标机。`dev.sh` 里原来只认 adb 的子命令改**双后端**：
`have_adb()`（ADB 是真实可执行文件才算）/ `dev_sh()`（host: adb shell / on-device: 本地 bash）/
`dev_root()`（host: adb shell su 0 / on-device: 本地 su -c）。

```bash
bash tools/dev.sh install-local                  # 缺省装 debug APK（on-device 静默安装）
bash tools/dev.sh install-local <apk 路径>
bash tools/dev.sh stop-app com.hihonor.mms       # kill -9（am force-stop 被壳侧策略拒）
bash tools/dev.sh modlog-grep MmsHook 20         # 本地 su grep，不再要求 adb
bash tools/dev.sh modlog-f HonorHook
bash tools/dev.sh restart-scope [pkg...]         # 缺省 = scope.list 全部
```

坑（都实测过）：

- **root 读不到 `/storage`**：`cmd package install /storage/.../x.apk` 直接报
  `Error: Can't open file`，必须先 `su -c 'cp <apk> /data/local/tmp/'` 再装。`install-local` 已封装。
- `lspctl` 只认 ADB root shell（报 `developer commands are only allowed from an ADB root shell`）
  ⇒ `enable-module` / `enable-cli` 在 on-device 形态不可用，属已知边界。
- 覆盖安装（`cmd package install -r`）**不丢 LSPosed 启用态**：实测
  `modules_state.enabled=1`、scope 20 条不变，装完无需 enable-module。
- 设备侧查 LSPosed 启用态：把 `/data/adb/lspd/config/modules_config.db` 拷到 `/data/local/tmp`
  用 `python3 -c "import sqlite3;..."` 读（本机无 `sqlite3` 可执行文件）。
