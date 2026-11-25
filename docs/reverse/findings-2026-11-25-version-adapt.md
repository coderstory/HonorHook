# 版本适配 findings（2026-11-25）

> 触发：用户「检测新版本兼容」。入口 `bash tools/dev.sh versions`（exit 1：未适配 2 / 已适配 16 / 未安装或未知 2）。
> 方法 = 真机判据（LSPosed 框架日志 `installed N/M`）+ 静态按**方法名**反查混淆类（本轮 MT MCP 未起，改用自写 dex 解析，见 §三）。
> 设备实装模块 = v1.3.8（HEAD 之后仅 xhs 改动，`v1.3.8..HEAD` 无 theme/jd 文件 ⇒ 1.3.8 的判据对本轮有效）。

## 一、实装 vs 代码登记（适配前）

| 宿主 | 实装 | 代码登记 | 结论 |
|---|---|---|---|
| `com.hihonor.android.thememanager` | **20.1.69.311**（2026-11-24 21:53 OTA） | 基线 20.1.63.305 + 65.302/67.302/67.303 | 未登记 ⇒ 落基线表盲挂，**installed 51/54** |
| `com.jingdong.app.mall` | **16.2.0** | 基线 16.1.0 + 16.1.50 | 未登记但判据 **installed 1/1 × 6+ 进程**，零漂移 |

其余宿主（xhs 9.54.0、sysmgr 17.0.0.584、launcher 18.0.0.331 等）已在 `FeatureCatalog` 登记，`versions` 判已适配。

## 二、主题商店 20.1.69.311（4 个混淆类改名）

盲挂（基线表 `THEME_POINTS_20_1_63_305` 直接套用）判据 `installed 51/54`，日志
`/data/adb/lspd/log/modules_2026-11-25T10:40:28.211204.log`（主进程与 `:data` 进程一致）：

- DexKit 宽松轨救回 3 点（仍挂上，但类名与期望不符的漂移行已列出）：`bz2→j83`、`f71→zb1`、`u15→yh5`（getBoolean 双载重各一条）
- 落空 3 点：`theme:e26.getDecryptTheme`、`theme:e26.getDecryptFile`（ThemeCheckTool 整体改名，无锚点可救）、
  `theme:DownloadItemVip.productSendFinish`（DexKit 命中类已有，但盲挂表里该行绑的是旧名）

静态核对（classes19/classes20）：

| 源名 | 69.311 混淆名 | 依据 |
|---|---|---|
| SharepreferenceUtils | **yh5** | `getBoolean(String,Z)Z` / `getBoolean(String)Z` + getInt/readString/removeKey 全在此类；DexKit 漂移行同结论 |
| LiveWallpaperHelper | **j83** | `createApplyWallPaper(WallPaperInfo)V`、`getDecryWallpaperPath(String)String`（classes20.dex） |
| DownloadItemIfVip | **zb1** | `downloadItem()V` + `productSendFinish(I,String,String,Pair)V` 同类；兄弟 ub1/xb1/yb1 也带同名方法，唯一性由 DexKit 用基线锚串解析到 zb1 给定 |
| ThemeCheckTool | **z16** | `getDecryptTheme(String,String,String,Z)ThemeInfo`、`getDecryptFile(String,File)File`、`getDecryptWallpaperFile(String,File)File` 全 dex 唯一持有者 |

⇒ 新增 `ThemeStoreMap_20_1_69_311`（`THEME_POINTS_20_1_63_305.map` 只换四个类名），`ThemeStoreHook` 选表加该版本；
`decryptToolClass` 同步覆盖为 `z16`（`getDecryptWallpaperFile` 已不在 `gl5`，不覆盖则 W2 付费壁纸解密回落 gl5 失败放行）。
细节与版本表见 `docs/reverse/theme-store.md` §「20.1.69.311 适配」。

## 三、静态核对工具（本轮环境限制与替代）

- MT MCP 不可用：`http://127.0.0.1:8787/mcp` connection refused，MT 已装但 MCP server 未起 ⇒ `tools/adapt_mt.py` 跑不了。
- `tools/android-sdk/build-tools/36.0.0/dexdump` 是 **x86-64 ELF**，`/storage` 又是 noexec ⇒ 不可用；jadx 全量反编译在真机上不现实。
- 替代：自写最小 dex 解析器（header → string/type/proto/method_ids → class_defs → class_data，按**方法名 + 参数个数**反查类），
  脚本放会话临时目录（未入库）。注意 dex header 字段不连续：string/type/proto/method/class_defs 分别在 0x38/0x40/0x48/0x58/0x60（中间夹 field_ids 0x50）。

## 四、待办

- 真机复测新表（`ThemeStoreMap_20_1_69_311`）期望 `installed 54/54`，需装新包 + `enable-module` + 重启宿主进程。
- 行为层（付费主题下载/应用、W2 付费壁纸解密）沿用既有验证清单，由用户真机确认。
