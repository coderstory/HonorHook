package top.honorhook.xposed.target.themestore

import top.honorhook.xposed.core.MountEngine.DexHint

/** F2 免费化 · ItemInfo/服务端 bean 层与按钮组（A 组 + T1 + btn-hide）。 */
internal fun collectFreeJudgmentHooks(pending: MutableList<MountTask>) {
    addConst(pending, CLS_ITEM_INFO, "isNeedPayItem", 0, "boolean", false,
        "theme:ItemInfo.isNeedPayItem", "F2 A1 需付费判定 → 免费")
    addConst(pending, CLS_ITEM_INFO, "isNeedOrder", 0, "boolean", false,
        "theme:ItemInfo.isNeedOrder", "F2 A2 需下单判定 → 不弹下单")
    addConst(pending, CLS_ITEM_INFO, "getPayed", 0, "boolean", true,
        "theme:ItemInfo.getPayed", "F2 A3 已购读点1 → 已购")
    addConst(pending, CLS_ITEM_INFO, "isPayed", 0, "boolean", true,
        "theme:ItemInfo.isPayed", "F2 A3 已购读点2 → 已购")
    // A4 VIP 专属标记：挂 **getVipOnly()** 而不是 `isVipResouce()`（2026-10-10 审计修正）——
    addConst(pending, CLS_ITEM_INFO, "getVipOnly", 0, "int", 0,
        "theme:ItemInfo.getVipOnly", "F2 A4 VIP 专属标记源头 → 0（同时覆盖 isVipResouce() 与直连 getVipOnly() 消费点）")
}

/** A5：price/actualPrice（double 字段）libxposed 102 无法 hook 字段，读点（getter 归零）+ 写点（setter 写 0）组合覆盖。 */
internal fun collectPriceZeroHooks(pending: MutableList<MountTask>) {
    addConst(pending, CLS_ITEM_INFO, "getPrice", 0, "double", 0.0,
        "theme:ItemInfo.getPrice", "F2 A5 价格读点（展示）→ 0 元")
    addConst(pending, CLS_ITEM_INFO, "getActualPrice", 0, "double", 0.0,
        "theme:ItemInfo.getActualPrice", "F2 A5 现价读点（展示）→ 0 元")
    addForceArg(pending, CLS_ITEM_INFO, "setPrice", 1, "void", 0.0,
        "theme:ItemInfo.setPrice", "F2 A5 价格写点 → 强制写 0")
    addForceArg(pending, CLS_ITEM_INFO, "setActualPrice", 1, "void", 0.0,
        "theme:ItemInfo.setActualPrice", "F2 A5 现价写点 → 强制写 0")
}

/** A11：列表接口直接用服务端 bean 源头（String 价格 + vipOnly）。 */
internal fun collectBeanSourceZeroHooks(pending: MutableList<MountTask>) {
    addConst(pending, CLS_ITEM_BEAN, "getPrice", 0, "java.lang.String", "0",
        "theme:ItemBean.getPrice", "F2 A11 服务端 bean 价格源头 → \"0\"")
    addConst(pending, CLS_ITEM_BEAN, "getActualPrice", 0, "java.lang.String", "0",
        "theme:ItemBean.getActualPrice", "F2 A11 服务端 bean 现价源头 → \"0\"")
    addConst(pending, CLS_ITEM_BEAN, "getVipOnly", 0, "int", 0,
        "theme:ItemBean.getVipOnly", "F2 A11 服务端 bean VIP 专属标记 → 0")
}

// A6-A10（5 个 is*BtnShow 门）与按钮显隐汇总点均已**删除**：不再逐按钮硬掰门/藏按钮，
// 按钮显隐交由"用户 VIP + 资源免费"的数据态驱动（ToolBarGroupLayout 原生 switch）。依据见 design.md §十。

/** T1：详情页按钮组类型 → **-1（无激励）**。 */
internal fun collectRealChainHooks(pending: MutableList<MountTask>) {
    addConst(pending, CLS_INCENTIVE_DATA, "getThemeDetailButtonType", 0, "int", -1,
        "theme:IncentiveData.getThemeDetailButtonType", "F2 T1 服务端按钮类型 → -1（无激励：hideLayout 只关「免费领/美豆」激励视图，保留下载/应用）")
    // 用户明确要求：详情页不显示「购买」按钮。changeVipBtnsVisibility 第 1 参即 buy
    // （OnlinePreviewBaseActivity.java:2837 定义，:2920 用它设 mBuyBtn 可见性；:3834 亦为 mBuyBtn 显隐点）
    addForceArg(pending, CLS_PREVIEW, "changeVipBtnsVisibility", 10, "void", false,
        "btn-hide:buy", "F2 详情页「购买」按钮隐藏（changeVipBtnsVisibility buy 参数强制 false）",
        forceArgIndex = 0)
}

