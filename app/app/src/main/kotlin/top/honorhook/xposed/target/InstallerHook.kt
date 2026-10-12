package top.honorhook.xposed.target

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.data.InstallerConfigTable
import top.honorhook.xposed.data.RemoteConfig

/** 包管理器目标（14.0.37.301）：五项「清净化」。 */
object InstallerHook : TargetHook {

    override val packageName = "com.android.packageinstaller"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("InstallerHook", packageName)

    private const val PKG_HN = "com.hihonor.packageinstaller"

    // ---- 混淆名目标（默认包 / 单字母方法名，R8 可重映射）----
    /** 广告请求条件实现（默认包混淆名；`implements wt1` 唯一实现）。 */
    private const val CLS_AD_REQUEST_CONDITION = "lt1"
    /** AB 实验工具（默认包混淆名；类内日志 TAG `AbExpUtils`）。 */
    private const val CLS_AB_EXP_UTILS = "sx1"
    /** 安全模式引导弹窗「是否展示」门助手（默认包混淆名；类内日志 TAG `InstalledGuideDialogHelper`）。 */
    private const val CLS_SAFE_MODE_DIALOG_HELPER = "n12"
    /** 联网安全检测分发器（默认包混淆名；构造器按 isHn 选 py1/qy1 两条管线，h() 是唯一入口）。 */
    private const val CLS_SECURITY_CHECK = "ly1"
    /** 安装指纹校验助手（默认包混淆名；类内日志 TAG `PwdControlUtil`，`qf2$a` 回调接口）。 */
    private const val CLS_FINGERPRINT_AUTH = "qf2"
    /** 安装页提示卡助手（默认包混淆名；方法体内有 isSupportSafeModeCardShow 等独有日志串）。 */
    private const val CLS_SAFE_MODE_CARD = "r12"

    // ---- 类名可读、方法名为单字母混淆（仅方法名会漂移）----
    /** 云控配置管理器（`configVO` key→JSON；方法 b..j 为单字母混淆）。 */
    private const val CLS_COMMON_CONFIG = "$PKG_HN.hncust.commonconfig.CommonConfigManager"
    /** V4 广告响应（服务端内容下发；读出口 = `getAdAppList()`）。 */
    private const val CLS_AD_RESP_V4 = "$PKG_HN.entity.resp.AdRespV4"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        // DexKit 第二轨兜底需要 APK 磁盘路径（MethodResolver）
        val apkPath = param.applicationInfo?.sourceDir
        engine.begin(apkPath, FeatureCatalog.baselineVersionOf(packageName))

        // 旧版宿主结构漂移 → 专用表优先；未命中任何表 = 基线源码路径（行为与表迁移前一致）
        val pick = HostVersion.pick(installerTables, HostVersion.resolve(apkPath))
        val pending: List<MountTask> = if (pick.matched) {
            pick.tasks
                .filter { it.feature.isEmpty() || RemoteConfig.isEnabled(it.feature) }
                .map { it.toInstallerTask() }
        } else {
            buildSourcePending()
        }

        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** 版本表注册：精确套（旧版）；无默认套——基线走 [buildSourcePending]。
     *  版本集唯一来源是 FeatureCatalog（extraVersions），增删适配版本只改那里；挂载点表文件名仍按版本命名。
     *  空集守卫：extraVersions 被清空时 Table(空集) 会翻成「默认套=任意版本兜底」（HostVersion.pick 语义），
     *  旧版挂载点将打到所有宿主版本上——必须整条表剔除而非留空表。 */
    private val installerTables =
        FeatureCatalog.extraVersionsOf(packageName).takeIf { it.isNotEmpty() }
            ?.let { listOf(HostVersion.Table(it) { INSTALLER_POINTS_14_0_22_326 }) }
            ?: emptyList()

