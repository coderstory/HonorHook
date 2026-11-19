# com.hihonor.camera（荣耀相机）

## §机型/设备门控审计（2026-11-19，MT MCP 在线 dex 只读审计）

门控中枢：`com.hihonor.camera2.utils.CustomConfigurationUtil`（未混淆，315 个方法，smali 全文 7514 行已核对）。所有读 prop 的方法走私有 `getSystemProperty(String, Object default, Class)`（declared-synchronized，内部应是 SystemProperties.get 反射桥）。

### 1. 机型判断的 prop 源（实证，smali 逐方法核对）

| 方法 | 判据 | 本机（AAK-AN00）实测 |
|---|---|---|
| getBuildProduct | `ro.build.product` | `AAK` |
| getProductName | `ro.product.name` | `AAK-AN00` |
| getProductModelName | `ro.product.model` | `AAK-AN00` |
| getProductMarketName | `ro.config.marketing_name` | 空（未设） |
| getProductType | `ro.build.characteristics`，默认 `default` | `default` |
| getSysProductName | `msc.sys.oemName` | `AAK-AN00` |
| getRunmode | `ro.runmode`，默认 `normal` | `normal` |
| is960Product | `ro.hardware` == `hi3660`（麒麟960） | qcom → false |
| is970Product | `ro.hardware` startsWith `Honor970` | false |
| isMiamiProduct | `ro.hardware` startsWith `honor710` | false |
| isRvlProduct | `ro.product.board` contains `RVL` | AAK → false |
| isMarxProduct | getBuildProduct == `MRX` | false |
| isPctProduct | getBuildProduct == `PCT` | false |
| isHdlL0jProduct | getProductName == `HDL-L0J` | false |
| isFoldDispProduct | `HwFoldScreenManagerEx.isFoldable()` | false |
| isMagicLite | `ro.build.magic_lite.enable`（bool，默认 false） | 空 → false |
| getMagicVersion | `ro.build.version.magic` | — |
| disaleLiteCameraFeature | `ro.build.hn_camera2G.enable`（bool，默认 false） | 空 → false |

结论：机型系（isXxxProduct）全部命中不了本机；isFoldProduct* 系走折叠屏管理器。本机不触发任何专属裁剪分支。

### 2. demo/内测门控（实证）

- `isDemoVersion()`：`msc.sys.vendor` 或 `msc.sys.country` 任一 equalsIgnoreCase `"demo"` → true。本机实测 `msc.sys.vendor=all`（或 oemName，两次读值有歧义）、`msc.sys.country=cn` → **非 demo**。
- `isBuildHide()`：先查 `ro.config.rom_update`=true 则直接 false；否则 `ro.build.hide`（bool，走 4 参 getSystemProperty 的 secure 通道）。本机两者均空 → false。
- `getDemoVersionFeatureList()`（private）：读 `msc.demo.feature_control`，**逗号分隔的功能标签数组**，空则返回空数组。语义按调用点逐标签判断，方向不一：
  - `isSmileCaptureDefaultOpen()`：列表含 `camSmileCaptureEnabled` → 默认开（**正向标签**）。
  - `isAigcRecommendDefaultOpen()`：列表含 `camAigcDisabled` → 返回 false（**负向标签**）。
  即该 prop 是 demo 机/内测机的功能清单开关表，可加标签亦可减标签。
- `isDemoVersion()` 调用面（smaliq 实证 26 个类）：`CameraActivity`、`NfcActivity`、`VideoTrackMode`/`TrackVideoFlow`、`AutoWatermarkUtil`、`PreferencesUtil`、`StentsUiData`、美妆/专业/场景识别/增距镜等多个 function 类。demo 机上大概率叠加水印与功能限制（各调用点细节因 MCP 超时未逐点核对，**方向为推测**）。

### 3. 「档位」开关（实证）

