package top.honorhook.ui.toolbox

/**
 * 荣耀系统包冻结四档名单（docs/research/2026-10-24-system-audit/12-应用治理名单.md，
 * 源：ame-amelia/honor-debloat-list + rddmwmddr/android-ad-cleaner + 本机实测）。
 *
 * **安全默认**：只有 B/C 档显式收录的包可选冻结；A 档（红线）与未收录/D 档一律不可选——
 * 名单的假阴性只会让包落在「不可选」，不会让它进队列（A 档 enqueue 前硬拦截的等价实现）。
 */
object GovernanceList {

    enum class Tier(val label: String) {
        A("禁碰"), B("可选冻"), C("建议冻"), PRESET_SAFE("纯净精选");
    }

    data class Entry(val pkg: String, val tier: Tier, val cost: String)

    /** A 档红线：冻结即事故（卡开机/丢通知/相机全灭）。UI 折叠灰显硬禁选。 */
    val RED_LINES: Set<String> = setOf(
        "com.hihonor.android.launcher",
        "androidhnext",
        "com.hihonor.frameworkreshnext.overlay",
        "com.hihonor.systemserver",
        "com.hihonor.systemmanager",
        "com.hihonor.iaware",
        "com.hihonor.powergenie",
        "com.hihonor.android.pushagent",
        "com.hihonor.securityserver",
        "com.hihonor.coauthservice",
        "com.hihonor.medialibrary",
        "com.hihonor.android.internal.app",
        "com.hihonor.secime",
        "com.baidu.input_hihonor",
        "com.sohu.inputmethod.sogou.honor",
        "com.hihonor.mms",
        "com.hihonor.contacts",
        "com.os.callservice",
        "com.newcall",
        "org.codeaurora.ims",
        "com.hihonor.bluetooth",
        "com.hihonor.nfc",
        "unionpay.tsmservice",
        "com.hihonor.keychain",
        "com.hihonor.fitness.healthservice",
        "com.hihonor.health",
        "com.hihonor.ouc",
        "com.hihonor.systemappsupdater",
        "com.hihonor.android.upgradeguide",
        "com.hihonor.desktop.systemui",
        "com.hihonor.aod",
        "com.hihonor.gameassistant", // D 档上升：用户 AI 键长按目标，冻结即断 AI 键
        "com.hihonor.game.kitserver",
    )

    /** 红线前缀：AOSP/GMS/高通基带栈/overlay 框架。 */
    val RED_LINE_PREFIXES: List<String> = listOf(
        "com.android.", "com.google.", "com.qualcomm.", "com.qti.", "vendor.qti.",
    )

    /** B 档：功能可选冻（有真实代价，逐项标注）。 */
    val TIER_B: List<Entry> = listOf(
        Entry("com.hihonor.calendar", Tier.B, "无日历/日程提醒"),
        Entry("com.hihonor.photos", Tier.B, "无相册（代价大，慎冻）"),
        Entry("com.hihonor.filemanager", Tier.B, "失去文件管理器"),
        Entry("com.hihonor.deskclock", Tier.B, "无闹钟"),
        Entry("com.hihonor.notepad", Tier.B, "笔记数据不可访问"),
        Entry("com.hihonor.android.thememanager", Tier.B, "不能换主题/壁纸"),
        Entry("com.hihonor.hnmusicplayer", Tier.B, "无自带播放器"),
        Entry("com.hihonor.cloudmusic", Tier.B, "荣耀音乐(网易云)失效"),
        Entry("com.hihonor.hnvideoplayer", Tier.B, "视频播放器消失"),
        Entry("com.hihonor.email", Tier.B, "无邮件客户端"),
        Entry("com.hihonor.compass", Tier.B, "罗盘消失"),
        Entry("com.hihonor.calculator", Tier.B, "计算器消失"),
        Entry("com.hihonor.soundrecorder", Tier.B, "录音消失"),
        Entry("com.hihonor.screenrecorder", Tier.B, "录屏消失"),
        Entry("com.hihonor.printservice", Tier.B, "不能打印"),
        Entry("com.hihonor.privatespace", Tier.B, "隐私空间数据先迁出"),
        Entry("com.hihonor.parallelspace", Tier.B, "平行空间数据先迁出"),
        Entry("com.hihonor.trustspace", Tier.B, "信任空间数据先迁出"),
        Entry("com.hihonor.findmydevice", Tier.B, "查找设备失效"),
        Entry("com.hihonor.devicefinder", Tier.B, "丢失定位失效"),
        Entry("com.hihonor.parentcontrol", Tier.B, "家长控制失效"),
        Entry("com.hihonor.lens", Tier.B, "智慧识图失效"),
        Entry("com.hihonor.visionengine", Tier.B, "AI 视觉失效"),
        Entry("com.hihonor.linktowindowsservice", Tier.B, "PC 互联失效"),
        Entry("com.hihonor.pcassistant", Tier.B, "PC 互联失效"),
        Entry("com.hihonor.iconnect", Tier.B, "荣耀互联失效"),
        Entry("com.hihonor.airlink", Tier.B, "多设备协同失效"),
        Entry("com.hihonor.handoff", Tier.B, "跨接续失效"),
        Entry("com.hihonor.controlcenter", Tier.B, "控制中心失效"),
        Entry("com.hihonor.crossdeviceserviceshare", Tier.B, "跨设备分享失效"),
        Entry("com.hihonor.cooperatedconfig", Tier.B, "协同配置失效"),
        Entry("com.hihonor.devicegroupmanage", Tier.B, "设备组失效"),
        Entry("com.hihonor.nearby", Tier.B, "附近分享失效"),
        Entry("com.hihonor.linktime", Tier.B, "闪连失效"),
        Entry("com.hihonor.motionservice", Tier.B, "手势服务失效"),
        Entry("com.hihonor.hnmagicportal", Tier.B, "任意门失效"),
        Entry("com.hihonor.smartshot", Tier.B, "截屏增强失效"),
        Entry("com.hihonor.magickey", Tier.B, "魔法键失效"),
        Entry("com.hihonor.globalwrite", Tier.B, "全局书写失效"),
        Entry("com.hihonor.featurelayer.sharedfeature.stylus", Tier.B, "手写笔特性失效"),
        Entry("com.hihonor.hnbluetoothpencilmanager", Tier.B, "手写笔管理失效"),
        Entry("com.hihonor.stylus.floatmenu", Tier.B, "手写笔浮窗失效"),
        Entry("com.hihonor.dsdscardmanager", Tier.B, "双卡管理失效"),
        Entry("com.hihonor.phone.recorder", Tier.B, "通话录音失效"),
    )

