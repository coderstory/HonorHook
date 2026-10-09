# 荣耀专用三插件 → HonorHook 整合方案

> 2026-10-09 · 分析对象：`reference/HonorMarketTamer`、`reference/HonorInstallerPurify`、`reference/HonorLiquidGlassRestore`（刚 clone，均 AI 生成项目）
> 整合目标：`app/`（libxposed API 102 + TargetHook 分发 + TargetRegistry + 远程 prefs，见 `docs/honorhook-theme-requirements.md` 的 F1-F4 模式）
> 纪律：hook 点逐个读源码实证（file:line）；README 宣传与代码实际不符处以代码为准（详见 5.7）

## 一、总览

| 插件 | 目标包 | 解决的问题 | API | 配置方式 | 规模 |
|---|---|---|---|---|---|
| HonorMarketTamer | com.hihonor.appmarket（+health/systemui） | 应用市场广告/推送/页签/推荐流净化（28 开关） | 旧版 Xposed（xposed_init + api-82） | 自研 XSP+conf 文件三级副本 | 21 个 Java 文件 ~1600 行 |
| HonorInstallerPurify | com.android.packageinstaller | 跳过联网安全检测/指纹验证、禁联网、界面净化 | 旧版 Xposed（xposedminversion 93） | 无 UI 无配置，全量常开 | 单文件 225 行 |
| HonorLiquidGlassRestore | systemui + launcher×2（+框架类） | 恢复 MagicOS 11 阉割的液态玻璃 + 参数实时可调 + 锁屏玻璃 | 旧版 Xposed（xposedminversion 82） | setprop persist.sys.lgr.*（2s TTL） | 单文件 1913 行 |

三者全部使用**旧版 Xposed API（de.robv.android.xposed 包）**，无 DexKit（类名硬编码）。HonorHook 全部需经 API 对应关系改写（§5.4），hook 策略本身可直移。

## 二、HonorMarketTamer（mengwuzhuanshou）——市场净化

### 1. 功能定位
荣耀应用市场净化：开屏广告、运营弹窗/悬浮窗、营销推送、自启广播唤醒、游戏/抢鲜/应用页签、搜索热词、推荐流（请求源头拦截）、搜索结果广告位、工具页入口全部可按开关屏蔽；「搜索应用」与「应用更新」永远可用（README.md:12-17）。已实测适配市场 16.1.6.302 / 16.1.7.303 / 16.1.8.301 / 16.1.8.305。

### 2. 技术栈
旧版 Xposed API（`de.robv.android.xposed`，assets/xposed_init 注册）；Java 8 手写构建（javac+dx，无 Gradle）；无 DexKit——全部类名硬编码，多版本适配靠「弹性签名」tryHookFlex（按参数量匹配）+ 同名多方法存档（hookAllMethods 挂多个版本方法名，落空的自然 no-op）。

### 3. hook 目标与作用域
- 主目标 `com.hihonor.appmarket`（MainHook.java:59-140）
- 附加 `com.hihonor.health`（传感器闸门，默认关，MainHook.java:59-77）
- 附加 `com.android.systemui`（指纹解锁震动，默认开，MainHook.java:41-54）

