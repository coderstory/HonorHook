package top.honorhook.xposed.target.themestore

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook

/** 荣耀主题商店目标（F1–F4）：F1 开 VIP / F2 全免费（含下载链修复）/ F3 试用无倒计时 / F4 不恢复默认。 */
object ThemeStoreHook : TargetHook {

    override val packageName = "com.hihonor.android.thememanager"

    /** 共享挂载基建（版本自检/重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("ThemeStoreHook", packageName)

    /** 版本选表：默认套 = 基线点（行为与迁移前一致）；适配其他宿主版本时在其上追加精确套。 */
    private val versionTables = listOf(
        HostVersion.Table(versions = emptySet()) { THEME_POINTS_20_1_63_305 },
    )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        // DexKit 第二轨兜底（MethodResolver）需要 APK 磁盘路径：param.applicationInfo.sourceDir
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))

        // 挂载表 46 项；各项归属见 description 编号（A/B/C/H/T/W/S 系列）。计数注释历史上一再漂移
        // （评审 #14：43→44→46 三代不符），故不再列分类算术——以表行数与真机 installed N/M 的 M 为准。
        // 数据表 ThemeStoreMap_20_1_63_305.kt 手维护。
        val actual = HostVersion.resolve(param.applicationInfo?.sourceDir)
        val pick = HostVersion.pick(versionTables, actual)
        // 盲挂留痕（评审 R7：把隐式行为变显式日志）：默认套任意版本兜底，实际版本偏离基线 = 按基线表盲挂，
        // 判据以 installed N/M 为准（与 MountEngine.checkHostVersionOnce 的 WARN 互补：那条说风险，这条说行为）。
        val baseline = FeatureCatalog.baselineVersionOf(packageName)
        if (actual != null && baseline != null && actual != baseline) {
            module.log(Log.INFO, MountEngine.TAG, "宿主 $actual 未适配，按基线 $baseline 挂载表盲挂，判据以 installed N/M 为准")
        }
        val pending = pick.tasks
            .filter { it.feature.isEmpty() || RemoteConfig.isEnabled(it.feature) }
            .map { it.toMountTask() }

        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** DOWNLOAD_TYPE_ROUTE 的 DownloadInfo 实例获取（口径同 isUriBlank 的 UriOwner 分派；drift → null → 放行）。 */
    private fun downloadInfoFor(chain: XposedInterface.Chain, task: MountTask): Any? = when (task.uriOwner) {
        UriOwner.THIS -> chain.thisObject
        UriOwner.GET_DOWNLOAD_INFO -> chain.thisObject?.let { owner ->
            cachedMethod(owner, "getDownloadInfo")?.invoke(owner)
        }
    }

    /** PRE_PROCESS 按 task.id 的处理函数表（评审 #12：if/else 魔法串分派表化——新表行忘登记从静默跑错变 WARN 可见）。 */
    private val preProcessHandlers: Map<String, (XposedInterface.Chain, XposedModule, ClassLoader) -> Unit> = mapOf(
        "theme:WallpaperPreview.fixPackagePath" to { chain, module, _ -> fixWallpaperPackagePath(chain, module) },
        "theme:WallpaperPreview.decryptPaidWp" to { chain, module, loader -> decryptPaidWallpaper(chain, module, loader) },
        "adsource:CreateTabs.pruneStateFlow" to { chain, module, _ -> pruneTabStateFlowBeforeCreateTabs(chain, module) },
    )

    /**
     * 单个挂载任务执行：解析/per-task 隔离/回调留痕/汇总全部交给共享 [engine.mount]，本函数只产出 kind 分派逻辑。
     * （评审 #13：46 点最大目标此前自行内联 resolve+builder+fold 绕开 engine——engine 的任何增强对 theme 不生效，
     * 两份 fold 已漂移；现在收敛到统一模板。）
     */
    private fun mountOne(
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountEngine.Result {
        // 挂载期一次求值（恢复旧实现口径，审计 nit：intercept 闭包内逐回调 invoke 纯浪费）；
        // resolveValue 幂等（enumConstants 纯查找）；null 未就绪已被 engine.resolveTask 拦成 NOT_RESOLVED（?: 兜底取声明值）
        val value = task.resolveValue?.invoke(loader) ?: task.value
        return engine.mount(task, module, loader) { chain ->
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
                        engine.judge(module, task, "theme-hook", "downloadType→$route（uriBlank=$uriBlank enc=$encrypted price=$price）")
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
                    preProcessHandlers[task.id]?.invoke(chain, module, loader)
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
