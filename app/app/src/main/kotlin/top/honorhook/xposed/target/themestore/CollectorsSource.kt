package top.honorhook.xposed.target.themestore

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.AdSlotFilter
import top.honorhook.xposed.data.BottomTabFilter
import top.honorhook.xposed.data.CloudSwitchTable

/** F2 下载链（H1/H2b/W1）+ 云控统一读点 + 服务端数据源头裁剪（S1 槽位 / S2 底部 tab）及其运行时实现。 */

/** H1/H2b：空 URL 付费项 → **VIP 下载通道**（修"下载永远 0%"）。依据见 docs/theme-store.md §三 H1/H2b + §十。 */
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

/** 云控布尔开关统一拦截：只挂**读点**，具体强制哪些 key 全部由 `CloudSwitchTable` 声明。 */
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

/** **S1 服务端槽位源头裁剪**：钩 `hitop.mate.SlotData#getList()`（服务端响应反序列化后的**域模型出口**）， */
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

/** **S2 底部 tab 源头裁剪**：钩 `hitop.entity.BottomTabConfigs#getBottomTabConfigs()` */
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
    // S2b 消费口兜底（2026-10-10 真机实证的第二条 tab 数据路径）：tab 清单除了 AMS 刷新链（S2 已裁）
    pending += MountTask(
        kind = MountKind.PRE_PROCESS,
        className = CLS_MAIN_ACTIVITY,
        methodName = "createTabs",
        paramCount = 1,
        returnType = "void",
        value = null,
        id = "adsource:CreateTabs.pruneStateFlow",
        description = "底部 tab S2b createTabs 前置裁剪 StateFlow（堵 MMKV 缓存直读路径，" +
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
 * 底部 tab 取值失败（字段可见性 / 版本漂移）的一次性留痕标志。
 *
 * 没有这行的话，"installed N/M 全绿"与"tab 没裁掉"会同时成立，排查时无从下手。
 */
private val unreadableTabIdWarned = java.util.concurrent.atomic.AtomicBoolean(false)

private fun warnUnreadableTabIdOnce(module: XposedModule, where: String) {
    if (unreadableTabIdWarned.compareAndSet(false, true)) {
        module.log(
            Log.WARN, TAG,
            "底部 tab 裁剪未生效（$where）：BottomTabConfigs.getBottomTabId() 取值失败（非 public 或版本漂移），放行原配置",
        )
    }
}

/** 出口裁剪：按 `BottomTabFilter` 表剔掉 VIP / 福利 tab。 */
internal fun pruneBottomTabPayload(orig: Any?, module: XposedModule): List<*>? {
    val list = orig as? List<*> ?: return null
    if (list.isEmpty()) return null
    var changed = false
    var unreadable = 0
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
        if (id == null) unreadable++
        out.add(item)
    }
    if (!changed) {
        if (unreadable > 0) warnUnreadableTabIdOnce(module, "S2 服务端配置出口")
        return null
    }
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

/** W1：动态壁纸 `packagePath` 路径撕裂修正（[MountKind.PRE_PROCESS]，apply 前置）。 */
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

/**
 * **S2b createTabs 前置裁剪**：proceed 前把 `HwThemeManagerModel.getBottomTabConfig()`（StateFlow）
 * 里的 tab 列表按 [BottomTabFilter] 表就地裁剪——堵 MMKV 缓存直读路径（见 collectBottomTabPruneHooks S2b 注释）。
 * 幂等：列表无目标 tab 时零操作；裁后 < [BottomTabFilter.MIN_LIST_SIZE] 放弃（宁可不裁也不踩 fallback）。
 * StateFlow 公开类型是只读 StateFlow，实际对象实现 MutableStateFlow ⇒ 反射 setValue 可写。
 */
internal fun pruneTabStateFlowBeforeCreateTabs(chain: XposedInterface.Chain, module: XposedModule) {
    runCatching {
        val activity = chain.thisObject ?: return
        // getModel() 可能在父类或非 public（Kotlin private 委托属性不生成公开 getter）：
        // 先沿继承链找方法，找不到再走 `model$delegate` Lazy 字段
        val model: Any = run {
            var cls: Class<*>? = activity.javaClass
            var found: Any? = null
            while (cls != null && found == null) {
                val m = cls.declaredMethods.firstOrNull { it.name == "getModel" && it.parameterCount == 0 }
                if (m != null) {
                    m.isAccessible = true
                    found = m.invoke(activity)
                }
                cls = cls.superclass
            }
            found ?: run {
                val f = generateSequence(activity.javaClass) { it.superclass }
                    .mapNotNull { c -> c.declaredFields.firstOrNull { it.name == "model\$delegate" } }
                    .firstOrNull() ?: return
                f.isAccessible = true
                val lazy = f.get(activity) as? Lazy<*> ?: return
                lazy.value ?: return
            }
        }
        val flow = model.javaClass.getMethod("getBottomTabConfig").invoke(model) ?: return
        val cur = flow.javaClass.getMethod("getValue").invoke(flow) as? List<*> ?: return
        val out = ArrayList<Any>(cur.size)
        var changed = false
        var unreadable = 0
        for (item in cur) {
            if (item == null) continue
            val id = (reflectGet(item, "getBottomTabId") as? Number)?.toInt()
            if (id != null && BottomTabFilter.ruleFor(id) != null) {
                changed = true
            } else {
                if (id == null) unreadable++
                out.add(item)
            }
        }
        if (!changed) {
            if (unreadable > 0) warnUnreadableTabIdOnce(module, "S2b createTabs 前置")
            return
        }
        if (out.size < BottomTabFilter.MIN_LIST_SIZE) {
            module.log(Log.WARN, TAG, "底部 tab 前置裁剪放弃：裁后仅 ${out.size} 项，放行原配置")
            return
        }
        flow.javaClass.getMethod("setValue", Any::class.java).invoke(flow, out as Any)
        module.log(Log.INFO, TAG, "底部 tab 缓存路径已裁剪（createTabs 前置）: ${cur.size} → ${out.size} 项")
    }.onFailure { t ->
        module.log(Log.WARN, TAG, "createTabs 前置裁剪失败（放行原逻辑）: ${t.message}")
    }
}

// ---------------- F1 运行时实现：VipManager 漏斗改写（VIP_STATE_FIX，2026-10-10 真机回归后恢复） ----------------

/** vip-state 漏斗日志节流 ≤3 次（getLocalVipState/getVipState 是高频 LiveData 汇聚点，防刷屏）。 */
private var vipFixLogCount = 0

/**
 * VIP_STATE_FIX 改写（after 语义）：先 proceed 拿 VipState 实例 → 逐 setter 填永久 VIP 值；
 * 实例 null（getVipState 首查未落缓存）→ 无参构造自建后返回——**这条 null 路径是 getter 短路
 * 覆盖不了的核心场景**（UI 拿到 null 直接走已过期/未登录分支，"VIP 已过期"横幅回归的真机根因）。
 * setter 签名按 jadx VipState.java 实证；逐个独立 runCatching，缺失的（版本漂移）跳过；
 * 整体失败放行原逻辑（PROTECTIVE 纪律）。判据日志：`vip-state 漏斗改写: <method> setter=N/6`。
 */
internal fun fixVipState(chain: XposedInterface.Chain, module: XposedModule, task: MountTask): Any? {
    val orig = runCatching { chain.proceed() }.getOrNull()
    val state = orig ?: runCatching {
        // null 实例（getVipState 首查）：按宿主 classLoader 构造空 VipState 再填会员值
        val loader = chain.thisObject?.javaClass?.classLoader
        Class.forName(CLS_VIP_STATE, true, loader).getDeclaredConstructor().newInstance()
    }.getOrNull()
    if (state == null) {
        module.log(Log.WARN, TAG, "vip-state 漏斗改写失败（实例不可得），放行原逻辑: ${task.methodName}")
        return orig
    }
    // 剩余时长 = now + 1.95e12（到 2088 的真实剩余；防消费端 now+X 溢出，2026-10-09 语义修正）
    val filled = listOf(
        Triple("setVipUser", java.lang.Boolean.TYPE, true as Any),
        Triple("setVipStatus", java.lang.Integer.TYPE, 2 as Any),
        Triple("setVipType", java.lang.Integer::class.java, 2 as Any),
        Triple("setExpireTimeTimestamp", java.lang.Long.TYPE, TS_2088_08_08_MILLIS as Any),
        Triple("setDisplayTime", String::class.java, TS_2088_08_08_STR as Any),
        Triple("setRemainingTime", java.lang.Long::class.java, System.currentTimeMillis() + 1_950_000_000_000L),
    ).count { (name, paramType, value) -> invokeVipSetter(state, name, paramType, value, module) }
    if (filled == 0) {
        module.log(Log.WARN, TAG, "vip-state 漏斗改写失败（全部 setter 缺失/异常），放行原逻辑: ${task.methodName}")
        return orig
    }
    if (vipFixLogCount < 3) {
        vipFixLogCount++
        module.log(
            Log.INFO, TAG,
            "vip-state 漏斗改写: ${task.methodName} setter=$filled/6 vipUser=true vipStatus=2 vipType=2 " +
                "expireTimeTimestamp=$TS_2088_08_08_MILLIS displayTime=$TS_2088_08_08_STR remainingTime=now+1.95e12",
        )
    }
    return state
}

/** 单个 VipState setter 反射调用（实证签名精确匹配；失败跳过 = 版本漂移容错，不阻断）。 */
private fun invokeVipSetter(state: Any, name: String, paramType: Class<*>, value: Any, module: XposedModule): Boolean =
    runCatching {
        state.javaClass.getMethod(name, paramType).invoke(state, value)
    }.onFailure { t ->
        module.log(Log.WARN, TAG, "vip-state setter 缺失/失败（跳过）: $name — ${t.message}")
    }.isSuccess
