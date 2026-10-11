# AGENTS.md — HonorHook

荣耀 LSPosed 模块（libxposed API 102，包名 `top.honorhook`）。一个 APK 两个运行面：**hook 侧**注入 6 个目标进程（只改内存态）、**UI 侧** Miuix Compose 面板经远端 prefs 下发开关。

详细背景见 `README.md`（功能台账 + 验证清单）、`CLAUDE.md`（纪律）、`docs/README.md`（文档索引）。

## 命令：一切走 `tools/dev.sh`

**禁止手拼 `gradle` / `adb` / `java -jar apktool` / `jadx`。**

```bash
tools/dev.sh help            # 子命令全量清单（唯一权威）
tools/dev.sh env             # 工具链解析结果：java/adb/aapt2/apktool/jadx 在哪
tools/dev.sh build           # debug APK
tools/dev.sh build-release   # release APK（混淆+收缩+debug 签名）
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

来自 **LSPosed 框架日志 `/data/adb/lspd/log/modules_*.log`，不在 logcat**。抓取用 `tools/dev.sh hook-test` / `wait-hook` / `log-hook` / `modlog`。

| 目标 | 应为 |
|---|---|
| `ThemeStoreHook` | 43/43 |
| `InstallerHook` | 11/11 |
| `Exam8Hook` | 10/10 |
| `SettingsHook` / `LauncherCardTitleHook` | 1/1 |
| `SystemUiHook` | 2/2 |

N 明显小于 M = **宿主版本错位或选点失效，先别改 hook 实现**。

覆盖安装后 LSPosed 会停用模块（进程活着但零模块日志）→ 跑 `tools/dev.sh enable-module`；按需拉起的进程还要 force-stop。

## 设备铁律

- 🚫 **禁 reboot（任何形态）** —— 需要重启才生效的事，停下来告诉用户，等用户自己重启
- 🚫 禁破坏性/难回退操作（恢复出厂、`pm uninstall` 系统应用、擦数据）—— 先问
- 🚫 不在用户主力机上实测未验证的破坏性 hook。**AI 交付 = 代码 + 构建通过 + 用户自执行的验证清单**
- `reference/` 是第三方项目的只读 submodule，只读不改
- 只做真机直连验证（`abiFilters` 只 arm64，无模拟器路径）

## 架构里不明显的事实

**开关通道是跨进程的，且延迟一轮注入。** UI 侧 `FeatureStore` 双写本地 prefs + 远端 prefs（group = `FeatureCatalog.REMOTE_GROUP`）；hook 侧 `RemoteConfig` 在 `HookEntry.onPackageLoaded` 注入一次后只读本地。**默认全开**：读不到 / 异常一律视为开；关闭某功能 = 宿主显式写 `false`，且**下次目标进程注入才生效**。

**`FeatureCatalog` 是功能 key 的唯一事实源**（`app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt`），UI 侧与 hook 侧共用同一份 key 字面量。**任何一侧都禁止手写 key 字符串**——加开关只动这张表。该类被 proguard `-keep` 防 R8 重命名。

**新增目标应用** = 新建 `TargetHook` 实现 + `TargetRegistry` 加一行 + `scope.list` 声明包名 + `FeatureCatalog.Target` 加一项；`HookEntry` 零改动。**`TargetRegistry` 与 `scope.list` 不一致时 HookEntry 收不到该包回调**，两者必须同步。防重装是 `TargetRegistry.installed` 的对象级判定，实现内不要自己加 installed 标志。

**挂载失败分两级**（`MountEngine`）：`FAILED` = 挂载抛异常，重试无意义；`NOT_RESOLVED` = 类/依赖未就绪，随类加载重试（懒加载重试循环）。汇总行始终输出，不受 debug 日志门控。

**DexKit 兜底必须有内容锚点**（混淆不动的字符串常量 / 参数类型序列）。**没有锚点就诚实跳过返回 `NOT_RESOLVED`，绝不猜类名硬兜。** 解析走双轨：直连反射优先（不缓存，赌类稍后加载）→ DexKit 兜底。

**`tools/adapt_new_version.py` 的挂载点提取当前覆盖不全**（实测：`extract_mounts()` 只 glob `target/*Hook.kt` 非递归、且只认 `addConst`/`addForceArg`/`MountTask(kind=MountKind.X, className=...)` 三种书写格式）。目前只能提取出 9 个点（全部来自 `InstallerHook.kt`），**漏掉 themestore/ 与 exam8/ 子目录，以及各文件私有的 `MountTask(className=..., ...)` 写法**。用它跑版本适配前，先手工核对挂载点台账（`docs/hook-inventory-audit.md`），别拿它的 N/M 当覆盖度结论。

## 构建 gotchas

- release = 混淆 + 资源收缩 + **debug 签名**；`abiFilters` 只有 `arm64-v8a`
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

动手前读 `docs/hook-craft.md`（怎么写 hook）与 `docs/reverse-craft.md`（怎么逆向）；每 app 的逆向记录在 `docs/reverse/`。方法论沉淀在 `.claude/skills/`（`honorhook-dev` 是 dev.sh 命令索引）。

## 放哪里

| 加什么 | 放哪 |
|---|---|
| 新 hook 点 | 对应 `xposed/target/<Target>/…Hook.kt` 的 `pending` 挂载表 |
| 云控 / 配置表 | `xposed/data/*Table.kt`，并在 `app/src/test/.../DecisionTablesTest.kt` 钉住契约 |
| 功能开关 | `FeatureCatalog.kt`（唯一事实源） |
| UI 页面 / 开关项 | `ui/screen/` |
| 逆向记录 | `docs/reverse/<app>.md` |
| 设备操作 | `tools/dev.sh` 新增子命令 |