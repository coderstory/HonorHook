# HonorHook

基于 **LSPosed（libxposed API 102）** 的定制模块，覆盖荣耀（Honor / MagicOS）系统应用与指定第三方应用。
一个 APK 两个运行面：**hook 侧**注入 22 个作用域包（功能默认全开，行为只改内存态，运行期输出日志判据）+ **UI 侧** Miuix Compose 配置面板（单 Activity 三 Tab，经 `libxposed service` 远端 prefs 下发开关）。

> 文档入口：全部文档索引见 [`docs/README.md`](docs/README.md)；全部挂载点台账见 [`docs/hook-inventory-audit.md`](docs/hook-inventory-audit.md)。

## 功能

**系统应用净化**

- 主题商店 `com.hihonor.android.thememanager`（基线 20.1.63.305）：VIP 状态收口 / 资源免费化 / 试用无倒计时 / 云控开关表驱动 / 广告槽位与底部推广 tab 源头裁剪 / 下载链 VIP 通道 / 动态壁纸应用修复。
- 包管理器 `com.android.packageinstaller`（基线 14.0.37.301，另适配 14.0.22.326 / 14.0.38.301）：不请求广告与 AB 实验 / 安装页广告与推广卡不展示 / 三方跳转不拦截 / 跳过联网安全检测 / 不提示开启安全模式。（现行宿主 14.0.38.301 真机挂载 `11/11`，行为层 2026-10-20 由用户真机实测通过）
- 短信 `com.hihonor.mms`：推广卡片统一清除。
- 设置 `com.android.settings`：应用详情页「停用」按钮恒可点（荣耀对管控应用置灰）/ 首页 HONOR ID 账户小红点隐藏。
- SystemUI `com.android.systemui`：隐藏副卡图标（≥2 卡才生效）/ 隐藏 5G 标识 / 双排信号与双排 5G 制式标 / 替换 WiFi 与状态栏图标 / 隐藏充电·热点·USB 调试通知 / 手电筒忽略低电量。
- 系统桌面 `com.hihonor.android.launcher`（基线 18.0.0.234）：隐藏 YOYO 卡片堆叠标题文本（应用图标名保留）/ 文件夹保持打开 / 清后台保留当前应用（直杀·管家广播二选一，默认关）。
- 系统更新 `com.hihonor.ouc`（基线 18.0.3.230）：禁后台自动检测 / WiFi 自动下载 / 夜间安装 / 升级提醒通知，手动检查更新保留。
- 升级引导 `com.hihonor.android.upgradeguide`：「发现新版本」全屏引导页闪现即退。
- 系统管家服务 `com.hihonor.systemmanager`：病毒检测 / 查杀 / 病毒弹窗三路全禁（安装后「发现病毒应用」弹窗从源头断链）。
- 视频壁纸增强（`com.hihonor.wallpapereditor` + `com.hihonor.livewallpaper.paradise`，功能入口归到「系统桌面」卡）：高画质转码 / 去时长限制 / 循环播放。

**荣耀系应用通杀**

- 埋点通杀：荣耀埋点 SDK（`com.hihonor.hianalytics`）上报链路拦截，事件不落库不出网，覆盖浏览器 / 日历 / 天气 / 游戏中心 / 视频 / 云音乐。
- 广告 SDK 通杀：`com.hihonor.adsdk` 信息流 / 横幅 / 开屏 / 插屏 / 激励全形态渲染层短路。
- 日历运营推荐位：`com.hihonor.osoperationsdk` 服务卡 / 资讯推荐位整链收口。
- 搜狗键盘（荣耀版）`com.sohu.inputmethod.sogou.honor`：候选区功能免同意开启。

**指定应用会员态**

- 成人高考题库 `com.exam8.chengrenGK`（基线 5.9.0.0-release）：会员档位 / 有效期 / 特权等级伪造为超级会员，「阅后即焚」特权 UI 门放行（正文由服务端按订单下发，hook 取不到）。

**指定第三方应用**

- 京东 `com.jingdong.app.mall`（基线 16.1.0）：开屏广告跳过（`SplashFragment` 永不挂载）。
- MFA 身份验证器 `com.aimazhishi.mfa.authenticator`（基线 1.0.5）：开屏广告短路（360 加固，框架 `Activity.onCreate` 收口）。
- 小红书 `com.xingin.xhs`（基线 9.50.0）：解除保存限制 / 接管下载（保存视频零宿主提示、只弹一次模块下载卡）。

## 免责声明

本项目仅供个人学习与研究 Android 框架与逆向工程之用，请于 24 小时内自行删除，勿用于任何商业用途；
因使用本项目产生的任何问题由使用者自行承担。
