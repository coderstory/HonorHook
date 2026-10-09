package top.honorhook.xposed.target

import android.app.Activity
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import top.honorhook.data.KEY_F1_VIP_ENABLED
import top.honorhook.data.KEY_F2_FREE_ENABLED
import top.honorhook.data.KEY_F3_TRIAL_ENABLED
import top.honorhook.data.KEY_F4_NO_RESET_ENABLED
import top.honorhook.data.KEY_HOOK_ENABLED
import top.honorhook.xposed.MethodResolver
import top.honorhook.xposed.MountScheduler
import top.honorhook.xposed.RemotePrefsHolder

/**
 * 荣耀主题商店目标（F1-F4）——docs/honorhook-theme-requirements.md 落地；实现依据
 * docs/honor-theme-payment-analysis.md §6 Hook 点清单（目标类名在 keep 名单内可读、不混淆漂移），
 * 已逐一对照 theme-store-jadx/sources 复核全部目标类/方法签名（行号与报告一致）。
 *
 * 按钮矩阵方向修正（docs/theme-ui-real-chain.md 唯一依据，2026-10）：门类要放行——原 isBuyBtnShow 等
 * 门返 false 会让 updateButtons 整段不执行、按钮停留 XML 初始态（真机"没变"的机制根因）；按钮组最终
 * 显隐由 T1 getThemeDetailButtonType→0 → updateButtons switch 主控（服务端激励失败强制置 -1 会
 * hideLayout 全隐藏，勿返 -1）；T2/T3 壁纸/挂件平行购买门关；T5/T6 VipState 状态→2（原 1=NOT_VIP
 * 是"新用户专享"卡根因）。实证：IncentiveData.java:142、ToolBarGroupLayout.java:489/924、
 * WallpaperDetailView.java:405/722、MineVipCardView.java:1880-1904、VipState.java:314/319/355、
 * OnlinePreviewBaseActivity.java:3097/4161/5686/2920-2924。
 *
 * 下载 0% 修复（docs/theme-download-chain.md §4 唯一依据，H1/H2 必须成对）：付费主题被 F2 免费化后
 * 详情页"下载"永远 0%——F2 把付费项推入免费分支（o52.java:604）直接用 item JSON 的 fileUrl 下载，
 * 而付费资源服务端不下发可用直链（真实 URL 只在 sendProduct 授权接口返回）→ uri 空 → DownloadHandler
 * 静默短路 → UI 定格 0%。H1 DownloadInfo.isPayedItem（:939，uri 字段 L145）uri 空 → true（导入授权
 * 下载分支）；H2 o52.addDownloadItem 4 参版（:882，dex 实证 `Lo52;` 默认包类，非 ads_mobile_sdk/yads
 * 同名类）uri 空 → 第 3 参 downloadType 2（TRIAL，分派 :617 → e71 试用策略 → sendProduct(trialFlag=1)
 * 签发试用 URL+密钥）；H2b common.a.getDownloadType（a.java:78）兜底详情页直调 builder 版链
 * （common/d.java:233 不经 4 参版），同条件返 2、双点不冲突。uri 非空（真免费 fileUrl/已购
 * download.do?k= 直链）一律放行不误伤；只做 H1 不做 H2 → 落 c71 普通购买分支弹 IAP 支付页。
 *
 * 挽留弹窗拦截（docs/theme-ui-real-chain.md §现象四 4.5，F1/F2 双保险，归组 F1）：showDialog 空实现
 * （void 拦截器返 null = 不调原方法）单点覆盖全部 6 型（含真机 MEMBER_EXPIRE=4）；onCreate proceed
 * 后 finish 兜 ARouter 直启（照 fanqie MainHook.java:689 先例；HC 的 finish+不 proceed 模式不采用）；
 * F3/F5 源头不挂：getData 拦截连带试用资源回收副作用（doResRecycle）、handleArrivedPushMsg 影响推送
 * 入库面大。
 *
 * F2 A 组 11 点全上（报告"建议全上"）：A1-A4 ItemInfo 判定短路（列表/详情全局生效——所有页面按钮
 * 逻辑都走它）；A5 price/actualPrice 是 double 字段、libxposed 102 不能 hook 字段 → getter 归零 +
 * setter 首参强 0 组合；A11 服务端 bean 源头归零（列表接口直接用）。F1 B 组：VipManager.isVipUser→
 * true（报告首选读点）+ setVipUser 首参强 true（网络/本地任何"过期"回写都失效）；VipState 到期/状态
 * getter → 2088-08-08 永久有效；另补 VipState.isVipUser→true（自带独立字段读路径，VipState.java:355）。
 * 真机补充：到期日期展示走 getDisplayTimeTimestamp（MineVipCardView.java:430/441、
 * HwThemeManagerActivity.java:894/901）而非 expireTimeTimestamp。B5 checkVipStateOnline 不挂
 * （报告标"非必须，B1 已够"，网络回写点不如读点稳）。F3 C 组 9 点：离线 8 分钟窗
 * （tryout.a.isValidTheme→true/isWareOutDated→false）、在线任务授权窗（duringIncentive→true/
 * getIncentiveExpireTime→Long.MAX）、isSupportTrial/checkCurrentTheme→true、云控
 * AgreeRepo.isTrialSupportOnline→true。F4：回退路径判定点全放行（tryout 有效态防 ThemeTimeFenceManager
 * 到期回收发"试用已结束" + ThemeResManager.getThemeApplyState→PERMANENT/isTrialTheme→false
 * + 永久 VIP 会员到期入口不触发）。
 *
 * 2026-10-09 哲学审计（12 点 → 4 点）：7 个 VipState 散读 getter（B3/B4/T5/T6/displayTime 等）
 * + T8 getVipCardType（T5→2 已覆盖其 switch，深签名冗余）+ 2 个 fetchShouldDialogBy* 休眠点（零调用
 * 链：.getShouldDialog( 唯一调用点 VipCheckVM.java:164 在 refreshShouldDialog 自环内、全工程无真实
 * 调用方；真机弹窗走 overallretentiondialog 链已由展示咽喉拦截，§4.4 实证）删除，并入 VipManager
 * 双漏斗 VIP_STATE_FIX（合并依据与调用方清单见 collectVipHooks KDoc）。
 *
 * 功能位归组（同一方法只属一个功能位；归组不确定保守归 F2；总开关为上位语义）：F2=KEY_F2_FREE_ENABLED
 * （A1-A11 + T1-T3 + H1/H2/H2b + C1/C2）；F1=KEY_F1_VIP_ENABLED（B1/B2 + 双漏斗 + 挽留弹窗）；
 * F3=KEY_F3_TRIAL_ENABLED（C3/C5/C6/C8/C9）；F4=KEY_F4_NO_RESET_ENABLED（C4/C7）。
 *
 * 开关语义（照 HC 模式，与 HookEntry 演示 hook 同构）：挂载无条件，每个 intercept 内现读远程 prefs
 * （总开关 × 功能位 key）决定本次是否生效——切开关下一次调用即生效，无需重启进程。
 * 挂载健壮性（docs/gap-analysis.md G1/G2）：未解析项 postDelayed 约 500ms 重试一轮、再失败约 2s
 * 最后一轮（HC installCustomTileHooks L408-416 同款），仍失败 WARN 放弃；汇总
 * `ThemeStoreHook: installed N/M` 始终输出（真机判据），细节日志由 DEBUG_LOGGING 门控（默认关）。
 *
 * 不挂的点（如实标注）：D 组购买/已购流程（F2 全免费后无购买场景，且 D4/D5 属服务端单/签名与 IAP SDK
 * 支付页，本地 hook 无效，§6 D 组注）；B5（见上）；T10 updateButtons 直改（theme-ui-real-chain.md
 * §五 标可选：全读点都走 T1，L3099/1733/2676/2714/3782 实证，T1 已覆盖 switch 输入，真机不足再上）；
 * F3/F5 挽留弹窗源头拦截（doResRecycle 副作用 + 推送入库面大，见上）。
 */
