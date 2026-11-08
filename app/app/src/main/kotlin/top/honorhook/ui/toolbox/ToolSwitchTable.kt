package top.honorhook.ui.toolbox

import top.honorhook.ui.scope.SafeCommands

/**
 * 持久层开关清单（28 号 §4）：全部走 settings 三表（/data 持久，一次执行永久生效）。
 * **resetprop 会话层全数排除**（43 号真机实证：17 项 B 层 msc.* 全 no_effect——
 * `-n` 直写回读通过但读取面不认；排除决定详见 docs/research/verification/summary.md）。
 *
 * 表陷阱（28 号 §7）：settings put 写错表 exit 0 静默无效 → 执行器先 get 三表定位
 * （[Entry.table] 仅作缺省），写后双回读（root settings get + Settings API）。
 *
 * 2026-11-08 用户指令「删除工具箱系统开关模块，彻底删除」：原 18 条系统开关
 * （显示 / AOD / 音频游戏 / 充电 / 网络）已随之删除，本表只剩营销 11 键（ADS_GROUP）。
 * Entry 结构保留（执行器/确认流仍按表反查标题），勿再往里塞非营销条目——
 * 已经没有页面能展示它们了。
 */
object ToolSwitchTable {

    enum class Risk(val label: String) { LOW("低"), MID("中"), HIGH("高") }

    data class Entry(
        val id: String,
        val title: String,
        val summary: String,
        val table: SafeCommands.Table,
        val key: String,
        /** 写「开」的值；null = 只有关闭语义（一般不会出现） */
        val onValue: String,
        /** 写「关」的值；null = 单向开关（只开不关，UI 明示） */
        val offValue: String?,
        val risk: Risk,
        /** 代码读不到的外部约束：副作用 / 版本语义 / 来源报告 */
        val note: String,
        /** 云端可能回写（pushagent global 键等），执行后隔天复查（05 号红线） */
        val cloudRewrite: Boolean = false,
        /** 同批联动键：UI 一键成组（营销 11 键） */
        val group: String? = null,
        /** HIGH 风险默认折叠进「更多」 */
        val folded: Boolean = false,
    )

    val ENTRIES: List<Entry> = listOf(
        // —— 营销 11 键（05 号，cloudRewrite）——
        Entry("ads_intelligent_message", "智慧短信营销卡片", "关=银行卡码变普通短信", SafeCommands.Table.SECURE, "intelligent_message_enabled", "1", "0", Risk.LOW, "05 号；副作用见 summary", cloudRewrite = true, group = "ads"),
        Entry("ads_personal_push", "个性化推送", "", SafeCommands.Table.SECURE, "personalized_push_state", "1", "0", Risk.LOW, "05 号", cloudRewrite = true, group = "ads"),
        Entry("ads_personal_recommend", "个性化推荐", "", SafeCommands.Table.SECURE, "personalized_recommendation", "1", "0", Risk.LOW, "05 号", cloudRewrite = true, group = "ads"),
        Entry("ads_hiboard_recommend", "负一屏推荐流", "", SafeCommands.Table.SECURE, "hiboard.recommend.status", "1", "0", Risk.LOW, "05 号", cloudRewrite = true, group = "ads"),
        Entry("ads_state", "系统广告总开关", "", SafeCommands.Table.SECURE, "state_of_ads_switch", "1", "0", Risk.LOW, "05 号；双表同键（执行器会写全部命中的表）", cloudRewrite = true, group = "ads"),
        Entry("ads_location_honor", "荣耀广告定位", "", SafeCommands.Table.SECURE, "location_honor_ads", "1", "0", Risk.LOW, "05 号", cloudRewrite = true, group = "ads"),
        Entry("ads_ai_recommend", "AI 推荐", "", SafeCommands.Table.SECURE, "settings_ai_recommend", "1", "0", Risk.LOW, "05 号", cloudRewrite = true, group = "ads"),
        Entry("ads_intelligent_recommend", "智慧推荐", "", SafeCommands.Table.SECURE, "intelligent_recommendation_toggle", "1", "0", Risk.LOW, "05 号", cloudRewrite = true, group = "ads"),
        Entry("ads_search_letter", "桌面下拉搜索热词", "", SafeCommands.Table.SECURE, "search.recommend.letter.switch", "1", "0", Risk.LOW, "05 号", cloudRewrite = true, group = "ads"),
        Entry("ads_cloud_maintenance", "云维护提醒", "", SafeCommands.Table.SECURE, "cloud_maintenance", "1", "0", Risk.LOW, "05 号", cloudRewrite = true, group = "ads"),
        Entry("ads_location_report", "位置上报", "", SafeCommands.Table.SECURE, "location_report_switch", "1", "0", Risk.LOW, "05 号；与 state_of_ads_switch 双表同键", cloudRewrite = true, group = "ads"),
    )

    val ADS_GROUP: List<Entry> = ENTRIES.filter { it.group == "ads" }
}
