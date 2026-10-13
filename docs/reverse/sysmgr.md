# 系统管家服务 `com.hihonor.systemmanager` — 逆向分析与实现

> 基线 **17.0.0.414**（system-files/sysmgrsvc-jadx 反编译实证，2026-10-13）
> 目标源码 `app/.../xposed/target/SysmgrHook.kt`
> 反编译产物：`system-files/sysmgrsvc-jadx`

## 结论一句话

弹窗文案「发现病毒应用」全系统只在 `com.hihonor.systemmanager`（R.string.risk_dialog_virus_title，sysmgrsvc resources/res/values-zh-rCN/strings.xml 实证）。杀毒功能自带总开关 `AntiVirusTools.hideVirusPrefer():755` → `SystemPropertiesEx.getBoolean("msc.config.hide_virus", false)`（厂商生产代码原生支持的「隐藏病毒功能」配置，海外/定制版在用）。

## 两条链图谱

### 链 A · 安装前预检（安装器进程发起，跨进程到本 app 执行）

```
PackageInstallerActivity:1847 ly1.h（已由 InstallerHook R6 CHECK_BYPASS 短路）
  → py1:479/qy1:463（systemManagerCheck）
    → bz1:23 HwSecurityManager.checkUninstalledApk(apkPath, pkg, 5000ms)  [SDK IPC]
      → AntivirusPluginEx.call():84（method name "checkUninstalledApk"）
        → CheckUninstallApkCaller.call(Bundle)  [:207]
          → ScanApkFileService（Messenger: msg.what=1 → 回 what=2 scan_result）
            → generateResult(:196-201) → Bundle{result_code, virus_type}
```

- virus_type 消费侧映射（bz1:38-53）：1=恶意 2=病毒 3=欺诈 4=骚扰 5=病毒禁止
- **CheckUninstallApkCaller.call 自带 calledBySubUser 分支（:223-226）直接 `generateResult(0,0)` 返回**——即「跳过扫描返回未检出」的宿主原生语义

### 链 B · 安装后检测（本 app 自主，弹窗真实来源）

```
PACKAGE_ADDED → AntiVirusReceiver.onReceive:157-162
  （AntivirusPluginEx.initBroadcast:62-67 动态注册）
  → AntiVirusTools.checkVirusForNewInstalledApk:384
    → startService(AntiVirusService, "hihonor.intent.action.antivirus.scanonepackage")
      → 扫描 → AntivirusScanObserver.onInsert(NEW_APP_INSTALL_SCAN):303
        → processNewInstallVirus:350
          → VirusAppsManager.insertVirusApp（病毒库落库+静默管控）
          → SecurityThreatsUtil.notifyNewInstallVirusToService:190
            → notifyVirusToUI:169
              → Intent(ACTION_VIRUS_NOTIFY)
                → VirusNotifyService.onStartCommand:258
                  → showHoldDialog:198
                    → VirusNotifyDialog.show:423（TYPE_APPLICATION_OVERLAY）
                      标题 R.string.risk_dialog_virus_title =「发现病毒应用」
```

弹窗服务还接：闲时全盘扫描（FreeScanNotifyManager:158）、云端推送（SecurityThreatsUtil.notifyVirusByPushToUI:152）、手动/用户扫描（processUserScanVirus:219）。

## 为什么现有 InstallerHook R6（ly1.h 短路）挡不住

R6 的 `ly1.h`（联网安全检测分发器）在 **链 A 发送端**（安装器进程内短路），只阻止安装器发起检测请求——链 A 不发。但**链 B 完全独立**：PACKAGE_ADDED 广播直接触发 `com.hihonor.systemmanager` 进程内的 `AntiVirusReceiver`，不经过 ly1.h，弹窗照弹。

两链独立、分属不同进程，R6 与其他 InstallerHook 挂载点对链 B 零影响。

## 挂载表（3 点）

| # | id | 类 | 方法 | 签名 | 手法 | DexKit 锚点 | 语义 |
|---|---|---|---|---|---|---|---|
| 1 | `sysmgr:AntivirusTools.hideVirusPrefer` | `com.hihonor.antivirus.utils.AntiVirusTools` | `hideVirusPrefer` | `()Z` | CONST→true | `"msc.config.hide_virus"` | 总开关恒开：主服务 onCreate stopSelf + 所有指令拒收 + 安装检测早退（:385）+ 闲时扫描早退（:860） |
| 2 | `sysmgr:CheckUninstallApkCaller.scanClean` | `com.hihonor.antivirus.CheckUninstallApkCaller` | `call` | `(Bundle)LBundle;` | CONST→Bundle{0,0} | `"checkUninstalledApk called by sub user, skip the scan and return ok"` + `("android.os.Bundle")` | 链 A 服务端恒返未检出（镜像宿主 calledBySubUser 分支:223-226） |
| 3 | `sysmgr:VirusNotifyService.noDialog` | `com.hihonor.securitycenter.antivirus.securitythreats.ui.VirusNotifyService` | `onStartCommand` | `(Intent,II)I` | CONST→2 | `"onStartCommand virus notify pkg: "` + `("android.content.Intent","int","int")` | 弹窗服务指令全拒（VIRUS/MALICIOUS/MULTI_VIRUS_NOTIFY + 云推 + 闲时 + 手动全收口） |

- 全部类名在 sysmgrsvc-jadx 中可读（com.hihonor.antivirus.* 未混淆），DexKit 锚点仅作兜底
- 点 2 构造 Bundle 失败时放行原方法（不死锁）

## 影响面与未覆盖项

**影响面**：杀毒能力整体失效（安装时检测、闲时全盘扫描、病毒弹窗、安装预检）。这是**用户要求的预期行为**——开关名「病毒检测全禁用」即此意。

**未覆盖**：
- 反诈/AI 行为采集链（走独立模块，不属本目标）
- 历史管控标记（VirusAppsManager.insertVirusApp 写入的病毒库落库）不回滚——存量标记需用户在系统管家设置里手动清除

## 真机判据（用户自执行）

```bash
tools/dev.sh deploy-release
tools/dev.sh modlog SysmgrHook
# 期待: SysmgrHook: installed 3/3
# 首 2 次命中判据:
#   sysmgr-hook 生效: sysmgr:AntivirusTools.hideVirusPrefer — 宿主病毒功能总开关恒开...
#   sysmgr-hook 生效: sysmgr:CheckUninstallApkCaller.scanClean — checkUninstalledApk 服务端恒返未检出...
#   sysmgr-hook 生效: sysmgr:VirusNotifyService.noDialog — 病毒弹窗服务指令全拒...
```

## 包名对照

用户问的"安装时调用了谁"：

| 包名 | 大小 | 角色 |
|---|---|---|
| `com.hihonor.devicemanager` | 10MB | 系统管家（UI 壳） |
| `com.hihonor.systemmanager` | 116MB | **系统管家服务**（杀毒引擎+弹窗归属）← 本目标 |
| `com.hihonor.securityserver` | 8 splits | 安全公共服务（认证/keystore，与病毒链无关） |
| `com.hihonor.appmarket` | — | 应用市场（安装器广告/下载/统计，与病毒检测无关） |