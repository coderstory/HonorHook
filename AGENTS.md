# AGENTS.md — HonorHook

荣耀 LSPosed 模块（libxposed API 102，包名 `top.honorhook`）。一个 APK 两个运行面：**hook 侧**注入 24 个作用域包（22 个已注册 `TargetHook` 实现 / 33 个注册实例，SDK 通杀类按包分别注册；口径以 `RegistryConsistencyTest` 动态断言为准，只改内存态）、**UI 侧** Miuix Compose 面板经远端 prefs 下发开关。

详细背景见 `README.md`（功能台账 + 验证清单）、`CLAUDE.md`（纪律）、`docs/README.md`（文档索引）。

## 命令：一切走 `tools/dev.sh`

**禁止手拼 `gradle` / `adb` / `java -jar apktool` / `jadx`。**

```bash
tools/dev.sh help            # 子命令全量清单（唯一权威）
tools/dev.sh env             # 工具链解析结果：java/adb/aapt2/apktool/jadx 在哪
tools/dev.sh build           # debug APK
tools/dev.sh build-release   # release APK（混淆+收缩+稳定 keystore 签名）
tools/dev.sh test            # JVM 单测（= :app:testDebugUnitTest）
tools/dev.sh deploy-release  # build-release + install-release + enable-module
```

`README.md` 里那句「或直接 Gradle：`./app/gradlew -p app assembleRelease`」**在裸 shell 里会失败**（PATH 上没有 java，报 "Unable to locate a Java Runtime"）。需要定向 gradle 任务时走带环境的入口：

```bash
tools/dev.sh exec ./app/gradlew -p app :app:testDebugUnitTest --tests 'top.honorhook.xposed.data.DecisionTablesTest'
tools/dev.sh exec python3 tools/adapt_new_version.py <新版本APK>
```

新设备操作**先加 `dev.sh` 子命令（case + help 表）再执行**。

CI（`.github/workflows/ci.yml`）：`dev` push = `assembleDebug` + `test`（真门禁，没有 `|| true`）；`master` push = `assembleRelease` + 自动发 Release。分支：`master` 稳定线、`dev` 日常线。

## 判据只有一条：`installed N/M`

来自 **LSPosed 框架日志 `/data/adb/lspd/log/modules_*.log`，不在 logcat**。抓取用 `tools/dev.sh modlog` / `modlog-grep` / `wait-hook` / `hook-test`。

N 明显小于 M = **宿主版本错位或选点失效，先别改 hook 实现**。

逐 hook 的应 M / 最近实测 N **只维护一份**：[`docs/hook-inventory-audit.md`](docs/hook-inventory-audit.md) §五 判据快照——本文件不复制数字（复制过一次，漂移过一次）。

覆盖安装后 LSPosed 会停用模块（进程活着但零模块日志）→ 跑 `tools/dev.sh enable-module`；按需拉起的进程还要 force-stop。

## 设备铁律

- 🚫 **禁 reboot（任何形态）** —— 需要重启才生效的事，停下来告诉用户，等用户自己重启
- 🚫 禁破坏性/难回退操作（恢复出厂、`pm uninstall` 系统应用、擦数据）—— 先问
- 🚫 不在用户主力机上实测未验证的破坏性 hook。**AI 交付 = 代码 + 构建通过 + 用户自执行的验证清单**
- `reference/` 是第三方项目的只读 submodule，只读不改
- 只做真机直连验证（`abiFilters` 只 arm64，无模拟器路径）

## 架构里不明显的事实

**XHS 下载器管线是外部依赖模块，不在本仓源码树里。** `com.neoruaa.xhsdn.*`（上游
XHS_Downloader_Android，**AGPL-3.0**）来自 submodule `reference/xhs/XHS_Downloader_Android`
（pin `d5c0070` / 1.4.1），构建时按 **7 文件白名单**同步进 `:xhs-dn-core` 模块再编译
（白名单 = 唯一事实源，`libs/xhs-dn-core/build.gradle.kts`）——**本仓不存上游任何源码**。
上游文件**禁改**（保持逐字一致 ⇒ AGPL 归因成立）；白名单缺文件 = 构建直接失败，不会静默少编译。
项目整体按 **AGPLv3** 分发（根 `LICENSE` + `NOTICE`）。

**开关通道是跨进程的，且延迟一轮注入。** UI 侧 `FeatureStore` 双写本地 prefs + 远端 prefs（group = `FeatureCatalog.REMOTE_GROUP`）；hook 侧 `RemoteConfig` 在 `HookEntry.onPackageLoaded` 注入一次后只读本地。**默认全开**：读不到 / 异常一律视为开；关闭某功能 = 宿主显式写 `false`，且**下次目标进程注入才生效**。

**`FeatureCatalog` 是功能 key 的唯一事实源**（`app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt`），UI 侧与 hook 侧共用同一份 key 字面量。**任何一侧都禁止手写 key 字符串**——加开关只动这张表。该类被 proguard `-keep` 防 R8 重命名。

**新增目标应用** = 新建 `TargetHook` 实现 + `TargetRegistry` 加一行 + `scope.list` 声明包名 + `FeatureCatalog.Target` 加一项；`HookEntry` 零改动。**`TargetRegistry` 与 `scope.list` 不一致时 HookEntry 收不到该包回调**，两者必须同步。防重装是 `TargetRegistry.installed` 的对象级判定，实现内不要自己加 installed 标志。

**挂载失败分两级**（`MountEngine`）：`FAILED` = 挂载抛异常，重试无意义；`NOT_RESOLVED` = 类/依赖未就绪，随类加载重试（懒加载重试循环）。汇总行始终输出，不受 debug 日志门控。