### 4. hook 点清单（实证）
| 功能 | 类 | 方法 | 策略 | 位置 |
|---|---|---|---|---|
| 开屏广告（8 钩子） | CachedSplashScreenService / v3.a / splash.a / fg7 / kg7 / splash.utils.a | d/e/H/l/o（多版本并存） | before 置 null（数据侧返回 null → 原生无广告分支；视图侧置 null → this.m=null 收尾） | hooks/SplashAdBlocker.java:30-130 |
| 运营弹窗/悬浮窗/个性化广告 | OperationDialog 等 | j0/i0（3 参弹性）等 | before 置 null | hooks/DialogBlocker.java:24-85 |
| 营销推送 | AppMarketPushMessageService / push.notification.a / TopNotifyManager | c / a（3 参静态）/ trigger | before 置 null；type 以 "1" 开头的更新族放行 | hooks/PushBlocker.java:25-81 |
| 自启/广播唤醒 | PowerConnectedReceiver / WifiStateChangeReceiver / BootController / WakeMarketProvider | onReceive / J / call / onCreate | before 置 null / 返 false | hooks/WakeBlocker.java:30-50 |
| 底部页签 | MainFrameFragmentTemp$a | d/g/i（六参并行数组同索引一致删除）+ 持久化读写钩子 | after 过滤列表，默认索引/菜单同步修正 | hooks/TabStripper.java:127-285 |
| 页面级屏蔽（37 组 Activity） | android.app.Activity | onCreate / onResume | before 查 BLOCKED_ACTIVITIES 表 → finish() | hooks/ActivityBlocker.java:35-52；TamerConfig.java:143-179 |
| 「我的」页板块 | MarketManageTopAdapter（viewType 2007/2004）/ MarketManageFragment | onBindViewHolder / e0 + onResume/onHiddenChanged/onViewCreated 文案扫描 | after 置 GONE | hooks/MineSectionBlocker.java:81-92,191-202 |
| 搜索框滚动热词 | CommonMainTitleView | A / F | before 置 null | hooks/RollWordBlocker.java:36-59 |
| 推荐流（请求源头拦截） | MarketManageViewModel / MarketManageFragment / SearchActivationViewModel / SearchActivationFragment / AppDetailRecommendViewModel / AppDetailsActivity$requestMiddle$1$1 / DiscoverMoreAdapter / UpdateManagerActivity | o/n/Z1/W1/V1/A1/y1/w1/h/invokeSuspend/getItemCount/access$setRecommendResponseData 等 | before 置 null / 空列表 / getItemCount→0（协程桥接 invokeSuspend 置 null 吞请求） | hooks/RecommendFeedBlocker.java:119-579 |
| 搜索结果广告位 | AssSearchActivationAdapter / CommAssAdapter | w1（首屏）/ G0（加载更多）/ A0（父类重建路径） | after 过滤广告 bean 落 sink | hooks/SearchAdBlocker.java:61-93 |
| 工具页运营入口 | ToolPageActivityEntryView | b(String) | before 置 null（void 方法、调用方不消费返回值） | hooks/ToolPageEntryBlocker.java:43-50 |
| 健康传感器闸门（熄屏拒注册/注销、亮屏重放） | android.hardware.SensorManager | registerListener / unregisterListener | before 熄屏时 setResult(false) + 会话缓存（上限 16）；亮屏重放 | hooks/HealthSensorGate.java:72-123 |
| 指纹解锁震动 | KeyguardUpdateMonitor | onFingerprintAuthenticated | after 取 mContext 震动 20ms（全反射） | hooks/FingerprintVibration.java:38-55 |

### 5. 配置界面与机制
纯代码 SettingsActivity.java（454 行，28+1 开关，每个带英文副标题）。Hook 侧配置读取为**三级副本**（TamerConfig.java:403-507）：①host-conf（市场自己 files/，经 ConfigRelay 从启动 Intent extras 重建，hooks/ConfigRelay.java:55+，挂市场 launcher Splash.onCreate/onNewIntent）→ ②模块 files conf → ③XSharedPreferences 兜底；另有 content:// Provider IPC 直拉通道（ConfigProvider.java）与 warm 直读 module SP（refreshFromModuleSp，TamerConfig.java:267-303）。
**这套配置机制（XSP + 3 副本 + Provider + 启动拉起中继）在 HonorHook 中整体废弃**，由远程 prefs（getRemotePreferences）替代——这是本插件移植最大的减负项。

### 6. 已知坑（PITFALLS.md + 代码注释）
- LSPosed 2.3.0 移除 XSharedPreferences：safeXsp() 吞异常返 null 降级 conf 文件链路，否则整个 handleLoadPackage 崩、模块自灭（TamerConfig.java:48-71）；
- conf 文件传输损坏曾把 master 解析成 false → 模块整体自灭，现严格布尔解析、非 true/false 整键跳过（TamerConfig.java:490-493）；
- dx 不支持 lambda（VerifyError），全匿名内部类（MainHook.java:125-126）；
- 混淆市场不同版本类名漂移：靠多版本钩子并存兜底，LSPosed 日志 "armed" 行确认命中（README.md:45-49）；
- Provider 拉取节流 10s + 代次（conf_gen）单调保护，旧代次不覆盖新代次（TamerConfig.java:232-240）。