    /** 基线 14.0.37.301 挂载表 11 项（广告 2 + AB 1 + 云控 3 + 安全模式弹窗/卡 3 + 检测分发器 1 + 指纹 1）；**以真机 `installed N/M` 的 M 为准。** */
    private fun buildSourcePending(): List<MountTask> {
        val pending = mutableListOf<MountTask>()
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_INSTALLER_AD)) {
            collectAdRequestHooks(pending)      // R4 不请求广告 + R1 不展示安装页广告
            collectAbRequestHooks(pending)      // R4 不请求 AB
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_INSTALLER_NO_INTERCEPT)) {
            collectCommonConfigHooks(pending)   // R2 三方跳转不拦截 + R3 安全模式默认不开启
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_INSTALLER_NO_SAFE_PROMPT)) {
            collectSafeModeDialogHooks(pending) // R5 不提示开启安全模式
            collectSafeModeCardHooks(pending)   // R5 安装页安全模式提示卡/底部推广卡
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_INSTALLER_SKIP_CHECK)) {
            collectSecurityCheckHooks(pending)  // R6 跳过联网安全检测（分发器层，双管线一并短路）
            collectFingerprintBypassHooks(pending) // R6 安装指纹校验直接通过（风险链兜底）
        }
        return pending
    }

    /** 数据表挂载点 → 本目标挂载任务（kind 字符串映射；DexHint 四维按非空聚合）。 */
    private fun HookPoint.toInstallerTask(): MountTask = MountTask(
        kind = MountKind.valueOf(kind),
        className = className,
        methodName = methodName,
        paramCount = paramCount,
        returnType = returnType,
        value = value,
        id = id,
        description = description,
        dex = DexHint(
            strings = dexStrings,
            paramTypes = dexParamTypes,
            classStrings = dexClassStrings,
            classFieldTypes = dexClassFieldTypes,
        ).takeIf {
            dexStrings != null || dexParamTypes != null || dexClassStrings != null || dexClassFieldTypes != null
        },
    )

    // ---------------- R4 不请求广告 + R1 不展示安装页广告 ----------------

