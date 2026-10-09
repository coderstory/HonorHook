# 荣耀 MagicOS 系统组件预研

> 日期：2026-10-09 · 方法：cs-web-fetch 抓取（共 8 次，预算用尽）· 状态：初步桌面调研，包名清单待 system-files 本地实测补齐
> 注意：本文件内容均来自公开网页（GitHub 仓库为主），单个仓库多标注"AI 生成、未人工长期审计"，引用其 Hook 点位属"社区实证"，最终以真机为准。

## 一、系统组件包名

### 已知结论

| 组件 | 包名/类 | 证据 |
| --- | --- | --- |
| 软件包安装程序 | `com.android.packageinstaller`（**沿用 AOSP 包名**，内部逻辑被 MagicOS 深度魔改，内置安全检测插件） | HonorInstallerPurify README 明确写出 |
| 安装流程安全检测 | 安装器进程内（非手机管家弹窗），检测逻辑在安装器自身 + 内置安全插件 | 同上 |
| 荣耀魔改 PackageParser | `com.android.server.pm.PackageParser` + `android.util.HnPackageUtils`（`Hn` 前缀 = Honor 魔改标志），`HnPackageUtils.notNeedCustomedPermission()` 按 `ro.product.locale.language/region`（zh/CN）分流 | HonorLsposedFix README（含崩溃栈证据） |
| 音量键步进 | Honor 系统设置模块（具体类名未在网页上公开） | honor-volume-master 仓库 |

- `com.android.systemui`：**未查到**荣耀是否定制（MagicOS 10 已到 Android 16，社区普遍认为深度定制，但本次抓取无直接证据），待拉取 system-files 后实测。
- `com.hihonor.*` 组件清单（launcher/设置/相机/手机管家等）：**未查到**完整的公开清单，待 system-files 实测。

### 来源 URL

- https://github.com/VoreulCH/HonorInstallerPurify
- https://github.com/mengwuzhuanshou/HonorLsposedFix

### 未查到处

- bing（中/英文）多次搜索 `com.hihonor 包名/系统应用` 均只返回官方商城/百科页，无包名清单。
- **待办**：以本仓库 `system-files/`（rain-extract 等解包产物）为权威来源拉取 `com.hihonor.*` 实际包名清单。

## 二、荣耀 LSPosed 生态（= 可 hook 点的直接证据）

GitHub 搜 `honor lsposed`（repositories）共 6 个结果，5 个活跃/归档模块。这些模块 hook 的目标点直接对应当前社区认为值得动的点：

### 1. VoreulCH/HonorInstallerPurify（荣耀安装器净化，活跃，4 star，2026-10 仍在更新）
面向 `com.android.packageinstaller`，LSPosed 模块，功能与 hook 点位：
- 跳过安装第三方 APK 的**联网安全检测**：hook 检测总入口 `ly1.h()`，投递官方"免检测通过"结果
- 跳过安装前指纹/锁屏验证：hook `qf2.g()` 直接回调成功
- 禁止安装器联网上报：hook `Socket.connect()`（安装器进程内一切联网立即失败）
- 允许降级安装：安装器侧补 `INSTALL_ALLOW_DOWNGRADE` 标志 + **system_server 侧放行降级校验（需"系统框架"作用域）**
- 隐藏结果横幅 `PackageInstallerActivity.e2()/k2()`、安全模式推广卡 `r12.j()/r12.s()`、屏蔽广告推荐 `AbstractAdBusinessPresenter.l()`、清理缓存提醒 `z62.E()`、断网 2s 超时 `g82.f()`
- 关键事实：MagicOS 安装器每次侧载 APK 必走云端策略 + 本地手机管家扫描，且"禁网/冻结手机管家"都绕不开（检测在安装器自身）；该模块因 hook 面大，需要 system_server/system 作用域

