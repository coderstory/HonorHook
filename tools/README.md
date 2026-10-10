# tools/ 目录说明

本目录分两类内容：**已入库的脚本**（git 跟踪）与**本地工具链**（gitignore，不入库，需按下面说明自行放置）。

## 已入库（git 跟踪）

| 文件 | 用途 |
|------|------|
| `dev.sh` | 开发/调试统一入口（编译/安装/日志/截屏/UI dump 等），`tools/dev.sh help` 看全部子命令 |
| `adapt_new_version.py` | 宿主新版本适配辅助脚本（apktool 资源比对） |
| `rain-decrypt.py` | Rain 参考模块字符串解密（逆向期工具，留档） |

## 本地工具链（不入库，`dev.sh` 依赖，缺失时按此补齐）

`dev.sh` 使用**项目内便携工具链**，不依赖系统 PATH。以下路径为硬编码约定：

| 路径 | 内容 | 来源 |
|------|------|------|
| `tools/jdk-21/` | 完整 JDK 21（须含 jlink；Android Studio 自带 JBR 缺 jlink 不能用） | [Adoptium Temurin JDK 21](https://adoptium.net/) Windows x64 zip，解压到此 |
| `tools/android-sdk/` | Android SDK（build-tools / platforms；`local.properties` 指向此处） | Android Studio SDK Manager 或 [commandlinetools](https://developer.android.com/studio#command-line-tools-only) |
| `tools/platform-tools/` | adb（Windows，需 `adb.exe`） | [SDK Platform-Tools](https://developer.android.com/studio/releases/platform-tools) zip 解压 |
| `tools/jadx/` | jadx 反编译器（逆向宿主 APK 用，运行期不需要） | [jadx releases](https://github.com/skylot/jadx/releases) |
| `tools/apktool.jar` | apktool（`adapt_new_version.py` 资源比对用） | [apktool releases](https://github.com/iBotPeaches/Apktool/releases) |

以上均已在 `.gitignore` 中排除，克隆仓库后只需照表放置即可跑通 `tools/dev.sh build`。

## 逆向参考资料（不在本目录）

宿主 APK、反编译产物与适配报告在 `system-files/`（同样 gitignore），清单见 `system-files/adapt-report-*.md`；
hook 设计与审计文档在 `docs/`。
