# 挂载点映射表（抽象层）

> 目标：**hook 点从代码里抽出来**，变成按 `目标 app + 宿主版本` 组织的**数据**。
> 反编译一个新版本 → 工具输出一份映射表 → 模块运行时按当前版本自动选表。
> 选点原则仍以 `CLAUDE.md`「跨版本兼容」为准：未混淆目标 > 通用手法 > DexKit 锚点 > 才轮到按版本分表。

## 一、分层

| 层 | 内容 | 是否数据化 |
|---|---|---|
| 定位信息 | 类名 / 方法名 / 参数个数 / 返回值 / 重载消歧 | ✅ 全部进表 |
| 动作 | kind（CONST / FORCE_ARG / BLOCK / PRE_PROCESS / PRUNE …）与取值 `value` | ✅ kind + 值进表 |
| DexKit 锚点 | strings / paramTypes / classStrings / classFieldTypes | ✅ 进表 |
| 运行时逻辑 | 按 kind 分派的 intercept（改参、裁剪、改返回值） | ❌ 留在代码（复用 `mountOne`） |
| 惰性值 | `resolveValue`（枚举常量等依赖类加载） | ⚠️ 声明式表达：`enumRef = "类全名.字段名"` |

即：**表里只放"挂哪儿、挂成什么"，"怎么改"仍由 kind 的实现负责**。

## 二、表结构（已实现）

```kotlin
// xposed/data/HookMap.kt —— 纯数据，无 lambda
HookPoint(
    id = "theme:DownloadInfo.isPayedItem",   // 稳定标识（判据日志 / 失败清单口径）
    kind = "CONST",                          // 字符串而非目标私有枚举，避免抽象层反向依赖
    className = "...", methodName = "isPayedItem",
    paramCount = 0, returnType = "boolean",
    value = true,                            // CONST 的返回值 / FORCE_ARG 的强制值
    description = "F2 H1 空 URL 付费项判定 → 付费",
    feature = "theme.free",                  // 所属功能开关 key（空 = 常挂）
    dexStrings / dexParamTypes / dexClassStrings / dexClassFieldTypes,  // DexKit 锚点
    forceArgIndex = 0, uriEmptyRoute = false, uriOwner = "THIS",
    enumRef = "类全名.枚举字段名",             // 惰性值的声明式表达（替代 resolveValue lambda）
)
```

选表用 `HostVersion.Table`（xposed/core/HostVersion.kt），没有独立的 VersionMapping 类：
`Table(versions = setOf("20.1.63.305")) { points }`，空集合 = 默认兜底套。

版本选择（`HostVersion.pick`）：

1. 宿主 versionName **精确命中**某套 → 用它；
2. 否则用 `hostVersions` 为空的**默认套**；
3. 都没有 → **空表 + WARN 跳过**，绝不硬挂（避免 39 个点白重试 2 轮）。

## 三、产出流程

```
反编译目标版本 APK（apktool/jadx）
   → 对照既有表逐点在新产物里核类名/方法名（tools/adapt_new_version.py 一键校验存活）
   → 手写 <Target>Map_<版本>.kt（数据表即唯一事实源，直接编辑）
   → 模块编译时纳入，ThemeStoreHook.versionTables 注册精确套，运行时按版本自动命中
```

（初代表生成器 `gen_hook_map.py` 已随 collect 源删除——表生成是一次性动作，后续适配直接手写表。）

## 四、落地顺序

1. 框架：`HookPoint` / `HostVersion.resolve` + `pick`（✅ `xposed/data/HookMap.kt`、`xposed/core/HostVersion.kt`）
2. 抽取：主题商店基线表为手维护（历史上曾记为「`tools/gen_hook_map.py` 生成 43 点」，该脚本实际不存在于仓库——评审 #14 澄清；现表 46 点，核对走 `tools/adapt_new_version.py`（✅；
   等价性单测逐字段验证后，collect* 源与测试已删除——表是唯一事实源）
3. 接入：`ThemeStoreHook` 读表 → `toMountTask()` 桥 → `attemptMount`（✅；按 feature 过滤保开关语义）
4. 验证：真机回归 `installed 4/43（宿主 20.0.11.327）` 与迁移前持平（✅ 2026-10-11，
   判据链全按设计输出；4 成功点构成见 hook-inventory-audit.md §一）
5. 其余目标按同一方式渐进迁移（installer 11 / exam8 10 / 其余各 1）——未迁移
6. **决策（2026-10-11）**：不做旧版映射，设备升级宿主至基线；框架保留待未来版本适配
