package top.honorhook.xposed.data

/** 主题商店云控布尔开关「强制表」—— key 与 hook 逻辑分离的唯一数据源。 */
object CloudSwitchTable {

    /**
     * 单条强制项。
     * @param key   云控 key 字面量（`u15.getBoolean` 的第 1 参）
     * @param value 强制返回值（命中即短路，不调原方法）
     * @param note  作用与读点简述（仅档注，不参与运行时）
     */
    data class Entry(val key: String, val value: Boolean, val note: String)

    /** 强制 false —— 关停广告 / 福利 / 会员 / 运营 / 个性化入口与推荐流。 */
    private val FORCE_FALSE = listOf(
        Entry("ader_show_global_switch", false, "广告全局总闸（ads/AdStrategy.isSupportAdDisplay:634）"),
        Entry("free_claim_show_value", false, "「免费领」美豆广告入口（AgreeRepo.isOpenFreeClaimSwitch）"),
        Entry("member_card_support", false, "会员卡 / 福利中心入口（AgreeRepo.isMemeberCardSupport）"),
        Entry("MEMBER_SELECTION", false, "「会员精选」（AgreeRepo.isOpenMemberSelection）"),
        Entry("detail_ad_support_from_settings", false, "详情页广告·设置来源（AgreeRepo:203）"),
        Entry("detail_ad_support_official_res", false, "详情页广告·官方资源（AgreeRepo:207）"),
        Entry("hot_ranking_show_detail_config_value", false, "热榜详情（AgreeRepo:272）"),
        Entry("trial_record_entry_switch", false, "试用记录入口（AgreeRepo:298）"),
        Entry("AICREATION_SHOWORHIDE_CONFIG", false, "AI 创作入口（AgreeRepo:294）"),
        Entry("redpoint_support_online", false, "红点角标（AgreeRepo:288）"),
        Entry("magic_baby_support_online", false, "魔法宝宝（AgreeRepo.isMagicBabySupport:255）"),
        Entry("help_consumer_support_online", false, "帮助与客服入口（AgreeRepo:227）"),
    )

    /** 强制 true —— 开启 VIP 免广告 / 本地已登录标记 / 试用能力。 */
    private val FORCE_TRUE = listOf(
        Entry("vip_no_ad", true, "VIP 免广告（AgreeRepo.isVipNoAd:342）"),
        Entry("local_has_signed", true, "本地已登录标记（AccountChangeHelper.LOCAL_HAS_SIGNED；经**单参** getBoolean 读取）"),
        Entry("trial_support_online", true, "试用能力在线开关（原 C8 方法级 hook 迁移入表，值不变）"),
    )

    /** 全表（false 组 + true 组），顺序即展示顺序。 */
    val ENTRIES: List<Entry> = FORCE_FALSE + FORCE_TRUE

    /** key → 强制值（运行时查表用；O(1)）。 */
    private val MAP: Map<String, Boolean> = ENTRIES.associate { it.key to it.value }

    /**
     * 表驱动查询：命中返回强制值，未命中返回 null（调用方放行原逻辑）。
     * 位于云控读取热路径（每次开关读取各一次），实现为单次哈希查表。
     */
    fun forced(key: String?): Boolean? = if (key == null) null else MAP[key]
}
