package top.honorhook.xposed.target.themestore

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountScheduler
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook

/** 荣耀主题商店目标（F1–F4）：F1 开 VIP / F2 全免费（含下载链修复）/ F3 试用无倒计时 / F4 不恢复默认。 */
object ThemeStoreHook : TargetHook {

    override val packageName = "com.hihonor.android.thememanager"

    /** 最近一次 VIP 路由（downloadType→3）看到的 serviceId：e26.getDecryptTheme/getDecryptFile 的
     *  serviceId 参数在下载完成自动解包路径上为 null（调用方未回填）→ null key 解不出内容 →
     *  isZipError 删文件报「文件损坏」。路由点 DownloadInfo 是唯一可靠来源（2026-11-02 诊断实证）。 */
    @Volatile
    var lastRouteServiceId: String? = null
        private set

    /** 当前版本 ThemeCheckTool 的混淆类名（DECRYPT_PREPARE 行取；null = 版本表未带该行，调用方回落 gl5）。 */
    @Volatile
    internal var decryptToolClass: String? = null

    /**
     * 版本选表：精确套优先，默认套（基线点）任何版本兜底。
     *
     * 版本集合**从 FeatureCatalog.extraVersionsOf(packageName) 派生**，不在本文件写死字面量——
     * 否则「UI 说已适配」与「hook 真的选了哪张表」会各说各话（VersionSupportTest 双向断言）。
     * 未在 catalog 登记的版本 = 无精确套 ⇒ 落默认套盲挂（与 [FeatureCatalog.MountPolicy.BlindLatest] 一致）。
     */
    private val versionTables: List<HostVersion.Table<HookPoint>> = buildList {
        FeatureCatalog.extraVersionsOf(packageName).forEach { version ->
            when (version) {
                "20.1.67.303" -> add(HostVersion.Table(setOf(version)) { THEME_POINTS_20_1_67_303 })
                "20.1.67.302" -> add(HostVersion.Table(setOf(version)) { THEME_POINTS_20_1_67_302 })
                "20.1.65.302" -> add(HostVersion.Table(setOf(version)) { THEME_POINTS_20_1_65_302 })
            }
        }
        add(HostVersion.Table(versions = emptySet()) { THEME_POINTS_20_1_63_305 })
    }

    /** 测试缝：本 hook 实际能精确命中的版本集合（VersionSupportTest 与 catalog 对账用）。 */
    internal fun pinnedTableVersions(): Set<String> = versionTables.filter { it.versions.isNotEmpty() }.flatMap { it.versions }.toSet()