## 三、HonorInstallerPurify（VoreulCH）——安装器净化

### 1. 功能定位
跳过荣耀安装器安装第三方的**联网安全检测与安装前指纹/锁屏验证**，点开即装；禁止安装器联网上报；隐藏「安全模式」卡片/「未发现风险」横幅；屏蔽「用过该应用的还喜欢」推荐与「清理缓存」提醒；断网不再死等 2s 超时（README.md:12-50）。

### 2. 技术栈
旧版 Xposed API（ecj+d8+aapt2 构建；api-82.jar 仅编译期；xposedminversion=93）；AndroidManifest minSdk 29 / targetSdk 34。单文件 MainHook.java（225 行）。

### 3. hook 目标与作用域
`com.android.packageinstaller`（manifest xposedscope 数组仅此项）。无 system_server 钩子。
**README 特性表声称「允许降级安装」需勾『系统框架』（README.md:44）——当前 v4.0 代码中不存在该 hook**（全文件无 INSTALL_ALLOW_DOWNGRADE / system_server 相关，§5.7 详述）。

### 4. hook 点清单（实证，MainHook.java）
| 功能 | 类 | 方法 | 策略 | 位置 |
|---|---|---|---|---|
| 跳过联网安全检测 | ly1（混淆名） | h(boolean, sy1) 检测总入口 | XC_MethodReplacement：调官方「可信来源免检测」结果工厂 ly1.a(isHn) 构造通过结果，主线程回调 sy1.b(oy1, jz1Proxy)（Proxy 实现 jz1 接口） | L144-199 |
| 跳过安装前指纹 | qf2 | g(Context, qf2$a) | 替换：直接回调 cb.c() + cb.a(true)（安装流指纹入口；设置页入口 qf2.h 刻意不挂） | L201-224 |
| 禁止联网上报 | java.net.Socket | connect(SocketAddress, int) | before setThrowable(SocketException)——安装器进程内一切联网即失败 | L71-84 |
| 安全模式卡片 | r12 | j(..., CardTipsRecord, String, boolean, String) / s(PackageInstallerActivity, RbiTempBean) | 恒返 false / 置 null（显示决策+底部弹窗，安装前后页面通杀） | L86-103 |
| 广告推荐条 | AbstractAdBusinessPresenter | l(kt1, RbiTempBean, ef2, boolean) | 置 null——三个页面的 Presenter 均继承该方法 | L105-114 |
| 「未发现风险」横幅 | PackageInstallerActivity | e2()/k2() | after 取字段 W0/Y0 置 View.GONE | L116-142 |
| 「清理缓存」提醒 | z62 | E(SafeModeCards) | 恒返 true | L60-69 |
| 断网 2s 死等 | g82 | f(String×4, long, String, int, long, int, int, g82$d, Runnable) | 替换：反射调 d.a(null) 直接走失败分支（2178ms→198ms） | L38-58 |

### 5. 配置界面
无 UI、无配置，全量功能常开（唯一「开关」是 LSPosed 作用域勾选）。安全敏感：**跳过安全检测削弱恶意应用拦截**（README 免责声明 README.md:174）。

### 6. 已知坑
- 混淆类名版本漂移：MagicOS 10（v3.2）与 MagicOS 11（v4.0，2026-09-30）安装器混淆完全不同，4.0 重新适配（README.md:149-151）；装错版本不崩、静默失效；
- 检测链为「安装器自身 + 内置 HnSecurityPluginBase」，禁网/冻结手机管家绕不开（README.md:25-28）——hook ly1.h 是唯一有效入口；
- 需要主线程投递回调（Handler(mainLooper).post，L178）——回调线程约束是移植重点。

## 四、HonorLiquidGlassRestore（VoreulCH）——液态玻璃恢复

