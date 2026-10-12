# CLAUDE.md

> 只写代码里读不到的。类名/方法/挂载点/结构读代码或查 `docs/`。**写得简短**。

## 定位

荣耀 LSPosed 模块（libxposed 102，`top.honorhook`）。hook 侧注入 6 个目标进程（默认全开，只改内存态）；UI 侧 Miuix Compose 面板经远端 prefs 下发开关（写 false 才关，**下次注入才生效**）。

## 先看这里

- 构建 / 安装 / 设备操作 / 反编译 → `tools/dev.sh`（**禁手拼 gradle/adb**）；路径跑 `dev.sh env`；命令索引 skill `honorhook-dev`
- 动手前读 `docs/hook-craft.md`（怎么写 hook）、`docs/reverse-craft.md`（怎么逆向）
- 索引 `docs/README.md`；每 app 逆向记录 `docs/reverse/`
- 判据 = 真机 `installed N/M`（LSPosed 框架日志 `/data/adb/lspd/log/modules_*.log`，不在 logcat）

## 纪律

- 改前出变更清单，只改清单内；不顺手改 bug / 重构 / 删死代码
- 别为压缩行数重构：注释是唯一审计痕迹、`vendored` 件要对齐上游，二者都不压（实测可压空间 2~4%，见 `docs/ui-miuix-overview.md` §压缩边界）
- 注释与文档只写代码读不到的（逆向实证 / 为什么 / 外部约束 / 判据）；长篇进 `docs/`，源码留指针
- 新操作先加 `dev.sh` 子命令再做；子任务只跑定向编译
- 路径全英文

## 设备铁律

- 🚫 **禁 reboot（任何形态）**——需要重启才生效的事，停下告诉用户，等用户自己重启
- 🚫 禁破坏性/难回退操作（恢复出厂、`pm uninstall` 系统应用、擦数据）——先问
- 🚫 不在用户主力机实测未验证 hook 的破坏性效果。AI 交付 = 代码 + 构建通过 + **用户自执行**的验证清单
- 只真机直连；`reference/` 只读

## 工具清单（tools/，详情 `tools/README.md`）

自写脚本：`dev.sh`（构建/安装/日志/UI 自动化/反编译统一入口）、`adapt_new_version.py`
（宿主新版本适配校验：从 hook 源码+数据表提取全部挂载点，现 68 点，对反编译产物核对类/方法存活）、
`rain-decrypt.py`（Rain 参考模块字符串解密，逆向期）。
下载件：`apktool.jar`（APK→smali，需 JDK 21）、`android-sdk/`（gradle 构建 + aapt2）、
`bin/apktool`、`bin/jadx`（PATH 垫片）。

## 环境坑

- 工具链由 `dev.sh` 自解析（项目内优先 → brew/PATH）；**JDK 21 需含 jlink**
- 手动跑 apktool/gradle 必须 `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home`
  （裸 `java` 不在 PATH）；SDK 路径由 `app/local.properties` 指向 `tools/android-sdk`
- 脚本一律 LF（CRLF 在 bash 3.2 直接报错）
- bash 3.2：`$VAR` 后紧跟中文必须写 `${VAR}`，否则 unbound variable 退出
- 代理已配：`http.https://github.com.proxy = socks5://127.0.0.1:7897`

## 构建 gotchas

- `packaging { excludes += "**" }` + 显式 merge `META-INF/xposed/*` = 混淆后 `java_init.list` 存活的保证；动 proguard/packaging 必须回归 release 包元数据
- release = 混淆 + 收缩 + 稳定 keystore 签名（CI 走 Secrets `-Phh*`，本地走 gitignored `app/app/signing.properties`，无凭据回落 debug；keystore 在 `tools/signing/`，备份见 `credentials.txt`）；`abiFilters` 只 arm64
- 模块名/简介只从 `<application>` 的 label 与 description（必须 `@string`）读
- DexKit 兜底必须有内容锚点，无锚点诚实跳过（`NOT_RESOLVED`），不猜类名
- 失败分级：`FAILED` = 重试无意义；`NOT_RESOLVED` = 随类加载重试

## 跨版本兼容（hook 选点原则）

宿主 OTA 会换 R8 混淆名 ⇒ **混淆类名/方法名与宿主版本绑定**。下面是**并列的考量维度**，
不是「上一条不行才用下一条」：

- **优先选没混淆的目标**：混淆对象随宿主版本变；未混淆对象（AOSP 共同祖先 / 公开接口 /
  `public final` 字段）跨版本稳定——能选它就不选混淆对象。
- **hook 手法尽量通用**：改数据源/参数而非改返回值（返回对象跨版本易变），挂收口点而非散点，
  少依赖宿主私有字段。
- **目标混淆才用动态检索**：配 DexKit 内容锚点（方法体日志串 / 参数类型序列 / 调用关系
  ——混淆不改这些）。**弱锚点（仅参数个数）等于没有**：失败时静默 `NOT_RESOLVED`，
  只有 `installed N/M` 汇总行暴露。
- **检索不到才按版本分发**：同一 app 维护多套挂载点，运行时按宿主版本号分发。

实证：SystemUI 的 `ModernStatusBarMobileView.constructAndBind`（AOSP 共同祖先）跨厂商跨版本成立；
包管理器 11 点曾因宿主 14.0.37.301 → 14.0.22.326 全线 `installed 0/N`——混淆点锚点不足，
可读点又把 `com.hihonor.packageinstaller` 前缀写死（「优先未混淆目标」本可避免）。

版本分发机制已建成（2026-10-11）：`HookPoint` 数据表（唯一事实源，勿手生成）+
`HostVersion` 选表器（`versionTables`，精确套优先默认套）+ `adapt_new_version.py` 校验。
**策略 = 只维护基线表，不做旧版映射**（设备升宿主至基线 20.1.63.305 / 14.0.37.301）；
新宿主版本 = 反编译逐点核对 → 手写新表注册 `versionTables`。
主题商店 43 点已入表；其余 5 目标仍源码 `MountTask` 式，渐进迁移。

## 哲学

**在精确的边界内，找最稳的锚点，最小侵入完成重定向，随时准备优雅失败。** 七条原则与审计应用见 `docs/hook-philosophy-manifesto.md`。

## 未解决

- ~~设备宿主待升级至基线~~ **已升级（2026-10-12 dumpsys 实证）**：theme 20.1.63.305 / installer 14.0.37.301，
  modlog `installed 43/43` 全绿；旧「4/43 / 0/11」判据作废
- 主题商店搜索页「免费领」是本地硬编码 tab，云控与槽位裁剪都管不到 → `docs/theme-store.md` §七·2
- ~~挂载点口径漂移~~ **已排查为假问题（2026-10-11）**：`docs/hook-inventory-audit.md` 的复核 grep 正则漏了 6 个命名空间，
  误报 theme 37 / installer 8；用全命名空间命令复查后代码计数（theme 43 / installer 11）与 README 一致。