### 2. mengwuzhuanshou/HonorLsposedFix（荣耀特有问题修复，活跃，2026-10-06 更新，1 star）
- **问题**：荣耀魔改 `PackageParser` 解析模块 APK 时调用 `HnPackageUtils.notNeedCustomedPermission()` → `ActivityManager.isSystemReady()`，在 `lspd` daemon 进程内必然 NPE（`ActivityManagerInternal` 为 null）→ 模块被**静默跳过**，不进 system_server 模块列表。**荣耀特有**，同版本 LSPosed 在小米/OPPO 正常。
- 崩溃栈特征：`failed to parse /data/app/.../base.apk` → `NullPointerException: ActivityManagerInternal.isSystemReady()` at `HnPackageUtils.java:213` → `PackageParser.parseReadAppsPermission(PackageParser.java:2967)`
- 修复原理：post-fs-data 阶段 `resetprop ro.product.locale.language=en / region=US`（绕过 isChinaVersion 分支），`boot_completed + 5s` 恢复 zh/CN
- **对本项目最重要**：系统作用域模块在该环境下必须配 KernelSU + Zygisk Next + LSPosed v2.2.0+，且实测机型 荣耀 LDY-AN00 / MagicOS 10 / Android 16 通过

### 3. crossly/honor-installer-hook（2 star）：patch Honor PackageInstaller 提示与检查
### 4. crossly/honor-volume-master（1 star，已归档）：定制音量键步进大小
### 5. crossly/galaxy-watch-plugin-hook（0 star，已归档）：在 MagicOS 上恢复 Samsung Galaxy Watch 插件的伴生检测

### 来源 URL

- https://github.com/search?q=honor+lsposed&type=repositories
- https://github.com/VoreulCH/HonorInstallerPurify
- https://github.com/mengwuzhuanshou/HonorLsposedFix

### 未查到处

- 除此之外未发现更多荣耀专用模块（搜索结果仅 6 个仓库，已全部覆盖）；泛用模块（不针对荣耀）未展开。

## 三、BL 锁 / root 现状

### 已知结论

- **直接搜索（中英文 bing，3 次）均未找到荣耀官方公开的 BL 解锁通道**——搜索结果只有官方商城/百科/零售页，官方解锁服务无公开信息（与荣耀/华为系一贯策略一致）。
- **环境侧实证**（关键）：截至 2026-10-06（3 天前）仍有活跃仓库在**荣耀 MagicOS 10 / Android 16（LDY-AN00）上实测运行 KernelSU + Zygisk Next + LSPosed v2.2.0** 并成功 hook 系统作用域——证明 root + LSPosed 大环境没有新增硬性限制，荣耀设备的 root 生态仍在正常运转。
- KernelSU（而非 Magisk）是荣耀 MagicOS 10 当前社区标配：HonorLsposedFix 依赖清单为 KernelSU + Zygisk Next + LSPosed v2.2.0+。

### 来源 URL

- https://github.com/mengwuzhuanshou/HonorLsposedFix（兼容性表格 + 安装要求）

### 未查到处

- 具体机型（本用户设备）的 BL 解锁方式/收费服务/可行性：未联网查证，且用户侧已具备 root，不需要；如有多个 MagicOS 版本共存（8/9/10）差异，未查。
- 建议：如遇具体设备问题，以真机 `pm list packages` / `getprop` 实测为准。

## 附：对 lsposed-plugin 项目的启示（预研结论）

1. **hook 目标优先考虑 `com.android.packageinstaller` 方向**：社区已有 2 个同类模块且框架初成，证明该方向需求真实、hook 点已被探明（ly1.h / qf2.g / Socket.connect 等）。
2. **系统作用域（system_server）在这条线上是硬需求**：降级校验、禁网等都要进 system_server；而荣耀的 PackageParser NPE 缺陷会让系统作用域模块静默失效——**本项目若做系统作用域，必须自带/依赖 HonorLsposedFix 同款 locale 处理，或先用真机小样本验证模块能注入 system_server 再投入开发**。
3. **包名清单别依赖网络**：网上无完整 com.hihonor.* 清单，直接以本地 `system-files/` 解包产物拉取为准。
4. 三个交叉引用的仓库均为"AI 生成"且标注"未长期人工审计"——其 Hook 点位可作起点，但实现时须以自己反编译为准。