### 1. 功能定位
系统更新把控制中心/通知中心「液态玻璃」降级为普通磨砂（三层阉割：MachineConfig 判 COMPAT 机型降级、材质位配置 11→1、mBlurType 缓存为磨砂 0x10000）。模块在 SystemUI 与桌面进程还原 LIQUID 渲染路径，并把锁屏通知卡/快捷按钮纳入玻璃；全参数 setprop 实时可调（遮罩/模糊半径/光泽/折射/厚度/色散/边光），一键套用桌面文件夹原生配方，定稿默认值烤入 APK（README.md:12-24, 56）。

### 2. 技术栈
旧版 Xposed API（xposedminversion 82）；单文件 MainHook.java（1913 行）；配置 = SystemProperties persist.sys.lgr.* + 2s TTL 缓存（refreshProps L295-320）+ 烤入默认值；无 UI。

### 3. hook 目标与作用域
`com.android.systemui`、`com.hihonor.android.launcher`、`com.hihonor.desktop.systemui`（arrays.xml 三包）。另有 bootclasspath 框架类（com.hihonor.android.app.UiModeManagerEx / com.hihonor.android.content.res.ConfigurationEx）——旧模块在 SystemUI 进程内可直接 hook（进程内可加载即命中），移植到 libxposed 102 同样成立（HookEntry 已按此模式 hook android.app.Application.onCreate）。

### 4. hook 点清单（实证，MainHook.java）
| 功能 | 类 | 方法/构造器 | 策略 | 位置 |
|---|---|---|---|---|
| LIQUID 材质位（核心） | TransparentModeManager（com.hihonor.systemui.magic.transparentmode） | getTransparentModeState（大方法 AOT 不内联） | after 改写字段 mBlurType = 0x20000（改字段而非返值——后续内联读点全命中） | L329-349 |
| 液态判定兜底 | 同 | isLiquidBlurType / isFrostedBlurType | after 透明开时强 true / 恒 false | L351-364 |
| 面板模糊半径 | 同 | getTransparentBlurRadius | after 返回值 × radius 倍率 | L366-388 |
| 磁贴玻璃参数 | BlurParametersConfig（com.hihonor.systemui.magic.blur） | initBulrParams（大方法） | after 批量字段缩放：9 个遮罩色 + 4 个数组 × mask；通知卡 nmask 绝对底色调色；refraction/depth/thickness/dispersion 分调；folder 配方绝对值（0.3/0.54/0.62/0.2）；tblur 二次模糊 | L469-649 |
| 面板遮罩/光泽（最大杠杆） | ShotBlurView（com.android.magic.blur） | setBlurParams(radius, maskColor) | before 改参：sat 缩放 arg0、mask alpha 缩放 arg1 | L907-940 |
| 边光宽度/亮度 | EdgeLightParamEx（com.hihonor.android.graphics.material.atomiceffect） | 构造器(250, 6.0f) / setColor(int) | 构造器 before 缩放 6.0→×edge（构造器不内联；防叠乘只缩放 6.0 原值）；setColor before 缩放 alpha ×rim | L950-1017 |
| 控件透明参数 | AbsHighGradeDegreeAlgorithm.withTransparency（uikit 框架） | withTransparency（大方法） | after 重置 currentTransparency + 缩放 lightBlurParam/darkBlurParam 的 radius/alphas/maskColors（ColorHelper.composeColors 重算） | L403-466 |
| 框架数据源 | UiModeManagerEx（com.hihonor.android.app） | getTransparentTexture / getTransparentDegree | after 恒 2（LIQUID）/ 抬升到 85 | L732-774 |
| 机型强制 HIGH | MachineConfig（uikit config） | getInstance / isHighMachine / isCompatMachine / getMachineType | after 改 3 字段；后三者恒 true/false/返回 HIGH 枚举 | L815-860 |
| 材质类型改判 | StyleType.getMaterialType（uikit style） | getMaterialType | after 透明模式下 FROSTED→LIQUID | L862-892 |
| 材质位配置 | TransparencyFeatureConfig.getConfigTransparentModeMaterial | 同 | 恒返 3（LIQUID 特效位恢复） | L804-813 |
| 系统配置位 | ConfigurationEx（框架） | getTransparentMode | after BLUR_TYPE_MASK 内 FROSTED 位→LIQUID | L698-729 |
| 锁屏玻璃位图合成（SystemUI only，默认开） | KeyguardWallpaper | getCurrentBlurBitmapForBg / processWallpaperChange | after：stock 为 null（静态壁纸无 BokehDrawable）时从锐利壁纸经原厂 ViewBlur.blurBitmap 合成轻量位图（kbblur/kveil/kbbright），缓存 + fireKgReapply（blurBitmapChanged + HwDependency.get(HnViewBlurController).blurWallpaperBitmap + engine onWallpaperChanged） | L1043-1160, 1407-1582 |
| 锁屏快捷按钮玻璃（默认开） | ViewBlur（com.hihonor.keyguard.support） | blurIcon(view, i, z) | before 改走 5 参 ViewEx 重载（纯 stock 代码路径），startup race 后重放 | L1186-1290 |
| 锁屏边光门 | NotificationBackgroundViewModel | turnOnViewBlur(view, config) | before/after 抬/放 sKgCardNow 旗（kedge 仅作用锁屏卡） | L273-293 |
| 纯观测（无行为） | KeyguardBlurUtils/ViewBlur/KeyguardWallpaper/BokehDrawable/KeyguardBusinessDataProvider/ToolBoxView/GlassParamEx/HnMaterialManager | 多条 | 仅 kdiag=1 时打日志 | L1679-1912 |

