package top.honorhook.xposed.target

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import top.honorhook.xposed.AdSlotFilter
import top.honorhook.xposed.BottomTabFilter
import top.honorhook.xposed.CloudSwitchTable
import top.honorhook.xposed.MountEngine
import top.honorhook.xposed.MountEngine.DexHint

/**
 * 荣耀主题商店目标（F1–F4）：F1 开 VIP / F2 全免费（含下载链修复）/ F3 试用无倒计时 / F4 不恢复默认。
 *
 * **设计依据、挂载点理由与真机判据全部归档在 `docs/theme-store-hook-design.md`**，本文件只保留代码。
 * 目标类名在 keep 名单内可读（不混淆漂移）；全部签名已逐一对 `system-files/theme-store-jadx/sources`
 * 复核。真机基线主题商店 20.1.63.305（挂载数随挂载表变化，**以真机 `installed N/M` 的 M 为准**）。
 *
 * 新增/调整挂载点前：先更新 design.md 的理由，再改此处代码（最少 hook 点 / 根源原则，CLAUDE.md §九）。
 */
object ThemeStoreHook : TargetHook {

    override val packageName = "com.hihonor.android.thememanager"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("ThemeStoreHook")

    private const val PKG = "com.hihonor.android.thememanager"

    // 目标类（报告 §6；jadx 已复核签名）
    private const val CLS_ITEM_INFO = "$PKG.common.ItemInfo"
    private const val CLS_ITEM_BEAN = "$PKG.hitop.entity.res.BaseResItemBean"
    private const val CLS_VIP_MANAGER = "$PKG.vip.manager.VipManager"
    private const val CLS_VIP_STATE = "$PKG.vip.data.VipState"
    // VipModel（服务端响应 → VipState 唯一转换器）曾挂 2 个 VIP_STATE_FIX 漏斗点 + 挽留弹窗休眠链等，
    // 已于 2026-10-09/10 三轮审计删除——历史依据归档见 docs/hook-inventory-audit.md §五。

    // 2026-10 反查新增（docs/theme-ui-real-chain.md §五 T1，jadx 实证）
    private const val CLS_INCENTIVE_DATA = "$PKG.incentive.IncentiveData"
    // 详情页 Activity：仅用于「购买」按钮隐藏（changeVipBtnsVisibility 第 1 参 buy，:2837 定义 / :2920 设 mBuyBtn）。
    // 已删的按钮策略类目标（needShowBuyButton 门 / MineVipCardView T8）历史见 docs/hook-inventory-audit.md §五。
    private const val CLS_PREVIEW = "$PKG.OnlinePreviewBaseActivity"
    // 在线壁纸预览页（类名未混淆）；W1 挂 applyLiveWallpaperPrepare 用，见 collectDownloadChainHooks。
    private const val CLS_WALLPAPER_PREVIEW_LOW = "$PKG.wallpaper.OnlineWallpaperPreviewLowActivity"

    // 2026-10 挽留弹窗拦截（docs/theme-ui-real-chain.md §现象四 4.5，jadx 实证）：
    // Manager 是 Activity 的 Kotlin object 嵌套静态类（Activity.java:150-163；showDialog L158-161
    // ARouter 带 Parcelable DIALOG_PARAMS）；F2 目标即 Activity 自身（onCreate L861 protected，extends FragmentActivity）
    private const val CLS_RETENTION_DIALOG_ACTIVITY = "$PKG.overallretentiondialog.ui.OverallRetentionDialogActivity"
    private const val CLS_RETENTION_DIALOG_MANAGER = "$PKG.overallretentiondialog.ui.OverallRetentionDialogActivity\$OverallRetentionDialogManager"

    /** 2088-08-08 00:00:00 东八区毫秒时间戳——会员到期时间的落地值（Long.MAX 会让日期格式化/剩余天数判定出现异常值）。 */
    private const val TS_2088_08_08_MILLIS = 3_742_732_800_000L
    /** 2088-08-08 展示字符串（与服端 displayTime 同格式 "yyyy-MM-dd HH:mm:ss"，即 fn5 默认解析格式）。 */
    private const val TS_2088_08_08_STR = "2088-08-08 00:00:00"
    // CLS_TRYOUT / CLS_DOWNLOADER_MGR / CLS_DOWNLOAD_MODEL / CLS_SHAREPREF_UTILS 这 4 个是**混淆名**
    // （类名或方法名会被 R8 重映射）。每个挂载点都配了 DexKit 内容锚点（字符串常量 / 参数类型 / 类字段类型），
    // 直连反射失败时按特征兜底；锚点与依据写在各自 collect* 函数的 DexHint 处。
    private const val CLS_TRYOUT = "$PKG.tryout.a"
    private const val CLS_INCENTIVE_INFO = "$PKG.incentive.entity.TimeLimitIncentiveInfo"
    // 云控开关：不再逐个挂 AgreeRepo 方法，统一走 CLS_SHAREPREF_UTILS 读点 + CloudSwitchTable 表
    private const val CLS_THEME_RES_MANAGER = "$PKG.theme.apply.ThemeResManager"
    private const val CLS_THEME_APPLY_STATE = "$PKG.theme.apply.ThemeApplyState"

    // 2026-10 下载链（docs/theme-download-chain.md §4）：DownloadInfo（uri L145 public String /
    // isPayedItem L939 只看 price）；o52 4 参版（L882，dex 实证 `Lo52;` classes18.dex 默认包类，
    // 非 ads_mobile_sdk/yads 同名类）；common.a builder 产品类（a.java:78）——详情页主链路
    // common/d.java:233 直调 builder 版（L563）不经 4 参版
    private const val CLS_DOWNLOAD_INFO = "$PKG.common.DownloadInfo"
    // 已删 H2（`o52.addDownloadItem`，被 H2b 完全覆盖）历史依据见 docs/hook-inventory-audit.md §三.2。
    // DownloadModel builder 产物（package 内混淆名）；DexKit 锚点 = 类字段类型 WeakReference + common.DownloadInfo。
    private const val CLS_DOWNLOAD_MODEL = "$PKG.common.a"

