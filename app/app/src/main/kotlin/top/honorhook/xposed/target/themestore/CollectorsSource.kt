package top.honorhook.xposed.target.themestore

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.AdSlotFilter
import top.honorhook.xposed.data.BottomTabFilter
import top.honorhook.xposed.data.CloudSwitchTable

/** F2 下载链（H1/H2b/W1）+ 云控统一读点 + 服务端数据源头裁剪（S1 槽位 / S2 底部 tab）及其运行时实现。 */

/** H1/H2b：空 URL 付费项 → **VIP 下载通道**（修"下载永远 0%"）。依据见 design.md §三 H1/H2b + §十。 */
internal fun collectDownloadChainHooks(pending: MutableList<MountTask>) {
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

/**
 * 云控布尔开关统一拦截：只挂**读点**，具体强制哪些 key 全部由 `CloudSwitchTable` 声明。
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
internal fun collectCloudSwitchHooks(pending: MutableList<MountTask>) {
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

/**
 * **S1 服务端槽位源头裁剪**：钩 `hitop.mate.SlotData#getList()`（服务端响应反序列化后的**域模型出口**），
 * 在把载荷交给任何 Model / Provider 之前，按 `AdSlotFilter` 的表剔掉美豆营销槽位：
 *
 * - 整体丢弃 `AdSlotFilter.PRUNED_SLOT_TYPES` 命中的 `SlotList`（腰封 5 / 搜索框左右 20·22 / 福利中心 25·27）；
 * - 仅从 `SlotList.list` 内删掉 `AdSlotFilter.PRUNED_TARGET_IDS` 命中的 `Slot`（灯笼位里内嵌的「免费领」，
 *   保留灯笼位其余图标）。
 *
 * 为什么选这个点（而不是逐个消费方去拦）：数据链是
 * `POST themeServer/themeBiz/slot|searchSlot` → Retrofit/Gson → `SlotData` → 各 Model/Provider → UI。
 * 请求体只有 `appPage`，**没有能关掉单个槽位的参数**，云控布尔表里也没有对应 key ⇒ 服务端载荷进入域模型
 * 之前的这一个出口，才是能一处覆盖全部入口（我的页腰封 / 推荐页免费领 / 福利中心 / 搜索框两侧）的位置。
 * 载荷实测与判据来源见 `AdSlotFilter` 类注释（调试期真机探针打印的原始 `SlotData.getList()`；探针已按冗余审计删除）。
 */
internal fun collectAdSlotPruneHooks(pending: MutableList<MountTask>) {
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

/**
 * **S2 底部 tab 源头裁剪**：钩 `hitop.entity.BottomTabConfigs#getBottomTabConfigs()`
 * （AMS 在线配置 JSON 反序列化后的**域模型出口**，全工程唯一调用点 `SystemParamSource.java:208`），
 * 在把 tab 清单交给 Model 之前，按 `BottomTabFilter` 的表剔掉目标 tab（VIP / 福利）。
 *
 * 为什么选这个点：底部 tab 的完整数据链是
 * `AMS systemParam（在线配置 JSON）→ Gson → BottomTabConfigs → countBottomTabConfig() →
 * HwThemeManagerModel.getBottomTabConfig()（StateFlow）→ createTabs() 建 tab + doSupportOnline() 建 Fragment`。
 * 列表出口只有这一个，裁掉即**tab 与页面同步消失**。
 *
 * 为什么不关总闸 `AgreeRepo.isSupportVip()`（key `vip_support`）：该闸另有 20+ 处消费点
 * （详情页按钮组、字体 VIP 区、推荐流副文案…），强改会外溢到与"删 tab"无关的 UI。
 * 详见 `BottomTabFilter` 类注释。
 */
internal fun collectBottomTabPruneHooks(pending: MutableList<MountTask>) {
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

// ---------------- S1 运行时实现：SlotData.getList() 出口裁剪 ----------------

/**
 * 出口裁剪：按 `AdSlotFilter` 表剔掉美豆营销槽位。
 *
 * 返回 `null` 表示**未裁剪/不可裁剪**（调用方放行原值，PROTECTIVE 纪律）：
 * 非 List、空表、反射失败（版本漂移）一律原样返回。
 * 裁剪分两级：① 命中 `AdSlotFilter.isPrunedSlotType` 的整个 `SlotList` 丢弃；
 * ② 其余 `SlotList` 只从内层 `list` 里删掉命中 `AdSlotFilter.isPrunedTargetId` 的 `Slot`（写回 `setList`）。
 */
internal fun pruneSlotPayload(orig: Any?, module: XposedModule): List<*>? {
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

// ---------------- S2 运行时实现：BottomTabConfigs 出口裁剪 ----------------

/**
 * 出口裁剪：按 `BottomTabFilter` 表剔掉 VIP / 福利 tab。
 *
 * 返回 `null` 表示**未裁剪/不可裁剪**（调用方放行原值，PROTECTIVE 纪律）：非 List、空表、
 * 反射取不到 `getBottomTabId()`（版本漂移）一律原样保留。
 * `getBottomTabId()` 返回 int（`MenuType.getValue()`），故按 Number 取值比较。
 *
 * **保护**：裁剪后若不足 `BottomTabFilter.MIN_LIST_SIZE` 项则**整体放行**——宿主在 `size < 3` 时会走
 * 静态硬编码回退分支，而那支的 VIP tab 由 `isSupportVip` 控制、会重新出现（详见 BottomTabFilter 注释）。
 */
internal fun pruneBottomTabPayload(orig: Any?, module: XposedModule): List<*>? {
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

// ---------------- W1 运行时实现：动态壁纸 packagePath 前置修正 ----------------

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
internal fun fixWallpaperPackagePath(chain: XposedInterface.Chain, module: XposedModule) {
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
