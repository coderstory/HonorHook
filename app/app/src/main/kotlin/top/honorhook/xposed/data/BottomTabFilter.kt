package top.honorhook.xposed.data

/**
 * 底部导航 tab 的「服务端配置」源头裁剪表 —— 数据与 hook 逻辑分离的唯一改动点。
 *
 * ## 数据从哪来（jadx 实证，主题商店 20.1.63.305）
 *
 * 底部 tab 不是本地写死的：`HwThemeManagerActivity.createTabs()` 的两条分支（静态硬编码 / 服务端配置）
 * 都以 `HwThemeManagerModel.getBottomTabConfig(): StateFlow<List<BottomTabItem>>` 为准，而这个列表的
 * **唯一产出点**是 `ams/source/SystemParamSource.countBottomTabConfig(...)` 内的协程体：
 * ```
 * for (BottomTabItem item : ((BottomTabConfigs) GsonUtils.fromJson(value, BottomTabConfigs.class))
 *                              .getBottomTabConfigs()) {
 *     if (item.getBottomTabId() != MenuType.VIP.getValue()) arrayList.add(item);
 *     else if (zIsSupportVip) arrayList.add(item);      // ← VIP 项只受 vip_support 云控控制
 * }
 * ```
 * 即：AMS 下发 JSON → Gson → `hitop.entity.BottomTabConfigs`，**读它的唯一出口 = `getBottomTabConfigs()`**
 * （全工程仅 `SystemParamSource.java:208` 一处调用）。
 *
 * ## 为什么裁这一点，而不是关掉 `AgreeRepo.isSupportVip()`
 *
 * `AgreeRepo.isSupportVip()`（= 云控 key `vip_support`，`common/i.java:158` 的 `THEME_SUPPORT_VIP`，
 * 默认值本就是 false，线上由服务端 systemParam 下发 true 打开）确实是 VIP tab 的总闸，但它全工程有
 * **20+ 处**独立消费点：详情页按钮组 `ToolBarGroupLayout:492` / `WallpaperDetailView:408`、字体预览
 * `setVipArea`、推荐流卡片副文案（`MateFlowAdapter:441` 等）……强改会连带改变详情页/列表的按钮与文案形态。
 * 本表只裁**底部 tab 列表**这一个出口，精准且零外溢。
 *
 * ## 裁剪后为什么不会错位
 *
 * - 底部 tab 由 `createTabs()` 遍历该列表生成，**Fragment 页面列表**由 `doSupportOnline()` 遍历**同一份**列表生成
 *   （`HwThemeManagerActivity.java:965/977`）⇒ tab 与页面同步少一项，不会出现"tab 少了页面还在"；
 * - 裁剪后列表仍满足 `3 ≤ size ≤ 5`（真机实测 4 项 → 裁后 3 项），不会触发 `size<3` 的静态硬编码回退分支
 *   （该分支同样受 `isSupportVip` 控制，此处不涉及）。
 */
object BottomTabFilter {

    /**
     * 要剔除的底部 tab 项。
     * @param id   `hitop.entity.MenuType.getValue()`。dex 实证取值：
     *             RECOMMEND=1 / CATEGORY=2 / **VIP=3** / RANKING=4 / MINE=5 / RANKING_CATEGORY=11 / **WELFARE=12**
     * @param name 语义名（判据日志用）
     * @param note 依据（仅档注）
     */
    data class Rule(val id: Int, val name: String, val note: String)

    val PRUNED_TABS: List<Rule> = listOf(
        Rule(
            3, "VIP",
            "MenuType.VIP —— VIP tab。点进去是 vip.ui.VipMemberCenterActivity（开通/购买页，底部主按钮固定为" +
                "「立即开通 %s」＝资源 R.string.vip_packages_activate，由 VipCenterFragment.showVipActivatePrice " +
                "按选中套餐价无条件设置，与用户是否 VIP 无关；续费入口等其它 VIP UI 不在此牵连）",
        ),
        Rule(
            12, "WELFARE",
            "MenuType.WELFARE —— 「免费拿」美豆福利 tab（createActionTab(R.string.get_it_free_bb)），" +
                "与 AdSlotFilter 同源的变现入口；真机当前未下发，按「宁删勿留」预防性纳入",
        ),
    )

    private val ID_MAP: Map<Int, Rule> = PRUNED_TABS.associateBy { it.id }

    /**
     * 裁剪后列表必须保留的最少项数。
     *
     * 宿主 `HwThemeManagerActivity.createTabs()` / `doSupportOnline()` 都以
     * `value == null || value.size < 3 || value.size > 5` 作为"**回退到静态硬编码分支**"的条件，
     * 而静态分支里的 VIP tab 由 `AgreeRepo.isSupportVip()` 控制（**不受本表影响、会重新出现**）。
     * 故裁剪后若不足 [MIN_LIST_SIZE] 项，**整体放弃裁剪**（宁可不裁，也不触发回退）。
     */
    const val MIN_LIST_SIZE = 3

    /** 该 tab 是否要剔除；未命中返回 null（调用方保留原项）。 */
    fun ruleFor(tabId: Int): Rule? = ID_MAP[tabId]
}
