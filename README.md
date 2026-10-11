# HonorHook —— 荣耀系统应用净化 / 指定应用会员态模块

基于 **LSPosed（libxposed API 102）** 的定制模块，覆盖荣耀（Honor / MagicOS）系统应用与指定第三方应用。
一个 APK 两个运行面：**hook 侧**注入 6 个目标进程（功能默认全开，行为只改内存态，运行期输出日志判据）+ **UI 侧** Miuix Compose 配置面板（单 Activity 三 Tab，经 `libxposed service` 远端 prefs 下发开关）。

> 文档入口：全部文档索引见 [`docs/README.md`](docs/README.md)（改哪个目标先读对应设计文档）。

## 功能

| 目标 | 能力 |
|------|------|
| 主题商店 `com.hihonor.android.thememanager`（基线 20.1.63.305） | VIP 状态收口 / 资源免费化 / 试用无倒计时 / 云控开关表驱动 / 广告槽位与底部 tab 源头裁剪 / 下载链 VIP 通道 / 动态壁纸应用修复，共 43 个挂载点 |
| 包管理器 `com.android.packageinstaller`（基线 14.0.37.301） | 不请求广告与 AB 实验 / 不展示安装页广告 / 三方跳转不拦截 / 安全模式默认关 / 不提示开启 / **跳过联网安全检测**（无"网络不佳"横幅、无风险拦截页、无指纹校验）/ 隐藏安全模式与推广卡，共 11 个挂载点 |
| 设置 `com.android.settings` | 应用详情页「停用」按钮恒可点（荣耀对管控应用置灰），共 1 个挂载点 |
| SystemUI `com.android.systemui` | 隐藏副卡（SIM2）状态栏信号图标（剔 `slotId==1` 副本，≥2 卡才生效；QS 瓷贴不受影响），共 1 个挂载点 |
| 系统桌面 `com.hihonor.android.launcher`（基线 18.0.0.234） | 隐藏 YOYO 卡片堆叠标题文本（挂 `ThemeUtilImpl.isHiddenTitle()` 恒 true，走宿主自带空态分支），**桌面应用图标名保留**，共 1 个挂载点 |
| 成人高考题库 `com.exam8.chengrenGK`（基线 5.9.0.0-release） | F1 会员态伪造为超级会员（档位/有效期/特权等级，共 7 点）/ F2「阅后即焚」特权门（`VipUtils.hasPrivilege(14)` + 兜底 2 点，共 3 点），合计 10 个挂载点 |

> 注 1：停用管控应用（如「健康使用手机」）的**执行层**受 system_server 保护（root 下 `pm disable-user` 同被拒），
> 解锁需 hook 系统框架，本项目评估后放弃（成本 > 收益，见 docs/hook-inventory-audit.md）。

> 注 2：`exam8` 属**客户端会员态伪造**——翻转本地会员徽标、UI 与客户端功能门。
> 「阅后即焚」的**试卷正文由服务端按订单态下发**（`api.wantiku.com/api/Sprint/Papers`，按 UserId 查订单表），
> hook 只能过 UI 门、**取不到正文**（真机实测：可进入试卷页但显示"加载失败"）。
> 同理，该功能也无法通过"改包固化"获得正文。

全部挂载点台账见 [`docs/hook-inventory-audit.md`](docs/hook-inventory-audit.md)；逐目标设计文档：主题商店
[`theme-store.md`](docs/theme-store.md)（+ 证据层 [`docs/reverse/theme-store.md`](docs/reverse/theme-store.md)）、包管理器
[`docs/reverse/installer.md`](docs/reverse/installer.md)、系统桌面 [`docs/reverse/launcher.md`](docs/reverse/launcher.md)、
SystemUI [`docs/reverse/systemui.md`](docs/reverse/systemui.md)、UI 面板 [`ui-miuix-overview.md`](docs/ui-miuix-overview.md)。
已放弃/悬置目标（wangxiao DEX 虚拟化双壳、s20cxq Flutter AOT、ujigu uni-app、主题引擎零付费逻辑）的结论见
[`docs/README.md`](docs/README.md) 末节。

## 构建

工具链（JDK 21 / Android SDK / adb / apktool / jadx / aapt2）由 `tools/dev.sh` 自解析——**先跑 `tools/dev.sh env` 看路径**，
不在会话里手拼 gradle/adb 命令（说明见 [`tools/README.md`](tools/README.md) 与 skill `honorhook-dev`）：

```bash
tools/dev.sh build           # debug APK
tools/dev.sh build-release   # release APK（混淆 + 资源收缩，debug 签名直出）
tools/dev.sh test            # JVM 单元测试（表驱动决策回归）
```

或直接 Gradle：`./app/gradlew -p app assembleRelease`

## 安装

1. 设备安装 LSPosed（Zygisk）；
2. 安装 release APK → **LSPosed 管理器启用模块**（重装后 LSPosed 会停用模块，需重新启用）；
3. 勾选作用域：主题商店 + 包管理器 + 设置 + 成人高考题库 + 系统桌面 + SystemUI；
4. 强停目标应用后重开；
5. 判据日志：`ThemeStoreHook: installed 43/43`、`InstallerHook: installed 11/11`、
   `SettingsHook: installed 1/1`、`Exam8Hook: installed 10/10`、`LauncherCardTitleHook: installed 1/1`、
   `SystemUiHook: installed 2/2`。

### 一键部署（本仓库开发流程）

```bash
tools/dev.sh deploy-release   # 编译 release → 卸载重装 → 自动重新启用模块 → 强停作用域应用
```

`enable-module` 会自动点击 LSPosed「模块未激活」系统通知（深链直达模块页）并点开启用开关——
通知栏与寄生管理器对 uiautomator 不可见，采用 screencap + PIL 像素定位实现。

### 验证清单（由设备所有者自行执行）

```bash
# 主题商店：下载付费动态壁纸 → 点「应用」应生效（日志：壁纸packagePath 已修正）
# 包管理器：安装任意第三方 APK → 无网络不佳横幅 / 无风险拦截页 / 无指纹弹窗
# 设置：任意应用详情页「停用」按钮可点（非管控应用确认后即停用）
# 系统桌面：YOYO 卡片标题（如「YOYO 建议 · 明日天气预报」）不再显示，桌面应用图标名保留
# 成人高考题库：会员页应显示「超级会员」+ 远期有效期；「阅后即焚」列表不再显示「立即抢购」
#   （试卷正文仍会「加载失败」——正文由服务端按订单态下发，见上方注 2）
```

## CI

| 分支 | 行为 |
|------|------|
| `dev` push | 编译 debug + 单测，上传 APK artifact |
| `master` push | 编译 release 并自动发 GitHub Release（`v<版本>.r<构建号>`） |

## 分支约定

- `master`：可发布的稳定线
- `dev`：日常开发线

## 免责声明

本项目仅供个人学习与研究 Android 框架与逆向工程之用，请于 24 小时内自行删除，勿用于任何商业用途；
因使用本项目产生的任何问题由使用者自行承担。
