package top.honorhook.xposed.data

/** 美豆（金币/福利/「耀宝」营销）**服务端槽位**的源头过滤表 —— 数据与 hook 逻辑分离的唯一改动点。 */
object AdSlotFilter {

    /**
     * 整体丢弃的槽位类型（线上 `SlotList.slotType` 字符串）。
     * @param value 线上字符串（探针/静态反查实证）
     * @param note  语义与依据（仅档注）
     */
    data class Rule(val value: String, val note: String)

    val PRUNED_SLOT_TYPES: List<Rule> = listOf(
        Rule("5", "腰封 TYPE_WAIST(内部6) —— 我的页「我的页面腰封-福利中心」横幅（探针实测 type=5）"),
        Rule("20", "搜索框右侧槽位 TYPE_SEARCH_LAYOUT —— 推荐页搜索框周边美豆入口"),
        Rule("22", "搜索框左侧槽位 TYPE_SEARCH_LEFT_LAYOUT —— 推荐页搜索框左侧「免费领」"),
        Rule("25", "福利中心 TYPE_WELFARE_CENTER"),
        Rule("27", "福利中心 H5 插件 TYPE_WELFARE_PLUGIN —— 探针实测 type=27 title=福利中心"),
    )

    /** 只删槽位内单个 Slot 的判据（`Slot.targetId`）。 */
    val PRUNED_TARGET_IDS: List<Rule> = listOf(
        Rule("1019001", "「免费领」/福利中心 H5 入口（灯笼位内嵌的美豆入口；与 RecommendFM.checkHasLanternWithWelfareCenter 同判据）"),
        Rule("1019002", "福利中心 H5 入口（type=27 福利中心槽位内）"),
    )

    private val SLOT_TYPE_SET: Set<String> = PRUNED_SLOT_TYPES.map { it.value }.toSet()
    private val TARGET_ID_SET: Set<String> = PRUNED_TARGET_IDS.map { it.value }.toSet()

    /** 该槽位类型是否整体丢弃（null/未命中 → 保留）。 */
    fun isPrunedSlotType(slotType: String?): Boolean = slotType != null && slotType in SLOT_TYPE_SET

    /** 该 Slot 是否命中删除判据（null/未命中 → 保留）。 */
    fun isPrunedTargetId(targetId: String?): Boolean = targetId != null && targetId in TARGET_ID_SET
}