    /** C 档：广告/推荐面，冻结代价低（appmarket/quickengine 有保守备注）。 */
    val TIER_C: List<Entry> = listOf(
        Entry("com.hihonor.magazine", Tier.C, "锁屏回默认壁纸（本机已冻）"),
        Entry("com.honor.browser", Tier.C, "用第三方浏览器（本机已冻）"),
        Entry("com.hihonor.browserhomepage", Tier.C, "浏览器首页定制，无"),
        Entry("com.hihonor.hiboard", Tier.C, "失去负一屏卡片"),
        Entry("com.hihonor.search", Tier.C, "桌面下拉搜索失效"),
        Entry("com.hihonor.searchservice", Tier.C, "系统内搜索变慢/失效"),
        Entry("com.hihonor.appmarket", Tier.C, "保守做法=关推荐设置而非冻结"),
        Entry("com.hihonor.gamecenter", Tier.C, "游戏推荐位消失"),
        Entry("com.hihonor.quickgame", Tier.C, "快游戏（37 号实证无反向依赖）"),
        Entry("com.hihonor.quickengine", Tier.C, "37 号修订：留，不冻（运行时依赖）"),
        Entry("com.hihonor.android.totemweather", Tier.C, "无天气卡片"),
        Entry("com.hihonor.assistant", Tier.C, "YOYO 助手/AI 键失效"),
        Entry("com.hihonor.suggestion", Tier.C, "负一屏推荐消失"),
        Entry("com.hihonor.geekcenter", Tier.C, "YOYO 建议消失"),
        Entry("com.honor.yoyoappsug", Tier.C, "应用推荐卡片消失"),
        Entry("com.honor.yoyocards", Tier.C, "YOYO 卡片消失"),
        Entry("com.hihonor.intelligentmessage", Tier.C, "短信回归纯文本（银行卡码变普通短信）"),
        Entry("com.hihonor.collect", Tier.C, "收藏中心消失"),
        Entry("com.hihonor.collectcenter", Tier.C, "收藏空间消失"),
        Entry("com.hihonor.videoeditor", Tier.C, "图库内剪辑入口消失"),
        Entry("com.hihonor.easygo", Tier.C, "快捷分发，无"),
        Entry("com.aura.oobe.honor", Tier.C, "开机预装引导"),
        Entry("com.hihonor.autoinstallapkfrommcc", Tier.C, "预装安装器"),
        Entry("android.clone", Tier.C, "换机克隆"),
        Entry("com.hihonor.hnstartupguide", Tier.C, "开机引导"),
        Entry("com.baidu.swan", Tier.C, "百度小程序推荐流"),
        Entry("com.bjbyhd.screenreader_hihonor", Tier.C, "若用无障碍读屏则禁冻"),
        Entry("com.hihonor.rcsserviceapplication", Tier.C, "增强短信失效"),
        Entry("com.hihonor.regservice", Tier.C, "营销注册"),
    )

    /**
     * 纯净模式精选集（12 号草案）：C 档排除 appmarket/assistant/intelligentmessage/quickengine。
     * quickgamecenter 本机未装条目作废，不收。
     */
    val PURE_PRESET: List<String> = listOf(
        "com.hihonor.magazine",
        "com.honor.browser",
        "com.hihonor.browserhomepage",
        "com.hihonor.hiboard",
        "com.hihonor.search",
        "com.hihonor.searchservice",
        "com.hihonor.gamecenter",
        "com.hihonor.quickgame",
        "com.hihonor.android.totemweather",
        "com.hihonor.suggestion",
        "com.hihonor.geekcenter",
        "com.honor.yoyoappsug",
        "com.honor.yoyocards",
        "com.hihonor.collect",
        "com.hihonor.collectcenter",
        "com.hihonor.videoeditor",
        "com.hihonor.easygo",
        "com.baidu.swan",
    )

    private val byPkg: Map<String, Entry> =
        (TIER_B + TIER_C).associateBy { it.pkg }

    /** 显式收录（含红线）：未知包返回 null = 不可选。 */
    fun lookup(pkg: String): Entry? = byPkg[pkg]

    fun isRedLine(pkg: String): Boolean =
        pkg in RED_LINES || RED_LINE_PREFIXES.any { pkg.startsWith(it) }

    /** UI 可选 = B/C 显式收录且非红线（quickengine 这类「留」注记靠 cost 文案提示，不硬拦）。 */
    fun selectable(pkg: String): Boolean = !isRedLine(pkg) && lookup(pkg) != null

    /** A 档 enqueue 前硬拦截（28 号 §7 ①）：红线/未收录包禁止进执行队列。 */
    fun requireSelectable(pkg: String) {
        check(selectable(pkg)) { "包不在可选冻结名单（红线或未收录）: $pkg" }
    }
}
