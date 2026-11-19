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

## 四、§机型/设备门控审计（2026-11-19，Settings 14.0.0.210 smali 实证，只读审计）

反编译产物：`system-files/Settings-apktool/`（apktool，仅 smali 成功，res 解码失败不影响本节）。
结论先行：**全库无硬编码机型串（AAK/HNAAK/AAK-AN00 零命中）**——门控全部走系统属性开关，
`ro.product.model` 仅用于设备名展示（`DeviceNameController`）与营销名（`Utils.U8` 读
`ro.config.marketing_name`），不参与功能门控。

### ① 判定中枢

| 类 | 角色 | 判定 prop |
|---|---|---|
| `com.android.settings.ud`（混淆类，门控**核心枢纽**） | clinit 静态缓存 13 个布尔/串，方法 V/R/S/X/N/O/e/l/o 等 boolean 出口 | 见下表 |
| `com.android.settings.Utils` | 28 处 `SystemProperties.getBoolean`（如 `A5()Z`=读 `ro.config.hw_hide_display_mode`） | ro.config.hw_* |
| `com.hihonor.settingslib.HwUtils` | `isMagicLite()`=读 `ro.build.magic_lite.enable`（**本机实测=true**）、`isChinaArea()`、`getXmlFromCust()` 等 | ro/msc.* |
| `com.android.settings.HwCust*Impl` 系列（约 20 个） | 每个设置项一个 cust 实现，直读 prop 或 cust XML | ro.config.hw_* |
| `ba.g` | 「YOYO 主动服务」可用性（isSupportProactiveService：海外判定+SystemUI capsule 判定） | — |
| `com.hihonor.devicemanager.DeviceFeature` | 枚举，仅 devicemanager 内部自用，**不是**设置门控 | — |

`ud` clinit 字段映射（a..p 全部 sput-boolean 静态缓存）：
`a`=ro.config.show_nolock · `c`=ro.config.show_smartlock · `d`=ro.config.hide_search_view ·
`e`=msc.settings.use_dts_hnversion · `g`=ro.config.hide_single_navi · `k`=ro.config.disable_user_title ·
`l`=ro.config.support_au_init · `m`=msc.config.support_cust_esim · `n`=ro.config.gms_smartlock_title ·
`o`=msc.config.notch_size 非空 · `p`=hbc.region=="tw" · `b`=ro.product.custom 含 "docomo"

### ② 受控设置项（实证调用点）

| 门控方法 → 消费类 | 控制的入口 | 本机 prop 值 → 强改效果 |
|---|---|---|
| `ud.V()Z` → `hwwirelesssettings/VoWifiController` | 设置内 VoWiFi 开关 | show_vowifi_settings 空/false → **置 true 解锁 VoWiFi 设置项** |
| `Utils.A5()Z` → `display/DisplayModePreferenceController` | 显示-显示模式项 | hw_hide_display_mode 空 → 置 true 反而隐藏（反向门控实例） |
| `ud.R()Z`(=has_felica_feature) → `nfc/*` 5 类 | NFC/Osaifu-Keitai/AndroidBeam 相关 | 空/false → 日本 FeliCa 项默认关 |
| `ud.N()/O()` → `deviceinfo/LegalInformation*` | 法律信息入口变体 | — |
| `ud.S()`(=p, hbc.region==tw) → `localepicker/*`、`HwCustZonePickerImpl`、输入法语言列表 | 地区/语言列表行为 | hbc.region 空 → 台湾区特有分支关闭 |
| `ud.X()`(=msc.config.support_cust_esim) → `hwwirelesssettings/ESimController` | eSIM 入口 | false → **置 true 解锁 cust eSIM** |
| `ud.e()`(=dts_hnversion) → `accounts/AccountSettings*` | 账户页分支 | — |
| `ud.l()`(=support_au_init) → `IccLockSettings` | SIM 卡锁初始化分支 | — |
| `HwCustOtgSettingsImpl`（读 `ro.config.hw_otgFeature`）→ `otg_feature_settings` 项 | OTG 设置项 | 本机 false → 置 true 放开 |
| `HwUtils.isMagicLite()` → `settingslib/g0`、`SuggestionParser` | MagicOS lite 分支/建议卡片 | 本机 **true** |
| `ba.g`（isSupportProactiveService） | YOYO 主动服务设置 | 需海外/capsule 双条件，强改可开（推测，未逐点验消费面） |
| `ro.config.hw_hide_operator_name` → `HwCustStatusImpl` 等 | 状态页运营商名 | — |

### ③ Hook 候选盘点（证据强度标注）

1. **【强，首选】hook `android.os.SystemProperties#get/getBoolean`（限 com.android.settings 进程）**，
   按 key 白名单改写返回值：一键批量解锁/隐藏上表任意项。平台类名不混淆 → 跨 OTA 稳定；
   Settings 进程内全部 HwCust*/ud/Utils 判定同源生效。注意 settings 进程也读大量系统 prop，
   **必须 key 白名单**，不能全改。
2. **【中】hook `com.android.settings.ud.<clinit>` after → 覆写静态字段**（类型定位，字段名 a..p
   混淆会漂移；锚点 = clinit 里的 prop 串集合，内容锚点强）。覆盖面同 1 的 ro.config.* 子集，
   但只动缓存不动平台 API，侵入更小；代价是字段定位随版本漂移。
3. **【弱，不推荐单点】** `ud.V()/R()/X()/Utils.A5()` 逐点改返回值：点散、混淆名随 OTA 漂，
   仅在 1/2 失效时兜底。
4. **【中】`ba.g` 主动服务强开**：条件链多（overseas + capsule），需真机验证效果后再定。

未证实/推测项：ba.g 消费面未逐点核；cust XML 层（`getXmlFromCust`）只确认存在，未展开映射。
