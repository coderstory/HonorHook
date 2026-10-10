# HonorHook —— 荣耀系统应用净化 / 指定应用会员态模块

基于 **LSPosed（libxposed API 102）** 的定制模块，覆盖荣耀（Honor / MagicOS）系统应用与指定第三方应用。**纯 hook 模块**——
无 Activity、无图标、无设置界面，安装激活后功能默认全部开启，运行期只输出日志判据。

## 功能

| 目标 | 能力 |
|------|------|
| 主题商店 `com.hihonor.android.thememanager`（基线 20.1.63.305） | VIP 状态收口 / 资源免费化 / 试用无倒计时 / 云控开关表驱动 / 广告槽位与底部 tab 源头裁剪 / 下载链 VIP 通道 / 动态壁纸应用修复，共 43 个挂载点 |
| 包管理器 `com.android.packageinstaller`（基线 14.0.37.301） | 不请求广告与 AB 实验 / 不展示安装页广告 / 三方跳转不拦截 / 安全模式默认关 / 不提示开启 / **跳过联网安全检测**（无"网络不佳"横幅、无风险拦截页、无指纹校验）/ 隐藏安全模式与推广卡，共 11 个挂载点 |
| 设置 `com.android.settings` | 应用详情页「停用」按钮恒可点（荣耀对管控应用置灰），共 1 个挂载点 |
| 成人高考题库 `com.exam8.chengrenGK`（基线 5.9.0.0-release） | F1 会员态伪造为超级会员（档位/有效期/特权等级，共 7 点）/ F2「阅后即焚」特权门（`VipUtils.hasPrivilege(14)` + 兜底 2 点，共 3 点），合计 10 个挂载点 |

> 注 1：停用管控应用（如「健康使用手机」）的**执行层**受 system_server 保护（root 下 `pm disable-user` 同被拒），
> 解锁需 hook 系统框架，本项目评估后放弃（成本 > 收益，见 docs/hook-inventory-audit.md）。

> 注 2：`exam8` 属**客户端会员态伪造**——翻转本地会员徽标、UI 与客户端功能门。
> 「阅后即焚」的**试卷正文由服务端按订单态下发**（`api.wantiku.com/api/Sprint/Papers`，按 UserId 查订单表），
> hook 只能过 UI 门、**取不到正文**（真机实测：可进入试卷页但显示"加载失败"）。
> 同理，该功能也无法通过"改包固化"获得正文。

全部挂载点台账与证据链见 [`docs/hook-inventory-audit.md`](docs/hook-inventory-audit.md)；
设计说明见 [`docs/theme-store-hook-design.md`](docs/theme-store-hook-design.md)；
逆向分析见 [`docs/wangxiao-reverse-analysis.md`](docs/wangxiao-reverse-analysis.md)（DEX 虚拟化双壳）、
[`docs/s20cxq-analysis.md`](docs/s20cxq-analysis.md)（Flutter AOT）。

## 构建

需要 JDK 17+ 与 Android SDK（工具链放置约定见 [`tools/README.md`](tools/README.md)）：

```bash
tools/dev.sh build           # debug APK
tools/dev.sh build-release   # release APK（混淆 + 资源收缩，debug 签名直出）
```

或直接 Gradle：`./app/gradlew -p app assembleRelease`

## 安装

1. 设备安装 LSPosed（Zygisk）；
2. 安装 release APK → **LSPosed 管理器启用模块**（重装后 LSPosed 会停用模块，需重新启用）；
3. 勾选作用域：主题商店 + 包管理器 + 设置 + 成人高考题库；
4. 强停目标应用后重开；
5. 判据日志：`ThemeStoreHook: installed 43/43`、`InstallerHook: installed 11/11`、
   `SettingsHook: installed 1/1`、`Exam8Hook: installed 10/10`。

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
