package top.honorhook.xposed.target.themestore

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import top.honorhook.xposed.core.MountScheduler
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

/**
 * W2 付费壁纸密文强制解密（2026-10-12，第二处 A1 撕裂；同日晚风牵手真机分诊后 v2）：
 * `bz2.createApplyWallPaper`（defpackage/bz2.java:256）以 `isNeedPayItem()` 门控解密——F2 A1 把它
 * 强 false ⇒ 付费壁纸**跳过 getDecryptWallpaperFile**，`.payed` 密文直达动态壁纸引擎 → 解不出 →
 * `reportApplyFailedEvent("403")` 纯埋点 →「点击应用没反应」。
 * v1 真机分诊（2026-10-12 晚风牵手 type=5 voiceVideo）：W2 零日志——A1 撕裂不只强 false
 * isNeedPayItem，还让 updateStatus():2660 重算的 packagePath 丢 `.payed` 子目录 ⇒ v1 的
 * `contains(".payed")` 门在真实撕裂路径上不成立，密文明明在却静默 return。v2 三改：
 *   ① 免费项（price 字段直读 ≤0）维持零干预静默；付费项 packagePath 不可用时调宿主
 *     `WallPaperInfo.getAbsolutExistFilePath()`（jadx WallPaperInfo:680，按 price 字段探测
 *     inner/outer × `.payed`/normal 四处，不受 hook 影响）找回密文并回写 packagePath
 *     （回写同时喂饱 prepare :644 的 isFileExists 检查）。
 *   ② 先删同名解密缓存再解密（对齐宿主 deleteAllCacheWallpaper():270「每次付费应用新解」语义，
 *     防半截/陈旧解密产物复用——getDecryptWallpaperFile 见缓存即复用）。
 *   ③ 付费项每个出口都有判据日志（v1 静默 return 让三分支无法分诊）。
 * 解密复用宿主 `gl5.getDecryptWallpaperFile(serviceId, file)`（gl5 类名混淆，DexKit 锚点 = 解密工具日志串）。
 */
internal fun decryptPaidWallpaper(chain: XposedInterface.Chain, module: XposedModule, loader: ClassLoader) {
    runCatching {
        val info = chain.getArg(0) ?: return
        val price = (info.javaClass.getField("price").get(info) as? Double) ?: 0.0
        if (price <= 0.0) return   // 免费项：路径本就明文可用（撕裂兜底归 W1）→ 零干预静默
        val pathField = info.javaClass.getField("packagePath")
        val cur = pathField.get(info) as? String
        var cipher: java.io.File? = null
        if (!cur.isNullOrEmpty() && cur.contains(".payed")) {
            val f = java.io.File(cur)
            if (f.exists()) cipher = f
        }
        if (cipher == null) {
            // A1 撕裂：重算路径丢 `.payed` 子目录（或为空）→ 按宿主探测找回真实密文
            val real = runCatching {
                info.javaClass.getMethod("getAbsolutExistFilePath").invoke(info) as? String
            }.getOrNull()
            if (!real.isNullOrEmpty() && java.io.File(real).exists()) {
                cipher = java.io.File(real)
                if (real != cur) {
                    pathField.set(info, cipher.canonicalPath)
                    module.log(Log.INFO, TAG, "W2 packagePath 撕裂已回写: $cur → ${cipher.canonicalPath}")
                }
                if (!real.contains(".payed")) {
                    module.log(Log.WARN, TAG, "W2 找回路径非密文（.payed 缺失）: $real → 跳过解密放行原逻辑")
                    return
                }
            }
        }
        val file = cipher ?: run {
            module.log(Log.WARN, TAG, "W2 密文不可寻（packagePath=$cur，宿主探测未命中）→ 放行原逻辑")
            return
        }
        val serviceId = info.javaClass.getField("serviceId").get(info).toString()
        val gl5 = runCatching { loader.loadClass("gl5") }.getOrElse {
            module.log(Log.WARN, TAG, "W2 壁纸解密工具类漂移（gl5），放行原逻辑")
            return
        }
        // ② 新解：先删同名解密缓存（cache/decryWallpaper/<文件名>，宿主 getDecryWallpaperPath 拼法），
        // 否则 getDecryptWallpaperFile 见缓存即复用，半截产物无法自愈
        runCatching {
            loader.loadClass("bz2").getDeclaredMethod("getDecryWallpaperPath", String::class.java)
                .invoke(null, file.name) as? String
        }.getOrNull()?.let { java.io.File(it).delete() }
        val decrypted = gl5.getDeclaredMethod("getDecryptWallpaperFile", String::class.java, java.io.File::class.java)
            .invoke(null, serviceId, file) as? java.io.File
        if (decrypted != null && decrypted.exists()) {
            pathField.set(info, decrypted.canonicalPath)
            module.log(Log.INFO, TAG, "W2 壁纸密文已解密: serviceId=$serviceId → ${decrypted.canonicalPath}")
        } else {
            module.log(Log.WARN, TAG, "W2 壁纸解密产物不存在（内容密钥缺失? serviceId=$serviceId），packagePath 保留密文")
        }
    }.onFailure { t ->
        module.log(Log.WARN, TAG, "W2 壁纸解密前置失败（放行原逻辑）: ${t.message}")
    }
}