    // 云控统一读点：SharepreferenceUtils（混淆名 u15，默认包）——全部云控布尔开关的唯一出口。
    // 强制项数据在 CloudSwitchTable（key 与逻辑分离），此处只挂读点、查表短路。
    // DexKit 锚点：方法体内的 SP 文件名常量 "themename" + 参数类型序列（见 collectCloudSwitchHooks）。
    private const val CLS_SHAREPREF_UTILS = "u15"

    // 美豆（bonus/金币/福利）入口——服务端下发的**槽位**，无云控布尔开关、无请求参数可关，
    // 只能在「服务端载荷 → 域模型」的出口按内容裁剪：唯一收口即 SlotData.getList()（详见 AdSlotFilter 的载荷实证）。
    // 旧口径（collect 层拦截）已废弃改源头，历史见 docs/theme-store-hook-design.md §九。
    private const val CLS_SLOT_DATA_MATE = "$PKG.hitop.mate.SlotData"

    // 底部导航 tab —— 同样是**服务端下发**的配置（AMS systemParam 的在线配置 JSON），
    // 唯一域模型出口 = BottomTabConfigs.getBottomTabConfigs()（详见 BottomTabFilter 类注释）。
    private const val CLS_BOTTOM_TAB_CONFIGS = "$PKG.hitop.entity.BottomTabConfigs"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // libxposed 102 的 PackageLoadedParam 只有 defaultClassLoader（HookEntry 同款写法）
        val loader = param.defaultClassLoader
        // DexKit 第二轨兜底（MethodResolver）需要 APK 磁盘路径：param.applicationInfo.sourceDir（HC ScreenRecorderModule.kt:85 实证）
        engine.begin(param.applicationInfo?.sourceDir)

        // 挂载表 39 项（38 + W1 动态壁纸应用路径撕裂修正）；**以真机 M 为准。**
        val pending = mutableListOf<MountTask>()
        collectFreeJudgmentHooks(pending)   // F2 A1-A4
        collectPriceZeroHooks(pending)      // F2 A5
        collectBeanSourceZeroHooks(pending) // F2 A11
        collectRealChainHooks(pending)      // F2 T1（按钮组类型兜底）
        collectDownloadChainHooks(pending)  // F2 H1/H2/H2b
        collectCloudSwitchHooks(pending)    // 云控统一读点（表驱动：CloudSwitchTable）
        collectAdSlotPruneHooks(pending)     // 美豆服务端槽位源头裁剪（表驱动：AdSlotFilter）
        collectBottomTabPruneHooks(pending)  // 底部 tab 源头裁剪（表驱动：BottomTabFilter）
        collectVipHooks(pending)            // F1 B1/B2 + 统一漏斗（VipModel.changeToVipState）
        collectRetentionDialogHooks(pending) // F1 挽留弹窗双保险
        collectTrialHooks(pending)          // F3 C3/C5/C6 + F4 C4 + 保守归 F2 C1/C2
        collectApplyStateHooks(pending)     // F4 C7 + F3 C9

        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    // ---------------- F2：全部资源免费（归组 F2） ----------------

