package top.honorhook.xposed.data

/** 底部导航 tab 的「服务端配置」源头裁剪表 —— 数据与 hook 逻辑分离的唯一改动点。 */
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
