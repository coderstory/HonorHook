package top.honorhook.ui.toolbox

import top.honorhook.ui.scope.SafeCommands

/**
 * 持久层开关清单（28 号 §4）：全部走 settings 三表（/data 持久，一次执行永久生效）。
 * **resetprop 会话层全数排除**（43 号真机实证：17 项 B 层 msc.* 全 no_effect——
 * `-n` 直写回读通过但读取面不认；排除决定详见 docs/research/verification/summary.md）。
 *
 * 表陷阱（28 号 §7）：settings put 写错表 exit 0 静默无效 → 执行器先 get 三表定位
 * （[Entry.table] 仅作缺省），写后双回读（root settings get + Settings API）。
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
        /** 同批联动键：UI 一键成组（营销 11 键 / 私有 DNS 成对） */
        val group: String? = null,
        /** HIGH 风险默认折叠进「更多」 */
        val folded: Boolean = false,
    )

    val ENTRIES: List<Entry> = listOf(
        // —— 显示（32 号）——
        Entry("sdr2hdr", "SDR 转 HDR", "普通内容提升到 HDR 亮度", SafeCommands.Table.SECURE, "hn_sdr2hdr_switch", "1", "0", Risk.LOW, "32 号键名实证"),
        Entry("sdrplus", "SDR+ 亮度增强", "普通内容进一步增强峰值亮度", SafeCommands.Table.SECURE, "hn_sdrplus_switch", "1", "0", Risk.LOW, "32 号"),
        Entry("tianshan", "天山显示增强", "画面效果增强（绿洲/天山同族显示键）", SafeCommands.Table.SECURE, "hn_tianshan_switch", "1", "0", Risk.LOW, "01+32 合并"),
        Entry("reduce_refresh", "降刷新率省电", "静止画面自动降刷新率省电；关闭后保持满刷新率", SafeCommands.Table.SECURE, "reduce_refresh_rate_enabled", "1", "0", Risk.LOW, "off=0（28 号 §4）"),
        Entry("low_power_dark", "省电时深色模式", "电量低时自动切换深色背景省电", SafeCommands.Table.SECURE, "enable_low_power_dark_mode", "1", "0", Risk.LOW, "32 号"),
        // —— AOD（47 号）——
        Entry("aod", "熄屏显示 AOD", "熄屏后在屏幕上留显时间与日期", SafeCommands.Table.SECURE, "aod_switch", "1", "0", Risk.LOW, "47 号"),
        Entry("aod_full", "全屏熄屏显示", "熄屏显示铺满整屏（默认只居中一小块）", SafeCommands.Table.SECURE, "enable_full_screen_AOD", "1", "0", Risk.MID, "47 号"),
        Entry("aod_duration", "锁屏 AOD 时长延长", "熄屏显示时长延长到 200 秒（单向，只延不缩）", SafeCommands.Table.SECURE, "lock_to_aod_duration", "200", null, Risk.LOW, "单向：只延不缩（恢复走系统设置）"),
        Entry("fp_slide", "指纹滑动通知", "在屏下指纹区域上下滑动翻看通知", SafeCommands.Table.SECURE, "fingerprint_slide_switch", "1", "0", Risk.LOW, "47 号"),
        Entry("fingersense", "指关节字母手势", "指关节在屏幕画字母触发对应快捷功能", SafeCommands.Table.SECURE, "fingersense_letters_enabled", "1", "0", Risk.LOW, "47 号"),
        // —— 音频/游戏 ——
        Entry("infinite_vol", "蓝牙无限音量", "蓝牙音量突破设备上限（大音量注意听力）", SafeCommands.Table.SECURE, "infinite_volume_music_bt_a2dp", "1", "0", Risk.LOW, "32 号"),
        Entry("ai_game_boost", "AI 游戏加速", "游戏场景由 AI 调度资源提速", SafeCommands.Table.SECURE, "ai_game_boost_state", "1", "0", Risk.MID, "45 号"),
        Entry("game_power_save", "游戏省电模式", "游戏时降性能省电；关闭即满性能运行", SafeCommands.Table.SECURE, "game_power_save_mode", "1", "0", Risk.MID, "off=0（28 号 §4）"),
        // —— 充电 ——
        Entry("charge_turbo", "超级快充加速", "亮屏使用中也保持满功率快充（发热略增）", SafeCommands.Table.SECURE, "charge_turbo_switch", "1", "0", Risk.MID, "04 号"),
        Entry("battery_limit", "自定义电量上限", "限制充电上限、延缓电池老化（效果依系统版本）", SafeCommands.Table.SECURE, "UserBatteryLimitState", "1", "0", Risk.MID, "04 号；电池健康相关，慎动"),
        // —— 网络 ——
        Entry("ims_datachannel", "IMS 数据通道", "显示 VoLTE 通话数据通道的调试项", SafeCommands.Table.SECURE, "show_ims_datachannel_switch", "1", "0", Risk.LOW, "32 号"),
        Entry("smart_network", "智能选网", "双卡按信号质量自动择优选网", SafeCommands.Table.SECURE, "smart_network_switch", "1", "0", Risk.LOW, "32 号"),
        Entry("network_speed", "状态栏网速显示", "状态栏实时显示网速（若与功能页网速 hook 同开，以 hook 为准）", SafeCommands.Table.SYSTEM, "show_network_speed_enabled", "1", "0", Risk.LOW, "32 号；已由 SystemUiHook U 系列部分覆盖时以 hook 为准"),
        Entry("network_mode_26", "首选网络模式=26", "锁定 NR 全家桶网络模式（写错值可能断网）", SafeCommands.Table.GLOBAL, "preferred_network_mode1", "26", null, Risk.HIGH, "写错值可断网；单向，恢复需记原值；默认收起", folded = true),
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
        // —— 私有 DNS 成对事务（07 号 hosts 块的持久层替代）——
        Entry("private_dns", "私有 DNS 去遥测", "AdGuard DNS；两键成对", SafeCommands.Table.GLOBAL, "private_dns_mode", "hostname", "off", Risk.MID, "off 时 specifier 置空成对回滚；07 号", group = "privateDns"),
        Entry("private_dns_spec", "私有 DNS 主机名", "dns.adguard-dns.com", SafeCommands.Table.GLOBAL, "private_dns_specifier", "dns.adguard-dns.com", "", Risk.MID, "与 private_dns_mode 成对执行", group = "privateDns"),
    )

    val ADS_GROUP: List<Entry> = ENTRIES.filter { it.group == "ads" }
    val PRIVATE_DNS_GROUP: List<Entry> = ENTRIES.filter { it.group == "privateDns" }
}
