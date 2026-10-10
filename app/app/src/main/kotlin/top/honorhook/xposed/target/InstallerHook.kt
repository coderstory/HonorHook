package top.honorhook.xposed.target

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.InstallerConfigTable

/**
 * 包管理器（软件包安装程序 `com.android.packageinstaller`，荣耀定制 14.0.37.301）目标 —— 五项「清净化」能力：
 *
 * 1. **不请求广告**：钩广告请求**条件门** `lt1.b(String)`（`checkIsSupportAdRequest`）恒 false ——
 *    `lt1` 是 `wt1` 接口的**唯一**实现，V4（`zv1.m`）与 V3（`mt1.m`）两条广告链都先问它，恒 false ⇒
 *    两条链路都走宿主自带的「不支持广告请求」分支（写错误码 + 回调 errorCode），**不发请求且回调完整**。
 * 2. **不展示安装页广告**：钩广告响应**数据源** `AdRespV4.getAdAppList()` 恒空表 —— 即便有缓存响应，
 *    渲染链（`AbstractAdBusinessPresenter.v1()`）也走宿主自带的「ad is empty after filter」分支、不渲染。
 * 3. **不请求 AB**：空实现阻断 AB 实验入口 `sx1.Q(boolean)`（`updateAbExpStrategy`）—— 不发 `AbExpReq`
 *    （全工程唯一调用点 `InstallStart:373` / `wb2:14`）。
 * 4. **三方安装异常跳转不拦截**：`CommonConfigManager.j(String)` 恒 false（拦截名单不命中，含 `"ALL"` 通配）。
 * 5. **安全模式默认不开启 / 不提示开启安全模式**：
 *    - 默认不开启 → `CommonConfigManager.g()` / `h(CommonConfigResponse)` 恒返
 *      [InstallerConfigTable.SAFE_MODE_ALL_OFF_JSON]（`{easyModeDefaultOpen:0, superProtectModeDefaultOpen:0}`）；
 *    - 不提示 → 「安全模式引导弹窗」唯一展示门 `n12.f(int, SafeModeRise, RbiTempBean, boolean)` 恒 false
 *      （`InstallSuccess` 两处调用：立即弹 `if(f(1,..)) r1()` → 落 `else finish()`；延后弹
 *      `k0 = f(2,..)` → false ⇒ `l0()` 不再弹）。
 *
 * **设计依据、挂载点理由与真机判据全部归档在 `docs/installer-cloud-config.md`**，本文件只保留代码。
 * 混淆名目标（默认包 `lt1`/`sx1`/`n12`，以及可读类名的单字母方法）都配了 DexKit 内容锚点；
 * 锚点四维强度与纪律见 [DexHint]、[MethodResolver]（**直连轨优先，锚点仅在直连失败时生效**）。
 *
 * 源列表：
 * - 云控配置：`$PKG_HN.hncust.commonconfig.CommonConfigManager`（方法 b..j）
 * - 广告：`lt1`(AdRequestConditionImpl) / `$PKG_HN.entity.resp.AdRespV4` / `$PKG_HN.presenter.AbstractAdBusinessPresenter`
 * - AB：`sx1`(AbExpUtils)
 * - 安全模式弹窗门：`n12`(InstalledGuideDialogHelper)
 */
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

    // ---- 类名可读、方法名为单字母混淆（仅方法名会漂移）----
    /** 云控配置管理器（`configVO` key→JSON；方法 b..j 为单字母混淆）。 */
    private const val CLS_COMMON_CONFIG = "$PKG_HN.hncust.commonconfig.CommonConfigManager"
    /** V4 广告响应（服务端内容下发；读出口 = `getAdAppList()`）。 */
    private const val CLS_AD_RESP_V4 = "$PKG_HN.entity.resp.AdRespV4"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        // DexKit 第二轨兜底需要 APK 磁盘路径（MethodResolver）
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        // 挂载表 7 项（广告 2 + AB 1 + 云控 3 + 安全模式弹窗 1）；**以真机 `installed N/M` 的 M 为准。**
        val pending = mutableListOf<MountTask>()
        collectAdRequestHooks(pending)      // R4 不请求广告 + R1 不展示安装页广告
        collectAbRequestHooks(pending)      // R4 不请求 AB
        collectCommonConfigHooks(pending)   // R2 三方跳转不拦截 + R3 安全模式默认不开启
        collectSafeModeDialogHooks(pending) // R5 不提示开启安全模式

        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** hook 编写时的宿主版本基线（aapt2 实证）；OTA 超出此版本时 MountEngine 首轮挂载打 WARN。 */
    private const val HOST_VERSION_BASELINE = "14.0.37.301"

    // ---------------- R4 不请求广告 + R1 不展示安装页广告 ----------------

    /**
     * 广告「不请求 + 不展示」双闸（源头优先）：
     *
     * - **请求条件门** `lt1.b(String)`：`lt1` 是 `wt1` 接口唯一实现，`zv1.m`（V4）与 `mt1.m`（V3）都用它判
     *   「是否支持广告请求」。恒 false ⇒ 走宿主自带 early-return（`this.c.put(错误码)` + `vt1.d(code,..)`），
     *   请求根本不发出、且 presenter 拿到完整 errorCode 回调 —— 比直接阻断 `m()` 更安全（不会挂起等待）。
     * - **响应数据源** `AdRespV4.getAdAppList()`：恒空表 ⇒ 即便存在缓存响应，渲染链 `v1()` 也走宿主自带的
     *   「ad is empty after filter」分支（带正常上报），不渲染任何广告卡片。属"源头裁剪"，非视图隐藏。
     */
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

    // ---------------- 挂载执行：本目标私有任务定义（重试/汇总/解析在共享 [MountEngine]） ----------------

    /**
     * 挂载任务类型：[CONST] 常量短路（命中即返回强制值，不调原方法）；
     * [BLOCK] void 空实现阻断（返回 null = 不调原方法，libxposed 语义，AI-lsposed-temlate SKILL.md:102 实证）。
     */
    private enum class MountKind { CONST, BLOCK }

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

    /** 单个挂载任务执行（解析/重试/汇总在共享 [engine]；本函数只做 kind 分派的 intercept 构造）。 */
    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result {
        val method = engine.resolveTask(task, module, loader)
            ?: return MountEngine.Result.NOT_RESOLVED
        return runCatching {
            val builder = module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(task.id)
            when (task.kind) {
                // 常量短路：命中即返回强制值（List 类值每次新建实例，避免共享可变集合被调用方改写）
                MountKind.CONST -> builder.intercept { chain ->
                    logJudge(module, task)
                    val v = task.value
                    if (v is Collection<*>) {
                        // 集合类强制值每次新建实例（避免共享可变集合被调用方改写）
                        ArrayList<Any?>(v.size).apply { addAll(v) }
                    } else {
                        v
                    }
                }
                // void 空实现阻断（不调原方法）
                MountKind.BLOCK -> builder.intercept { chain ->
                    logJudge(module, task)
                    null
                }
            }
        }.fold(
            onSuccess = {
                if (MountEngine.DEBUG_LOGGING) {
                    val verb = when (task.kind) {
                        MountKind.CONST -> "常返"
                        MountKind.BLOCK -> "空实现阻断"
                    }
                    module.log(Log.INFO, MountEngine.TAG, "hook 已挂载: ${task.description} → $verb ${task.value ?: "null"}")
                }
                MountEngine.Result.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, MountEngine.TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountEngine.Result.FAILED
            },
        )
    }

    /** 判据日志节流（每 task 前 2 次；真机核对"清净化已生效"用，不受 DEBUG_LOGGING 门控）。 */
    private val judgeLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun logJudge(module: XposedModule, task: MountTask) {
        val n = judgeLogCount.merge(task.id, 1) { a, b -> a + b } ?: 1
        if (n <= 2) {
            module.log(Log.INFO, MountEngine.TAG, "installer-hook 生效: ${task.id} — ${task.description}")
        }
    }
}
