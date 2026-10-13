# 应用逆向分析记录 · 总览

> 目录规则：**每个 hook 目标一份**（`<app>.md`）= 该 app 的逆向分析 + 实现落地记录。
> 本文件 = 总览：目标总表、能力边界、已放弃目标、以及"改哪个 app 读哪份"。
> 设计/策略与跨目标台账不在这里：主题商店设计见 [`../theme-store.md`](../theme-store.md)，全量挂载点台账见 [`../hook-inventory-audit.md`](../hook-inventory-audit.md)。
> 逆向方法论（工具链、jadx/smali、反混淆、DexKit）**不在 docs**，在项目 skill：`.claude/skills/{reverse-decompile,static-analysis,lsposed-hook-expert,reverse-and-hook}`；命令与工具路径见 skill `honorhook-dev`。

## 一、目标总表

| app | 包名 | 逆向基线 | 逆向难点（混淆 / 防护 / 结构约束） | 记录 |
|---|---|---|---|---|
| 主题商店 | `com.hihonor.android.thememanager` | 20.1.63.305 | 半混淆：主包 ~9800 类可读（keep 名单），`defpackage/` 8074 类单字母名；付费资源文件加密（未深挖） | [`theme-store.md`](theme-store.md) |
| 包管理器 | `com.android.packageinstaller` | 14.0.37.301 | 半混淆；**无布尔开关表**（云控 = `CommonConfig` configVO→JSON + AB 实验）；广告是内容下发型（无总开关） | [`installer.md`](installer.md) |
| 设置 | `com.android.settings` | 14.0.0.210 | 类名可读，方法名 R8 单字母混淆（需 DexKit 锚点兜底）；**执行层受 system_server 保护**（已放弃守卫方案） | [`settings.md`](settings.md) |
| 系统桌面 | `com.hihonor.android.launcher` | 18.0.0.234 | 目标文本在 **`YOYOCardStackPlugin.apk` 独立 ClassLoader**（插件类不可经 base CL 解析 ⇒ 只能挂 base 包等价判定）；清后台保留当前 app 方案见 [`launcher-recents-keepcurrent.md`](launcher-recents-keepcurrent.md) | [`launcher.md`](launcher.md) |
| SystemUI | `com.android.systemui` | 17.0.0.1 | 目标类有 2 个 1 参 void 重载（直连轨须按参数类型消歧）；重载消歧坑；U4 通知身份锚点（dumpsys 渠道打码 → group/id/探针） | [`systemui.md`](systemui.md) |
| 系统更新 | `com.hihonor.ouc` + `com.hihonor.android.upgradeguide` | 18.0.3.206 / 16.0.0.005 | 主代码 R8 深混淆，但 **manifest 组件名未混淆**（锚点全选组件 framework 回调，零 DexKit）；手动检查路径与自动链分离须保留 | [`ouc.md`](ouc.md) |
| 成人高考题库 | `com.exam8.chengrenGK` | 5.9.0.0-release | **未混淆**（bean 稳定）；但**服务端按订单态下发正文** ⇒ 能力边界见下 | [`exam8.md`](exam8.md) |
| 荣耀短信+智能信息 | `com.hihonor.mms` + `com.hihonor.intelligentmessage` | 19.0.9.304 / 10.0.41.308 | 引擎以插件载入 mms 进程；`ted.sms` 半可读、广告管线在 `defpackage`；mms 侧桥接 `com.smartsms.proxy.hw.*` **未混淆** | [`sms-ads.md`](sms-ads.md) |
| 京东 | `com.jingdong.app.mall` | 16.0.70 (102834) | 半可读：业务类名大量保留（SplashFragment/JDHomeManager），方法名 R8 单字母；开屏自研（无第三方广告 SDK） | [`jd.md`](jd.md) |
| MFA身份验证器 | `com.aimazhishi.mfa.authenticator` | 1.0.5 (5) | **360 加固**（StubApp/libjiagu）：业务代码静态不可读，hook 走 manifest 类名 + classloader 捕获；广告 = 穿山甲开屏 | [`mfa-authenticator.md`](mfa-authenticator.md) |
| 系统管家服务 | `com.hihonor.systemmanager` | 17.0.0.414 | 116MB 巨包；杀毒模块类名可读（`com.hihonor.antivirus.*` 未混淆）；自带 `msc.config.hide_virus` 总开关 | [`sysmgr.md`](sysmgr.md) |

> 挂载点数与口径漂移不在本表（代码可数、且存在文档/代码口径不一致）——见根 `CLAUDE.md` §八 未解决项与 [`../hook-inventory-audit.md`](../hook-inventory-audit.md) §一。

## 二、能力边界（诚实结论，勿当已完成）

