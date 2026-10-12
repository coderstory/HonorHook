# 系统更新 `com.hihonor.ouc`（HnOUC）+ `com.hihonor.android.upgradeguide` — 逆向分析与实现

> 基线 HnOUC **18.0.3.206** / UpgradeGuide **16.0.0.005**（aapt2 实证，APK 拉自真机 2026-10-12）
> 目标源码 `app/.../xposed/target/OucHook.kt` + `UpgradeGuideHook.kt`
> 反编译产物：`system-files/HnOUC-jadx`、`system-files/HnUpgradeGuide-jadx`

## 一、需求

禁止系统更新的**后台自动**行为（自动检测 / 自动下载 / 夜间安装 / 升级提醒）；设置内手动检查保留。

## 二、逆向结论

| 项 | 值 |
|---|---|
| 混淆情况 | 主代码 R8 深混淆（单字母包）；**manifest 组件名未混淆**（导出组件名须可读）⇒ 锚点全选 manifest 命名组件 |
| 检测链 | `check.auto.AutoCheckService`（周期调度，onStartCommand 自有覆写 :942）→ 检测 → 结果广播/提醒 |
| 手动检查 | phoneservice 发 `action.CHECK_NEW_VERSION` → `CheckNewVersionReceiver.onReceive`（HICARE 模式，CheckNewVersionReceiver.java:57）——**与自动链分离，单独保留** |
| 自动下载 | `FirmwareAutoDownloadReceiver`（AUTO_DOWNLOAD_ALARM）+ `WifiAutoDownloadManageService`（onStartCommand :63） |
| 夜间安装 | `NightUpgradeReceiver`（NIGHT_UPGRADE_ALARM）→ `NightUpgradeService`（无自有 onStartCommand，挂 Receiver 即断链） |
| 升级提醒 | `UpgradeRemindReceiver`（提醒通知/红点复唤） |
| 其它触发 | PowerConnected / DeviceActivated / CarrierConfig / FirmwareAndApp / FirmwareInstall / SelfUpdate 各 Receiver |
| 引导页 | upgradeguide 整包**只有 1 个 activity**：`UpgradeWizardActivity`（manifest 唯一） |

## 三、选点（对照 CLAUDE.md 哲学）

- **入口收口**：挂 Receiver.onReceive / Service.onStartCommand（AOSP framework 回调，签名跨版本稳定），
  入口短路 ⇒ 检测/下载/通知整链不发生，不碰混淆业务逻辑。
- **零 DexKit**：全部类名 manifest 实证未混淆。
- **手动检查不拦**：CheckNewVersionReceiver 故意不挂（它是 phoneservice 手动检查的落点）。
- onStartCommand 短路返回 `START_NOT_STICKY(2)` 防 restart；onReceive void 返回 null（libxposed 返回值
  硬约束，docs/reverse/systemui.md §7.4）。
- 引导页：onCreate 先 proceed（super 链完整）后 finish——页面闪现即退，流程无法展开。

## 四、挂载点清单（11 + 1）

Receiver ×9（onReceive 短路）：FirmwareAutoDownload / NightUpgrade / UpgradeRemind / PowerConnected /
DeviceActivated / SelfUpdate / CarrierConfig / FirmwareAndApp / FirmwareInstall。
Service ×2（onStartCommand 短路→2）：check.auto.AutoCheckService / biz.impl.service.WifiAutoDownloadManageService。
引导 ×1：UpgradeWizardActivity.onCreate → finish。

## 五、验证判据（用户自执行）

```bash
tools/dev.sh deploy-release
tools/dev.sh modlog OucHook          # 期待 installed 11/11
tools/dev.sh modlog UpgradeGuideHook # 期待 installed 1/1
```

- 充电/解锁/到点不再出现升级提醒通知与红点；解锁不弹全屏升级引导
- 设置→系统更新→手动「检查更新」仍可用（本功能故意保留）
- logcat `HnOUC` tag 的自动检测日志（"start to check" 自 AutoCheckService 的）不再出现

## 六、边界与未覆盖（如实）

- `systemappsupdater`（系统应用商店更新，`com.hihonor.systemappsupdater`）不在本需求内，未 hook。
- 若 OTA 由 phoneservice（`com.hihonor.phoneservice`）侧另行调度（manifest 未见，未实证），自动检测
  可能仍有残余——真机验证后按判据补挂。
- 下载到一半的已有包、已计划的 JobScheduler 任务（系统侧）不受模块控制；重启后由被断的入口不再补调度。
