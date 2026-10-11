package top.honorhook.xposed.target.themestore

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import top.honorhook.xposed.data.AdSlotFilter
import top.honorhook.xposed.data.BottomTabFilter

/** 挂载点拦截器的运行时实现（S1 槽位 / S2 tab / W1 壁纸 / VIP 漏斗），与数据表解耦。 */

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

/** tab 取值失败（字段可见性 / 版本漂移）的一次性留痕：没有它「N/M 全绿」与「tab 没裁掉」会同时成立。 */
private val unreadableTabIdWarned = java.util.concurrent.atomic.AtomicBoolean(false)

private fun warnUnreadableTabIdOnce(module: XposedModule, where: String) {
    if (unreadableTabIdWarned.compareAndSet(false, true)) {
        module.log(
            Log.WARN, TAG,
            "底部 tab 裁剪未生效（$where）：BottomTabConfigs.getBottomTabId() 取值失败（非 public 或版本漂移），放行原配置",
        )
    }
}

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
    // 确认真正裁剪后才打判据日志（避免"记了日志却没裁"的误导）
    pruned.forEach { logBottomTabPrune(module, it) }
    return out
}

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

internal fun pruneTabStateFlowBeforeCreateTabs(chain: XposedInterface.Chain, module: XposedModule) {
    runCatching {
        val activity = chain.thisObject ?: return
        // getModel() 可能非 public（Kotlin private 委托属性不生成公开 getter）：沿继承链找，找不到走 model$delegate
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

/** vip-state 漏斗日志节流 ≤3 次（getLocalVipState/getVipState 是高频 LiveData 汇聚点，防刷屏）。 */
private var vipFixLogCount = 0

/**
 * VIP_STATE_FIX（after 语义）：proceed 拿 VipState 实例逐 setter 填会员值；null 自建——
 * 这条 null 路径是 getter 短路覆盖不了的核心场景（"VIP 已过期"横幅回归的真机根因）。
 * setter 逐个独立容错（缺失 = 版本漂移，跳过）；整体失败放行原逻辑。
 */
internal fun fixVipState(chain: XposedInterface.Chain, module: XposedModule, task: MountTask): Any? {
    val orig = runCatching { chain.proceed() }.getOrNull()
    val state = orig ?: runCatching {
        val loader = chain.thisObject?.javaClass?.classLoader
        Class.forName(CLS_VIP_STATE, true, loader).getDeclaredConstructor().newInstance()
    }.getOrNull()
    if (state == null) {
        module.log(Log.WARN, TAG, "vip-state 漏斗改写失败（实例不可得），放行原逻辑: ${task.methodName}")
        return orig
    }
    // 剩余时长 = now + 1.95e12（到 2088 的真实剩余；防消费端 now+X 溢出）
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

private fun invokeVipSetter(state: Any, name: String, paramType: Class<*>, value: Any, module: XposedModule): Boolean =
    runCatching {
        state.javaClass.getMethod(name, paramType).invoke(state, value)
    }.onFailure { t ->
        module.log(Log.WARN, TAG, "vip-state setter 缺失/失败（跳过）: $name — ${t.message}")
    }.isSuccess
