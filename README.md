# HonorHook —— 荣耀系统应用净化模块

基于 **LSPosed（libxposed API 102）** 的荣耀（Honor / MagicOS）定制模块。**纯 hook 模块**——
无 Activity、无图标、无设置界面，安装激活后功能默认全部开启，运行期只输出日志判据。

## 功能

| 目标 | 能力 |
|------|------|
| 主题商店 `com.hihonor.android.thememanager`（基线 20.1.63.305） | VIP 状态收口 / 资源免费化 / 试用无倒计时 / 云控开关表驱动 / 广告槽位与底部 tab 源头裁剪 / 下载链 VIP 通道 / 动态壁纸应用修复，共 43 个挂载点 |
| 包管理器 `com.android.packageinstaller`（基线 14.0.37.301） | 不请求广告与 AB 实验 / 不展示安装页广告 / 三方跳转不拦截 / 安全模式默认关 / 不提示开启 / **跳过联网安全检测**（无"网络不佳"横幅、无风险拦截页、无指纹校验）/ 隐藏安全模式与推广卡，共 11 个挂载点 |
| 设置 `com.android.settings` | 应用详情页「停用」按钮恒可点（荣耀对管控应用置灰），共 1 个挂载点 |
| 系统框架 `android`（system_server） | 受保护应用停用解锁：`ProtectedPackages.isPackageStateProtected` 恒 false——「健康使用手机」等管控应用的停用/强停不再被 PMS 静默拒绝（root 下 `pm disable-user` 同被拒），共 1 个挂载点 |

全部挂载点台账与证据链见 [`docs/hook-inventory-audit.md`](docs/hook-inventory-audit.md)；
设计说明见 [`docs/theme-store-hook-design.md`](docs/theme-store-hook-design.md)。

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
3. 勾选作用域：主题商店 + 包管理器 + 设置 + **系统框架（android）**；
4. 强停目标应用后重开；system hook 需**重启手机**生效；
5. 判据日志：`ThemeStoreHook: installed 43/43`、`InstallerHook: installed 11/11`、
   `SettingsHook: installed 1/1`、`SystemHook: installed 1/1`。

### 一键部署（本仓库开发流程）

```bash
tools/dev.sh deploy-release   # 编译 release → 卸载重装 → 自动重新启用模块 → 强停作用域应用
```

`enable-module` 会自动点击 LSPosed「模块未激活」系统通知（深链直达模块页）并点开启用开关——
通知栏与寄生管理器对 uiautomator 不可见，采用 screencap + PIL 像素定位实现。

### 验证清单（由设备所有者自行执行）

```bash
# 主题商店：下载付费动态壁纸 → 点「应用」应生效（日志：壁纸 packagePath 已修正）
# 包管理器：安装任意第三方 APK → 无网络不佳横幅 / 无风险拦截页 / 无指纹弹窗
# 设置：任意应用详情页「停用」按钮可点，确认后实际生效（需 system hook + 重启）
tools/platform-tools/adb.exe shell "su -c 'pm disable-user --user 0 com.hihonor.parentcontrol'"
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