- `isForceFullSpec()`：prop `msc.camera.diy.full_spec`（bool，默认 false）。
  实证调用点：`v8/a.n(SilentCameraCharacteristics)` —— **StoryMode（成片故事/模板模式）注册收口**：fullSpec=true 直接跳过 `isStoryModeNotSupportedForLowRAM` 与 `/product_h/etc/camera/storytemplate` 目录检查，无条件注册 StoryMode。强返 true = 低内存机也出故事模式（且需设备上有模板目录或该路径存在才真出 UI，模板文件校验被跳过——**UI 是否可用推测**）。
- `getRsaFeatureTierLevel()`：prop `msc.rsa.feature.tier_level`（int，默认 0）。实证调用点：`CameraUtil.getHdrVividDefaultValue(String, SilentCameraCharacteristics)` —— tier==3 时 HDR Vivid 默认 `"on"`（否则走能力判断/默认 off）。本机 prop 空 → tier 0。**调用面仅 CameraUtil 这一处（smaliq 全库仅 2 个 dex_class：定义+CameraUtil）**。
- `isFeaturePreservedInMagic6()`：prop `ro.features_preserved_6.0`（bool，默认 false）。Magic6 系升 6.0 后保留旧功能的兼容开关。实证调用点 3 个 FunctionBase 子类（混淆类）：
  - `A4/d`（图像调节：亮度/对比度/饱和度/补偿 `image_adjust_*`）：isAvailable/isDeviceSupport 首查该开关，false 直接不可用。
  - `F4/a`（ISO 扩展 `iso_extension`，专业模式 ISO 档位扩展，且限前置才走这路）：同上。
  - `R7/c`（白平衡扩展 `white_balance_extension`）：同上。
  强返 true = 这三个专业模式手动调节项在本机解锁（前提是底层 capability 也支持，**实际成像效果推测**）。

### 4. 本机可 hook 的候选点清单

证据强度：✅实证（smali 逐行核对）｜⚠️推测（效果未真机验证）。

| # | 类#方法 | 改成 | 预期 | 强度 |
|---|---|---|---|---|
| H1 | CustomConfigurationUtil#isFeaturePreservedInMagic6 | true | 专业模式图像调节/ISO扩展/白平衡扩展三组菜单出现 | ✅门控实证，⚠️成像效果推测 |
| H2 | CustomConfigurationUtil#getRsaFeatureTierLevel | 3 | HDR Vivid 录像默认开（仍受 isVideoModeSupportHdrVivid 能力判断） | ✅实证 |
| H3 | CustomConfigurationUtil#isForceFullSpec | true | 低 RAM 机不裁掉 StoryMode；模板目录缺失时仍不出入口 | ✅门控实证，⚠️入口出现与否推测 |
| H4 | CustomConfigurationUtil#isDemoVersion | false | demo 机去水印/解限制；本机本就 false，无收益 | ✅实证（方向推测） |
| H5 | CustomConfigurationUtil#isBuildHide | false | `ro.build.hide` 机型的隐藏裁剪解除；本机本就 false | ✅实证 |
| H6 | CustomConfigurationUtil#isMagicLite / disaleLiteCameraFeature | false / true | Lite 裁剪解除；本机均未触发，无收益 | ✅实证 |
| H7 | getDemoVersionFeatureList 注入标签 | 加 `camSmileCaptureEnabled` 等 | 微笑抓拍等默认值翻转（标签语义逐个核对后可用） | ✅机制实证，⚠️全标签语义未盘点 |

通用注意：全部是**静态方法返回值直改**，类名未混淆、跨版本稳；但方法返回类型简单（boolean/int），改返回值即可，无需改参数。demo 门控走 `msc.sys.*` prop，若设备可写 prop（root `setprop`），H4/H7/H1/H2/H3 均可**零 hook 达成**——先试 `setprop msc.camera.diy.full_spec true`（需重启相机进程）。

### 审计方法注记
- smali 全文经 MT MCP `mt_apk_read_text` + `mt_apk_continue` 分页拉全（单次窗口 40000 字符/2000 行截断，勿信单次结果）。
- MCP 在连续读超大类（CameraActivity）后出现整服超时（>100s 无响应），大类查询要挑小块类、控制节奏。