object ThemeStoreHook : TargetHook {

    override val packageName = "com.hihonor.android.thememanager"

    private const val PKG = "com.hihonor.android.thememanager"

    // 目标类（报告 §6；jadx 已复核签名）
    private const val CLS_ITEM_INFO = "$PKG.common.ItemInfo"
    private const val CLS_ITEM_BEAN = "$PKG.hitop.entity.res.BaseResItemBean"
    private const val CLS_PREVIEW = "$PKG.OnlinePreviewBaseActivity"
    private const val CLS_VIP_MANAGER = "$PKG.vip.manager.VipManager"
    private const val CLS_VIP_STATE = "$PKG.vip.data.VipState"
    // 已删（2026-10-09 哲学审计）：CLS_VIP_DEFAULT_CONFIG / CLS_VIP_CLOUD_CONFIG
    // ——fetchShouldDialogByDefault/fetchShouldDialogByCloud 零调用休眠链，见 collectVipHooks 内删除注释

    // 2026-10 反查新增（docs/theme-ui-real-chain.md §五 T1/T2/T3，jadx 实证）
    private const val CLS_INCENTIVE_DATA = "$PKG.incentive.IncentiveData"
    private const val CLS_TOOLBAR_GROUP_LAYOUT = "$PKG.entity.ToolBarGroupLayout"
    private const val CLS_WALLPAPER_DETAIL_VIEW = "$PKG.detail.WallpaperDetailView"
    // 已删（2026-10-09 哲学审计）：CLS_MINE_VIP_CARD / CLS_VIP_CARD_TYPE
    // ——T8 getVipCardType 深签名冗余点（T5 已覆盖其 switch 分支），见 collectVipHooks 内删除注释

    // 2026-10 挽留弹窗拦截（docs/theme-ui-real-chain.md §现象四 4.5，jadx 实证）：
    // Manager 是 Activity 的 Kotlin object 嵌套静态类（Activity.java:150-163；showDialog L158-161
    // ARouter 带 Parcelable DIALOG_PARAMS）；F2 目标即 Activity 自身（onCreate L861 protected，extends FragmentActivity）
    private const val CLS_RETENTION_DIALOG_ACTIVITY = "$PKG.overallretentiondialog.ui.OverallRetentionDialogActivity"
    private const val CLS_RETENTION_DIALOG_MANAGER = "$PKG.overallretentiondialog.ui.OverallRetentionDialogActivity\$OverallRetentionDialogManager"

    /** 2088-08-08 00:00:00 东八区毫秒时间戳——会员到期时间的落地值（Long.MAX 会让日期格式化/剩余天数判定出现异常值）。 */
    private const val TS_2088_08_08_MILLIS = 3_742_732_800_000L
    /** 2088-08-08 展示字符串（与服端 displayTime 同格式 "yyyy-MM-dd HH:mm:ss"，即 fn5 默认解析格式）。 */
    private const val TS_2088_08_08_STR = "2088-08-08 00:00:00"
    private const val CLS_TRYOUT = "$PKG.tryout.a"
    private const val CLS_INCENTIVE_INFO = "$PKG.incentive.entity.TimeLimitIncentiveInfo"
    private const val CLS_AGREE_REPO = "$PKG.ams.mgr.AgreeRepo"
    private const val CLS_THEME_RES_MANAGER = "$PKG.theme.apply.ThemeResManager"
    private const val CLS_THEME_APPLY_STATE = "$PKG.theme.apply.ThemeApplyState"

    // 2026-10 下载链（docs/theme-download-chain.md §4）：DownloadInfo（uri L145 public String /
    // isPayedItem L939 只看 price）；o52 4 参版（L882，dex 实证 `Lo52;` classes18.dex 默认包类，
    // 非 ads_mobile_sdk/yads 同名类）；common.a builder 产品类（a.java:78）——详情页主链路
    // common/d.java:233 直调 builder 版（L563）不经 4 参版
    private const val CLS_DOWNLOAD_INFO = "$PKG.common.DownloadInfo"
    private const val CLS_DOWNLOADER_MGR = "o52"
    private const val CLS_DOWNLOAD_MODEL = "$PKG.common.a"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // libxposed 102 的 PackageLoadedParam 只有 defaultClassLoader（HookEntry 同款写法）
        val loader = param.defaultClassLoader
        // DexKit 第二轨兜底（MethodResolver）需要 APK 磁盘路径：param.applicationInfo.sourceDir（HC ScreenRecorderModule.kt:85 实证）
        apkPath = param.applicationInfo?.sourceDir
        // 预热远程 prefs（进程级缓存、失败自动重读——修复「install 期一次性读取失败后 null 冻结」坑）
        RemotePrefsHolder.obtain(module)