### 5. 配置界面
无 UI。`persist.sys.lgr.*` 16 个属性（mask/radius/tblur/sat/nmask/folder/refract/thick/disp/edge/rim/kbblur/kveil/kbbright/kedge/kbg/keng/kicon），2s TTL 生效；清空属性回落烤入默认值（README.md:106-142）。三把锁屏逃生闸（kbg/keng/kicon 全 0 + 重启 SystemUI = 完全回退原厂锁屏）。

### 6. 已知坑（类注释安全规则 L75-84 + README）
- **只 Hook 大方法或构造器**：LegacyBridge/Vector 管理器无 deopt，小 getter 被 AOT 内联绕过——本模块目标方法全部内联免疫设计（README.md:78，实测 `hookMethod(单 Method)` 被 LegacyBridge 忽略，必须 hookAllMethods，L990-991）；
- **改字段而非改返回值**：上游改写被缓存状态字段，让内联读取者直接见修正值；
- **不碰共享位图**：渲染端 getPanelBitmap() 优先返回 [0] 号位图，按索引暗化会串面板（通知卡改卡片级着色）；
- v2.3 事故：handleLoadPackage 里 Class.forName(initialize=true) 触发系统类 <clinit>，SystemUI 孵化期 boot-loop；**禁 Class.forName 初始化** + 每钩子体独立 try/catch + 未验证钩子默认关；
- 硬编码资源 id 曾翻转无关布尔随机弄坏 SystemUI——资源 id 一律运行时 getIdentifier 解析；
- 全量 AOT 重编译后可绕过 hook：`cmd package compile -m interpret-only -f com.android.systemui` + 重启恢复（README.md:146）；
- 锁屏合成位图需像素级对齐壁纸几何（getBokehScaleValueForLockScreen 缩放注册，L1468-1499），否则玻璃读作贴纸。

## 五、整合方案（HonorHook）

### 5.1 目标 → TargetHook 映射
| 新目标文件（app/.../xposed/target/） | 包名（TargetRegistry 键） | 来源插件 |
|---|---|---|
| `MarketHook.kt`（新建） | com.hihonor.appmarket | HonorMarketTamer 全量 |
| `InstallerPurifyHook.kt`（**填 HonorInstallerHook.kt 占位**，现有文件 TargetRegistry.kt:21-23 注释解除） | com.android.packageinstaller | HonorInstallerPurify 全量 |
| `GlassRestoreHook.kt`（新建） | com.android.systemui / com.hihonor.android.launcher / com.hihonor.desktop.systemui（**同一 object 注册 3 键**——TargetRegistry 按单包名查，内部按 lpparam.packageName 过滤，MarketTamer MainHook.java:41-70 同款多分支形态） | HonorLiquidGlassRestore |