/** F1 VIP：VipManager 读/写点 + VipState 散读直改（原「VipModel 双漏斗」已删，依据见 audit §五）——
 *  `VipState` 字段**全部 private** ⇒ 类外读取**只能**经过 VipState 的 7 个 getter，而 getter 已被
 *  短路为会员常量 ⇒ getter 短路即全覆盖，无需任何实例字段改写层。 */
internal fun collectVipHooks(pending: MutableList<MountTask>) {
    // B1 保留：VipManager 页面级直接判定读点（非 VipState 实例散读）
    addConst(pending, CLS_VIP_MANAGER, "isVipUser", 0, "boolean", true,
        "theme:VipManager.isVipUser", "F1 B1 VIP 判定读点（首选）→ 是 VIP")
    // B2 保留：任何 setVipUser(false)（含服务端拉到的过期状态）强制写 true，覆盖登出/特权撤销/网络回写
    addForceArg(pending, CLS_VIP_MANAGER, "setVipUser", 1, "void", true,
        "theme:VipManager.setVipUser", "F1 B2 VIP 写点 → 强制置 true")
    // VipState 散读点恢复（2026-10-09 真机验证回归：漏斗改写依赖"消费方走漏斗"的静态实证，真机上
    // VIP tab 判定/到期显示未走漏斗——散读方法级直改不依赖任何调用链假设，与漏斗双层并存）：
    // 到期时间/剩余时长/状态/类型/判定/展示 getter 全部直返会员态（消费方实证见 2e2b0f1^ 版类注释）
    addConst(pending, CLS_VIP_STATE, "getExpireTimeTimestamp", 0, "long", TS_2088_08_08_MILLIS,
        "theme:VipState.getExpireTimeTimestamp", "F1 B3 到期时间戳 → 2088-08-08")
    addConst(pending, CLS_VIP_STATE, "getRemainingTime", 0, "java.lang.Long", System.currentTimeMillis() + 1_950_000_000_000L,
        "theme:VipState.getRemainingTime", "F1 剩余时长 → 到 2088 的真实剩余（防消费端 now+X 溢出，A1 审计值语义修正）")
    addConst(pending, CLS_VIP_STATE, "getVipStatus", 0, "int", 2,
        "theme:VipState.getVipStatus", "F1 T5 VIP 状态 → 2 会员态（1=NOT_VIP '新用户专享'卡根因）")
    addConst(pending, CLS_VIP_STATE, "getVipType", 0, "java.lang.Integer", 2,
        "theme:VipState.getVipType", "F1 T6 VIP 类型 → 2 连续包月（激活续费入口/账户已开通识别）")
    addConst(pending, CLS_VIP_STATE, "isVipUser", 0, "boolean", true,
        "theme:VipState.isVipUser", "F1 VipState 实例内判定 → 是 VIP（VipState.java:355 独立字段读路径）")
    addConst(pending, CLS_VIP_STATE, "getDisplayTimeTimestamp", 0, "long", TS_2088_08_08_MILLIS,
        "theme:VipState.getDisplayTimeTimestamp", "F1 到期日期展示时间戳 → 2088-08-08（UI 展示链 MineVipCardView:430/441、HwThemeManagerActivity:894/901）")
    addConst(pending, CLS_VIP_STATE, "getDisplayTime", 0, "java.lang.String", TS_2088_08_08_STR,
        "theme:VipState.getDisplayTime", "F1 到期展示字符串 → 2088-08-08")
    // getSecondTitle：**服务端下发**的二级标题文案（ MineVipCardView.java:1789-1792 ——
    // secondTitle 与 linkUrl 均非空才渲染 ll_secondary_container 横幅行；服务端按真实购买记录
    // 下发"VIP 已过期，立即行动"文案，客户端 getter 伪造不影响它）→ 置空串 ⇒ 判空整行隐藏。
    addConst(pending, CLS_VIP_STATE, "getSecondTitle", 0, "java.lang.String", "",
        "theme:VipState.getSecondTitle", "F1 二级标题横幅（服务端下发'VIP 已过期'文案）→ 空串隐藏（MineVipCardView:1789 判空）")
    // 已删的历史挂载点（fetchShouldDialogBy* 休眠链 / T8 getVipCardType）依据全部归档于
    pending += MountTask(
        kind = MountKind.VIP_STATE_FIX,
        className = CLS_VIP_MANAGER,
        methodName = "getLocalVipState",
        paramCount = 0,
        returnType = CLS_VIP_STATE,
        value = null,
        id = "theme:VipManager.getLocalVipState",
        description = "F1 漏斗1 页面级 VIP 状态汇聚点改写（VipManager.java:55；DetailSource:219/ApplySource:1149/WidgetUtil:131/AdsRequestSource:285/ApplyResourceServer:357 实证）",
    )
    pending += MountTask(
        kind = MountKind.VIP_STATE_FIX,
        className = CLS_VIP_MANAGER,
        methodName = "getVipState",
        paramCount = 0,
        returnType = CLS_VIP_STATE,
        value = null,
        id = "theme:VipManager.getVipState",
        description = "F1 漏斗2 会员中心直读点改写（VipManager.java:88；VipMemberCenterActivity:345/VipCenterModel:1131/ArrivedPushManager:3228 实证；null 时自建实例）",
    )
    // **F1 现状：VipManager 2 点 + VipState 7 getter + 漏斗 2 点，共 11 点。**
}

