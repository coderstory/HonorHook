# XHS 下载器代码外移为独立依赖模块（AGPLv3）

> 2026-10-20 · 执行台账（计划即 spec）。分支 `dev`。
> 触发：用户 2026-10-20 指示「小红书下载器的代码你不要直接复制到本项目啊」→「这玩意应该是独立的仓库，作为依赖的模块？」→「gradle 的脚本里处理？」→「git submodule」→「走 AGPLv3」。

## 一、目标

把 `com.neoruaa.xhsdn.*`（XHS_Downloader_Android 下载器管线，7 文件 798 行）从主仓源码树里**移出去**，
改为**从上游仓库（git submodule）按需引用**，作为独立 Gradle 依赖模块参与构建；
本项目按 **AGPLv3** 发布（用户拍板）。

## 二、现状事实（全部实测，非推断）

### 2.1 复制件

- 主仓 `app/app/src/main/kotlin/com/neoruaa/xhsdn/` 共 **7 个 .kt，798 行**：

  | 文件 | 外部依赖 |
  |---|---|
  | `core/model/ResolvedNote.kt` | `kotlinx.serialization.Serializable`（仅注解） |
  | `data/storage/StorageDestination.kt` | — |
  | `data/storage/StoredMediaRef.kt` | `android.net.Uri`（唯一 Android API） |
  | `data/xhs/XhsContentRepository.kt` | `okhttp3.*`（`OkHttpXhsPageSource`） |
  | `data/xhs/XhsNoteParser.kt` | — |
  | `data/xhs/XhsUrlParser.kt` | — |
  | `domain/download/DownloadContracts.kt` | — |

- 与上游 **逐字一致（7/7 校验通过）**，零改动。
- 使用方 4 个：`xposed/target/xhs/{XhsSavePivot,XhsImageUrl,XhsVideoSave,XhsBatchSave}.kt`
  （静态 import `com.neoruaa.xhsdn.*`，**无按名反射**）。

### 2.2 上游

- 上游 = 独立仓库 `https://github.com/NEORUAA/XHS_Downloader_Android`，**已作为 submodule 存在**：
  `.gitmodules` → `reference/xhs/XHS_Downloader_Android`，pin `d5c0070`（tag 1.4.1）。
- 上游许可 **AGPL-3.0**；上游 `com/neoruaa/xhsdn` 共 112 个 .kt（15 个含 Compose/Activity）⇒ **整目录当源集必炸**。
- 上游 4 个相关目录内容：
  - `core/model/` = 1 文件（= 我们的 1 个）
  - `data/storage/` = 4 文件（我们要 2 个）
  - `data/xhs/` = 3 文件（= 我们的 3 个）
  - `domain/download/` = 10 文件（我们要 1 个）

### 2.3 R8 现状（决定"能不能搬"的硬事实）

- release mapping 实测：存活的 `com.neoruaa.*` 类恰好 **10 个**
  （`MediaCandidate` / `ResolvedMedia` + 3 个嵌套 / `ResolvedNote` / `ParsedXhsNote` / `XhsLivePhoto` / `XhsNoteMetadata` / `XhsUrlParser`），
  且**全部被混淆**（`ResolvedNote -> m71`）。
- `OkHttpXhsPageSource` **不在存活名单** ⇒ 被 R8 当不可达代码整类删除
  ⇒ okhttp 的缺失类引用随之消失 ⇒ proguard 里**没有** `-dontwarn okhttp3.**` 也能构建过。
  **这条是承重的**：一旦它变得可达，R8 立刻报 `Missing class okhttp3.OkHttpClient` ⇒ release 构建失败。
- release APK `classes.dex` 里字符串 `neoruaa` 出现 **0 次**（包名全被混淆）。
- 全仓反射用法核对：`Class.forName` / `loadClass` 命中的全是**宿主类**
  （`okhttp3.Interceptor`、`okhttp3.ResponseBody`、`android.app.ActivityThread`…），
  **没有**按名反射 `com.neoruaa.xhsdn.*` ⇒ R8 改名安全。

### 2.4 构建现状

- `app/settings.gradle.kts`：`rootProject.name = "HonorHook"`，只有 `include(":app")`。
- `app/app/build.gradle.kts:119-120`：`compileOnly("com.squareup.okhttp3:okhttp:5.5.0")`、
  `compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")`（注释里写明"版本必须 5.5.0 对齐上游"）。
- kotlinx-coroutines：**未显式声明**，编译期经 UI 依赖树可见，缓存里解析到 **1.9.0**。
- 版本目录：`agp = 9.2.1`、`kotlin = 2.3.21`、compose（JetBrains）`1.11.0`、miuix `0.9.4`；
  plugin 别名只有 `android-application` / `kotlin-compose` / `kotlin-jvm` ⇒ **没有 android-library 别名**。
- CI `.github/workflows/ci.yml`：`actions/checkout@v5` **未带 `submodules: true`**
  ⇒ 今天能构建成功是因为构建**不依赖** submodule（复制件在仓里）；改造后必须补。
- 单测 130 例**不引用** `neoruaa` / `xhsdn`。
- 主仓根目录**无 LICENSE 文件**（现状 = 保留所有权利）。

