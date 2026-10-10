# com.s20cxq.adultscollege（成人学历/成考刷题）逆向分析

> 分析日期：2026-10-10 ｜ 设备：AAK-AN00 / MagicOS 11 / KernelSU + LSPosed
> 目的：判断能否用 LSPosed（HonorHook 模块）"开启会员"

## 1. 应用身份
- 包名 `com.s20cxq.adultscollege`，v2.2.4，minSdk24 / targetSdk37
- **Flutter App**（`libflutter.so` + `assets/flutter_assets/`，业务逻辑在 `libapp.so` 60MB AOT）
- 域名系：生产 `qmb.meite.com` / `qmb.jyp.com` / `qmb.ydfee.com`，题目域 `questions.*`，用户态 `uc.eepw.com`（JWT issuer 即 `uc.eepw.com/v2/auth/onekeylogin`）
- 会员模型同构 exam8/ujigu：搜题卡、VIP 课程、题库 VIP(tkVIP)、湖南 VIP(hnVipLevel)、成人 VIP(adultVipEndDate) 多层级，UI 现"我的搜题卡 剩余N次"

## 2. 架构关键约束（决定 hook 打法）
- **Flutter 用 Dart 自有 `dart:io` 网络栈，不走 Android okhttp/WebView** → 现有 HonorHook 那套 Java/ART 反射 hook 对网络层无效。
- **但 LSPosed 模块可 hook `.so`（native 层）**，故可钩 `libflutter.so`/`libapp.so` 的网络读，在 JSON 进 Dart 解析前改掉会员字段。frida 仅作辅助分析。

## 3. 会员态落盘探查（已实证）
- `shared_prefs/FlutterSharedPreferences.xml` 32 个键，全部为 `_token`(JWT) / `_userInfo`(用户 JSON, 内嵌 `card_count:0`、`card_long:"1970-01-02..."`) / 广告配置 / UI 标记。**无独立 `isVip`/`expireTime`/`hnVipLevel`/`vipType` 键**。
  - 注：`card_count`/`card_long`/`tkvip` 等只出现在 `_userInfo` JSON 值或广告事件名里，不是持久化会员键。
- `databases/ua.db` 仅 umeng 分析表（`__sd`/`__et`/`__er`/`__is`），无会员表。
- **结论：会员态每次启动实时从服务端拉取，仅存 Dart 内存，本地不落盘。**

## 4. libapp.so 字符串实证（会员字段名）
- 多层级：`isVip`/`curIsVip`/`isVipFlag`、`vipType`(1/2)、`hnVipLevel`(v1–v3)、`hnxxVipLevel`、`tkVIP`(题库VIP)、`adultVipEndDate`/`adultSVipEndDate`、`auxPoliceBuyPremiumGoodsEndTime`、`expireTime`/`endTime`/`endTimeMills`
- 大量 debug print 痕迹：`*******isVip =`、`curIsVip : 11111111111`、`hnxxVipLevel =`、`vipLevel =`、`isVipPage =`、`showOpenVipDialog ---NNNNN`（便于验证 hook 是否生效）
- 开通页：`/AgentBuyPrivilegeDetailsPage?vipType=2`、`/HigherVipBuyMainPage?buyType=0&hnVipLevel=v3`

## 5. hook 路线判定
| 路线 | 可行性 | 说明 |
|---|---|---|
| Java Xposed 钩 SharedPreferences/SQLite | ❌ 无效 | 会员态不落盘，无键可改 |
| Java Xposed 钩 okhttp/WebView | ❌ 无效 | Flutter 不用这些 |
| LSPosed native 钩 `libflutter.so` 网络读（SSL_read / socket read） | ✅ 唯一可行 | JSON 进 Dart 前注入 `isVip:true` + 远期 `expireTime` + `hnVipLevel:v9` 等 |
| Dart 函数级 hook（需 blutter 符号） | ⚠️ 较重 | 要恢复符号，单点但脆弱 |

## 6. 风险（必须实测）
- **服务端权威**：本地伪造 `isVip` 可能只解锁 UI 入口；VIP 专属题目/课程服务器仍按账号身份下发，内容层未必解锁。
- native hook 需定位 `libflutter.so` 内 `SSL_read`/socket read 符号，跨版本可能漂移。

## 7. 下一步（待用户拍板）
1. frida 辅助抓取真实会员 API 响应，确认 JSON 字段形状与 API 路径（已进行）。
2. 若响应可被篡改生效 → 在 HonorHook 加 `AdultCollegeHook` 目标，走 native 配套 `.so`（inline hook `SSL_read`）实现响应注入，接入 `TargetRegistry` + `scope.list`。
3. 真机验证：UI 是否显示"已开通"+ 内容是否真解锁。
