# 系统设置 `com.android.settings` — 逆向分析与实现

> 真机基线 **14.0.0.210** · 目标源码 `app/.../xposed/target/SettingsHook.kt`
> 需求背景：荣耀对「管控应用」把应用详情页的「停用」按钮置灰，用户无法停用（如「健康使用手机」）。

## 一、逆向结论

| 项 | 值 |
|---|---|
| 目标类 | `com.android.settings.applications.InstalledAppDetails`（**类名可读**，非混淆漂移面） |
| 混淆情况 | 方法名为 R8 单字母混淆 ⇒ **OTA 后方法名会漂移，属版本适配风险点**，已配 DexKit 锚点兜底（`MethodResolver` 第二轨） |
| 挂载点 | 1 点：应用详情页「停用」按钮恒可点 |

## 二、实现要点

- 挂载 `InstalledAppDetails` 里控制按钮可用状态的判定方法，使其恒可点（`installed 1/1` 真机已验证）。
- 走 `MountEngine` + `DexHint`（混淆方法名不可依赖 ⇒ 用方法体字符串常量/参数序列做锚点）。

## 二·2、S2 首页账户小红点（2026-10-12，Settings-14.0.0.210-jadx 实证）

- **渲染链**：首页 HWID tile（账户头像+名字行）由 `dashboard/u`（HwidEnabler）驱动；
  `u.g(tileData)` 里 `tileData.badge == true` → 名字 TextView `setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,ic_hwid_badge,0)`
  （u.java:128-131）；badge 是云端 tile 数据（badgeUri provider 经 `settingslib.drawer.r.p()` 取
  "com.android.settings.badge"，u.java:253），缓存于应用私有文件 `hwid_tile_cache`（parcelable）。
- **选点**：挂 `u.g`（混淆名，DexKit 锚点 = 方法体独有日志串 "data.badge is false"+"data.title is"），
  前置把入参（嵌套 parcelable `u.d`）**唯一的 boolean 字段**置 false 后放行——宿主 else 分支
  `setCompoundDrawables(0,0,0,0)` 自清红点。只动数据不动视图；title/summary/icon 走各自字段不受影响。
- **字段定位按类型不按名**：`u.d` 四字段 Drawable/String/String/boolean，唯一 boolean 即 badge
  （混淆名 f22223d 会漂移，类型不会）。
- 缓存里旧 badge=true 会在 tile 刷新（u 内 100ms 轮询/onChange）后经 g 重走本点清除。
- 判据：`modlog SettingsHook` installed 含 `settings:hwidTile.badge`；真机首页账户名后无红点
 （云端再下发也不出现）。

## 三、边界与已放弃项（勿重新评估）

- **只解锁 UI 门**：停用「管控应用」的**执行层受 system_server 保护**——root 下 `pm disable-user` 同被拒；要真正生效须 hook 系统框架。
- **system_server 守卫 hook = 放弃决策（2026-10-10）**：该 fork 只回调 `onSystemServerLoaded`，而 libxposed api 102 **无此回调类型**，合成 stub 的维护成本 > 收益。已在 `docs/hook-inventory-audit.md` 与本文件双处留档，**不要再评估这条路线**。
- 相关行为判据：设置内任意应用详情页「停用」按钮可点（非管控应用确认后即停用）。