## 三、许可决策（用户拍板 2026-10-20：走 AGPLv3）

- 上游 AGPL-3.0 与 GPLv2 **不兼容**（GNU 官方 license-list 原文："the GNU AGPL is not compatible with GPLv2"），
  与 GPLv3 严格说也不兼容（可同工程组合，但 AGPL 部分不能降级 ⇒ 组合作品实际须按 AGPLv3 分发）。
- copyleft 触发条件是**链接 + 分发**，与"是否修改上游源码"无关 ⇒ 不改源码也传染。
- AGPLv3 = GPLv3 + §13（网络交互）。本模块不提供网络服务 ⇒ §13 基本不触发；§6（源码交付）由
  **公开仓库 + 带 pin 的 submodule + 白名单**满足。
- **可逆性**：今天按 AGPLv3 发布，日后若上游改 MIT/Apache，删掉 AGPL 依赖即可整体降级；反之先选 GPLv2 就锁死。

## 四、架构决策

**独立 library 模块 `libs/xhs-dn-core`（在本仓内，是 Gradle 模块）+ 源码来自上游 submodule**。

```
libs/xhs-dn-core/build.gradle.kts   ← 本仓文件：library 模块 + 源集指向 submodule + 7 文件白名单
reference/xhs/XHS_Downloader_Android/  ← submodule（上游，只读，pin d5c0070）
app/app/                             ← implementation(project(":xhs-dn-core"))
```

- 上游仍是**独立仓库**（submodule），本仓**不存任何上游源码** ⇒ 满足「不要直接复制到本项目」。
- 模块在 Gradle 侧是**真依赖模块**（`implementation(project(...))`）⇒ 满足「作为依赖的模块」。
- 白名单（7 个具体文件路径）而不是整目录 ⇒ 上游 112 文件（含 Compose/UI）不会进编译。

### Ruling 1：白名单用「逐文件路径」而非目录通配
`data/storage/`（4 取 2）与 `domain/download/`（10 取 1）目录级通配不精确 ⇒ 用 7 条显式路径，
顺带把「我们到底用了上游哪些文件」变成可审计的显式清单。

### Ruling 2：library 模块放本仓 `libs/`，不放 `reference/`
`reference/` 的语义是第三方**只读参考**；现在它是**构建输入**，语义变了 ⇒ 另开 `libs/` 段。
submodule 本体**仍留在** `reference/xhs/XHS_Downloader_Android`（移动 gitlink 会牵动 .gitmodules/.gitignore/文档多处，
收益不抵风险；只读不改的性质不变）。

### Ruling 3：okhttp / serialization 在 library 里仍是 compileOnly
宿主小红书自带 okhttp/serialization，打包我们的会重复类冲突；且约定不加载 `OkHttpXhsPageSource`。
library 声明 compileOnly ⇒ **不传递给消费方** ⇒ app 模块必须保留自己那两条 compileOnly（现状不变）。

## 五、变更清单（只改清单内的）

| # | 文件 | 动作 |
|---|---|---|
| C1 | `app/gradle/libs.versions.toml` | 新增 `android-library` plugin 别名 + coroutines 依赖项（版本 1.9.0，对齐现状解析值） |
| C2 | `libs/xhs-dn-core/build.gradle.kts` | 新建：library 模块 + 源集指向 submodule + 7 文件白名单 + 白名单存在性校验任务 |
| C3 | `app/settings.gradle.kts` | `include(":xhs-dn-core")` + `projectDir` 指向 `libs/xhs-dn-core` |
| C4 | `app/app/build.gradle.kts` | `implementation(project(":xhs-dn-core"))`；两条 compileOnly 保留（注释改写：来源从"复制件"改为"上游模块"） |
| C5 | `app/app/src/main/kotlin/com/neoruaa/` | **删除**（7 文件 798 行） |
| C6 | `.github/workflows/ci.yml` | checkout 加 `submodules: true`（构建现在依赖 submodule） |
| C7 | `LICENSE`（新建） | AGPL-3.0 全文 |
| C8 | `NOTICE`（新建） | 上游项目 / 作者 / 上游许可 / pin commit / 未修改声明 |
| C9 | `README.md` | 许可段（AGPLv3 + 上游来源 + 对应源码地址） |
| C10 | `app/app/src/main/kotlin/top/honorhook/xposed/target/xhs/{XhsSavePivot,XhsBatchSave,XhsImageUrl,XhsVideoSave}.kt` | 注释措辞：「整文件原样复制，见 app/…/neoruaa/」→「上游 submodule 经 :xhs-dn-core 引用」 |
| C11 | `docs/reverse/xhs-downloader-parity.md` | 「销账」段更新为新依赖形态 |
| C12 | `AGENTS.md` | 架构事实段补：下载器管线是外部 AGPL 依赖模块（submodule 供给），本仓不存上游源码 |
| C13 | `.gitignore` | 确认 `reference/xhs/*` 忽略规则仍放行 submodule（已放行，无需改；仅核对） |

## 六、验收判据