**DexKit 兜底必须有内容锚点**（混淆不动的字符串常量 / 参数类型序列）。**没有锚点就诚实跳过返回 `NOT_RESOLVED`，绝不猜类名硬兜。** 解析三阶段：直连反射优先（不缓存，赌类稍后加载）→ DexKit 严格轨（方法名/签名钉死）→ 宽松轨（只按内容锚点查，名字/签名只用于排序，唯一胜出才采纳）；abstract/native 不可 hook，**static 可 hook**。

**`tools/adapt_new_version.py` 现提取 140 个挂载点**（2026-10-20 实测：`extract_mounts()` 已改递归 `rglob` 全 `target/`——旧的「只覆盖 9 点」结论作废）。注意**提取数 ≠ 运行期 M**：挂载点声明四格式并存（`HookPoint` 表 / 源码 `addConst`·`addForceArg` / OucHook 字符串对模板 / SDK 内联 `MountTask`，见 `docs/review-findings-2026-10-12.md` #22），且 id 字面量与运行期任务非一一对应（例：mms 4 个 id ↔ `installed 2/2`）。它只回答「反编译产物里这些类/方法还在不在」，**别拿它的计数当覆盖度结论**——口径与逐 hook M 见 `docs/hook-inventory-audit.md` §一/§五。

## 构建 gotchas

- release = 混淆 + 资源收缩 + **稳定 keystore 签名**（CI Secrets / 本地 `app/app/signing.properties`，缺省回落 debug）；`abiFilters` 只有 `arm64-v8a`
- `packaging { excludes += "**" }` + 显式 `merges += "META-INF/xposed/*"` 是混淆后 `java_init.list` 存活的**唯一保证**。动 proguard / packaging 必须回归 release 包的 Xposed 元数据
- `HookEntry` 必须具名且禁止混淆（proguard 里有具名 keep，不是通配 + allowobfuscation——那是锁步脆弱设计，R8 改行为时构建零报错但模块被静默跳过）
- `libxposed` api 是 `compileOnly`，proguard 需要 `-dontwarn io.github.libxposed.annotation.**`
- 模块名 / 简介只从 `<application>` 的 `label` 与 `description` 读；`android:description` 只接受 `@string` 引用，字面量会被 aapt2 拒绝 → 落在 `res/values/strings.xml`

## 环境与脚本坑

- 工具链自解析顺序：`tools/` 项目内便携版 → brew → PATH。**JDK 21 必须含 `bin/jlink`**（release 打包需要，brew 的要认 `libexec/openjdk.jdk/Contents/Home`）
- 脚本一律 **LF**，CRLF 在 bash 3.2 直接报错
- bash 3.2：`$VAR` 后紧跟中文字符必须写 `${VAR}`，否则 unbound variable 退出
- 路径全英文，不带中文/空格
- Gradle 走阿里云镜像；JetBrains 的 `material3` 只在 Maven Central（阿里云未同步），必须保留直连仓库

## 写代码的纪律

- **改前出变更清单，只改清单内的**；不顺手修 bug、不重构、不删死代码
- **注释与文档只写代码读不到的**：逆向实证、为什么这么写、外部约束、判据。长篇进 `docs/`，源码留指针
- **注释是唯一的审计痕迹，不许为了压缩行数删改**；`vendored` 件要对齐上游。实测可压空间只有 2~4%（`docs/ui-miuix-overview.md` §压缩边界）——**别为压缩行数重构**
- hook 回调里任何异常都必须吞掉并记日志，**绝不能炸宿主进程**
- 挂载日志按 `task.id` 节流，别刷屏

动手前读 `docs/hook-craft.md`（怎么写 hook）与 `docs/reverse-craft.md`（怎么逆向）；每 app 的逆向记录在 `docs/reverse/`。方法论沉淀在 `.claude/skills/`（`honorhook-dev` 是 dev.sh 命令索引）；
DSH 会话读 `.agents/skills/`（项目级技能根）——两份副本，改完 .claude 跑 `tools/sync-skills.sh`。

## 放哪里

| 加什么 | 放哪 |
|---|---|
| 新 hook 点 | 对应 `xposed/target/<Target>/…Hook.kt` 的 `pending` 挂载表 |
| 云控 / 配置表 | `xposed/data/*Table.kt`，并在 `app/src/test/.../DecisionTablesTest.kt` 钉住契约 |
| 功能开关 | `FeatureCatalog.kt`（唯一事实源） |
| UI 页面 / 开关项 | `ui/screen/` |
| 逆向记录 | `docs/reverse/<app>.md` |
| 设备操作 | `tools/dev.sh` 新增子命令 |
| 上游第三方代码接入 | `libs/<模块>/build.gradle.kts`（独立模块 + 文件白名单）；源码只走 `reference/` submodule，**不复制进本仓** |

## Agent skills

### Issue tracker

Issues and specs live as GitHub issues on `coderstory/HonorHook`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context. See `docs/agents/domain.md`.

<!-- cockpit-conventions: start · written by /cockpit-setup, which updates it in place -->
## Cockpit conventions

**Shells**
- Start the build with `tools/dev.sh build` as a background shell described "build". Reuse it if it is already running; never start a second one.
- Run the tests with `tools/dev.sh test` as a background shell described "test". Read its output instead of running the suite again.

**Trail**
- Tickets are GitHub issues on coderstory/HonorHook, keys like `#123`. When a PR, branch or page is for a ticket, put its key in `for`.
- Pull requests go to `github.com/coderstory/HonorHook`.

**Subagents**
- Explore the codebase in background subagents and keep this conversation free.
<!-- cockpit-conventions: end -->