        retryRounds = 0
        okCount = 0
        failedIds.clear()

        // 挂载表 38 项（46 − 2026-10-09 审计删 10 点 + 新增漏斗 2）；"同一方法只属一个功能位"
        val pending = mutableListOf<MountTask>()
        collectFreeJudgmentHooks(pending)   // F2 A1-A4
        collectPriceZeroHooks(pending)      // F2 A5
        collectBeanSourceZeroHooks(pending) // F2 A11
        collectButtonMatrixHooks(pending)   // F2 A6-A10
        collectRealChainHooks(pending)      // F2 T1-T3
        collectDownloadChainHooks(pending)  // F2 H1/H2/H2b
        collectVipHooks(pending)            // F1 B1/B2 + 双漏斗
        collectRetentionDialogHooks(pending) // F1 挽留弹窗双保险
        collectTrialHooks(pending)          // F3 C3/C5/C6/C8 + F4 C4 + 保守归 F2 C1/C2
        collectApplyStateHooks(pending)     // F4 C7 + F3 C9
        totalCount = pending.size

        attemptMount(pending, module, loader)
    }

    // ---------------- F2：全部资源免费（功能位 key = KEY_F2_FREE_ENABLED） ----------------

    /** A1-A4：ItemInfo 付费/已购判定全短路为免费态（列表/详情全局生效——所有页面按钮逻辑都走它）。 */
    private fun collectFreeJudgmentHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_ITEM_INFO, "isNeedPayItem", 0, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.isNeedPayItem", "F2 A1 需付费判定 → 免费")
        addConst(pending, CLS_ITEM_INFO, "isNeedOrder", 0, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.isNeedOrder", "F2 A2 需下单判定 → 不弹下单")
        addConst(pending, CLS_ITEM_INFO, "getPayed", 0, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.getPayed", "F2 A3 已购读点1 → 已购")
        addConst(pending, CLS_ITEM_INFO, "isPayed", 0, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.isPayed", "F2 A3 已购读点2 → 已购")
        addConst(pending, CLS_ITEM_INFO, "isVipResouce", 0, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.isVipResouce", "F2 A4 VIP 专属标记 → 移除以当普通资源")
    }

    /** A5：price/actualPrice（double 字段）libxposed 102 无法 hook 字段，读点（getter 归零）+ 写点（setter 写 0）组合覆盖。 */
    private fun collectPriceZeroHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_ITEM_INFO, "getPrice", 0, "double", 0.0, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.getPrice", "F2 A5 价格读点（展示）→ 0 元")
        addConst(pending, CLS_ITEM_INFO, "getActualPrice", 0, "double", 0.0, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.getActualPrice", "F2 A5 现价读点（展示）→ 0 元")
        addForceArg(pending, CLS_ITEM_INFO, "setPrice", 1, "void", 0.0, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.setPrice", "F2 A5 价格写点 → 强制写 0")
        addForceArg(pending, CLS_ITEM_INFO, "setActualPrice", 1, "void", 0.0, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.setActualPrice", "F2 A5 现价写点 → 强制写 0")
    }

    /** A11：列表接口直接用服务端 bean 源头（String 价格 + vipOnly）。 */
    private fun collectBeanSourceZeroHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_ITEM_BEAN, "getPrice", 0, "java.lang.String", "0", KEY_F2_FREE_ENABLED,
            "theme:ItemBean.getPrice", "F2 A11 服务端 bean 价格源头 → \"0\"")
        addConst(pending, CLS_ITEM_BEAN, "getActualPrice", 0, "java.lang.String", "0", KEY_F2_FREE_ENABLED,
            "theme:ItemBean.getActualPrice", "F2 A11 服务端 bean 现价源头 → \"0\"")
        addConst(pending, CLS_ITEM_BEAN, "getVipOnly", 0, "int", 0, KEY_F2_FREE_ENABLED,
            "theme:ItemBean.getVipOnly", "F2 A11 服务端 bean VIP 专属标记 → 0")
    }

    /**
     * A6-A10：按钮矩阵门类统一放行（docs/theme-ui-real-chain.md §0.1/§四 反查修正，2026-10）。
     * [半效/DEAD 标注：这些方法不是渲染开关而是入口门；布尔参数仅喂 changeVipBtnsVisibility
     * （L4161/2920-3028 实证）、非最终仲裁——本轮保守保留，真机日志观察后按需清理]
     * 机制：门返 false 会让 L3097 updateButtons 整段不执行、按钮停留 XML 初始态（真机"没变"根因）；
     * 最终显隐由 T1 → updateButtons switch（L5686）主控；mBuyBtn 另受 u02.isSupportPayed()
     * （u02.java:459）与 isShowIconCustom/isAudit（L2920-2924）约束。
     */
    private fun collectButtonMatrixHooks(pending: MutableList<MountTask>) {
        // 门要放行（原 false 方向反了，门开 L3097 updateButtons 才执行）；购买按钮显隐由
        // u02.isSupportPayed() 能力判定兜底，不另挂【购买】隐藏
        addConst(pending, CLS_PREVIEW, "isBuyBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isBuyBtnShow", "F2 A6 购买门放行（原 false 跳过 updateButtons 停留初始态）→ true")
        // 门类统一放行（对照表：布尔参数非最终仲裁，T1 主控）
        addConst(pending, CLS_PREVIEW, "isDownloadBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isDownloadBtnShow", "F2 A7 '下载'按钮常显（门类放行）")
        addConst(pending, CLS_PREVIEW, "isFreeAccessBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isFreeAccessBtnShow", "F2 A8 '免费获取'门放行（非最终仲裁）")
        addConst(pending, CLS_PREVIEW, "isTrialBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isTrialBtnShow", "F2 A10 '试用'门放行（非最终仲裁）")
        addConst(pending, CLS_PREVIEW, "isVipBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isVipBtnShow", "F2 A9 VIP 门放行（原 false 属门类误关）→ true")
    }

    /**
     * T1-T3（docs/theme-ui-real-chain.md §五）：按钮组最终显隐 = 门过后读服务端按钮类型
     * getThemeDetailButtonType → updateButtons switch（OnlinePreviewBaseActivity.java:3097-3102、
     * ToolBarGroupLayout.java:924-932、WallpaperDetailView.java:722-730 实证；全读点同一 getter，
     * 钩一处全覆盖）。T1→0 免费领小按钮组（IncentiveCommonManager.java:636 激励失败强制置 -1 →
     * hideLayout 全隐藏，绝不返 -1）；T2/T3→false 壁纸/挂件平行购买门（与 isBuyBtnShow 同构的
     * 独立副本，旧 hook 未覆盖）。
     */
    private fun collectRealChainHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_INCENTIVE_DATA, "getThemeDetailButtonType", 0, "int", 0, KEY_F2_FREE_ENABLED,
            "theme:IncentiveData.getThemeDetailButtonType", "F2 T1 服务端按钮类型 → 0（免费领小按钮组；勿返 -1 全隐藏）")
        addConst(pending, CLS_TOOLBAR_GROUP_LAYOUT, "needShowBuyButton", 1, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:ToolBar.needShowBuyButton", "F2 T2 壁纸/挂件购买门（ToolBarGroupLayout:489，旧未覆盖）→ 关，走免费分支")
        addConst(pending, CLS_WALLPAPER_DETAIL_VIEW, "needShowBuyButton", 1, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:WallpaperDetail.needShowBuyButton", "F2 T3 壁纸详情弹窗购买门（WallpaperDetailView:405，T2 同构副本）→ 关")
    }

    /**
     * H1/H2/H2b（docs/theme-download-chain.md §4，2026-10）：空 URL 付费项 → 试用授权下载通道
     * （e71 = sendProduct trialFlag=1，服务端签发真实 URL + 内容密钥）。
     * 机制：F2 A5 价格归零 → isPayedItem 恒 false → o52 免费分支（L604）用 fileUrl 下载，
     * 付费资源 fileUrl 非直链 → uri 空 → DownloadHandler 静默短路 → 0% 定格。
     * H1 uri 空 → 常返 true（进授权分支）；H2 4 参版 uri 空 → downloadType 2（TRIAL，避开 c71
     * 普通购买弹 IAP）；H2b builder 版分派读出兜详情页主链路（common/d.java:233 直调 1 参版 L563
     * 不经 4 参版）。三挂载点共用条件：**uri 为空（null/空串）**；非空放行原逻辑（不误伤）。
     * 挂载点分工路由见 MountTask.uriEmptyRoute/uriOwner 注释。
     */
    private fun collectDownloadChainHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_DOWNLOAD_INFO,
            methodName = "isPayedItem",
            paramCount = 0,
            returnType = "boolean",
            value = true,
            featureKey = KEY_F2_FREE_ENABLED,
            id = "theme:DownloadInfo.isPayedItem",
            description = "F2 H1 空 URL 付费项判定 → 付费（导入授权下载分支；uri 字段 DownloadInfo.java:145 实证）",
            uriEmptyRoute = true,
        )
        pending += MountTask(
            kind = MountKind.FORCE_ARG,
            className = CLS_DOWNLOADER_MGR,
            methodName = "addDownloadItem",
            paramCount = 4,
            returnType = "boolean",
            value = 2,
            featureKey = KEY_F2_FREE_ENABLED,
            id = "theme:o52.addDownloadItem",
            description = "F2 H2 空 URL 项 downloadType → 2（TRIAL；o52.java:882/617 e71 试用策略）",
            uriEmptyRoute = true,
            uriOwner = UriOwner.ARG,
            uriArgIndex = 1,
            forceArgIndex = 2,
        )
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_DOWNLOAD_MODEL,
            methodName = "getDownloadType",
            paramCount = 0,
            returnType = "int",
            value = 2,
            featureKey = KEY_F2_FREE_ENABLED,
            id = "theme:DownloadModel.getDownloadType",
            description = "F2 H2b 空 URL 项 downloadType 分派读出 → 2（TRIAL；o52.java:616 分派点，兜底详情页 builder 版链路）",
            uriEmptyRoute = true,
            uriOwner = UriOwner.GET_DOWNLOAD_INFO,
        )
    }

    // ---------------- F1：开启 VIP（功能位 key = KEY_F1_VIP_ENABLED） ----------------

    /**
     * F1 VIP 组（2026-10-09 哲学审计后结构，12 点 → 4 点）。
     * - 删除 10 点：7 个 VipState 散读 getter（B3a/B3b/T5/T6/VipState.isVipUser/getDisplayTimeTimestamp/
     *   getDisplayTime）+ T8 MineVipCardView.getVipCardType（深签名冗余，T5 已覆盖其 switch）
     *   + 2 个 fetchShouldDialogBy* 休眠点（零调用链，删因见函数内注释）；
     * - 保留 2 点：B1 VipManager.isVipUser 读点（VipManager.java:111 页面级直接判定，非实例散读）、
     *   B2 setVipUser 写点（覆盖登出/特权撤销/网络回写路径，setVipUser(false) 强制 true）；
     * - 新增 2 漏斗：getLocalVipState（VipManager.java:55，页面级 LiveData 汇聚点：DetailSource:219/
     *   ApplySource:1149/WidgetUtil:131/AdsRequestSource:285/ApplyResourceServer:357 实证）与
     *   getVipState（VipManager.java:88 @Nullable，VipCenter 中心直读：VipMemberCenterActivity:345/
     *   VipCenterModel:1131/ArrivedPushManager:3228 实证），intercept 拿 VipState 实例逐 setter 改写——
     *   散读 getter 读的是同一实例，一处改写全链路生效（依赖权重：汇聚点 2 钩 > 散读点 7 钩）。
     */
    private fun collectVipHooks(pending: MutableList<MountTask>) {
        // B1 保留：VipManager 页面级直接判定读点（非 VipState 实例散读）
        addConst(pending, CLS_VIP_MANAGER, "isVipUser", 0, "boolean", true, KEY_F1_VIP_ENABLED,
            "theme:VipManager.isVipUser", "F1 B1 VIP 判定读点（首选）→ 是 VIP")
        // B2 保留：任何 setVipUser(false)（含服务端拉到的过期状态）强制写 true，覆盖登出/特权撤销/网络回写
        addForceArg(pending, CLS_VIP_MANAGER, "setVipUser", 1, "void", true, KEY_F1_VIP_ENABLED,
            "theme:VipManager.setVipUser", "F1 B2 VIP 写点 → 强制置 true")
        // 已删（2026-10-09 审计）：fetchShouldDialogBy* 休眠点——零调用链实证：唯一调用点
        // VipCheckVM.java:164 在 refreshShouldDialog 自环内、全工程无真实调用方（HwThemeManagerActivity.java:2785
        // 仅定义处）；真机弹窗走 overallretentiondialog 链已由展示咽喉拦截（theme-ui-real-chain.md §4.4）
        // 已删（2026-10-09 审计）：T8 getVipCardType 深签名冗余点——T5 getVipStatus→2 已使同一 switch
        // （MineVipCardView.java:1880-1904）走 SINGLE_WITH_SAVE 分支
        pending += MountTask(
            kind = MountKind.VIP_STATE_FIX,
            className = CLS_VIP_MANAGER,
            methodName = "getLocalVipState",
            paramCount = 0,
            returnType = CLS_VIP_STATE,
            value = null,
            featureKey = KEY_F1_VIP_ENABLED,
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
            featureKey = KEY_F1_VIP_ENABLED,
            id = "theme:VipManager.getVipState",
            description = "F1 漏斗2 会员中心直读点改写（VipManager.java:88；VipMemberCenterActivity:345/VipCenterModel:1131/ArrivedPushManager:3228 实证）",
        )
    }

    // ---------------- F1：挽留弹窗拦截（VIP 已过期挽留弹窗，双保险；归组 F1） ----------------

    /**
     * 挽留弹窗（docs/theme-ui-real-chain.md §现象四 4.5，唯一依据）：走 overallretentiondialog 子系统
     * （pushType=VIP_ARRIVED→MEMBER_EXPIRE=4 等，展示咽喉唯一出口是 showDialog）；旧 fetchShouldDialogBy*
     * hook 拦不住——它是休眠消费链（4.4 实证）。拦截点选展示咽喉，F1/F2 双保险：
     * - F1 showDialog 空实现（void 拦截器返 null = 不调原方法，libxposed api 语义，
     *   AI-lsposed-temlate SKILL.md:102 实证）：单点覆盖全部 6 型（RESOURCE_TRIAL_END=1/
     *   MEMBER_SOON_EXPIRE=2/EXPIRED_RESOURCES=3/MEMBER_EXPIRE=4/EXPIRED_SPRITE=5/MEMBER_LOGOUT=6），
     *   含真机 MEMBER_EXPIRE；
     * - F2 onCreate 先 proceed 后 finish（ARouter 直启绕过 F1 的兜底）：不 proceed 会让生命周期在未
     *   初始化状态下走完（onDestroy 等回调可能 NPE），proceed 后再 finish 更稳。
     * F3/F5 不挂的原因见类注释"不挂的点"。
     */
    private fun collectRetentionDialogHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.BLOCK,
            className = CLS_RETENTION_DIALOG_MANAGER,
            methodName = "showDialog",
            paramCount = 1,
            returnType = "void",
            value = null,
            featureKey = KEY_F1_VIP_ENABLED,
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
            featureKey = KEY_F1_VIP_ENABLED,
            id = "theme:RetentionActivity.onCreate",
            description = "F2 兜底 ARouter 直启：原 onCreate 走完后 finish（OverallRetentionDialogActivity.java:861 实证）",
        )
    }

    // ---------------- F3：试用无倒计时 + F4：回退路径判定放行 ----------------

    /** 功能位归组：C1/C2 为 F3/F4 双用途、归组不确定 → 保守归 F2；C3/C5/C6/C8 → F3；C4 → F4。 */
    private fun collectTrialHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_TRYOUT, "isValidTheme", 0, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:TryOut.isValidTheme", "F3/F4 C1 离线试用有效判定（8 分钟窗）→ 永久有效（归组不确定，保守归 F2）")
        addConst(pending, CLS_TRYOUT, "isWareOutDated", 1, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:TryOut.isWareOutDated", "F3/F4 C2 SP 超窗判定 → 永不过期（归组不确定，保守归 F2）")
        addConst(pending, CLS_TRYOUT, "isSupportTrial", 0, "boolean", true, KEY_F3_TRIAL_ENABLED,
            "theme:TryOut.isSupportTrial", "F3 C3 支持试用 → 常显可用")
        addConst(pending, CLS_TRYOUT, "checkCurrentTheme", 0, "boolean", true, KEY_F4_NO_RESET_ENABLED,
            "theme:TryOut.checkCurrentTheme", "F4 C4 当前主题试用态核查 → 有效（防 fence 到期回收）")
        addConst(pending, CLS_INCENTIVE_INFO, "duringIncentive", 0, "boolean", true, KEY_F3_TRIAL_ENABLED,
            "theme:Incentive.duringIncentive", "F3 C5 在线任务授权窗判定 → 永久有效")
        addConst(pending, CLS_INCENTIVE_INFO, "getIncentiveExpireTime", 0, "long", Long.MAX_VALUE, KEY_F3_TRIAL_ENABLED,
            "theme:Incentive.getIncentiveExpireTime", "F3 C6 授权到期时间 → 拉远")
        addConst(pending, CLS_AGREE_REPO, "isTrialSupportOnline", 0, "boolean", true, KEY_F3_TRIAL_ENABLED,
            "theme:AgreeRepo.isTrialSupportOnline", "F3 C8 云控试用开关 → 常开")
    }

    /** C7/C9：ROM ThemeProvider 路径——试用态判定（含回退来源）与试用角标都走 ThemeResManager。 */
    private fun collectApplyStateHooks(pending: MutableList<MountTask>) {
        // C7：PERMANENT 延迟解析（依赖 ThemeApplyState 类加载，未加载时归懒加载重试）
        // enumConstants 会触发目标枚举 <clinit>（invoke 静态 values()）——已由下方 runCatching 全捕获；
        // 仅运行于主题商店进程挂载/重试线程（非 SystemUI/launcher 启动路径，v2.3 boot-loop 事故模式不适用）
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_THEME_RES_MANAGER,
            methodName = "getThemeApplyState",
            paramCount = 0,
            returnType = CLS_THEME_APPLY_STATE,
            value = null,
            featureKey = KEY_F4_NO_RESET_ENABLED,
            id = "theme:ThemeResManager.getThemeApplyState",
            description = "F4 C7 主题应用状态 → PERMANENT（永非试用态，不回退）",
            resolveValue = { loader ->
                runCatching {
                    loader.loadClass(CLS_THEME_APPLY_STATE)
                        .enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "PERMANENT" }
                }.getOrNull()
            },
        )
        addConst(pending, CLS_THEME_RES_MANAGER, "isTrialTheme", 1, "boolean", false, KEY_F3_TRIAL_ENABLED,
            "theme:ThemeResManager.isTrialTheme", "F3 C9 移除'试用中'判定 → 无试用角标/到期提醒")
    }

    // ---------------- 挂载执行：懒加载重试（HC postDelayed 模式）+ 汇总（G1/G2） ----------------

    /** 失败分级：[FAILED] hook 挂载异常（重试无意义）；[NOT_RESOLVED] 类/方法（或依赖值）未解析（可随类加载重试）。 */
    private enum class MountResult { OK, FAILED, NOT_RESOLVED }

    /**
     * 挂载任务类型：[CONST] 常量短路；[FORCE_ARG] 第 forceArgIndex 参强制；
     * [BLOCK] void 空实现阻断（返回 null = 不调原方法，libxposed 语义，AI-lsposed-temlate SKILL.md:102 实证）；
     * [FINISH_ACTIVITY] 先 proceed 再 finish（不 proceed 生命周期会在未初始化状态走完，可能 NPE）；
     * [VIP_STATE_FIX] after 改写 VipState 实例字段（失败放行原逻辑，PROTECTIVE 纪律，见 fixVipState）。
     */
    private enum class MountKind { CONST, FORCE_ARG, BLOCK, FINISH_ACTIVITY, VIP_STATE_FIX }

    /** DownloadInfo 实例获取路径（uriEmptyRoute 条件路由）：[THIS] H1；[ARG] H2 第 uriArgIndex 参；[GET_DOWNLOAD_INFO] H2b 反射 getDownloadInfo()。 */
    private enum class UriOwner { THIS, ARG, GET_DOWNLOAD_INFO }

    private class MountTask(
        val kind: MountKind,
        val className: String,
        val methodName: String,
        val paramCount: Int,
        val returnType: String,
        val value: Any?,
        val featureKey: String,
        val id: String,
        val description: String,
        val resolveValue: ((ClassLoader) -> Any?)? = null,
        /** 条件路由（下载链 H1/H2/H2b）：仅 uri 为空（null/空串）时短路/改参，非空放行（不误伤正常免费/已购项）。 */
        val uriEmptyRoute: Boolean = false,
        /** DownloadInfo 实例获取路径（见 [UriOwner]）。 */
        val uriOwner: UriOwner = UriOwner.THIS,
        /** [UriOwner.ARG] 时 DownloadInfo 所在参数下标（0 起；H2 为 1）。 */
        val uriArgIndex: Int = -1,
        /** [MountKind.FORCE_ARG] 强制参数下标（0 起；默认 0 = 既有 setter 首参语义不变）。 */
        val forceArgIndex: Int = 0,
    )

    /** 已完成重试轮数（节奏见 RETRY_* 常量；HC installCustomTileHooks postDelayed 同款）。 */
    private var retryRounds = 0
    private var okCount = 0
    private var totalCount = 0
    private val failedIds = mutableListOf<String>()
    /** 目标 APK 磁盘路径（install 期写入，MethodResolver 第二轨 DexKit 兜底用；null 时跳过）。 */
    private var apkPath: String? = null

    private const val MAX_RETRY_ROUNDS = 2
    private const val RETRY_DELAY_MS = 500L
    private const val RETRY_FINAL_DELAY_MS = 2000L

    /** 挂载细节日志门控（默认关；汇总日志不受门控、始终输出）。 */
    private const val DEBUG_LOGGING = false

    private fun addConst(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        value: Any?,
        featureKey: String,
        id: String,
        description: String,
    ) {
        pending += MountTask(
            kind = MountKind.CONST, className = className, methodName = methodName, paramCount = paramCount,
            returnType = returnType, value = value, featureKey = featureKey, id = id, description = description,
        )
    }

    private fun addForceArg(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        forcedArg: Any?,
        featureKey: String,
        id: String,
        description: String,
    ) {
        pending += MountTask(
            kind = MountKind.FORCE_ARG, className = className, methodName = methodName, paramCount = paramCount,
            returnType = returnType, value = forcedArg, featureKey = featureKey, id = id, description = description,
        )
    }

    /** 挂载一轮 pending（HC installCustomTileHooks L408-416 postDelayed 懒加载重试同款）。 */
    private fun attemptMount(
        pending: MutableList<MountTask>,
        module: XposedModule,
        loader: ClassLoader,
    ) {
        val unresolved = mutableListOf<MountTask>()
        for (task in pending) {
            when (mountOne(task, module, loader)) {
                MountResult.OK -> okCount++
                MountResult.FAILED -> failedIds += task.description
                MountResult.NOT_RESOLVED -> unresolved += task
            }
        }
        if (unresolved.isEmpty()) {
            logSummary(module)
        } else if (retryRounds >= MAX_RETRY_ROUNDS) {
            failedIds += unresolved.map { it.description }
            module.log(
                Log.WARN, TAG,
                "部分 hook 最终未挂载（类/方法不存在，已重试 $MAX_RETRY_ROUNDS 轮，放弃）: " +
                    unresolved.joinToString { it.description },
            )
            logSummary(module)
        } else {
            retryRounds++
            val delay = if (retryRounds == 1) RETRY_DELAY_MS else RETRY_FINAL_DELAY_MS
            MountScheduler.postDelayed(delay) {
                runCatching { attemptMount(unresolved, module, loader) }
                    .onFailure { t -> module.log(Log.ERROR, TAG, "懒加载重试挂载异常", t) }
            }
        }
    }

    /** 单个挂载任务执行（失败分级见 [MountResult]）。 */
    private fun mountOne(
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountResult {
        val resolved = task.resolveValue?.invoke(loader)
        if (task.resolveValue != null && resolved == null) {
            module.log(Log.WARN, TAG, "hook 未挂载（依赖项未解析，将重试）: ${task.description}")
            return MountResult.NOT_RESOLVED
        }
        val method = findMethod(loader, task.className, task.methodName, task.paramCount, task.returnType)
            ?: MethodResolver.resolveMethod(loader, task.className, task.methodName, task.paramCount, task.returnType, apkPath)
        if (method == null) {
            module.log(Log.WARN, TAG, "hook 未挂载（类/方法不存在，版本差异，将重试）: ${task.description}")
            return MountResult.NOT_RESOLVED
        }
        val value = resolved ?: task.value
        return runCatching {
            val builder = module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(task.id)
            when (task.kind) {
                // 常量短路（每次调用现读远程 prefs——不捕获 install 期，防 null 冻结坑）
                MountKind.CONST -> builder.intercept { chain ->
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) value else chain.proceed()
                }
                // 参数强制（setter 写点 / 下载类型路由点）
                MountKind.FORCE_ARG -> builder.intercept { chain ->
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) {
                        val args = chain.args?.toTypedArray() ?: arrayOfNulls<Any?>(task.forceArgIndex + 1)
                        args[task.forceArgIndex] = value
                        chain.proceedWith(chain.thisObject, args)
                    } else {
                        chain.proceed()
                    }
                }
                // void 空实现阻断（挽留弹窗 F1；判据日志不受 DEBUG_LOGGING 门控）
                MountKind.BLOCK -> builder.intercept { chain ->
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) {
                        module.log(Log.INFO, TAG, "挽留弹窗已拦截: ${task.methodName}")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                // 放行后 finish（挽留弹窗 F2 兜底）：先 proceed 再 finish，生命周期完整（不 proceed 会让
                // onStart/onDestroy 等在未初始化状态跑，可能 NPE；fanqie MainHook.java:689 先例，HC 的
                // finish+不 proceed 模式不采用）
                MountKind.FINISH_ACTIVITY -> builder.intercept { chain ->
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) {
                        val result = chain.proceed()
                        (chain.thisObject as? Activity)?.finish()
                        module.log(Log.INFO, TAG, "挽留弹窗已拦截: ${task.methodName}")
                        result
                    } else {
                        chain.proceed()
                    }
                }
                // VIP 状态漏斗改写（F1 双漏斗，2026-10-09 审计新增；失败放行原逻辑，PROTECTIVE 纪律）
                MountKind.VIP_STATE_FIX -> builder.intercept { chain ->
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) {
                        fixVipState(chain, module, loader, task)
                    } else {
                        chain.proceed()
                    }
                }
            }
        }.fold(
            onSuccess = {
                if (DEBUG_LOGGING) {
                    val verb = when (task.kind) {
                        MountKind.CONST -> "常返"
                        MountKind.FORCE_ARG -> "参数强制"
                        MountKind.BLOCK -> "空实现阻断"
                        MountKind.FINISH_ACTIVITY -> "放行后 finish"
                        MountKind.VIP_STATE_FIX -> "实例改写"
                    }
                    module.log(Log.INFO, TAG, "hook 已挂载: ${task.description} → $verb ${value ?: "null"} (feature=${task.featureKey})")
                }
                MountResult.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountResult.FAILED
            },
        )
    }

    // ---------------- VIP_STATE_FIX 漏斗改写（F1，2026-10-09 哲学审计新增） ----------------

    /** vip-state 漏斗日志节流 ≤3 次（getLocalVipState/getVipState 是高频 LiveData 汇聚点，防刷屏）。 */
    private var vipFixLogCount = 0

    /**
     * VIP_STATE_FIX 改写（after 语义）：先 proceed 拿 VipState 实例 → 逐 setter 填永久 VIP 值；
     * 实例 null（getVipState 首查可能）→ 无参构造自建（VipState.java:94 数据类全默认参）后返回。
     * setter 签名按 jadx VipState.java 实证：setVipUser(Z)/setVipStatus(I)/setVipType(Integer)/
     * setExpireTimeTimestamp(J)/setDisplayTime(String)/setRemainingTime(Long)；逐个独立 runCatching，
     * 缺失的 setter（版本漂移）跳过记 WARN；整体失败放行原逻辑（PROTECTIVE 纪律）。
     * 真机判据日志：`vip-state 漏斗改写: <method> vipUser=true vipStatus=2 ...`（节流 ≤3 次）。
     */
    private fun fixVipState(
        chain: XposedInterface.Chain,
        module: XposedModule,
        loader: ClassLoader,
        task: MountTask,
    ): Any? {
        val orig = runCatching { chain.proceed() }.getOrNull()
        val state = orig ?: runCatching {
            loader.loadClass(CLS_VIP_STATE).getDeclaredConstructor().newInstance()
        }.getOrNull()
        if (state == null) {
            module.log(Log.WARN, TAG, "vip-state 漏斗改写失败（实例不可得），放行原逻辑: ${task.methodName}")
            return orig
        }
        // 剩余时长 = now + 1_950_000_000_000L（到 2088 的真实剩余；原"常量绝对时间戳当剩余时长"语义错误，
        // 防消费端 now+X 计算溢出，2026-10-09 一行语义修正）
        val filled = listOf(
            Triple("setVipUser", java.lang.Boolean.TYPE, true as Any),
            Triple("setVipStatus", java.lang.Integer.TYPE, 2 as Any),
            Triple("setVipType", java.lang.Integer::class.java, 2 as Any),
            Triple("setExpireTimeTimestamp", java.lang.Long.TYPE, TS_2088_08_08_MILLIS as Any),
            Triple("setDisplayTime", String::class.java, TS_2088_08_08_STR as Any),
            Triple("setRemainingTime", java.lang.Long::class.java, System.currentTimeMillis() + 1_950_000_000_000L),
        ).count { (name, paramType, value) -> invokeVipSetter(state, name, paramType, value, module) }
        if (filled == 0) {
            module.log(Log.WARN, TAG, "vip-state 漏斗改写失败（全部 setter 缺失/异常），放行原逻辑: ${task.methodName}")
            return orig
        }
        if (vipFixLogCount < 3) {
            vipFixLogCount++
            module.log(
                Log.INFO, TAG,
                "vip-state 漏斗改写: ${task.methodName} setter=${filled}/6 vipUser=true vipStatus=2 " +
                    "vipType=2 expireTimeTimestamp=$TS_2088_08_08_MILLIS displayTime=$TS_2088_08_08_STR remainingTime=now+1.95e12",
            )
        }
        return state
    }

    /** 单个 VipState setter 反射调用（实证签名精确匹配；失败跳过 = 版本漂移容错，不阻断）。 */
    private fun invokeVipSetter(
        state: Any,
        name: String,
        paramType: Class<*>,
        value: Any,
        module: XposedModule,
    ): Boolean = runCatching {
        state.javaClass.getMethod(name, paramType).invoke(state, value)
    }.onFailure { t ->
        module.log(Log.WARN, TAG, "vip-state setter 缺失/失败（跳过）: $name — ${t.message}")
    }.isSuccess

    /** 挂载汇总始终输出，不受 [DEBUG_LOGGING] 门控（Rain "installed N/M" + fanqie 汇总行模式）。 */
    private fun logSummary(module: XposedModule) {
        val failurePart = if (failedIds.isEmpty()) "" else " — 失败: ${failedIds.joinToString()}"
        module.log(
            if (failedIds.isEmpty()) Log.INFO else Log.WARN,
            TAG,
            "ThemeStoreHook: installed $okCount/$totalCount$failurePart",
        )
    }

    // ---------------- 通用挂载工具 ----------------

    /** 总开关现读（每次 intercept 调用）：prefs 获取失败返回 false = 原逻辑放行（安全默认）。 */
    private fun hookEnabled(prefs: SharedPreferences?): Boolean = prefs?.getBoolean(KEY_HOOK_ENABLED, false) ?: false

    /** 功能位开关 = 总开关（上位语义）&& 对应 key（默认 true）；prefs 失败 = false 放行（安全默认）。 */
    private fun featureEnabled(prefs: SharedPreferences?, key: String): Boolean =
        hookEnabled(prefs) && (prefs?.getBoolean(key, true) ?: true)

    /**
     * 路由激活判定（含下载链 H1/H2/H2b 的 uri 空条件）：总开关 × 功能位开关现读（关 = 原逻辑放行）；
     * uriEmptyRoute=true 时额外要求 DownloadInfo.uri 为空（null/空串）才激活，非空放行；uri 读取失败
     * （版本漂移）放行（安全默认）。触发时输出判据日志（INFO 不受 DEBUG_LOGGING 门控）——
     * 真机从 LSPosed 日志（TAG HonorHook）确认修复链路是否生效（theme-download-chain.md §5）。
     */
    private fun routeActive(
        chain: XposedInterface.Chain,
        prefs: SharedPreferences?,
        module: XposedModule,
        task: MountTask,
    ): Boolean {
        if (!featureEnabled(prefs, task.featureKey)) return false
        if (!task.uriEmptyRoute) return true
        val blank = isUriBlank(chain, task)
        if (blank) {
            module.log(
                Log.INFO, TAG,
                "theme-download-chain 路由触发: ${task.id}（DownloadInfo.uri 为空 → ${actionDesc(task)}）",
            )
        }
        return blank
    }

    private fun actionDesc(task: MountTask): String = when (task.kind) {
        MountKind.CONST -> "常返 ${task.value}"
        MountKind.FORCE_ARG -> "第 ${task.forceArgIndex + 1} 参强制 ${task.value}"
        MountKind.BLOCK -> "空实现阻断（不调原方法）"
        MountKind.FINISH_ACTIVITY -> "放行原方法后 finish Activity"
        MountKind.VIP_STATE_FIX -> "VipState 实例字段改写"
    }

    /**
     * 条件路由的 uri 判空：按 [UriOwner] 取 DownloadInfo 实例，反射读 public String uri
     * （DownloadInfo.java:145 实证）。三分支：① 实例 null → 视为空（H2 场景参数 null 时原入口本就
     * 提前 return，路由无害）；② 字段值 null/空串 → 空、触发路由（坑史：字段值 null 是「读到了 null」
     * 非反射异常，原实现误判成异常放行，本轮修复）；③ 反射异常（版本漂移）→ 放行原逻辑（安全默认）。
     */
    private fun isUriBlank(chain: XposedInterface.Chain, task: MountTask): Boolean {
        val info: Any? = when (task.uriOwner) {
            UriOwner.THIS -> chain.thisObject
            UriOwner.ARG -> chain.args?.getOrNull(task.uriArgIndex)
            UriOwner.GET_DOWNLOAD_INFO -> runCatching {
                chain.thisObject?.javaClass?.getMethod("getDownloadInfo")?.invoke(chain.thisObject)
            }.getOrElse { return false }  // ③ 探测异常（版本漂移）→ 放行（安全默认）
        }
        val owner = info ?: return true  // ① 实例为 null：视为空（原入口本就提前 return，路由无害）
        val uri = runCatching { owner.javaClass.getField("uri").get(owner) }
            .getOrElse { return false }  // ③ 反射异常（NoSuchField/版本漂移）→ 放行（安全默认）
        return (uri as? String).isNullOrEmpty()  // ② 字段值 null/空串 → 空（触发路由）
    }

    /** 直连反射解析（双轨第一轨，HookEntry.resolveTargetMethod 同构）；未命中时 mountOne 转第二轨
     * [MethodResolver.resolveMethod] DexKit 按特征兜底（对抗 OTA 混淆漂移，需 [apkPath]；null 跳过）。 */
    private fun findMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
    ): Method? = runCatching {
        loader.loadClass(className).declaredMethods.firstOrNull { m ->
            m.name == methodName && m.parameterCount == paramCount && m.returnType.name == returnType
        }
    }.getOrNull()

    private const val TAG = "HonorHook"
}