| 判据 | 口径 |
|---|---|
| 构建 | `tools/dev.sh build` 成功；`tools/dev.sh build-release` 成功 |
| R8 不回归 | release mapping 里存活的 `com.neoruaa.*` 类**集合与 §2.3 的 10 个完全一致**；`OkHttpXhsPageSource` 仍被删；`classes.dex` 里 `neoruaa` 字符串 = 0 |
| 单测 | `tools/dev.sh test` → 130 pass / 0 fail / 1 skipped |
| 真机 | 装 release 后拉起小红书，判据 `XhsHook` 相关行 `installed N/M` **与 §2.3 基线一致**（XHS 7/7 不掉） |
| 合规 | 根目录有 `LICENSE`（AGPL-3.0）+ `NOTICE`；README 有许可段与源码地址 |

## 七、执行台账

（任务执行记录追加于此；每条任务一行，含命令与结果。）

| 任务 | 状态 | 记录 |
|---|---|---|
| T1 版本目录补别名/依赖 | ✅ | `android-library` plugin 别名（复用 agp 9.2.1）+ `kotlinx-coroutines-core` 1.9.0（对齐现状解析值）；根 `build.gradle.kts` 补 `alias(libs.plugins.android.library) apply false`——否则子模块再带版本请求会被判「插件已在 classpath 且版本未知」 |
| T2 建 `libs/xhs-dn-core` 模块 | ✅ | library 模块（namespace `com.neoruaa.xhsdn`，compileSdk 37 / minSdk 26）+ `Sync` 任务按 7 文件白名单落到 `build/generated/xhsdn/kotlin` + 白名单缺文件即失败 |
| T3 Gradle 接线 + 删复制件 | ✅ | `settings.gradle.kts` `include(":xhs-dn-core")` + `projectDir`；app 侧 `implementation(project(":xhs-dn-core"))`；主仓 7 文件 `git rm`（798 行，本仓不再存上游源码） |
| T4 debug 构建通过 | ✅ | `tools/dev.sh build` → BUILD SUCCESSFUL in 52s；生成目录 7 文件齐、模块 class 齐、debug APK 5 个 dex 含 `neoruaa` 字符串（debug 不混淆） |
| T5 release 构建 + R8 不回归 | ✅ | `tools/dev.sh build-release` → BUILD SUCCESSFUL（1.9M APK）；**mapping 存活 `com.neoruaa.*` = 10 个，与 §2.3 基线逐个一致**（仅混淆名不同）；`OkHttpXhsPageSource` 仍被 R8 当不可达删除（0）；全部 dex `neoruaa` 字符串 = 0 ⇒ **外移前后 release 产物等价** |
| T6 LICENSE/NOTICE/README | 🟡 | `LICENSE`（AGPL-3.0 全文 661 行）+ `NOTICE`（上游项目/许可/pin commit/7 文件白名单/未修改声明/对应源码地址）已落；README 许可段并入 README 重写任务（用户向排版，用户 2026-10-20 指定） |
| T7 注释与文档同步 | ✅ | 3 个 xhs 文件「vendored」→「上游」（28 处）+ 空格瑕疵修正；2 处头部改为「代码不在本仓，经 :xhs-dn-core 引用」；`docs/reverse/xhs-downloader-parity.md` 销账段更新；`AGENTS.md` 架构事实段新增（并放回正确章节） |
| T8 CI submodules | ✅ | `.github/workflows/ci.yml` checkout 加 `submodules: true`（构建现在依赖 submodule，不带则 `:xhs-dn-core` 同步失败） |
| T9 单测 + 真机判据 | 🟡 | 单测 **130 / 0 fail / 1 skipped** ✅（与基线一致）；**真机判据未做**——release 包未装到设备，装包属设备动作，留用户执行 |

### Ruling 4：源集接线走 Variant API，不升 AGP

AGP 9 判死了两代旧源集 API：
① 旧 `android.sourceSets`（`com.android.build.gradle.api.*`）——内部实现 `DefaultAndroidLibrarySourceSet`
不再实现旧接口 ⇒ 取 source set 直接 ClassCastException；
② 新旧 SourceSet API 都**禁 Provider**（"You cannot add Provider instances to the Android SourceSet API"），
AGP 错误信息明确指引改用 Variant API 的 `Sources`。
最终用 `variant.sources.kotlin.addStaticSourceDirectory(...)`（`variant.sources.kotlin` 可空 ⇒ 显式判空炸掉，
不静默少编译）。**AGP 不升级**：AGP 10 不存在（最新稳定 9.4.1、9.5.0 在 alpha），且旧 DSL 在 9 就已判死，
升版不解决本源集问题；升级属独立任务（须重跑 release + R8 比对 + 真机判据），不混进本次外移。

### Ruling 5：静态目录语义 ⇒ 任务依赖要显式带

`addStaticSourceDirectory` 是「静态目录」语义，**不会自动带 Gradle 任务依赖** ⇒ 保留
`tasks.named("preBuild") { dependsOn(syncUpstream) }`。（若改用 `addGeneratedSourceDirectory`，
需要一个带 `DirectoryProperty` 输出的自定义任务类型——当前不需要。）