    /** A1-A4：ItemInfo 付费/已购判定全短路为免费态（列表/详情全局生效——所有页面按钮逻辑都走它）。 */
    private fun collectFreeJudgmentHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_ITEM_INFO, "isNeedPayItem", 0, "boolean", false,
            "theme:ItemInfo.isNeedPayItem", "F2 A1 需付费判定 → 免费")
        addConst(pending, CLS_ITEM_INFO, "isNeedOrder", 0, "boolean", false,
            "theme:ItemInfo.isNeedOrder", "F2 A2 需下单判定 → 不弹下单")
        addConst(pending, CLS_ITEM_INFO, "getPayed", 0, "boolean", true,
            "theme:ItemInfo.getPayed", "F2 A3 已购读点1 → 已购")
        addConst(pending, CLS_ITEM_INFO, "isPayed", 0, "boolean", true,
            "theme:ItemInfo.isPayed", "F2 A3 已购读点2 → 已购")
        // A4 VIP 专属标记：挂 **getVipOnly()** 而不是 `isVipResouce()`（2026-10-10 审计修正）——
        // ① `isVipResouce()` 实现就是 `getVipOnly() == 1`（ItemInfo.java:781）⇒ 改挂后它自然为 false，覆盖不缩水；
        // ② `getVipOnly()` 另有**直连消费点**（BrowseDataHelper:60/98/129/229/266 遍历
        //    ThemeInfo/WallPaperInfo/FontInfo/MagicBabyInfo，四者均 extends ItemInfo 且未覆盖该方法）
        //    ⇒ 只挂 isVipResouce 会漏掉这条链。详见 docs/hook-inventory-audit.md §三.3。
        addConst(pending, CLS_ITEM_INFO, "getVipOnly", 0, "int", 0,
            "theme:ItemInfo.getVipOnly", "F2 A4 VIP 专属标记源头 → 0（同时覆盖 isVipResouce() 与直连 getVipOnly() 消费点）")
    }

    /** A5：price/actualPrice（double 字段）libxposed 102 无法 hook 字段，读点（getter 归零）+ 写点（setter 写 0）组合覆盖。 */
    private fun collectPriceZeroHooks(pending: MutableList<MountTask>) {
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
    private fun collectBeanSourceZeroHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_ITEM_BEAN, "getPrice", 0, "java.lang.String", "0",
            "theme:ItemBean.getPrice", "F2 A11 服务端 bean 价格源头 → \"0\"")
        addConst(pending, CLS_ITEM_BEAN, "getActualPrice", 0, "java.lang.String", "0",
            "theme:ItemBean.getActualPrice", "F2 A11 服务端 bean 现价源头 → \"0\"")
        addConst(pending, CLS_ITEM_BEAN, "getVipOnly", 0, "int", 0,
            "theme:ItemBean.getVipOnly", "F2 A11 服务端 bean VIP 专属标记 → 0")
    }

    /** A6-A10（5 个 is*BtnShow 门）与按钮显隐汇总点均已**删除**：不再逐按钮硬掰门/藏按钮，
     *  按钮显隐交由"用户 VIP + 资源免费"的数据态驱动（ToolBarGroupLayout 原生 switch）。依据见 design.md §十。 */


    /**
     * T1：详情页按钮组类型 → **-1（无激励）**。
     *
     * 2026-10-09 真机回归修正（原取值 0 是错的）：`OnlinePreviewBaseActivity.updateButtons` 的 switch 实证——
     * `case 0` 显示 `freeReceiveSmallView`、`case 1` 显示 `freeReceiveBigView`（二者即「免费领」= 美豆入口）；
     * 而 **`case 2/3/4/5` 在 `VipManager.isVipUser()==true` 时同样落到 `freeReceiveSmallView.setVisibility(0)`**
     * ——把用户强制成 VIP 后，普通中段值也全都会显示免费领。
     *
     * `case -1` → `hideLayout()`，而 `hideLayout` 只隐藏激励类视图（freeReceiveSmall/BigView、freeReceiveBtn、
     * vipReceiveSmall/BigView、timeLimitDownloadBtn，OnlinePreviewBaseActivity:1751 实证），
     * **不碰 mDownloadBtn/mApplyBtn/mBuyBtn** → 免费领（美豆）消失、下载/应用照常。
     * （旧注释"−1 会把按钮全隐藏"系误读，已更正；-1 另有 `getIncentiveData()!=null` 守卫，无数据时本就保持隐藏态。）
     *
     * 依据见 design.md §三 T1。
     */
    private fun collectRealChainHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_INCENTIVE_DATA, "getThemeDetailButtonType", 0, "int", -1,
            "theme:IncentiveData.getThemeDetailButtonType", "F2 T1 服务端按钮类型 → -1（无激励：hideLayout 只关「免费领/美豆」激励视图，保留下载/应用）")
        // 用户明确要求：详情页不显示「购买」按钮。changeVipBtnsVisibility 第 1 参即 buy
        // （OnlinePreviewBaseActivity.java:2837 定义，:2920 用它设 mBuyBtn 可见性；:3834 亦为 mBuyBtn 显隐点）
        addForceArg(pending, CLS_PREVIEW, "changeVipBtnsVisibility", 10, "void", false,
            "btn-hide:buy", "F2 详情页「购买」按钮隐藏（changeVipBtnsVisibility buy 参数强制 false）",
            forceArgIndex = 0)
    }

    /** H1/H2b：空 URL 付费项 → **VIP 下载通道**（修"下载永远 0%"）。依据见 design.md §三 H1/H2b + §十。 */
    private fun collectDownloadChainHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_DOWNLOAD_INFO,
            methodName = "isPayedItem",
            paramCount = 0,
            returnType = "boolean",
            value = true,
            id = "theme:DownloadInfo.isPayedItem",
            description = "F2 H1 空 URL 付费项判定 → 付费（进付费策略分派分支，再由 H2b 路由到 VIP 通道；uri 字段 DownloadInfo.java:145 实证）",
            uriEmptyRoute = true,
        )
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_DOWNLOAD_MODEL,
            methodName = "getDownloadType",
            paramCount = 0,
            returnType = "int",
            value = 3,
            id = "theme:DownloadModel.getDownloadType",
            description = "F2 H2b 空 URL 项 downloadType 分派读出 → 3（VIP 通道；o52.java:616 分派点，兜底详情页 builder 版链路）",
            uriEmptyRoute = true,
            uriOwner = UriOwner.GET_DOWNLOAD_INFO,
            // 混淆名兜底：`common.a`（DownloadModel）类内**无字符串**、方法**无参数**，
            // 唯一稳定锚点是**类字段类型** —— 它同时持有 `WeakReference`（context）与 `common.DownloadInfo`
            // 两种字段（common/a.java:11-12 实证），而字段类型引用的是类名（DownloadInfo 未被混淆）。
            dex = DexHint(
                classFieldTypes = listOf(
                    "java.lang.ref.WeakReference",
                    "$PKG.common.DownloadInfo",
                ),
            ),
        )
        pending += MountTask(
            kind = MountKind.PRE_PROCESS,
            className = CLS_WALLPAPER_PREVIEW_LOW,
            methodName = "applyLiveWallpaperPrepare",
            paramCount = 0,
            returnType = "void",
            value = null,
            id = "theme:WallpaperPreview.fixPackagePath",
            description = "F2 W1 动态壁纸应用前修正 packagePath（付费撕裂：A1 把 isNeedPayItem 强制 false → updateStatus():2660 重算路径漏掉 PAYED_HWTHEMES 子目录 → isFileExists(false) 静默 return）",
            // 类未混淆（keep 名单），方法名 jadx 可读；仍配方法体独有日志串锚点抗未来混淆
            dex = DexHint(strings = listOf("applyLiveWallpaper start")),
        )
    }

    // ---------------- 云控开关：统一读点 + 表驱动强制（数据见 CloudSwitchTable） ----------------

    /**
     * 云控布尔开关统一拦截：只挂**读点**，具体强制哪些 key 全部由 [CloudSwitchTable] 声明。
     *
     * 为什么能统一：主题商店全部云控布尔开关都由 `SharepreferenceUtils`（混淆名 `u15`）从 SP
     * `themename` 读出——`ams/mgr/AgreeRepo` 约 30 个 `is*()` 开关方法、`ads/AdStrategy` 的广告总闸
     * 等 83 处读点全走它。故在此挂一次，即等价于逐个方法级 hook，且**新版本新增读点自动覆盖**。
     *
     * 两个重载都要挂：`getBoolean(String, boolean)` 覆盖绝大多数（含带默认值的 AgreeRepo 全量）；
     * `getBoolean(String)` 覆盖固定默认 false 的读点（如 `local_has_signed`，AgreeRepo:243）。
     *
     * 机制：命中表内 key → 直接短路返回强制值（不调原方法）；未命中 → 放行原逻辑（零影响）。
     */
    private fun collectCloudSwitchHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.KEY_OVERRIDE,
            className = CLS_SHAREPREF_UTILS,
            methodName = "getBoolean",
            paramCount = 2,
            returnType = "boolean",
            value = null,
            id = "cloud:ShareprefUtils.getBoolean2",
            description = "云控统一读点 getBoolean(String key, boolean def) — 表驱动强制（CloudSwitchTable ${CloudSwitchTable.ENTRIES.size} 项）",
            // 混淆名兜底：SP 文件名 "themename" 是方法体内的字符串常量（混淆不改）+ 参数类型序列
            dex = DexHint(
                strings = listOf("themename"),
                paramTypes = listOf("java.lang.String", "boolean"),
            ),
        )
        pending += MountTask(
            kind = MountKind.KEY_OVERRIDE,
            className = CLS_SHAREPREF_UTILS,
            methodName = "getBoolean",
            paramCount = 1,
            returnType = "boolean",
            value = null,
            id = "cloud:ShareprefUtils.getBoolean1",
            description = "云控统一读点 getBoolean(String key) — 表驱动强制（覆盖 local_has_signed 等单参读点）",
            // 与双参重载同锚点，靠 paramTypes 区分（单参版内部直接调 SharedPreferences，不调双参版，两个都得挂）
            dex = DexHint(
                strings = listOf("themename"),
                paramTypes = listOf("java.lang.String"),
            ),
        )
    }

    // ---------------- F2：美豆（金币/福利）服务端槽位源头裁剪（挂载点 S1） ----------------

    /**
     * **S1 服务端槽位源头裁剪**：钩 `hitop.mate.SlotData#getList()`（服务端响应反序列化后的**域模型出口**），
     * 在把载荷交给任何 Model / Provider 之前，按 [AdSlotFilter] 的表剔掉美豆营销槽位：
     *
     * - 整体丢弃 [AdSlotFilter.PRUNED_SLOT_TYPES] 命中的 `SlotList`（腰封 5 / 搜索框左右 20·22 / 福利中心 25·27）；
     * - 仅从 `SlotList.list` 内删掉 [AdSlotFilter.PRUNED_TARGET_IDS] 命中的 `Slot`（灯笼位里内嵌的「免费领」，
     *   保留灯笼位其余图标）。
     *
     * 为什么选这个点（而不是逐个消费方去拦）：数据链是
     * `POST themeServer/themeBiz/slot|searchSlot` → Retrofit/Gson → `SlotData` → 各 Model/Provider → UI。
     * 请求体只有 `appPage`，**没有能关掉单个槽位的参数**，云控布尔表里也没有对应 key ⇒ 服务端载荷进入域模型
     * 之前的这一个出口，才是能一处覆盖全部入口（我的页腰封 / 推荐页免费领 / 福利中心 / 搜索框两侧）的位置。
     * 载荷实测与判据来源见 [AdSlotFilter] 类注释（调试期真机探针打印的原始 `SlotData.getList()`；探针已按冗余审计删除）。
     */
    private fun collectAdSlotPruneHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.SLOT_PRUNE,
            className = CLS_SLOT_DATA_MATE,
            methodName = "getList",
            paramCount = 0,
            returnType = "java.util.List",
            value = null,
            id = "adsource:SlotData.getList",
            description = "美豆 S1 槽位源头裁剪（SlotData.getList 出口按 AdSlotFilter 表剔营销槽位：" +
                "类型 ${AdSlotFilter.PRUNED_SLOT_TYPES.joinToString("/") { it.value }} + targetId " +
                "${AdSlotFilter.PRUNED_TARGET_IDS.joinToString("/") { it.value }}）",
        )
    }

    // ---------------- S2：底部导航 tab 源头裁剪（挂载点 S2） ----------------

    /**
     * **S2 底部 tab 源头裁剪**：钩 `hitop.entity.BottomTabConfigs#getBottomTabConfigs()`
     * （AMS 在线配置 JSON 反序列化后的**域模型出口**，全工程唯一调用点 `SystemParamSource.java:208`），
     * 在把 tab 清单交给 Model 之前，按 [BottomTabFilter] 的表剔掉目标 tab（VIP / 福利）。
     *
     * 为什么选这个点：底部 tab 的完整数据链是
     * `AMS systemParam（在线配置 JSON）→ Gson → `BottomTabConfigs` → `countBottomTabConfig()` →
     * `HwThemeManagerModel.getBottomTabConfig()`（StateFlow）→ `createTabs()` 建 tab + `doSupportOnline()` 建 Fragment`。
     * 列表出口只有这一个，裁掉即**tab 与页面同步消失**。
     *
     * 为什么不关总闸 `AgreeRepo.isSupportVip()`（key `vip_support`）：该闸另有 20+ 处消费点
     * （详情页按钮组、字体 VIP 区、推荐流副文案…），强改会外溢到与"删 tab"无关的 UI。
     * 详见 [BottomTabFilter] 类注释。
     */
    private fun collectBottomTabPruneHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.BOTTOM_TAB_PRUNE,
            className = CLS_BOTTOM_TAB_CONFIGS,
            methodName = "getBottomTabConfigs",
            paramCount = 0,
            returnType = "java.util.List",
            value = null,
            id = "adsource:BottomTabConfigs.getBottomTabConfigs",
            description = "底部 tab 源头裁剪（BottomTabConfigs.getBottomTabConfigs 出口按 BottomTabFilter 表剔除 " +
                BottomTabFilter.PRUNED_TABS.joinToString("/") { "${it.name}(${it.id})" } + "）",
        )
    }

    // ---------------- F1：开启 VIP（归组 F1） ----------------

    /**
     * F1 VIP：VipManager 读/写点 + VipState 散读直改（原「VipModel 双漏斗」已删，依据见 audit §五）——
     * `VipState` 字段**全部 private** ⇒ 类外读取**只能**经过 VipState 的 7 个 getter，而 getter 已被
     * 短路为会员常量 ⇒ getter 短路即全覆盖，无需任何实例字段改写层。
     */
    private fun collectVipHooks(pending: MutableList<MountTask>) {
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
        // 已删的历史挂载点（fetchShouldDialogBy* 休眠链 / T8 getVipCardType / VipManager 读点漏斗 /
        // VipModel 双漏斗）依据全部归档于 docs/hook-inventory-audit.md §五。
        // **F1 现状：VipManager 2 点 + VipState 7 个 getter，共 9 点。**
    }

    // ---------------- F1：挽留弹窗拦截（VIP 已过期挽留弹窗，双保险；归组 F1） ----------------

    /** F1/F2 双保险拦截 VIP 挽留弹窗（展示咽喉 showDialog + ARouter 直启兜底）。依据见 design.md §三 F1 挽留弹窗。 */
    private fun collectRetentionDialogHooks(pending: MutableList<MountTask>) {
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

    // ---------------- F3：试用无倒计时 + F4：回退路径判定放行 ----------------

    /** 功能位归组：C1/C2 为 F3/F4 双用途、归组不确定 → 保守归 F2；C5/C6 → F3；C4 → F4。 */
    private fun collectTrialHooks(pending: MutableList<MountTask>) {
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

    // ---------------- 挂载执行：本目标私有任务定义（重试/汇总/解析在共享 [MountEngine]） ----------------

    /**
     * 挂载任务类型：[CONST] 常量短路；[FORCE_ARG] 第 forceArgIndex 参强制；
     * [KEY_OVERRIDE] 云控读点按 key 查表强制（数据在 [CloudSwitchTable]）；
     * [SLOT_PRUNE] 服务端槽位载荷出口裁剪（数据在 [top.honorhook.xposed.AdSlotFilter]）；
     * [BOTTOM_TAB_PRUNE] 服务端底部 tab 配置出口裁剪（数据在 [top.honorhook.xposed.BottomTabFilter]）；
     * [BLOCK] void 空实现阻断（返回 null = 不调原方法，libxposed 语义，AI-lsposed-temlate SKILL.md:102 实证）；
     * [FINISH_ACTIVITY] 先 proceed 再 finish（不 proceed 生命周期会在未初始化状态走完，可能 NPE）。
     */
    private enum class MountKind { CONST, FORCE_ARG, KEY_OVERRIDE, SLOT_PRUNE, BOTTOM_TAB_PRUNE, BLOCK, FINISH_ACTIVITY, PRE_PROCESS }

    /** DownloadInfo 实例获取路径（uriEmptyRoute 条件路由）：[THIS] H1；[GET_DOWNLOAD_INFO] H2b 反射 getDownloadInfo()。 */
    private enum class UriOwner { THIS, GET_DOWNLOAD_INFO }

    private class MountTask(
        val kind: MountKind,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        val value: Any?,
        override val id: String,
        override val description: String,
        override val resolveValue: ((ClassLoader) -> Any?)? = null,
        /** 条件路由（下载链 H1/H2b）：仅 uri 为空（null/空串）时短路/改参，非空放行（不误伤正常免费/已购项）。 */
        val uriEmptyRoute: Boolean = false,
        /** DownloadInfo 实例获取路径（见 [UriOwner]）。 */
        val uriOwner: UriOwner = UriOwner.THIS,
        /** [MountKind.FORCE_ARG] 强制参数下标（0 起；默认 0 = 既有 setter 首参语义不变）。 */
        val forceArgIndex: Int = 0,
        /** DexKit 兜底特征（混淆名目标用；null = 仅直连轨；四维强度与纪律见 [DexHint]）。 */
        override val dex: DexHint? = null,
    ) : MountEngine.Task

    private fun addConst(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        value: Any?,
        id: String,
        description: String,
        /** DexKit 兜底特征（混淆名目标用；null = 仅直连轨）。 */
        dex: DexHint? = null,
    ) {
        pending += MountTask(
            kind = MountKind.CONST, className = className, methodName = methodName, paramCount = paramCount,
            returnType = returnType, value = value, id = id, description = description,
            dex = dex,
        )
    }

    private fun addForceArg(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        forcedArg: Any?,
        id: String,
        description: String,
        /** 强制哪个参数（0 起）。默认 0 = 既有 setter 首参语义不变。 */
        forceArgIndex: Int = 0,
        /** DexKit 兜底特征（混淆名目标用；null = 仅直连轨）。 */
        dex: DexHint? = null,
    ) {
        pending += MountTask(
            kind = MountKind.FORCE_ARG, className = className, methodName = methodName, paramCount = paramCount,
            returnType = returnType, value = forcedArg, id = id, description = description,
            forceArgIndex = forceArgIndex, dex = dex,
        )
    }

    /** 单个挂载任务执行（解析/重试/汇总在共享 [engine]；本函数只做 kind 分派的 intercept 构造）。 */
    private fun mountOne(
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountEngine.Result {
        val method = engine.resolveTask(task, module, loader)
            ?: return MountEngine.Result.NOT_RESOLVED
        // resolveValue 幂等（enumConstants 纯查找，无副作用），此处二次调用仅为取值；null 情况已在 engine 内拦截
        val value = task.resolveValue?.invoke(loader) ?: task.value
        return runCatching {
            val builder = module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(task.id)
            when (task.kind) {
                // 常量短路（每次调用现读远程 prefs——不捕获 install 期，防 null 冻结坑）
                MountKind.CONST -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) value else chain.proceed()
                }
                // 参数强制（setter 写点 / 下载类型路由点）
                MountKind.FORCE_ARG -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        val args = chain.args?.toTypedArray() ?: arrayOfNulls<Any?>(task.forceArgIndex + 1)
                        args[task.forceArgIndex] = value
                        chain.proceedWith(chain.thisObject, args)
                    } else {
                        chain.proceed()
                    }
                }
                // 云控读点：按第 1 参 key 查表，命中即短路返回强制值；未命中放行原逻辑（对非表内 key 零影响）
                MountKind.KEY_OVERRIDE -> builder.intercept { chain ->
                    val key = runCatching { chain.getArg(0) as? String }.getOrNull()
                    val forced = CloudSwitchTable.forced(key)
                    if (key != null && forced != null) {
                        logCloudOverride(module, key, forced)
                        forced
                    } else {
                        chain.proceed()
                    }
                }
                // 服务端槽位载荷出口裁剪（美豆 S1）：proceed 拿到 SlotData.list 后按表剔营销槽位，失败放行原值
                MountKind.SLOT_PRUNE -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        val orig = chain.proceed()
                        pruneSlotPayload(orig, module) ?: orig
                    } else {
                        chain.proceed()
                    }
                }
                // 服务端底部 tab 配置出口裁剪（S2）：proceed 拿到 tab 列表后按表剔 VIP/福利 tab，失败放行原值
                MountKind.BOTTOM_TAB_PRUNE -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        val orig = chain.proceed()
                        pruneBottomTabPayload(orig, module) ?: orig
                    } else {
                        chain.proceed()
                    }
                }
                // void 空实现阻断（挽留弹窗 F1；判据日志不受 DEBUG_LOGGING 门控）
                MountKind.BLOCK -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
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
                    if (routeActive(chain, module, task)) {
                        val result = chain.proceed()
                        (chain.thisObject as? Activity)?.finish()
                        module.log(Log.INFO, TAG, "挽留弹窗已拦截: ${task.methodName}")
                        result
                    } else {
                        chain.proceed()
                    }
                }
                // 前置数据修正：proceed 前修正 this 对象的字段（壁纸 packagePath 路径撕裂修复 W1），再原样放行
                MountKind.PRE_PROCESS -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        fixWallpaperPackagePath(chain, module)
                    }
                    chain.proceed()
                }
            }
        }.fold(
            onSuccess = {
                if (MountEngine.DEBUG_LOGGING) {
                    val verb = when (task.kind) {
                        MountKind.CONST -> "常返"
                        MountKind.FORCE_ARG -> "参数强制"
                        MountKind.KEY_OVERRIDE -> "云控表强制"
                        MountKind.SLOT_PRUNE -> "槽位载荷裁剪"
                        MountKind.BOTTOM_TAB_PRUNE -> "底部 tab 裁剪"
                        MountKind.BLOCK -> "空实现阻断"
                        MountKind.FINISH_ACTIVITY -> "放行后 finish"
                        MountKind.PRE_PROCESS -> "前置修正"
                    }
                    module.log(Log.INFO, TAG, "hook 已挂载: ${task.description} → $verb ${value ?: "null"}")
                }
                MountEngine.Result.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountEngine.Result.FAILED
            },
        )
    }

    // ---------------- SLOT_PRUNE 实现（美豆 S1：服务端槽位载荷源头裁剪） ----------------

    /** 反射方法缓存（`类名#方法名` → Method），避免在 getList 这类的取数路径上反复查找。 */
    private val reflMethodCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Method>>()

    private fun cachedMethod(o: Any, name: String): Method? = reflMethodCache.computeIfAbsent(
        "${o.javaClass.name}#$name",
    ) { key ->
        java.util.Optional.ofNullable(
            runCatching { o.javaClass.getMethod(key.substringAfterLast('#')) }.getOrNull(),
        )
    }.orElse(null)

    private fun reflectGet(o: Any, name: String): Any? =
        runCatching { cachedMethod(o, name)?.invoke(o) }.getOrNull()

    /**
     * `SlotData.getList()` 出口裁剪：按 [AdSlotFilter] 表剔掉美豆营销槽位。
     *
     * 返回 `null` 表示**未裁剪/不可裁剪**（调用方放行原值，PROTECTIVE 纪律）：
     * 非 List、空表、反射失败（版本漂移）一律原样返回。
     * 裁剪分两级：① 命中 [AdSlotFilter.isPrunedSlotType] 的整个 `SlotList` 丢弃；
     * ② 其余 `SlotList` 只从内层 `list` 里删掉命中 [AdSlotFilter.isPrunedTargetId] 的 `Slot`（写回 `setList`）。
     */
    private fun pruneSlotPayload(orig: Any?, module: XposedModule): List<*>? {
        val list = orig as? List<*> ?: return null
        if (list.isEmpty()) return null
        var changed = false
        val out = ArrayList<Any>(list.size)
        for (slotList in list) {
            if (slotList == null) {
                changed = true
                continue
            }
            val slotType = reflectGet(slotList, "getSlotType") as? String
            if (AdSlotFilter.isPrunedSlotType(slotType)) {
                changed = true
                logSlotPrune(module, "slotList", slotType!!, reflectGet(slotList, "getTitle")?.toString())
                continue
            }
            // 混合槽位（如灯笼位）：只摘掉内嵌的美豆入口 Slot，保留其余图标
            val inner = reflectGet(slotList, "getList") as? List<*>
            if (inner != null && inner.isNotEmpty()) {
                val kept = ArrayList<Any>(inner.size)
                var innerChanged = false
                for (slot in inner) {
                    val targetId = if (slot == null) null else reflectGet(slot, "getTargetId") as? String
                    if (slot != null && AdSlotFilter.isPrunedTargetId(targetId)) {
                        innerChanged = true
                        logSlotPrune(module, "slot", targetId!!, reflectGet(slot, "getTitle")?.toString())
                    } else if (slot != null) {
                        kept.add(slot)
                    }
                }
                if (innerChanged) {
                    changed = true
                    runCatching {
                        slotList.javaClass.getMethod("setList", java.util.List::class.java).invoke(slotList, kept)
                    }.onFailure { t ->
                        module.log(Log.WARN, TAG, "美豆槽位裁剪：内层 setList 失败（放行原对象）— ${t.message}")
                    }
                }
            }
            out.add(slotList)
        }
        return if (changed) out else null
    }

    /** 裁剪判据日志（节流，每 key 前 2 次；真机核对"美豆入口已从载荷剔除"用）。 */
    private val slotPruneLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun logSlotPrune(module: XposedModule, kind: String, value: String, title: String?) {
        val key = "$kind:$value"
        val n = slotPruneLogCount.merge(key, 1) { a, b -> a + b } ?: 1
        if (n <= 2) {
            module.log(Log.INFO, TAG, "美豆槽位已从服务端载荷剔除: $kind=$value title=$title（AdSlotFilter）")
        }
    }

    // ---------------- BOTTOM_TAB_PRUNE 实现（S2：服务端底部 tab 配置源头裁剪） ----------------

    /**
     * `BottomTabConfigs.getBottomTabConfigs()` 出口裁剪：按 [BottomTabFilter] 表剔掉 VIP / 福利 tab。
     *
     * 返回 `null` 表示**未裁剪/不可裁剪**（调用方放行原值，PROTECTIVE 纪律）：非 List、空表、
     * 反射取不到 `getBottomTabId()`（版本漂移）一律原样保留。
     * `getBottomTabId()` 返回 int（`MenuType.getValue()`），故按 Number 取值比较。
     *
     * **保护**：裁剪后若不足 [BottomTabFilter.MIN_LIST_SIZE] 项则**整体放行**——宿主在 `size < 3` 时会走
     * 静态硬编码回退分支，而那支的 VIP tab 由 `isSupportVip` 控制、会重新出现（详见 BottomTabFilter 注释）。
     */
    private fun pruneBottomTabPayload(orig: Any?, module: XposedModule): List<*>? {
        val list = orig as? List<*> ?: return null
        if (list.isEmpty()) return null
        var changed = false
        val out = ArrayList<Any>(list.size)
        val pruned = ArrayList<BottomTabFilter.Rule>()
        for (item in list) {
            if (item == null) {
                changed = true
                continue
            }
            val id = (reflectGet(item, "getBottomTabId") as? Number)?.toInt()
            val rule = if (id == null) null else BottomTabFilter.ruleFor(id)
            if (rule != null) {
                changed = true
                pruned += rule
                continue
            }
            out.add(item)
        }
        if (!changed) return null
        if (out.size < BottomTabFilter.MIN_LIST_SIZE) {
            module.log(
                Log.WARN, TAG,
                "底部 tab 裁剪已放弃：裁后仅 ${out.size} 项（< ${BottomTabFilter.MIN_LIST_SIZE} " +
                    "会触发宿主静态回退分支、VIP tab 反而复活），放行原配置",
            )
            return null
        }
        // 确认真正裁剪后才打判据日志（避免出现"记了日志却没裁"的误导）
        pruned.forEach { logBottomTabPrune(module, it) }
        return out
    }

    /** 裁剪判据日志（每 tab 前 2 次；真机核对"VIP tab 已从服务端配置剔除"用）。 */
    private val bottomTabPruneLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun logBottomTabPrune(module: XposedModule, rule: BottomTabFilter.Rule) {
        val n = bottomTabPruneLogCount.merge(rule.name, 1) { a, b -> a + b } ?: 1
        if (n <= 2) {
            module.log(Log.INFO, TAG, "底部 tab 已从服务端配置剔除: ${rule.name}(bottomTabId=${rule.id})（BottomTabFilter）")
        }
    }

    // ---------------- 通用挂载工具 ----------------

    /**
     * 路由激活判定：uriEmptyRoute=false 时恒激活；=true 时额外要求 DownloadInfo.uri 为空才激活
     * （非空放行；uri 读取失败按版本漂移放行，安全默认）。触发时输出 INFO 判据日志（不受 DEBUG_LOGGING 门控）。
     */
    private fun routeActive(
        chain: XposedInterface.Chain,
        module: XposedModule,
        task: MountTask,
    ): Boolean {
        if (!task.uriEmptyRoute) return true
        val blank = isUriBlank(chain, task)
        if (blank) {
            val msg = "theme-download-chain 路由触发: ${task.id}（DownloadInfo.uri 为空 → ${actionDesc(task)}）"
            module.log(
                Log.INFO, TAG,
                msg,
            )
            // 路由触发 = 下载修复链路生效的判据，双写进环形缓冲供 UI 日志页核对
        }
        return blank
    }

    /** 云控强制命中计数（key → 已打印次数）。读点高频（多处循环内读取），每 key 只打前 [CLOUD_LOG_PER_KEY] 次防刷屏。 */
    private val cloudHitLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private const val CLOUD_LOG_PER_KEY = 2

    /** 云控强制命中判据日志（节流；真机确认表驱动拦截生效用）。 */
    private fun logCloudOverride(module: XposedModule, key: String, value: Boolean) {
        val n = cloudHitLogCount.merge(key, 1) { a, b -> a + b } ?: 1
        if (n <= CLOUD_LOG_PER_KEY) {
            module.log(Log.INFO, TAG, "cloud-switch 强制: $key → $value")
        }
    }

    private fun actionDesc(task: MountTask): String = when (task.kind) {
        MountKind.CONST -> "常返 ${task.value}"
        MountKind.FORCE_ARG -> "第 ${task.forceArgIndex + 1} 参强制 ${task.value}"
        MountKind.KEY_OVERRIDE -> "云控 key 命中即短路（表见 CloudSwitchTable）"
        MountKind.SLOT_PRUNE -> "SlotData.list 按 AdSlotFilter 表裁剪"
        MountKind.BOTTOM_TAB_PRUNE -> "BottomTabConfigs.list 按 BottomTabFilter 表裁剪"
        MountKind.BLOCK -> "空实现阻断（不调原方法）"
        MountKind.FINISH_ACTIVITY -> "放行原方法后 finish Activity"
        MountKind.PRE_PROCESS -> "前置修正 this 数据后放行原方法"
    }

    /**
     * 条件路由的 uri 判空：按 [UriOwner] 取 DownloadInfo 实例，反射读 public String uri。四分支：
     * ① 实例 null → 视为空；② 字段 null/空串/"null" 占位/无 "://" → 空（触发路由，真免费 CDN 直链必含
     * "://" 不受影响）；③ 反射异常（版本漂移）→ 放行原逻辑。
     */
    private fun isUriBlank(chain: XposedInterface.Chain, task: MountTask): Boolean {
        val info: Any? = when (task.uriOwner) {
            UriOwner.THIS -> chain.thisObject
            UriOwner.GET_DOWNLOAD_INFO -> runCatching {
                chain.thisObject?.javaClass?.getMethod("getDownloadInfo")?.invoke(chain.thisObject)
            }.getOrElse { return false }  // ③ 探测异常（版本漂移）→ 放行（安全默认）
        }
        val owner = info ?: return true  // ① 实例为 null：视为空（原入口本就提前 return，路由无害）
        val uri = runCatching { owner.javaClass.getField("uri").get(owner) }
            .getOrElse { return false }  // ③ 反射异常（NoSuchField/版本漂移）→ 放行（安全默认）
        val s = uri as? String ?: return false  // 非 String 字段（版本漂移）→ 放行
        return s.isNullOrEmpty() || s == "null" || !s.contains("://")  // ② 空/占位/非 URL → 触发路由
    }

    /**
     * W1：动态壁纸 `packagePath` 路径撕裂修正（[MountKind.PRE_PROCESS]，apply 前置）。
     *
     * **根因**：付费动态壁纸的包文件落盘在 `HWTHEMES/PAYED_HWTHEMES/`（下载侧判定 `DownloadInfo.isPayedItem()`
     * 是 `price` **字段直读**，不受 hook 影响 → payed 目录）；而预览页 `updateStatus()`（`OnlineWallpaperPreview
     * LowActivity.java:2660`）用 `mInfo.isNeedPayItem()` 重算 `packagePath` —— 该方法被本模块 A1 强制 false
     * ⇒ packagePath 指向**不带 PAYED_HWTHEMES 的错误目录**。点「应用」→ `applyLiveWallpaperPrepare()` 里
     * `isFileExists(packagePath)` 为 false → 上报 402 后**静默 return**（构造的 Message 从未发送，零 UI 反馈）。
     *
     * **修法**：proceed 前把 `mInfo.packagePath` 修正为真实存在的文件 —— 复用宿主自己的
     * `WallPaperInfo.getAbsolutExistFilePath()`（它按 `this.price` **字段直读**判 payed，inner/outer ×
     * PAYED_HWTHEMES/正常目录 四处探测，天然不受 hook 影响）。修正失败（null/反射异常）一律不动原数据（PROTECTIVE）。
     * `doLiveWallPaperApply` 后续读的是同一 `mInfo.packagePath`，一次修正全链路生效。
     */
    private fun fixWallpaperPackagePath(chain: XposedInterface.Chain, module: XposedModule) {
        runCatching {
            val activity = chain.thisObject ?: return
            val info = activity.javaClass.getDeclaredField("mInfo").apply { isAccessible = true }.get(activity)
                ?: return
            val pathField = info.javaClass.getField("packagePath")
            val cur = pathField.get(info) as? String
            if (!cur.isNullOrEmpty() && java.io.File(cur).exists()) return  // 路径本就正确（免费项常态）→ 零干预
            val real = info.javaClass.getMethod("getAbsolutExistFilePath").invoke(info) as? String
            if (!real.isNullOrEmpty() && java.io.File(real).exists() && real != cur) {
                pathField.set(info, real)
                module.log(Log.INFO, TAG, "壁纸 packagePath 已修正: $cur → $real")
            }
        }.onFailure { t ->
            module.log(Log.WARN, TAG, "壁纸 packagePath 修正失败（放行原逻辑）: ${t.message}")
        }
    }

    private const val TAG = "HonorHook"
}