架构贴合度：HookEntry 的 isFirstPackage 门控 + TargetRegistry 防重装 + 每目标 install() 一次，与三插件的进程分发语义完全一致；MarketTamer 多进程分支（systemui/health/market 各自独立）在 102 下用「多包名注册同一个 object」表达。

### 5.2 功能开关扩展（HookSettings.kt）
F1-F4（主题）保持不动，新增功能位键（data/HookSettings.kt:29-34 的 FEATURE_DEFAULTS 追加 + 默认值表 + setFeatureEnabled 同款双写，UI 加在 HookSettingsScreen 或新 Settings 分页）：

| 新键 | 默认 | 语义 |
|---|---|---|
| `f5_market_enabled`（F5） | false | 市场净化总开关（MarketTamer 全套） |
| `f6_installer_enabled`（F6） | false | 安装器净化总开关（**安全敏感：跳过安全检测削弱防护，默认关**） |
| `f7_glass_enabled`（F7） | false | 液态玻璃总开关（面板+锁屏全链；有 boot-loop 事故史，保守默认关） |
| `f7_glass_keyguard`（F7 子位） | false | 锁屏玻璃链（kbg/keng/kicon 合成+图标重定向，对应原 kbg 等属性） |

建议：**不加到 28 个市场子开关**——MarketTamer 内部默认值即推荐配置（README.md:33），F5 开启即按推荐配置生效；个别子开关（如 game_tab、rollword 等原默认关项）需要细调时再按需补键（hook 侧 intercept 现读，加键无成本）。LiquidGlass 的光学参数（mask/radius/sat 等）保持 setprop 通道（需 root 的 adb 操作），不进 UI——UI 只放开关位，避免 16 个滑杆的工程量。

### 5.3 scope.list 扩展
现有仅 `com.hihonor.android.thememanager`。追加（module.prop 已 staticScope=true，scope.list 全量声明即可）：
```
com.hihonor.appmarket
com.android.packageinstaller
com.android.systemui
com.hihonor.android.launcher
com.hihonor.desktop.systemui
```
com.hihonor.health 可不加（F5 的传感器闸门默认关；需要时再加 scope + 子开关）。注意：加 systemui 后 LSPosed 需重启生效。

### 5.4 旧 Xposed API → libxposed 102 对应关系（移植改写法）
| 旧 API | libxposed 102 写法（ThemeStoreHook 已有范式） |
|---|---|
| IXposedHookLoadPackage.handleLoadPackage | XposedModule.onPackageLoaded（HookEntry 已就位） |
| hookAllMethods(clazz,"n") / findAndHookMethod | clazz.declaredMethods.filter{ name } 逐个 `hook(m).setExceptionMode(PROTECTIVE).setId("honorhook:...")`；构造器用 declaredConstructors |
| XC_MethodReplacement.returnConstant(v) | intercept { chain -> v } |
| before setResult(null) 短路 | intercept { chain -> null }（不调 proceed） |
| after 改返回值 | val r = chain.proceed(); 改后返回 r |
| before 改参数 / setThrowable | 改 chain.args / throw |
| XposedHelpers 字段读写 | java.lang.reflect.Field（ThemeStoreHook 的 addForceArg 已示范） |
| XSharedPreferences 三级副本 | 全部废弃 → module.getRemotePreferences("honorhook") 每次 intercept 现读（ThemeStoreHook.kt:163-169 已示范，含失败降级全放行） |
| java.lang.reflect.Proxy 回调（安装器 sy1/jz1） | 原样可用（纯 Java 反射） |
| Class.forName(...) | 禁 initialize=true（boot-loop 史）；用 loader.loadClass + runCatching |
| 懒加载重试 | ThemeStoreHook 已有：解析失败的 MountTask 收集 + Handler postDelayed 500ms/2s 重试（ThemeStoreHook.kt:102-107） |

关键差异提醒：102 下 hook 必须持有 Method 对象——「类/方法漂移」从静默落空变为显式解析失败，恰好接入现成的懒加载重试 + DexKit 双轨兜底（HookEntry.kt:135-163，市场/安装器混淆名漂移场景的免费升级）。