    /** 测试缝：是否存在任意版本兜底的默认套（= 隐含 BlindLatest 策略）。 */
    internal fun hasDefaultTable(): Boolean = versionTables.any { it.versions.isEmpty() }

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        // 版本读 + 选表 + 挂载整体挪到后台线程（宿主主线程零同步磁盘 IO）：版本读是同步 IPC——
        // system_server 那边要解析宿主 APK，放在宿主主线程会拖慢宿主启动。
        // 挂载本来就由 MountScheduler 执行（attemptMount 非调度线程会转投），挪进来顺带把 begin 放到
        // 同一线程 ⇒ begin 写入的字段不再跨线程读。
        MountScheduler.postDelayed(0L) {
            // DexKit 第二轨兜底（MethodResolver）需要 APK 磁盘路径：param.applicationInfo.sourceDir
            val apkPath = param.applicationInfo?.sourceDir

            // 挂载表条目按 description 编号（A/B/C/H/T/W/S 系列）归属；计数注释历史上一再漂移
            // （评审 #14 记过 43/44/46 三代不符），故不写死数字——以表文件行数与真机 installed N/M 的 M 为准。
            // 数据表 ThemeStoreMap_20_1_63_305.kt 手维护。
            val actual = HostVersion.resolveHostVersion(apkPath, packageName)
            val pick = HostVersion.pick(versionTables, actual)
            // 盲挂留痕（评审 R7）：精确套未命中（含未知新版本）= 按默认套盲挂；命中精确套则自检口径
            // 与 InstallerHook 同构（matchedVersion 为期望版本，65.302 命中精确表，不再报基线偏离）。
            val baseline = FeatureCatalog.baselineVersionOf(packageName)
            if (pick.matchedVersion == null && actual != null && baseline != null) {
                module.log(Log.INFO, MountEngine.TAG, "宿主 $actual 未适配，按基线 $baseline 挂载表盲挂，判据以 installed N/M 为准")
            }
            /** 共享挂载基建（版本自检/重试循环/失败分级/汇总日志/方法解析）。闸门 = 功能开关（表级过滤下沉到引擎）。 */
            val session = MountEngine.open("ThemeStoreHook", packageName, apkPath, pick.matchedVersion ?: baseline, RemoteConfig)
            val pending = pick.tasks
                .map { it.toMountTask() }
            // 解密工具类（源名 ThemeCheckTool）的混淆名随版本绑定：W2 付费壁纸解密要直接调它的
            // getDecryptWallpaperFile。历史版本该方法在独立工具类 gl5；2026-11-07 的 20.1.67.303 实证
            // gl5 已变成不相关的 Rx 类、方法整体迁进 ThemeCheckTool 本体 b26 ⇒ 只对本表显式指定，
            // 其余版本保持旧的 gl5（不能拿 DECRYPT_PREPARE 行的类名顶替：老表的 e26 只挂 getDecryptTheme）。
            decryptToolClass = if (pick.matchedVersion == "20.1.67.303") THEME_DECRYPT_TOOL_67_303 else null

            session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
        }
    }

    /** DOWNLOAD_TYPE_ROUTE 的 DownloadInfo 实例获取（口径同 isUriBlank 的 UriOwner 分派；drift → null → 放行）。 */
    private fun downloadInfoFor(chain: XposedInterface.Chain, task: MountTask): Any? = when (task.uriOwner) {
        UriOwner.THIS -> chain.thisObject
        UriOwner.GET_DOWNLOAD_INFO -> chain.thisObject?.let { owner ->
            cachedMethod(owner, "getDownloadInfo")?.invoke(owner)
        }
    }

    /** PRE_PROCESS 按 task.id 的处理函数表（评审 #12：if/else 魔法串分派表化——新表行忘登记从静默跑错变 WARN 可见）。
     *  第 4 参传 task：处理函数里要复用的宿主类名（W2 的 bz2）是**混淆名、随版本绑定**，
     *  只能从当前命中的表行 task.className 取，不得在函数里写死（2026-11-07 20.1.67.303 实证）。 */
    private val preProcessHandlers: Map<String, (XposedInterface.Chain, XposedModule, ClassLoader, MountTask) -> Unit> = mapOf(
        "theme:WallpaperPreview.fixPackagePath" to { chain, module, _, _ -> fixWallpaperPackagePath(chain, module) },
        "theme:WallpaperPreview.decryptPaidWp" to { chain, module, loader, task ->
            decryptPaidWallpaper(chain, module, loader, task.className)
        },
        "adsource:CreateTabs.pruneStateFlow" to { chain, module, _, _ -> pruneTabStateFlowBeforeCreateTabs(chain, module) },
    )

    /**
     * 单个挂载任务执行：解析/per-task 隔离/回调留痕/汇总全部交给共享 [session.mount]，本函数只产出 kind 分派逻辑。
     * （评审 #13：46 点最大目标此前自行内联 resolve+builder+fold 绕开 engine——engine 的任何增强对 theme 不生效，
     * 两份 fold 已漂移；现在收敛到统一模板。）
     */
    private fun mountOne(session: MountSession, 
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountEngine.Result {
        // 挂载期一次求值（恢复旧实现口径，审计 nit：intercept 闭包内逐回调 invoke 纯浪费）；
        // resolveValue 幂等（enumConstants 纯查找）；null 未就绪已被 session.resolveTask 拦成 NOT_RESOLVED（?: 兜底取声明值）
        val value = task.resolveValue?.invoke(loader) ?: task.value
        return session.mount(task, module, loader) { chain ->
            when (task.kind) {
            // 常量短路：命中即返回强制值，不调原方法
            MountKind.CONST -> {
                if (routeActive(chain, module, task)) value else chain.proceed()
            }
            // 下载类型路由（2026-10-12，docs/reverse/theme-store.md §E）：
            // o52.addDownloadItem 是下载策略唯一分派点（o52.java:604 免费分支审计门 / :616 付费分派），
            // getDownloadType 是其唯一读点。按 DownloadInfo 实际状态改写分派值，三类目标各归其道：
            //   空 URL → 3（f71 VIP 试用授权，原 H2b 语义：付费资源服务端不下发 JSON 直链，须 sendProduct 签发）
            //   免费但加密（isSpecialEncrypt）→ 4（a71 审计通道 = o52 原生 free+enc+4 路线，
            //     sendAuditProduct 免购买签发 URL+内容密钥——小部件/图标「文件已损坏」的根治）
            //   有 URL 付费项（price>0 / -100 已购标记）→ 3（避开 c71 普通购买策略弹 IAP 支付页）
            //   真免费明文直链 → 放行原值（不误伤）
            // DownloadInfo.price/isSpecialEncrypt 是 public 字段且 d.setDownloadInfo 用**字段直拷**（d.java:174/196），
            // 不经过被 F2 hook 的 getter——此处读到的是服务端真实值，路由判定不受 F2 影响。
            MountKind.DOWNLOAD_TYPE_ROUTE -> {
                val info = downloadInfoFor(chain, task)
                if (info == null) {
                    chain.proceed()   // 实例缺失/反射漂移 → 放行（安全默认）
                } else {
                    // uri/price/isSpecialEncrypt 必须走**字段直读**（reflectFieldGet）：三者是
                    // DownloadInfo 的 public 字段且无同名 getter（DownloadInfo.java:119/131/145），
                    // getMethod 语义的 reflectGet 全读 null → uriBlank 恒 true → 每次下载误路由 3
                    // （W3，2026-10-12；详见 reflectFieldGet 注释与 theme-store.md §E）。
                    val uri = reflectFieldGet(info, "uri") as? String
                    val price = (reflectFieldGet(info, "price") as? Double) ?: 0.0
                    val encrypted = reflectFieldGet(info, "isSpecialEncrypt") as? Boolean ?: false
                    // uri 判空口径与 isUriBlank 一致：空串/"null" 占位/无 "://"
                    val uriBlank = uri == null || uri.isEmpty() || uri == "null" || !uri.contains("://")
                    // 付费判定（含 -100 已购标记）先于加密判定：-100+加密若落入审计通道会改走
                    // getAuditApplyThemeLicenseV2（与 §E.3「付费→3」表不一致）；真免费（price=0）
                    // 加密项才进 4 审计通道（a71，字节码实证 free 分支 new a71 后 move-object v0 接回
                    // downloadItem，jadx 丢弃了该 move——o52 smali 0x010f-0x0136）
                    val route = when {
                        uriBlank -> 3
                        price > 0.0 || price == -100.0 -> 3
                        encrypted && price <= 0.0 -> 4
                        else -> null
                    }
                    if (route == null) {
                        chain.proceed()
                    } else {
                        if (route == 3) {
                            // 存下 serviceId 供 DECRYPT_PREPARE 回填（null key 解密必败，见字段注释）
                            runCatching {
                                info.javaClass.declaredFields.firstOrNull { it.name == "serviceId" }?.let {
                                    it.isAccessible = true
                                    (it.get(info) as? Number)?.let { n -> lastRouteServiceId = n.toString() }
                                        ?: (it.get(info) as? String)?.takeIf { s -> s.isNotBlank() }?.let { s -> lastRouteServiceId = s }
                                }
                            }
                        }
                        session.judge(module, task, "theme-hook", "downloadType→$route（uriBlank=$uriBlank enc=$encrypted price=$price）")
                        route
                    }
                }
            }
            // VIP 通道下载兜底（F-fallback，2026-10-12）：f71.downloadItem 布看门狗，
            // f71.productSendFinish 失败/15s 无回调（过期 VIP 服务端挂死，真机实证）→ 已购通道兜底。
            // 实现（看门狗/守卫/续流）在 ThemeStoreRuntime；此处只做 kind 分派。
            MountKind.DOWNLOAD_FALLBACK -> {
                if (task.methodName == "downloadItem") {
                    armVipFallbackWatchdog(chain, module, loader)
                    chain.proceed()
                } else {
                    chain.proceed()   // productSendFinish：after 语义，先让宿主失败路径跑完（toast/abort）
                    onVipProductSendFinish(chain, module, loader)
                }
            }
            // 通用校验诊断（2026-11-02）：after 只读打点——打印全部入参 + 返回值，定位「文件损坏」
            // 具体校验方（hashCheck = 主题/字体 hash 门；checkFontValid = 字体有效性门）。随诊可撤。
            MountKind.DIAG_DECRYPT -> {
                // Toast 诊断特化：show() after 提取文本 + 调用栈（定位「文件损坏」toast 的真实弹出方）
                if (task.className == "android.widget.Toast") {
                    val r = chain.proceed()
                    runCatching {
                        val text = chain.thisObject?.let { t ->
                            runCatching {
                                var c: Class<*>? = t.javaClass
                                while (c != null) {
                                    try {
                                        val f = c.getDeclaredField("mNextView")
                                        f.isAccessible = true
                                        val v = f.get(t) as? android.view.View
                                        if (v is android.widget.TextView) return@runCatching v.text.toString()
                                    } catch (_: NoSuchFieldException) { }
                                    c = c.superclass
                                }
                                "?"
                            }.getOrDefault("?")
                        }
                        val stack = Thread.currentThread().stackTrace
                            .dropWhile { it.className.startsWith("android.") || it.className.startsWith("java.") || it.className.startsWith("top.honorhook") || it.className.contains("LSPosed") || it.className.startsWith("androidx") }
                            .take(6).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                        session.judge(module, task, "theme-hook", "Toast 诊断 text=[$text] stack=$stack")
                    }
                    return@mount r
                }
                val r = chain.proceed()
                runCatching {
                    fun hashOf(a: Any): String = runCatching {
                        var c: Class<*>? = a.javaClass
                        while (c != null && c != Any::class.java) {
                            try {
                                val f = c.getDeclaredField("hashCodeValue")
                                f.isAccessible = true
                                return f.get(a)?.toString() ?: "?"
                            } catch (_: NoSuchFieldException) { }
                            c = c.superclass
                        }
                        "?"
                    }.getOrDefault("?")
                    val args = chain.args?.joinToString(", ") { a ->
                        when (a) {
                            null -> "null"
                            is String, is Number, is Boolean, is java.io.File -> a.toString()
                            else -> "${a.javaClass.simpleName}(hash=${hashOf(a)})"
                        }
                    }
                    session.judge(module, task, "theme-hook", "校验诊断 result=$r args=[$args]")
                }
                r
            }
            // 付费解包前置修复（2026-11-02，诊断实证）：下载完成自动解包链 e26.getDecryptTheme /
            // e26.getDecryptFile 的 serviceId 参数为 null（调用方未回填）→ 内容密钥查不到 → 解密失败
            // / 密文被 isZipError 误判 → 删文件报「文件损坏」；重进详情页参数齐了才成功。
            // 修复：① serviceId 空参用 lastRouteServiceId 回填；② 已解密产物（getDecryName(origin)）
            // 若存在且非 PK zip 头（历史失败残留）先删，强制重新解密。改参后 proceedWith。
            MountKind.DECRYPT_PREPARE -> {
                // 两目标方法均为 static（e26 静态工具）：thisObject 为 null，proceedWith 用类对象作
                // receiver（反射 invoke 对静态方法忽略 receiver）；整段 try 包裹——hook 回调绝不炸宿主。
                runCatching {
                    val args = chain.args?.toTypedArray()
                    if (args == null) return@runCatching chain.proceed()
                    val svcIdx = 0                                 // getDecryptTheme/getDecryptFile 首参均为 serviceId
                    val originIdx = if (task.paramCount == 4) 2 else 1 // origin path 参数位
                    val svc = args.getOrNull(svcIdx) as? String
                    if (svc.isNullOrBlank() || svc == "null") {
                        lastRouteServiceId?.let { filled -> args[svcIdx] = filled }
                    }
                    // 清理坏缓存产物：e26.getDecryName(origin) → 若存在且头两字节非 "PK" 则删除
                    runCatching {
                        val origin = args.getOrNull(originIdx) as? String
                        if (!origin.isNullOrBlank()) {
                            val m = loader.loadClass(task.className).getDeclaredMethod("getDecryName", java.io.File::class.java)
                            m.isAccessible = true
                            val out = m.invoke(null, java.io.File(origin)) as? String
                            if (!out.isNullOrBlank()) {
                                val f = java.io.File(out)
                                if (f.exists()) {
                                    val head = f.inputStream().use { ins ->
                                        val b = ByteArray(2); val n = ins.read(b); if (n == 2) String(b, Charsets.US_ASCII) else ""
                                    }
                                    if (head != "PK") {
                                        f.delete()
                                        session.judge(module, task, "theme-hook", "DECRYPT_PREPARE 坏缓存已删: $out")
                                    }
                                }
                            }
                        }
                    }
                    val receiver = chain.thisObject
                        ?: runCatching { loader.loadClass(task.className) }.getOrNull()
                        ?: return@runCatching chain.proceed()
                    chain.proceedWith(receiver, args)
                }.getOrElse { chain.proceed() }
            }
            // 下载完成路径撕裂修复（2026-11-02，updateProgress smali 实证）：付费项下载实际落
            // `.payed/`（DownloadInfo.isPayedItem=-100 标记侧），而预览页 ItemInfo.packagePath 经
            // A1（isNeedPayItem→false）算成不带 .payed 的路径 → updateProgress 的 isFileExists 门
            // 直接 false → toast_pkg_error「文件已损坏」，自动应用链根本不进。修复：进门前若
            // packagePath 不存在，按变体（同目录/.payed/、.cache/、Honor/Themes/.cache/）探测真实
            // 文件并写回 packagePath 字段，放行后续链（该链解密应用已实证可 SUCCESS）。
            MountKind.PATH_MEND -> {
                // 前置语义：toast 在 updateProgress 内部触发，必须先修 packagePath 再放行
                runCatching {
                    val item = runCatching { chain.getArg(0) }.getOrNull() ?: return@runCatching
                    val field = runCatching {
                        var cc: Class<*>? = item.javaClass
                        while (cc != null && cc != Any::class.java) {
                            try { return@runCatching cc.getDeclaredField("packagePath") } catch (_: NoSuchFieldException) { }
                            cc = cc.superclass
                        }
                        null
                    }.getOrNull() ?: return@runCatching
                    field.isAccessible = true
                    val path = field.get(item) as? String
                    if (path.isNullOrBlank() || java.io.File(path).exists()) return@runCatching
                    val f = java.io.File(path)
                    val candidates = listOf(
                        java.io.File(f.parentFile, ".payed/" + f.name),
                        java.io.File(f.parentFile, ".cache/" + f.name),
                        java.io.File(f.parentFile?.parentFile, ".payed/" + f.name),
                        java.io.File(f.parentFile?.parentFile, ".cache/" + f.name),
                        java.io.File("/storage/emulated/0/Honor/Themes/.cache", f.name),
                    )
                    val real = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return@runCatching
                    field.set(item, real.absolutePath)
                    session.judge(module, task, "theme-hook", "PATH_MEND packagePath 修正: $path → ${real.absolutePath}")
                }
                chain.proceed()
            }
            // 参数强制（setter 写点 / 下载类型路由点）
            MountKind.FORCE_ARG -> {
                if (routeActive(chain, module, task)) {
                    val args = chain.args?.toTypedArray() ?: arrayOfNulls<Any?>(task.forceArgIndex + 1)
                    args[task.forceArgIndex] = value
                    chain.proceedWith(chain.thisObject, args)
                } else {
                    chain.proceed()
                }
            }
            // 云控读点：按第 1 参 key 查表，命中即短路返回强制值；未命中放行原逻辑（对非表内 key 零影响）
            MountKind.KEY_OVERRIDE -> {
                val key = runCatching { chain.getArg(0) as? String }.getOrNull()
                val forced = top.honorhook.xposed.data.CloudSwitchTable.forced(key)
                if (key != null && forced != null) {
                    logCloudOverride(module, key, forced)
                    forced
                } else {
                    chain.proceed()
                }
            }
            // 服务端槽位载荷出口裁剪（美豆 S1）：proceed 拿到 SlotData.list 后按表剔营销槽位，失败放行原值
            MountKind.SLOT_PRUNE -> {
                if (routeActive(chain, module, task)) {
                    val orig = chain.proceed()
                    pruneSlotPayload(orig, module) ?: orig
                } else {
                    chain.proceed()
                }
            }
            // 服务端底部 tab 配置出口裁剪（S2）：proceed 拿到 tab 列表后按表剔 VIP/福利 tab，失败放行原值
            MountKind.BOTTOM_TAB_PRUNE -> {
                if (routeActive(chain, module, task)) {
                    val orig = chain.proceed()
                    pruneBottomTabPayload(orig, module) ?: orig
                } else {
                    chain.proceed()
                }
            }
            // void 空实现阻断（挽留弹窗 F1 / 广告 SDK init S3；判据日志不受 DEBUG_LOGGING 门控）
            MountKind.BLOCK -> {
                if (routeActive(chain, module, task)) {
                    module.log(Log.INFO, MountEngine.TAG, "已空实现阻断: ${task.methodName}")
                    null
                } else {
                    chain.proceed()
                }
            }
            // 放行后 finish（挽留弹窗 F2 兜底）：先 proceed 再 finish，生命周期完整（不 proceed 会让
            // onStart/onDestroy 等在未初始化状态跑，可能 NPE；fanqie MainHook.java:689 先例，HC 的
            // finish+不 proceed 模式不采用）
            MountKind.FINISH_ACTIVITY -> {
                if (routeActive(chain, module, task)) {
                    val result = chain.proceed()
                    (chain.thisObject as? Activity)?.finish()
                    module.log(Log.INFO, MountEngine.TAG, "挽留弹窗已拦截: ${task.methodName}")
                    result
                } else {
                    chain.proceed()
                }
            }
            // 前置数据修正（proceed 前修 this/共享数据，再原样放行）：按任务 id 查表分派（未注册 = 新表行错路，WARN 可见）
            MountKind.PRE_PROCESS -> {
                if (routeActive(chain, module, task)) {
                    preProcessHandlers[task.id]?.invoke(chain, module, loader, task)
                        ?: module.log(
                            Log.WARN, MountEngine.TAG,
                            "PRE_PROCESS 处理函数未注册（放行原逻辑）: ${task.id} — 新表行需在 preProcessHandlers 登记",
                        )
                }
                chain.proceed()
            }
            // VIP 状态漏斗改写（after 语义）：proceed 拿 VipState 实例逐 setter 填会员值；null 自建。
            // 2026-10-10 真机回归后恢复——getter 短路覆盖不了 null 实例与类内字段直读（见 collectVipHooks 注释）
            MountKind.VIP_STATE_FIX -> {
                fixVipState(chain, module, task)
            }
            }
        }
    }
}