/** 广告「不请求 + 不展示」双闸（源头优先）。 */
    private fun collectAdRequestHooks(pending: MutableList<MountTask>) {
        addConst(
            pending, CLS_AD_REQUEST_CONDITION, "b", 1, "boolean",
            InstallerConfigTable.AD_REQUEST_NOT_SUPPORTED,
            "ad-request:AdCondition.checkIsSupportAdRequest",
            "R4 广告请求条件恒 false（wt1 唯一实现；V3/V4 两条链路都不发请求，走宿主 early-return + errorCode 回调）",
            // 锚点：方法体独有日志串（"checkIsSupportAdRequest() ..." 多条，取最独特一条）+ 参数类型序列
            dex = DexHint(
                strings = listOf("checkIsSupportAdRequest() personalizedRecommendationSwitchOpen = false"),
                paramTypes = listOf("java.lang.String"),
            ),
        )
        addConst(
            pending, CLS_AD_RESP_V4, "getAdAppList", 0, "java.util.List",
            emptyList<Any>(),
            "ad-source:AdRespV4.getAdAppList",
            "R1 安装页广告响应数据源恒空表（渲染链走宿主自带 ad-empty 分支，不展示广告）",
        )
    }

    // ---------------- R4 不请求 AB ----------------

    /**
     * AB 实验请求入口 `sx1.Q(boolean)`（`updateAbExpStrategy`）空实现阻断 —— 不发 `AbExpReq`。
     * 全工程唯一调用点 `InstallStart:373` / `wb2:14`；方法体是「判网络/时间间隔 → `w92.E(AbExpReq)`」，
     * 阻断后 AB 数据沿用 SP 里已有的旧值（`ab_exp_strategy_data_key`），不影响其它逻辑。
     */
    private fun collectAbRequestHooks(pending: MutableList<MountTask>) {
        addBlock(
            pending, CLS_AB_EXP_UTILS, "Q", 1, "void",
            "ab-request:AbExpUtils.updateAbExpStrategy",
            "R4 AB 实验请求不发（updateAbExpStrategy 空实现；唯一调用点 InstallStart/wb2）",
            // 锚点：方法体独有日志串 + 参数类型序列
            dex = DexHint(
                strings = listOf("updateAbExpStrategy() network close"),
                paramTypes = listOf("boolean"),
            ),
        )
    }

    // ---------------- R2 三方安装异常跳转不拦截 + R3 安全模式默认不开启 ----------------

    /**
     * `CommonConfigManager`（云控 `configVO` 读点）三处强制：
     *
     * - `j(String)` = `是否命中三方安装异常跳转拦截名单` → 恒 **false**（不拦截任何来源包，名单含 `"ALL"` 通配）。
     * - `g()` / `h(CommonConfigResponse)` = `取 SafeModeConfig JSON` → 恒返 `{easyModeDefaultOpen:0, superProtectModeDefaultOpen:0}`。
     *   两者都挂：`g()` 是页面懒加载读点（`vz1.d()`），`h()` 是网络响应即时应用点（`onNext` → `vz1.h/j` 写 Settings）；
     *   `g()` 内部虽调 `h(null)`，但 R8 可能内联 ⇒ 两个都挂以抗漂移。
     */
    private fun collectCommonConfigHooks(pending: MutableList<MountTask>) {
        addConst(
            pending, CLS_COMMON_CONFIG, "j", 1, "boolean",
            InstallerConfigTable.THIRD_PARTY_JUMP_NOT_INTERCEPTED,
            "installer:CommonConfig.isNewTaskStartInstallBySourcePkg",
            "R2 三方安装异常跳转拦截名单不命中（CommonConfigManager.j → false）",
            // 锚点：方法体独有日志串（"isNewTaskStartInstallBySourcePkg ..."）
            dex = DexHint(strings = listOf("isNewTaskStartInstallBySourcePkg")),
        )
        addConst(
            pending, CLS_COMMON_CONFIG, "g", 0, "java.lang.String",
            InstallerConfigTable.SAFE_MODE_ALL_OFF_JSON,
            "installer:CommonConfig.getSafeModeConfigJson",
            "R3 安全模式默认全关 JSON（g() 页面懒加载读点 → {easyModeDefaultOpen:0,superProtectModeDefaultOpen:0}）",
            // g() 方法体只有 `return h(null)`（无字符串）⇒ 用**类级**字符串锚点（"CommonConfigManager"）
            dex = DexHint(classStrings = listOf("CommonConfigManager")),
        )
        addConst(
            pending, CLS_COMMON_CONFIG, "h", 1, "java.lang.String",
            InstallerConfigTable.SAFE_MODE_ALL_OFF_JSON,
            "installer:CommonConfig.getSafeModeConfigJsonFromRsp",
            "R3 安全模式默认全关 JSON（h(CommonConfigResponse) 网络响应点 → 写 Settings uz1.d/e = 0）",
            // 锚点：方法体独有日志串（"initGetSafeModeConfig ..."）+ 参数类型序列
            dex = DexHint(
                strings = listOf("initGetSafeModeConfig"),
                paramTypes = listOf("$PKG_HN.entity.resp.CommonConfigResponse"),
            ),
        )
    }

    // ---------------- R5 不提示用户开启安全模式 ----------------

    /**
     * 「安全模式引导弹窗」唯一展示门 `n12.f(int, SafeModeRise, RbiTempBean, boolean)`（`isShowGuideOpenSafeModeDialog`）
     * 恒 false —— `InstallSuccess` 仅两处调用：
     * - `z0()`（"完成"按钮）：`if (f(1,...)) r1()` 不成立 → 落 `else finish()`（正常的"无弹窗"路径，页面正常关闭）；
     * - `onCreate` 途中：`k0 = f(2,...)` → false ⇒ 回调 `l0()` 不再弹。
     */
    private fun collectSafeModeDialogHooks(pending: MutableList<MountTask>) {
        addConst(
            pending, CLS_SAFE_MODE_DIALOG_HELPER, "f", 4, "boolean",
            InstallerConfigTable.SAFE_MODE_GUIDE_DIALOG_NOT_SHOWN,
            "installer:SafeModeDialogGate.isShowGuideOpenSafeModeDialog",
            "R5 安全模式引导弹窗不展示（n12.f 恒 false；立即弹/延后弹两处调用点全覆盖）",
            // 锚点：方法体独有日志串（"isShowGuideOpenSafeModeDialog ..." 多条，取首条）+ 参数类型序列
            dex = DexHint(
                strings = listOf("isShowGuideOpenSafeModeDialog input params not available. triggerType = "),
                paramTypes = listOf(
                    "int",
                    "$PKG_HN.entity.resp.SafeModeRise",
                    "$PKG_HN.rbi.RbiTempBean",
                    "boolean",
                ),
            ),
        )
    }

    // ---------------- R5 补充：安装页安全模式提示卡 / 底部推广卡 ----------------

    /**
     * `r12.j(CardTipsRecord, String, boolean, String)` = `isSupportSafeModeCardShow()` 恒 false（提示卡整卡隐藏）；
     * `r12.s(PackageInstallerActivity, RbiTempBean)` = `setSafeModeBottomSheetShow` 空实现（底部推广卡不展示）。
     */
    private fun collectSafeModeCardHooks(pending: MutableList<MountTask>) {
        addConst(
            pending, CLS_SAFE_MODE_CARD, "j", 4, "boolean",
            InstallerConfigTable.SAFE_MODE_CARD_NOT_SHOWN,
            "installer:SafeModeCard.isSupportSafeModeCardShow",
            "R5 安装页安全模式提示卡不展示（r12.j → false，整卡隐藏）",
            // 锚点：方法体独有日志串 + 参数类型序列（CardTipsRecord 可读类名，强锚点）
            dex = DexHint(
                strings = listOf("isSupportSafeModeCardShow() pageIndex="),
                paramTypes = listOf(
                    "$PKG_HN.utils.CardTipsRecord",
                    "java.lang.String",
                    "boolean",
                    "java.lang.String",
                ),
            ),
        )
        addBlock(
            pending, CLS_SAFE_MODE_CARD, "s", 2, "void",
            "installer:SafeModeCard.setSafeModeBottomSheetShow",
            "R5 安全模式底部推广卡不展示（r12.s 空实现阻断）",
            // 锚点：方法体独有日志串 + 参数类型序列（两个可读类名，强锚点）
            dex = DexHint(
                strings = listOf("setSafeModeBottomSheetShow() first return"),
                paramTypes = listOf(
                    "com.android.packageinstaller.PackageInstallerActivity",
                    "$PKG_HN.rbi.RbiTempBean",
                ),
            ),
        )
    }

    // ---------------- R6 跳过联网安全检测（分发器层，双管线一并短路） ----------------

    /**
     * `ly1.h(boolean, sy1)` = 联网安全检测**唯一入口**（全工程唯一调用点 PackageInstallerActivity:1847）。
     * 构造器内按 isHn 选 py1（荣耀云检测）/ qy1（通用）两条管线 —— **在分发器层短路，两条管线一并跳过**：
     * 不调原方法（不发检测请求 ⇒ 无"当前网络不佳"横幅），直接复用宿主静态工厂 `ly1.a(isHn)` 构造"通过"结果，
     * 经 `sy1.b(oy1, jz1)` 在主线程回吐（模拟宿主异步回调语义）⇒ 无风险判定 ⇒
     * 风险拦截页 / "已了解"勾选框 / 指纹校验整条下游链不出现。
     * jz1 用空实现动态代理（宿主回调体内会调 `jz1.a(Drawable)`，null 安全）。
     * 短路准备失败时**放行原方法**（降级为有检测，安装流程不死锁）。
     */
    private fun collectSecurityCheckHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.CHECK_BYPASS,
            className = CLS_SECURITY_CHECK,
            methodName = "h",
            paramCount = 2,
            returnType = "void",
            value = null,
            id = "installer:SecurityCheck.bypass",
            description = "R6 联网安全检测分发器短路（ly1.h → 直接回传通过；检测请求/网络不佳横幅/风险页/勾选框一并消除）",
            // ly1 类内零字符串常量 ⇒ 仅参数类型序列弱锚点（OTA 重混淆时诚实 NOT_RESOLVED，由汇总行暴露）
            dex = DexHint(paramTypes = listOf("boolean", "sy1")),
        )
    }

    // ---------------- R6 安装指纹校验直接通过（风险链兜底） ----------------

    /**
     * `qf2.g(Context, qf2$a)` = 安装前指纹/密码校验唯一入口。回调 `c()`（清除待决弹窗）+ `a(true)`（判定通过），
     * 不调原方法（校验弹窗不出现）。检测分发器短路后该链通常已不触发，此点为双保险。
     */
    private fun collectFingerprintBypassHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.FINGERPRINT_BYPASS,
            className = CLS_FINGERPRINT_AUTH,
            methodName = "g",
            paramCount = 2,
            returnType = "void",
            value = null,
            id = "installer:FingerprintAuth.bypass",
            description = "R6 安装指纹校验直接通过（qf2.g → 回调 c()+a(true)，不弹校验）",
            // 锚点：类级日志 TAG（PwdControlUtil）+ 参数类型序列（Context 可读）
            dex = DexHint(
                classStrings = listOf("PwdControlUtil"),
                paramTypes = listOf("android.content.Context", "qf2\$a"),
            ),
        )
    }

    // ---------------- 挂载执行：本目标私有任务定义（重试/汇总/解析在共享 [MountEngine]） ----------------

    /**
     * 挂载任务类型：[CONST] 常量短路（命中即返回强制值，不调原方法）；
     * [BLOCK] void 空实现阻断（返回 null = 不调原方法，libxposed 语义，`.cursor/skills/libxposed-api/SKILL.md` 实证）；
     * [CHECK_BYPASS] 安全检测分发器短路（自构造"通过"结果回调宿主，失败放行原方法）；
     * [CHECK_BYPASS_RET] 检测直通·返回式（旧版：不调原方法，静态工厂 a(isHn) 的返回值直接作为方法结果，失败放行）；
     * [FINGERPRINT_BYPASS] 指纹校验直接通过（回调 c()+a(true)，失败放行原方法）。
     */
    private enum class MountKind { CONST, BLOCK, CHECK_BYPASS, CHECK_BYPASS_RET, FINGERPRINT_BYPASS }

    private class MountTask(
        val kind: MountKind,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        val value: Any?,
        override val id: String,
        override val description: String,
        /** DexKit 兜底特征（混淆名目标用；null = 仅直连轨；四维强度与纪律见 [DexHint]）。 */
        override val dex: DexHint? = null,
    ) : MountEngine.Task

    private fun addConst(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        value: Any?,
        id: String,
        description: String,
        dex: DexHint? = null,
    ) {
        pending += MountTask(
            MountKind.CONST, className, methodName, paramCount, returnType, value, id, description, dex,
        )
    }

    private fun addBlock(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        id: String,
        description: String,
        dex: DexHint? = null,
    ) {
        pending += MountTask(
            MountKind.BLOCK, className, methodName, paramCount, returnType, null, id, description, dex,
        )
    }

    /** 单个挂载任务执行：本函数只产出 kind 分派的 intercept 逻辑，解析/异常/分级交给共享 [MountEngine.mount]。 */
    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result {
        val result = engine.mount(task, module, loader) { chain ->
            when (task.kind) {
                // 常量短路：命中即返回强制值（List 类值每次新建实例，避免共享可变集合被调用方改写）
                MountKind.CONST -> {
                    engine.judge(module, task, JUDGE_PREFIX)
                    val v = task.value
                    if (v is Collection<*>) {
                        // 集合类强制值每次新建实例（避免共享可变集合被调用方改写）
                        ArrayList<Any?>(v.size).apply { addAll(v) }
                    } else {
                        v
                    }
                }
                // void 空实现阻断（不调原方法）
                MountKind.BLOCK -> {
                    engine.judge(module, task, JUDGE_PREFIX)
                    null
                }
                // 安全检测分发器短路：不调原方法，主线程回吐"通过"结果；准备失败放行原方法（不死锁）
                MountKind.CHECK_BYPASS -> {
                    val delivered = runCatching {
                        val callback = chain.args?.getOrNull(1) ?: return@runCatching false
                        val self = chain.thisObject
                        val isHn = self.javaClass.getMethod("d").invoke(self) as Boolean
                        // 通过结果复用宿主静态工厂 ly1.a(boolean)（与原管线自产 pass 同构）
                        val pass = self.javaClass.getDeclaredMethod("a", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(null, isHn)
                        // jz1 回调参数用空实现动态代理（宿主回调体内调 jz1.a(Drawable)，null 安全）
                        val jz1 = loader.loadClass("jz1")
                        val noop = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(jz1)) { _, _, _ -> null }
                        val deliver = callback.javaClass.methods.first { it.name == "b" && it.parameterTypes.size == 2 }
                        // 模拟宿主异步回调语义（原管线自工作线程回吐结果）
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching { deliver.invoke(callback, pass, noop) }.onFailure { t ->
                                module.log(Log.WARN, MountEngine.TAG, "安全检测通过结果回调异常: ${t.message}")
                            }
                        }
                        true
                    }.getOrDefault(false)
                    if (delivered) {
                        engine.judge(module, task, JUDGE_PREFIX)
                        null
                    } else {
                        chain.proceed()
                    }
                }
                // 检测直通·返回式（旧版 br0.b）：不调原方法，arg0(isHn) 经静态工厂 a(Z) 构造通过结果直接返回；失败放行
                MountKind.CHECK_BYPASS_RET -> {
                    val pass = runCatching {
                        val isHn = chain.args?.getOrNull(0) as? Boolean ?: return@runCatching null
                        loader.loadClass(task.className)
                            .getDeclaredMethod("a", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                            .invoke(null, isHn)
                    }.getOrNull()
                    if (pass != null) {
                        engine.judge(module, task, JUDGE_PREFIX)
                        pass
                    } else {
                        chain.proceed()
                    }
                }
                // 指纹校验直接通过：回调 c()（清待决弹窗）+ a(true)（判定通过），不调原方法；失败放行
                MountKind.FINGERPRINT_BYPASS -> {
                    val ok = runCatching {
                        val cb = chain.args?.getOrNull(1) ?: return@runCatching false
                        val cMethod = cb.javaClass.methods.firstOrNull { it.name == "c" && it.parameterTypes.isEmpty() }
                        val aMethod = cb.javaClass.methods.firstOrNull {
                            it.name == "a" && it.parameterTypes.size == 1 &&
                                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                        }
                        if (cMethod == null || aMethod == null) return@runCatching false
                        cMethod.invoke(cb)
                        aMethod.invoke(cb, true)
                        true
                    }.getOrDefault(false)
                    if (ok) {
                        engine.judge(module, task, JUDGE_PREFIX)
                        null
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
        if (result == MountEngine.Result.OK && MountEngine.DEBUG_LOGGING) {
            val verb = when (task.kind) {
                MountKind.CONST -> "常返"
                MountKind.BLOCK -> "空实现阻断"
                MountKind.CHECK_BYPASS -> "检测短路直通"
                MountKind.CHECK_BYPASS_RET -> "检测直通(返回式)"
                MountKind.FINGERPRINT_BYPASS -> "指纹直通"
            }
            module.log(Log.INFO, MountEngine.TAG, "hook 已挂载: ${task.description} → $verb ${task.value ?: "null"}")
        }
        return result
    }

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "installer-hook"
}