internal fun fixWallpaperPackagePath(chain: XposedInterface.Chain, module: XposedModule) {
    runCatching {
        val activity = chain.thisObject ?: return
        // mInfo 声明在 OnlineWallpaperPreviewLowActivity（jadx :214 protected WallPaperInfo mInfo），
        // 真机详情页是子类 OnlineWallpaperPreviewActivity ⇒ getDeclaredField 不走父类必
        // NoSuchFieldException（2026-10-12 真机判据行实证），沿继承链找
        val mInfoField = run {
            var cls: Class<*>? = activity.javaClass
            var found: java.lang.reflect.Field? = null
            while (cls != null && found == null) {
                found = runCatching { cls.getDeclaredField("mInfo") }.getOrNull()
                cls = cls.superclass
            }
            found
        } ?: return
        val info = mInfoField.apply { isAccessible = true }.get(activity) ?: return
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

// ---------------- F-fallback：VIP 通道下载失败/挂死 → 已购通道兜底（2026-10-12） ----------------
//
// 真机实证（光影 serviceId=101040131，已购 + VIP 过期）：H2b' 路由 3 → f71（DownloadItemIfVip）→
// sendProduct(trialFlag=1) + DRM 密钥双请求服务端不签发（过期 VIP 静默无回调）→ uri 永空 → UI 0% 定格
// （下载表无记录、无失败 toast —— f71.java 全失败路径都有 toast，唯一静默路径 = 服务端挂死）。
// 兜底 = 服务端语义上最稳的已购通道 themeServer/order/userProduct（NetHelper.getPayedList，
// 按账号查已购、返回 BaseResItemBean.fileUrl；整条链 NetHelper/PayRequest/AccountManager 未混淆）。
// 边界：只救 isSpecialEncrypt=false 项（加密付费项无 DRM 密钥，下载成功也会"文件损坏"，诚实失败）；
// 每 serviceId 每进程最多兜底 1 次（防回填后再次失败→再兜底→循环）。

/** 看门狗在挂状态（serviceId → armed）。productSendFinish 任意回调即除名；超时回调里除名并触发兜底。 */
private val vipFallbackArmed = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

/** 兜底已尝试计数（serviceId → 次数）。>0 即不再触发，防 fallback 自身失败被 f71 失败回调再触发成环。 */
private val vipFallbackAttempted = java.util.concurrent.ConcurrentHashMap<Int, Int>()

/** F 入口布看门狗：15s 内 productSendFinish 未回调（服务端挂死）→ 走已购通道兜底。
 *  chain 只在本次拦截内消费：DownloadInfo/包装链节点在此**预解析**为普通对象，闭包不持 chain。 */
internal fun armVipFallbackWatchdog(chain: XposedInterface.Chain, module: XposedModule, loader: ClassLoader) {
    runCatching {
        val owner = chain.thisObject ?: return
        val info = runCatching { owner.javaClass.getMethod("getDownloadInfo").invoke(owner) }.getOrNull() ?: return
        val node = wrappedZ61Node(owner) ?: return
        val serviceId = reflectFieldGet(info, "serviceId") as? Int ?: return
        if (serviceId <= 0) return
        vipFallbackArmed[serviceId] = true
        MountScheduler.postDelayed(15_000) {
            if (vipFallbackArmed.remove(serviceId) == true) {
                module.log(Log.INFO, TAG, "vip-fallback 看门狗触发: serviceId=$serviceId（productSendFinish 15s 无回调，服务端挂死）")
                runVipFallback(module, loader, info, node, serviceId)
            }
        }
    }
}

/** productSendFinish 回调出口（after 语义）：成功 = 除名看门狗零干预；失败 = 立即兜底（不等 15s）。 */
internal fun onVipProductSendFinish(chain: XposedInterface.Chain, module: XposedModule, loader: ClassLoader) {
    runCatching {
        val args = chain.args ?: return
        val serviceId = (args.getOrNull(0) as? Number)?.toInt() ?: return
        val url = args.getOrNull(1) as? String
        val keySaved = (args.getOrNull(3) as? kotlin.Pair<*, *>)?.second == true
        val armed = vipFallbackArmed.remove(serviceId) != null
        val ok = !url.isNullOrBlank() && keySaved
        if (ok || !armed) return   // 成功：无需兜底；未布岗：看门狗已先触发过（兜底在途/已试），不重复
        module.log(
            Log.INFO, TAG,
            "vip-fallback 触发: serviceId=$serviceId（productSendFinish 失败 urlEmpty=${url.isNullOrBlank()} keySave=$keySaved）",
        )
        val owner = chain.thisObject ?: return
        val info = runCatching { owner.javaClass.getMethod("getDownloadInfo").invoke(owner) }.getOrNull()
            ?: run { module.log(Log.WARN, TAG, "vip-fallback 放弃: DownloadInfo 不可达（版本漂移）"); return }
        val node = wrappedZ61Node(owner)
            ?: run { module.log(Log.WARN, TAG, "vip-fallback 放弃: 包装链内层 z61 节点不可达（版本漂移）"); return }
        runVipFallback(module, loader, info, node, serviceId)
    }
}

/** f71（i71 包装节点）→ 内层 z61：按字段类型扫描（类型名 z61，混淆不改类签名；rename 兼容）。 */
private fun wrappedZ61Node(owner: Any): Any? = runCatching {
    owner.javaClass.declaredFields
        .firstOrNull { it.type.name == "z61" }
        ?.apply { isAccessible = true }
        ?.get(owner)
}.getOrNull()

private fun runVipFallback(module: XposedModule, loader: ClassLoader, info: Any, node: Any, serviceId: Int) {
    if (vipFallbackAttempted.merge(serviceId, 1) { a, b -> a + b } != 1) return   // 每进程每项一次
    val uri = reflectFieldGet(info, "uri") as? String
    if (!uri.isNullOrBlank() && uri.contains("://")) return   // 已有可用直链，不干预
    val encrypted = (reflectFieldGet(info, "isSpecialEncrypt") as? Boolean)
        ?: ((reflectFieldGet(info, "isSpecialEncrypt") as? Number)?.toInt() ?: 0) != 0
    if (encrypted) {
        module.log(Log.INFO, TAG, "vip-fallback 跳过: serviceId=$serviceId 加密资源（无 DRM 密钥，直下会文件损坏，诚实失败）")
        return
    }
    // 下载已被用户取消 → 不再续（isDownloading 覆盖 WAITING/STARTED/LOADING/FAILURE，CANCELLED 不在内）
    if (reflectGet(info, "isDownloading") == false) {
        module.log(Log.INFO, TAG, "vip-fallback 跳过: serviceId=$serviceId 下载已被取消")
        return
    }
    val resType = reflectFieldGet(info, "onlineResType") as? Int ?: run {
        module.log(Log.WARN, TAG, "vip-fallback 放弃: onlineResType 不可读（版本漂移）")
        return
    }
    // 网络查询放后台线程（宿主 productSendFinish 回调在主线程；看门狗在挂载线程）
    Thread({
        val fileUrl = runCatching { queryPayedFileUrl(loader, resType, serviceId) }
            .getOrElse { t ->
                module.log(Log.WARN, TAG, "vip-fallback 已购列表查询异常: serviceId=$serviceId — ${t.message}")
                null
            }
        if (fileUrl == null) {
            module.log(Log.INFO, TAG, "vip-fallback 未命中: serviceId=$serviceId resType=$resType（已购列表无此项或无 fileUrl，保持宿主原失败路径）")
            return@Thread
        }
        reflectFieldSet(info, "uri", fileUrl)
        module.log(Log.INFO, TAG, "vip-fallback 命中: serviceId=$serviceId uri 已回填，走宿主 z61 续流（deleteData+落盘路径+resumeDownload）")
        // node.downloadItem() = z61.downloadItemInfo()：删旧记录 + 生成落盘路径 + o52.resumeDownload + 起下载服务（宿主原生续流）
        runCatching { node.javaClass.getMethod("downloadItem").invoke(node) }
            .onFailure { t -> module.log(Log.WARN, TAG, "vip-fallback 续流失败: ${t.message}") }
    }, "HonorHook-vipfb").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
}

/**
 * 已购通道查询（全反射、全未混淆类）：PayRequest{resType,cursor=0,pageSize=MAX,userId} →
 * NetHelper.INSTANCE.getPayedList(req, Const.Cache.NET) → ResBaseRspBean.getResourceList() →
 * 按 hitopId/resId/productId 匹配 serviceId → fileUrl（须含 "://"）。
 */
private fun queryPayedFileUrl(loader: ClassLoader, resType: Int, serviceId: Int): String? {
    val reqCls = loader.loadClass("$PKG.hitop.entity.pay.PayedBean\$PayRequest")
    val req = reqCls.getDeclaredConstructor().newInstance()
    reqCls.getMethod("setResType", Int::class.javaPrimitiveType).invoke(req, resType)
    reqCls.getMethod("setCursor", Int::class.javaPrimitiveType).invoke(req, 0)
    reqCls.getMethod("setPageSize", Int::class.javaPrimitiveType).invoke(req, Int.MAX_VALUE)
    runCatching {   // userId best-effort：服务端按会话判权，取不到不影响请求形态
        val amCls = loader.loadClass("$PKG.account.AccountManager")
        val am = amCls.getField("INSTANCE").get(null)
        reqCls.getMethod("setUserId", String::class.java).invoke(req, amCls.getMethod("getUserId").invoke(am))
    }
    val cacheCls = loader.loadClass("$PKG.common.Const\$Cache")
    val net = cacheCls.enumConstants.first { (it as? Enum<*>)?.name == "NET" }
    val nhCls = loader.loadClass("$PKG.hitop.NetHelper")
    val nh = nhCls.getField("INSTANCE").get(null)
    val rsp = nhCls.getMethod("getPayedList", reqCls, cacheCls).invoke(nh, req, net) ?: return null
    val list = rsp.javaClass.getMethod("getResourceList").invoke(rsp) as? List<*> ?: return null
    for (item in list) {
        if (item == null) continue
        val matched = listOf("hitopId", "resId", "productId").any { f ->
            (reflectFieldGet(item, f) as? String)?.toLongOrNull() == serviceId.toLong()
        }
        if (!matched) continue
        val url = (reflectFieldGet(item, "fileUrl") as? String)?.trim().orEmpty()
        if (url.contains("://")) return url
    }
    return null
}
