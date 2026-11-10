# 系统应用更新器 com.hihonor.systemappsupdater（16.0.20.302）逆向记录

> 2026-11-09 首次侦察。APK：`/system/priv-app/SystemAppsUpdater/SystemAppsUpdater.apk`（本机复制）。
> 产物：`system-files/base-apktool/`（smali/资源）、`system-files/systemappsupdater-jadx/`（伪代码）。未加固，纯 Java。

## 结构

- 主代码 `smali_classes4/com/hihonor/updater/`（169 类）：`config` / `entity` / `fast`(急速更新) / `service` / `receiver` / `provider` / `notification` / `ui` / `third/remote`
- 混淆产物大量落在 `defpackage`（a/b/c… 命名），核心业务类已被混淆改名，选点要靠字符串/资源锚点

## 关键组件（Manifest）

| 组件 | 作用 |
|---|---|
| `ui.UpdateListActivity` | 可更新应用列表页（主界面） |
| `ui.UpdateRemindActivity` | 更新提醒弹窗页 |
| `ui.SettingActivity` / `PreferencesActivity` | 设置（WLAN 自动更新开关等） |
| `service.AutoUpdateJobService` | JobScheduler 自动更新任务 |
| `service.KeepAliveService` | 保活 Job（`ext_job_schedule_time` 静默安装窗口） |
| `receiver.WifiStateChangeReceiver(WithoutConditions)` / `PowerConnectedReceiver` / `AlarmTimerUpdateReceiver` / `BootCompletedReceiver` | 触发链：WiFi 连上 / 插电 / 闹钟 / 开机 |
| `receiver.SilenceInstallResultReceiver` | 静默安装结果回执 |
| `provider.AppUpdateProvider` / `SystemAppCallProvider` | 跨进程查询/调用入口 |

## 关键事实

- 云控配置 `config.AppConfig`（Gson `@SerializedName`）：`silent_update_alarms`（静默安装时间窗，默认 0–21600s 即 0–6 点）、`delay_check_update`、`night_install_time`、`support_install_min_battery`、`activate_app_market*`（联动应用市场）、`limit_update_*`（频控）
- 检查更新：`defpackage/w21`（"TargetAppRequestManager"，`check_update_time` 限频 1 天）
- 下载管理：`defpackage/qo`（`startDownload pkgName=... silent:... fastUpdate:...`，`"not silent, continue download"` 是静默判定锚点字符串）
- 服务端：`res/values/strings.xml` → `base_url=https://apig.hihonor.com/`、`updater_default_host=https://appresource-gw-drcn.hispace.hihonorcloud.com`；埋点 `metrics-drcn.dt.hihonorcloud.com`（听云 NBS + hianalytics 全量插桩）
- 权限含 `INSTALL_PACKAGES` / `INSTALL_SELF_UPDATES`（静默安装能力在本 app 内，不在 packageinstaller）

## 可改点（hook 候选，按手法归类）

1. **关静默安装**：qo 静默判定门（`silent` 参数/分支）改 false → 全部走可见流程；或 `SilenceInstallResultReceiver` 链路观测
2. **停自动触发链**：`WifiStateChangeReceiver.onReceive` / `PowerConnectedReceiver` / `AutoUpdateJobService.onStartJob` 短路 void 重载 → 断 WiFi/插电自动更新
3. **拦更新提醒弹窗**：`UpdateRemindActivity` 启动拦截或 `Dialog.show` 内容判定
4. **频控/唤醒治理**：`KeepAliveService` Companion `h()`（schedule job）短路
5. **断埋点**：hianalytics / NBS 上报收口点短路（此 app 埋点极重）
6. **改本地状态**：`w21` 的 `check_update_time` 限频字段（Settings/prefs）→ 强制立即检查或永不检查
7. **UI**：`UpdateListActivity` 列表裁剪（红点/角标来源查询在此进程）

## 限制

- 更新列表内容由服务端下发（apig.hihonor.com），只能本地裁剪显示，拦不住服务端知道装了什么（除非断网域）
- 静默安装最终走系统 `INSTALL_PACKAGES`，hook 本 app 只能挡它发起，不能替它校验签名

## 判据

LSPosed 日志 `installed N/M`；宿主版本 16.0.20.302（混淆名绑定该版本，OTA 后须重验）。