/** F1/F2 双保险拦截 VIP 挽留弹窗（展示咽喉 showDialog + ARouter 直启兜底）。依据见 design.md §三 F1 挽留弹窗。 */
internal fun collectRetentionDialogHooks(pending: MutableList<MountTask>) {
    pending += MountTask(
        kind = MountKind.BLOCK,
        className = CLS_RETENTION_DIALOG_MANAGER,
        methodName = "showDialog",
        paramCount = 1,
        returnType = "void",
        value = null,
        id = "theme:RetentionMgr.showDialog",
        description = "F1 挽留弹窗公共咽喉空实现（全部 6 型，OverallRetentionDialogActivity.java:158 实证）",
    )
    pending += MountTask(
        kind = MountKind.FINISH_ACTIVITY,
        className = CLS_RETENTION_DIALOG_ACTIVITY,
        methodName = "onCreate",
        paramCount = 1,
        returnType = "void",
        value = null,
        id = "theme:RetentionActivity.onCreate",
        description = "F2 兜底 ARouter 直启：原 onCreate 走完后 finish（OverallRetentionDialogActivity.java:861 实证）",
    )
}

/** 功能位归组：C1/C2 为 F3/F4 双用途、归组不确定 → 保守归 F2；C5/C6 → F3；C4 → F4。 */
internal fun collectTrialHooks(pending: MutableList<MountTask>) {
    // CLS_TRYOUT = $PKG.tryout.a —— **混淆名**，各点带 DexKit 锚点：
    // 类内日志 TAG "TryOutHelper" 是类级稳定字符串；isValidTheme/checkCurrentTheme 的方法体另有独有串。
    addConst(pending, CLS_TRYOUT, "isValidTheme", 0, "boolean", true,
        "theme:TryOut.isValidTheme", "F3/F4 C1 离线试用有效判定（8 分钟窗）→ 永久有效（归组不确定，保守归 F2）",
        dex = DexHint(strings = listOf("not register theme ware")))
    addConst(pending, CLS_TRYOUT, "isWareOutDated", 1, "boolean", false,
        "theme:TryOut.isWareOutDated", "F3/F4 C2 SP 超窗判定 → 永不过期（归组不确定，保守归 F2）",
        dex = DexHint(classStrings = listOf("TryOutHelper"), paramTypes = listOf("int")))
    addConst(pending, CLS_TRYOUT, "checkCurrentTheme", 0, "boolean", true,
        "theme:TryOut.checkCurrentTheme", "F4 C4 当前主题试用态核查 → 有效（防 fence 到期回收）",
        dex = DexHint(strings = listOf("isFileExists")))
    addConst(pending, CLS_INCENTIVE_INFO, "duringIncentive", 0, "boolean", true,
        "theme:Incentive.duringIncentive", "F3 C5 在线任务授权窗判定 → 永久有效")
    addConst(pending, CLS_INCENTIVE_INFO, "getIncentiveExpireTime", 0, "long", Long.MAX_VALUE,
        "theme:Incentive.getIncentiveExpireTime", "F3 C6 授权到期时间 → 拉远")
    // C8 isTrialSupportOnline → 已并入云控统一读点表（CloudSwitchTable: trial_support_online → true），
    // 不再单独挂 AgreeRepo 方法（同一 key 只保留一个拦截点）。已删 C3（零调用者）见 audit §三.1。
}

/** C7/C9：ROM ThemeProvider 路径——试用态判定（含回退来源）与试用角标都走 ThemeResManager。 */
internal fun collectApplyStateHooks(pending: MutableList<MountTask>) {
    // C7：PERMANENT 延迟解析（依赖 ThemeApplyState 类加载，未加载时归懒加载重试）
    // enumConstants 会触发目标枚举 <clinit>（invoke 静态 values()）——已由 runCatching 全捕获；
    // 仅运行于主题商店进程挂载/重试线程（非 SystemUI/launcher 启动路径，v2.3 boot-loop 事故模式不适用）
    pending += MountTask(
        kind = MountKind.CONST,
        className = CLS_THEME_RES_MANAGER,
        methodName = "getThemeApplyState",
        paramCount = 0,
        returnType = CLS_THEME_APPLY_STATE,
        value = null,
        id = "theme:ThemeResManager.getThemeApplyState",
        description = "F4 C7 主题应用状态 → PERMANENT（永非试用态，不回退）",
        resolveValue = { loader ->
            runCatching {
                loader.loadClass(CLS_THEME_APPLY_STATE)
                    .enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "PERMANENT" }
            }.getOrNull()
        },
    )
    addConst(pending, CLS_THEME_RES_MANAGER, "isTrialTheme", 1, "boolean", false,
        "theme:ThemeResManager.isTrialTheme", "F3 C9 移除'试用中'判定 → 无试用角标/到期提醒")
}
