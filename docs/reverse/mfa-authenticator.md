# MFA身份验证器（爱码仕）`com.aimazhishi.mfa.authenticator` — 屏蔽开屏广告逆向

> 基线 **1.0.5（versionCode 5）**（2026-10-12 真机 `dumpsys`/`pm path` 实证，设备 AAK-AN00）。
> 反编译产物：`system-files/MfaAuth-1.0.5-jadx/`（jadx 默认参数；**仅出 5 个类**，见下）。
> 状态：**调研期**——只有逆向实证，无任何 hook 代码。

## 一、壳判定（静态读码死路）

- `com.stub.StubApp`（manifest application）+ `com.tianyu.util.DtcLoader` + `assets/libjiagu_a64.so` = **360 加固**。
- jadx 产物只有 stub/资源类，真实 dex 运行时内存解密 ⇒ 业务代码（含 `MfaSplashActivity` 逻辑）静态不可读。
- 与 wangxiao DEX 虚拟化双壳不同：360 壳类名/组件名在 manifest 保留、运行时类可经 app ClassLoader 解析 ⇒ **hook 不需要脱壳**，但需 classloader 捕获模式。

## 二、广告面（真机实证，2026-10-12 冷启动）

- 冷启动链：LAUNCHER = `com.aimazhishi.mfa.authenticator.Activities.MfaSplashActivity`（manifest :124，exported）→ 全屏视频开屏广告（千问推广：立即进入/跳过/上滑跳转/右下「广告」标，穿山甲形态）→ `Activities.MainActivity`。
- 广告窗口内 View 树实证：广告容器 resource-id `com.aimazhishi.mfa.authenticator:id/layoutAdContainer`（app 自有布局 id）；广告渲染子树 content-desc `NativeVideoAdView` + 一组 hex desc（`749141`/`a6c63b`…）= 穿山甲模板的无障碍节点。
- 穿山甲 SDK 实证：manifest 权限 `...openadsdk.permission.TT_PANGOLIN` + `TTFileProvider`；native 库全套（`libpanglearmor.so`/`libpangleflipped.so`/`libtt_ugen_layout.so`/`libttmplayer_lite.so`/`libPglbizssdk_ml.so`）。
- SDK 运行期日志近乎静默（logcat 无 openadsdk/TTAd 行）⇒ 判据不能依赖宿主日志，只能靠行为（冷启动无广告）。

## 三、挂载点候选（按稳序）

| 点 | 锚点性质 | 副作用 / 风险 |
|---|---|---|
| **主选** hook `MfaSplashActivity.onCreate` hookAfter → `finish()` + `startActivity(MainActivity)` | 类名来自 manifest（**跨版本最稳锚点**）；360 壳下需等真实 dex 类加载（classloader 捕获/Class.loadClass 等待模式，先例 sms-ads 载体捕获） | 跳过 splash 内全部逻辑（壳内不可读）：清单有 `REQUEST_INSTALL_PACKAGES`，若应用内更新初始化挂在 splash 需回归验证；无广告时也直接进主页（体验只快不慢） |
| 备选 hook 穿山甲开屏 API（`com.bytedance.sdk.openadsdk.TTAdNative.loadSplashAd` 系）→ 回调直接 fail | SDK 类名未混淆但随 SDK 版本漂移；View desc 显示 `NativeVideoAdView`，实际走的 API 形态（标准 splash vs native 模板）**需运行时探针确认** | 同样需 classloader 捕获；只杀广告保留 splash 品牌页，改动面更小但锚点更脆 |
| 拒绝：hook `layoutAdContainer` 的 addView 清子 view | 资源 id 数值逐版变，须 getIdentifier；addView 泛挂波及全 app | 复杂且误伤面大 |

## 四、边界

- 广告请求照发（只砍展示）；穿山甲初始化/画像采集链不动。
- 跳页方案下 splash 的初始化逻辑全部跳过 —— **未脱壳前无法枚举清单**，落地时以真机回归覆盖：TOTP 正常生成、备份/恢复、设置、应用内更新检查。

## 五、待验证（落地后）

- 冷启动直达 `MainActivity`、无广告帧闪现；`installed N/M` 含新目标。
- classloader 捕获在 360 壳下的挂载时序（壳 attach 后真实类才可解析；挂早了 NOT_RESOLVED，须随类加载重试，项目已有失败分级纪律）。
- 应用内更新/下载路径不受影响（`REQUEST_INSTALL_PACKAGES` 存在的用途需回归确认）。

## 六、落地接线（同 jd.md §六）

`TargetRegistry` 新建 target → `scope.list` 加 `com.aimazhishi.mfa.authenticator` → `FeatureCatalog` 加开关条目。壳内类 hook 统一走「捕获 app ClassLoader + 按名解析 + 失败 NOT_RESOLVED 随类加载重试」。
