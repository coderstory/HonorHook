# tools/ 目录说明

两类内容：**已入库脚本**（git 跟踪）与**本地工具链**（gitignore，不入库）。

> **工具路径不要问人、不要手拼** —— 跑 `tools/dev.sh env`，它打印解析后的全部路径。
> 命令索引见 skill `honorhook-dev`（`.claude/skills/honorhook-dev/`）。

## 已入库（git 跟踪）

| 文件 | 用途 |
|------|------|
| `dev.sh` | 开发/调试统一入口：构建/安装/部署、日志判据、真机 UI 自动化、反编译、工具链自检（`tools/dev.sh help` 看全量） |
| `adapt_new_version.py` | 宿主新版本适配校验（对基线反编译产物核对挂载点存活） |
| `rain-decrypt.py` | Rain 参考模块字符串解密（逆向期工具，算法见 skill `reverse-decompile/references/rain-decrypted-strings.md`） |

## 本地工具链（不入库；`dev.sh` 按"项目内便携版优先 → 本机安装兜底"自解析）

| 组件 | 项目内路径（可选） | 本机兜底 | 备注 |
|---|---|---|---|
| JDK 21 | `tools/jdk-21/` | brew `openjdk@21`（`/opt/homebrew` 或 `/usr/local` 的 `libexec/openjdk.jdk/Contents/Home`） | **必须含 `bin/jlink`**（release 打包需要），故只认完整 JDK |
| adb | `tools/platform-tools/adb`（或 Windows 的 `adb.exe`） | PATH（brew `android-platform-tools`） | |
| Android SDK | `tools/android-sdk/`（`app/local.properties` 的 `sdk.dir` 指向此处） | — | build-tools 里含 **aapt2**，`dev.sh aapt2` 自动取最高版本 |
| jadx | `tools/jadx/`（跨平台 bundle，入口 `bin/jadx`；Windows 为 `bin/jadx.bat`） | — | `dev.sh jadx <apk>` |
| apktool | `tools/apktool.jar`（`dev.sh decompile` 用它） | — | 需 JDK 21 运行 |

以上均已在 `.gitignore` 排除；克隆后照表放置（或用 brew 装）即可跑通 `tools/dev.sh build`。

## 逆向材料（不在本目录）

宿主 APK 与反编译产物在 `system-files/`（gitignore）——**产物按需现场生成**：`tools/dev.sh decompile <apk> [名称]` / `tools/dev.sh jadx <apk> [名称]`。
hook 设计与证据文档在 `docs/`（索引 `docs/README.md`），方法论沉淀在 `.claude/skills/`。