### 5.5 代码复用评估
| 插件 | 可直移 | 需重写 | 结论 |
|---|---|---|---|
| InstallerPurify | hook 逻辑 ~90%（8 组 hook 全部「置 null/恒返/回调」形态 1:1 映射到 intercept）；Proxy 回调、主线程投递原样保留 | 无——无 UI 无配置 | **几乎全文移植** |
| LiquidGlassRestore | 面板 10 组 hook（大方法+字段改写模式在 102 下不变）；构造器 hook（EdgeLightParamEx）照挂 | 锁屏合成链（ensureLightBitmap/fireKgReapply/reapplyIconTargets，约 600 行）建议二期（kdiag 观测先行）；SystemProperties 反射读 + 2s TTL 缓存照搬 | **核心直移，锁屏二期** |
| MarketTamer | 全部 Blocker 的「before 置 null/filter 列表」形态可直移；多版本钩子并存手法保留 | 配置机制整体删除（XSP/3 副本/Provider/ConfigRelay/启动拉起 = ~500 行 Java）；28 开关归组进 prefs；ActivityBlocker 的 Activity 表原样搬 | **逻辑直移 + 配置机制大砍** |

### 5.6 依赖新增
**无新增**。DexKit 2.2.0 已在 app/build.gradle.kts:76；遥测/日志用现成 module.log。三个插件均无第三方运行时依赖（api-82 编译期且由框架提供）。

### 5.7 README 与代码不符（移植以代码为准，已实证）
- **InstallerPurify「允许降级安装」代码中不存在**：README 特性表列此功能并要求勾『系统框架』作用域（README.md:44），但 v4.0 MainHook.java 全文件无降级相关 hook，scope 数组也只有 packageinstaller；git 显示 HEAD=v4.0（57eaa2b「Update README for 4.0」）。此为 README 残留宣传（4.0 changelog 只删了「不同签名覆盖安装」）。**移植不包含降级安装**（如需补，Hook `InstallParams`/system_server 降级校验属另一工作量，且需 android 作用域）；
- **InstallerPurify manifest 版本号与 README 对不上**：manifest versionCode=4/versionName=2.2 vs README 声称 4.0/versionCode 13——构建产物与文档漂移，不影响移植；
- MarketTamer README 说 28 开关，TamerConfig.ALL_KEYS 实为 29 键（多 fingerprint_vibrate）——以 ALL_KEYS 为准；
- LiquidGlassRestore HEAD 2.5.18（f273f00）与代码一致（MainHook.java:18 自报 v2.5.18）。

## 六、整合工作量分级（建议顺序）

| 插件 | 级别 | 依据 | 建议 |
|---|---|---|---|
| **HonorInstallerPurify → F6** | **小**（1 个 target 文件 ~250 行） | 单文件、无配置、hook 形态最简；HonorInstallerHook.kt 占位已存在，只需填实现 + 解除注册注释 + scope 一行 | 最先做——最快见真机收益（点开即装） |
| **HonorLiquidGlassRestore → F7** | **中**（面板核心 ~800 行直移；锁屏二期 ~600 行） | 面板 hook 全部大方法/构造器形态、模式成熟；风险点集中在 boot-loop 史纪律（禁 Class.forName init/禁硬编码资源 id）与 SystemUI 真机验证成本；锁屏合成链独立可拆 | 面板先（F7 主键），锁屏链作为 f7_glass_keyguard 子位二期 |
| **HonorMarketTamer → F5** | **大**（~1000 行移植 + UI 归组） | 14 个 hook 文件、37 组 Activity 表、28+ 开关归组与 UI 开关页扩展；多版本兼容钩子存档需保留；混淆名漂移依赖 DexKit 双轨验证 | 最后做；分两批：广告/推送/唤醒（Splash/Dialog/Push/Wake/ToolPage）→ 页面/推荐流（Tab/Mine/Feed/Search/Activity） |

真机验证三条通用前置：三插件均为「类名漂移 = 静默失效」设计，HonorHook 的懒加载重试 + armed 日志（LSPosed 日志过滤各 target TAG）必须保留；新增 systemui 作用域后重启；F6 敏感提示（跳过安全检测）写进 UI 开关副标题。