# 宿主 UI（Miuix Compose）— 只记非代码可得的部分

> 界面结构、组件、开关清单、产物大小读代码（`ui/`、`FeatureCatalog.kt`）。此处只留**语义与取舍**。

## 开关下发通道（语义）

- 单一事实源 `top.honorhook.FeatureCatalog`：双端共用 key 字面量，**禁止任一侧手写字符串**。
- UI 写远端 prefs（group `honorhook_config`）→ hook 侧 `HookEntry` 注入时缓存 `getRemotePreferences` 一次，install 阶段本地查表（无重复 Binder）。
- **默认全开**：读不到 / 异常 / 未写入一律视为开；关某功能 = 显式写 false，且**下次目标进程注入才生效**（不是立刻）。

## 取舍（为什么这么选）

- 依赖对齐 HyperChanger（AGP 9.2.1 / Kotlin 2.3.21 / Miuix 0.9.3），避免 LSPFRIDA 的 Kotlin 2.4 工具链漂移。
- **激活判定**：只有收到 LSPosed 的 `XposedService` Binder 才算「已激活」（照 LSPFRIDA），不靠包名/文件探测。
- **液态玻璃**：Miuix 0.9.3 无真 blur，用半透明磨砂近似；真模糊需引入外部 backdrop 库，暂不引（体积/风险换观感，不值）。
