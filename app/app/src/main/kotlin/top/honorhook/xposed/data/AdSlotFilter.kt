package top.honorhook.xposed.data

/**
 * 美豆（金币/福利/「耀宝」营销）**服务端槽位**的源头过滤表 —— 数据与 hook 逻辑分离的唯一改动点。
 *
 * ## 数据从哪来（真机载荷实证，2026-10-10）
 *
 * 这两个入口**都不是本地写死的 UI**，而是服务端下发的「槽位」：
 * - 接口：`POST themeServer/themeBiz/slot`（我的页 / 推荐页，`MineApi.getSlotInfo` / `RecommendApi.getSlotInfo`）
 *   与 `POST themeServer/themeBiz/searchSlot`（搜索框两侧，`RecommendApi.getSearchSlotInfo`）
 *   （声明见 `hitop/RetrofitApiService.java`、`logic/mine/api/MineApi.java`、`logic/recommend/api/RecommendApi.java`）
 * - 响应经 Retrofit/Gson 反序列化为 `hitop.mate.SlotData`，域模型出口即 `SlotData.getList(): List<SlotList>`；
 *   所有消费方（MineModel / RecommendModel / RecommendRootFM / 各 Provider）都从它取数据。
 * - 请求参数只有 `appPage`（我的页 1800 / 推荐页 2957 / 搜索框 2100）等，**没有"关掉某一槽位"的开关**；
 *   云控布尔表（`CloudSwitchTable`）里也没有对应 key（`common/i.java` 内无 welfare/waist/slot 关键字），
 *   所以只能在**服务端载荷进入域模型之前**按内容裁掉 —— 这就是本表的用途。
 *
 * 真机探针（调试期临时挂载，已按冗余审计删除；`git log` 可找回）拿到的原始载荷：
 * ```
 * 我的页 appPage=1800:
 *   [0] SlotList type=5  coins=1 title=我的页面腰封-福利中心  → Slot{resType=1019,targetType=1019,targetId=1019001,
 *                                         url=hnt://…immerseUrl=https://h5-marketing-drcn.platform.hihonorcloud.com/act}
 *   [1] SlotList type=7  coins=0 title=25我的页面横图有耀宝    → 2×Slot{resType=1004, targetId=null}
 * 推荐页 appPage=2957:
 *   [3] SlotList type=2  coins=1 title=灯笼位-25版本          → 8×Slot，其中第 1 个 = title=免费领
 *                                                              resType=1019 targetType=1019 targetId=1019001
 *   [4] SlotList type=27 coins=1 title=福利中心               → Slot{resType=1019,targetType=1019,targetId=1019002}
 * ```
 * 与静态反查交叉验证：
 * - `hitop/mate/SlotList.java` 的 `Companion` 命名常量：`TYPE_WAIST=6`、`TYPE_SEARCH_LAYOUT=20`、
 *   `TYPE_SEARCH_LEFT_LAYOUT=22`、`TYPE_WELFARE_CENTER=25`、`TYPE_WELFARE_PLUGIN=27`；
 *   `onSlotIntType(6)` 返回字面量 `"5"` ⇒ **线上 slotType 字符串 "5" 即腰封**（与探针实测 type=5 吻合）；
 *   `onSlotIntType(27)` 亦对应 "27"（与探针实测 福利中心 type=27 吻合）。
 * - `RecommendModel.getSearchSlot$1` 用字面量 `"20"`/`"22"` 从响应里挑搜索槽位 ⇒ 搜索框左右两侧槽位的
 *   线上 slotType 就是 **"20"/"22"**。
 * - `RecommendFM.checkHasLanternWithWelfareCenter` 用 `targetId == "1019001"` 判定"灯笼位里夹了福利中心" ⇒
 *   灯笼位内嵌的「免费领」判据就是 **targetId 1019001**（探针实测该 Slot 确实 resType/targetType=1019、
 *   targetId=1019001、url 指向 h5-marketing 福利活动）。
 *
 * ## 过滤口径（本表）
 * 1) [PRUNED_SLOT_TYPES]：整个 `SlotList` 直接丢弃（这些槽位本身即营销位）；
 * 2) [PRUNED_TARGET_IDS]：只删 `SlotList.list` 里命中的**单个 Slot**（灯笼位这类混合槽位要保留其余图标，
 *    否则会把「最新/魔法主题/小组件/彩铃/图标」一起干掉）。
 */
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
