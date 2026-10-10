# HonorHook Miuix UI 实现概览

> 为纯 hook 模块 `top.honorhook` 加回 Miuix（Compose）三 Tab 界面，并把「功能恒开」升级为「13 个功能开关真正生效」。

## 界面结构（底部三 Tab，微信式）

| Tab | 内容 |
| --- | --- |
| **首页** | 「老色批状态」卡（LSPosed 激活状态：已激活/未激活 + 框架名/版本/API）；系统信息卡（设备型号/安卓版本/模块版本/框架/API） |
| **功能** | 一级 = 6 个目标应用列表（主题商店/包管理器/系统设置/成考题库/系统桌面/SystemUI）；点开二级 = 该应用的功能开关，每项带说明 |
| **设置** | 外观（跟随系统/浅色/深色 单选 + 液态玻璃开关）；框架状态与模块 API；关于 |

底部是**悬浮玻璃胶囊底栏**（液态玻璃开关控制磨砂半透明 vs 纯色）。

## 功能开关（6 目标 × 13 开关）

- **主题商店**：VIP 会员解锁 / 主题全免费 / 试用永不过期 / 广告净化
- **包管理器**：广告净化 / 安装不拦截 / 安全模式提示净化 / 跳过安全检测
- **系统设置**：停用按钮恒可点
- **成考题库**：超级会员解锁 / 阅后即焚解锁
- **系统桌面**：隐藏卡片标题
- **SystemUI**：隐藏副卡图标

## 配置通道（开关如何真正生效）

```
宿主 App（UI）                        hook 侧（注入目标进程）
FeatureStore.setEnabled(key)  ──写──▶  远端 prefs（group "honorhook_config"）
   └ 本地 SharedPreferences（UI 显示）      ▲
                                          └ RemoteConfig.isEnabled(key) 读取（HookEntry 注入 getRemotePreferences 缓存）
```

- 单一事实源 `top.honorhook.FeatureCatalog`（纯 Kotlin，双端共用 key 字面量）。
- 默认全开；关闭某功能后需**重启对应目标进程**（下次注入读取）才生效。

## 关键技术决策

1. **依赖对齐 HyperChanger**（AGP 9.2.1 + Kotlin 2.3.21 + Miuix 0.9.3 + Compose 1.11.0），避免 LSPFRIDA 的 Kotlin 2.4.10/Compose 1.12 工具链漂移。
2. **激活状态探测**：`XposedServiceHelper`（libxposed service）收到 Binder 即判「已激活」。
3. **液态玻璃**：半透明磨砂近似（Miuix 0.9.3 的真实 blur 需外部 kyant0/backdrop 库，暂不引入）。

## 构建产物

- debug：`app-debug.apk`（11.69 MB）
- release：`app-release.apk`（1.39 MB，R8 混淆 + 资源收缩）
- 桌面副本：`D:\Data\desktop\HonorHook-ui-{debug,release}.apk`
