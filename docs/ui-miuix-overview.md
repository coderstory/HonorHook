# 宿主 UI（Miuix Compose）— 只记非代码可得的部分

> 界面结构、组件、开关清单、产物大小读代码（`ui/`、`FeatureCatalog.kt`）。此处只留**语义与取舍**。

## 开关下发通道（语义）

- 单一事实源 `top.honorhook.FeatureCatalog`：双端共用 key 字面量，**禁止任一侧手写字符串**。
- UI 写远端 prefs（group `honorhook_config`）→ hook 侧 `HookEntry` 注入时缓存 `getRemotePreferences` 一次，install 阶段本地查表（无重复 Binder）。
- **默认全开**：读不到 / 异常 / 未写入一律视为开；关某功能 = 显式写 false，且**下次目标进程注入才生效**（不是立刻）。
- **写失败必须可见**（2026-10-11）：`FeatureStore.setEnabled` 返回远端下发是否成功。框架未连接或写入抛异常时，
  一级列表顶部显示提示卡「LSPosed 未激活 —— 开关只会保存到本地，hook 侧仍按默认全开运行」。
  滑点在于：只写本地时 UI 状态照样翻转，用户会以为改生效了 —— 这属于必须有反馈的一类假状态。
- **生效时机必须写在界面上**：二级功能页顶部提示卡写死「开关在目标进程下次启动时读取 —— 改完请强停并重开对应应用」，
  不再只存在于本文档。
- **UI 侧状态是 Compose 可观察镜像**（`FeatureStore.states`）：直接读 `SharedPreferences` 不是 snapshot state，
  会让「已启用 N/M」「运行中/待启用」停在旧值；所有读写统一走镜像，一处改动同时刷新二级开关与两级计数。

## 取舍（为什么这么选）

- 依赖对齐 HyperChanger（AGP 9.2.1 / Kotlin 2.3.21 / Miuix 0.9.4），避免 LSPFRIDA 的 Kotlin 2.4 工具链漂移。
- **激活判定**：只有收到 LSPosed 的 `XposedService` Binder 才算「已激活」（照 LSPFRIDA），不靠包名/文件探测。
- **液态玻璃**：`miuix-blur`（0.9.4 起提供 `Backdrop`/`blur`/`lens`）+ `io.github.kyant0:backdrop` 2.0.0 已引入，底栏走真·背景模糊；
  低版本/开关关闭时退回实色底栏（`MainActivity` 的 `enableBlurSdk` 守卫）。`miuix-blur` 声明 minSdk 33，靠 `tools:overrideLibrary` 覆盖到本项目的 minSdk 26。
