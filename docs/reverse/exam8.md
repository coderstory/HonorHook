# 成人高考题库 `com.exam8.chengrenGK` — 逆向分析与实现

> 基线 **5.9.0.0-release**（真机 `dumpsys` 实证）· 目标源码 `app/.../xposed/target/exam8/Exam8Hook.kt`
> 反向索引：设计/策略在本文；跨目标台账见 [`../hook-inventory-audit.md`](../hook-inventory-audit.md) §exam8。

## 一、身份与逆向手段

| 项 | 值 |
|---|---|
| 包名 / 版本 | `com.exam8.chengrenGK` / `5.9.0.0-release`（base.apk ≈ 109 MB） |
| 混淆 | **未混淆**——会员 bean 类名与方法名稳定可读 |
| 逆向手段 | `dexdump` 特征检索定位 bean 与工具类；`jadx` 反编译全量（109 MB：`-j 4 --no-res` 约 52 s、8081 类、102 个方法级 error，**exit 1 属正常**，主体可用） |
| 解析策略 | 全部**直连反射**挂载（类名/方法名稳定，无需 DexKit 兜底；`MethodResolver` 第一轨命中） |

## 二、会员模型（逆向结论）

会员态由两个**未混淆 bean** 承载，UI 与特权判定都从它们读：

| 类 | 字段 | 作用 |
|---|---|---|
| `com.exam8.newer.tiku.bean.VipUserStatus` | `VipType`(int) 档位 / `IsValid`(int) 是否有效 / `DaysToExpire`(int) / `ExpireDate`(String) | 中央会员态，驱动「我的 / 会员页」UI（`MemberActivity$UserVipStatusRunnable` 拉服务端响应后写入；`user_state_tv` 显示「暂未开通」即读此） |
| `com.exam8.newer.tiku.bean.VipPrivilegeRes` | `VipType`(String) / `HasVipPrivilege`(int) / `UserVipLevel`(int) | 特权资源，UI 特权展示与解锁判定读此 |

**后端档位编码**（`VipPrivilegeRes.setLevel1_1 / setLevel2_1 / setLevel4_1` 按 1/2/4 分组）：`1` = 轻享 / `2` = 功勋 / **`4` = 超级** ⇒ 强制 `VipType` 置 4（int 与 String 两处一并改，保持两 bean 一致）。

## 三、挂载点（10 点 · 代码内 id 去重计数）

| 分组 | 开关 key | 点 | 动作 |
|---|---|---|---|
| F1 超级会员（7 点） | `exam8.super_vip` | `vipUserStatus.getVipType` / `getIsValid` / `getDaysToExpire` / `getExpireDate` | 档位 → 4、有效 → 1、剩余天数拉满、到期日期置远期 |
| | | `vipPrivilege.getVipType` / `getUserVipLevel` | 档位（String）→ 超级、等级 → 对应值 |
| | | `vipPrivilege.getHasVipPrivilege` | → 1（拥有特权） |
| F2 阅后即焚（3 点） | `exam8.burn_reading` | `vipUtils.hasPrivilege`（**条件短路**：仅特权位 `14`=阅后即焚返 true，其他特权位放行原逻辑，避免误伤无关判定） | 过 UI / 功能门 |
| | | `vipPrivilege.getRemainDays` + `vipPrivilege.getHasSubjectConfig` | 兜底：`hasPrivilege` 的第 4 / 第 1 个条件 |

## 四、能力边界（诚实结论）

- 本 hook 是**客户端会员态伪造**：翻转本地会员徽标、UI 与客户端功能门，**不改网络协议**。
- **「阅后即焚」试卷正文由服务端按订单态下发**（`api.wantiku.com/api/Sprint/Papers`，按 `UserId` 查订单表）⇒ hook 只能过 UI 门，**取不到正文**（真机实测：可进入试卷页但显示"加载失败"）；改包固化同样无效。
- 该功能另有**单次付费路径** `orderState == 1`（服务端订单态），本 hook 不涉及其校验。
- 真实付费音视频流 / 云端题库下发等**服务端强校验能力**不可得。

## 五、判据与未定项

- **判据**：`installed 10/10` + 行为清单（会员页显示「超级会员」+ 远期有效期；「阅后即焚」列表不再显示「立即抢购」；正文仍"加载失败"属预期）。
- **未定项**：`SUPER_VIP_TYPE = 4` 的档位编码来自 `setLevel*` 分组推断，**待真机诊断日志确认**；`getHasSubjectConfig` 作为兜底条件的具体语义未逐行核读。
