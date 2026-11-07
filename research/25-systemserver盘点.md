# HnSystemServer 服务面盘点（2026-10-24）

样本：system-files/hnssystemserver/（11 APK 全量 + 盘点 md）。
**架构修正**：11 个 APK = 同包 com.hihonor.systemserver v70.1.0.1 的 split；persistent=true + sharedUserId=android.uid.system，宿主 HwServerApplication——**独立持久进程（uid 1000），不在 framework system_server 内**。崩溃=该进程重启+自动拉起，非整机重启；crash-loop 拖垮胶囊/感知全家仍属高危。

## 子 APK 清单（Top 可 hook 加粗）
| APK | 内容 | 价值 |
|---|---|---|
| **HnInteractionFwk** | 灵动胶囊/实况窗服务端（IInteractionDisplayService、ILiveClipManagerService）；**DisplayBehaviorRecord 就在这**（behaviorrecord 点名项落位） | ★★★ |
| **HnSystemServer（15.3MB 大杂烩）** | 30+ 子系统：contentrecognizer（IHnContentRecognizerManagerService，喂胶囊疑似上游信号=contentsensor 实体）、geofence、magicanimator/magiclayout、searchservice… | ★★★ |
| **ManMachineInterface** | AI brain→场景决策桥（mmi IEventManager + brain NOTIFY 广播） | ★★ |
| **Hicom** | 对三方半开放 SDK 面（emcom/awareness/kitassistant/onehop，signature 权限） | ★★ |
| Booster | 链路诊断/游戏加速/ollie 建议 | ★ |
| Hypercomm | 卫星通信+北斗短报文 | ★ |
| HnCollie / HnHotpatch / HnFoldScreen / HnPadEdu / HnThemeAsset | 掉话看门狗 / **热补丁（持久化通道 hook 慎入）** / 折叠 / 教育平板 / 主题资产 | 低 |

## 点名项落位
behaviorrecord→HnInteractionFwk；usescene→仅引用（服务端不在本目录）；contentsensor→无此名，最近=contentrecognizer（HnSystemServer.apk）；push 通道/防诈→本目录零命中（在其它 priv-app）。
例外进程仅 :process2/:ui 两个。AIDL 签名串/权限串/dumpsys 调参项（-a/-ee/setWidgetMaxCount root-only）见盘点 md 原文。