| 目标 | 能改 | 改不了 |
|---|---|---|
| 主题商店 | 本地判定（免费/VIP/试用/槽位/底 tab）、UI 放行、下载走 VIP 通道 | ROM ThemeProvider 是否独立校验（未证实）；`isSpecialEncrypt` 付费资源解密链（未深挖）；服务端下单/授权接口 |
| 包管理器 | 广告请求门 + 响应出口、安全模式/检测链本地判定 | 服务端风险库判定（内容下发型广告无总开关） |
| 成人高考题库 | 客户端会员徽标 / UI 门 / `hasPrivilege(14)` 功能门 | **试卷正文按订单态服务端下发**（真机实测：可进试卷页但"加载失败"）；改包固化同样取不到 |
| SystemUI / 桌面 / 设置 | 纯本地 UI 判定 | — |

## 三、已放弃 / 悬置目标（结论留档，长文已删）

| 目标 | 结论 |
|---|---|
| `cn.wangxiao.ckzhuntiku`（成考准题库 v5.40） | **DEX 虚拟化双壳**（`libDexHelper.so` + `libdexvmp.so`），`classes.dex` 整体加密、运行时纯内存解密无落盘 ⇒ 静态 + 落盘双死路；要动只能运行时 dump（frida 本机缺失） |
| `com.s20cxq.adultscollege` | **Flutter AOT**，逻辑在 `libapp.so`；会员字段名已在 so 字符串实证，但 hook 面与 Java 层不同 ⇒ 未推进（native 素材已随清理删除） |
| `com.ujigu.tcchengrengaokao`（上学吧系 uni-app） | 会员态服务端下发（`isAskMember` from `GET_USER_INFO`），但内置本地 SQLite `_doc/youtibao.db`（题目疑似本地）⇒ 非目标包，app 已卸载，结论悬置 |
| 主题引擎 `com.hihonor.mocoolengine`（11.0.1.122） | **引擎内零付费逻辑**（全文检索 free/vip/member/price/trial 仅命中 `MemberClasses` 注解与 DRM 错误码）⇒ 不需要 hook，付费门禁全在商店侧 |
| system_server 守卫（设置类） | 该 fork 只回调 `onSystemServerLoaded`，api 102 无此类型，合成 stub 成本 > 收益 ⇒ **放弃，勿重新评估** |

## 三·2、放弃理由的通用判据（外部 skill `apk-reverse`）

三个放弃目标不是"我们没本事"，而是**交付形态够不到那一层**。该插件已废弃，其内容已**整份 vendor 进仓库** → [`.claude/skills/apk-reverse/`](../../.claude/skills/apk-reverse/)（MIT，未维护，索引见其 `README.md` 与 `UPSTREAM-SKILL.md`）：

| 我们放弃的 | 对应的层 | 去哪儿看 |
|---|---|---|
| wangxiao DEX 虚拟化双壳 | 抽取式 dex / dex VMP —— **LSPosed 模块够不到**（原方法体可能根本不以 dex 存在） | `.claude/skills/apk-reverse/references/code-virtualization-and-custom-linkers.md`、`vmp-differential-analysis.md`（已知明文差分）、`advanced-unpacking.md`（主动调用式抽取）；脚本 `scripts/dex_mem_scan.py` + `dex_dump_validate.py`（内存找 dex 并校验，胜过"落盘找不到就放弃"） |
| s20cxq Flutter AOT | Dart AOT（Java 层只是壳，业务在 `libapp.so`） | `references/dart-aot.md` |
| exam8 服务端正文 | 服务端决策 —— **hook 与 patch 同样无能为力** | `references/server-api.md`（门所有者请求矩阵）、`membership-and-limits.md`（可做/不可做清单 + 该怎么对外表述） |
| （顺带）为什么本模块选 hook 而非改包 | 路线取舍：模块 / Frida / 重打包三者交付物与存活期不同；改包还会撞签名校验与签名派生密钥 | `references/lsposed-and-modules.md`（含"模块够不到的层"总表）、`signature-derived-keys.md` |

## 四、按需求读

| 我要做的事 | 先读 |
|---|---|
| 改某 app 的 hook 逻辑 | 该 app 的记录（上表）+ [`../hook-inventory-audit.md`](../hook-inventory-audit.md) 对应段 |
| 主题商店策略/按钮/下载链 | [`../theme-store.md`](../theme-store.md)（设计）→ 证据翻 [`theme-store.md`](theme-store.md) |
| 加新目标 app | 抄最像的现有实现 → `TargetRegistry` 注册 → `scope.list` 同步 → `FeatureCatalog` 加条目（`CLAUDE.md` §3.2） |
| 逆一个新 app | skill `reverse-decompile` / `static-analysis` → 命令 `tools/dev.sh decompile|jadx` |
