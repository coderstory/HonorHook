package top.honorhook.xposed.target

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import top.honorhook.data.KEY_F6_INSTALLER_PURIFY_ENABLED
import top.honorhook.data.KEY_HOOK_ENABLED
import top.honorhook.xposed.MethodResolver
import top.honorhook.xposed.RemotePrefsHolder

/**
 * 荣耀安装器目标（F6 安装器净化）——移植自 reference/HonorInstallerPurify v4.0 单文件 MainHook.java
 * （旧版 Xposed API → libxposed 102 改写，对应关系见 docs/honor-plugins-integration.md §5.4）。
 *
 * 真机基线校验（2026-10-09，system-files/installer-jadx，目标 = 真机实际安装的软件包安装程序 14.0.37.301）：
 * 插件 8 组 hook 逐点对照反编译产物，**全部命中、签名一致**（类名混淆在真机版本与插件适配版本同为
 * ly1/qf2/r12/z62/g82/sy1/oy1/jz1/kt1/ef2，非混淆类包名 com.hihonor.packageinstaller.* 亦存在）：
 * - F6-1 检测总入口 ly1.h(boolean, sy1)：ly1.java:90；静态结果工厂 ly1.a(boolean) L21、isHn 读点 d() L75、
 *   回调 sy1.b(oy1, jz1)（sy1.java:8）、jz1 接口（jz1.java，单方法 a(Drawable)，Proxy 置空即可）——与插件完全一致；
 * - F6-2 指纹 qf2.g(Context, qf2$a)：qf2.java:93（static）；回调接口 qf2$a = qf2 内接口 a，含 c() 与 a(boolean)
 *   （qf2.java:19-23）——与插件 cb.c()/cb.a(true) 调用一致；qf2.h(Context, a, String) L113 为设置页入口，刻意不挂；
 * - F6-3 安全模式卡片 r12.j(CardTipsRecord, String, boolean, String)：r12.java:213（static boolean）；
 *   r12.s(PackageInstallerActivity, RbiTempBean)：r12.java:384（static void）——均存在；
 * - F6-4 禁网 Socket.connect：java.net 平台类，恒存在（bootclasspath）；安装器进程内一切联网短路；
 * - F6-5 清理缓存 z62.E(SafeModeCards)：z62.java:294（private boolean）——真机语义为"卡片数据非法"判定，
 *   构造器（L206）与 N(SafeModeCards)（L836）都在开头 `if (E(...)) return;`，恒返 true 使整个安全卡片系统
 *   （含"清理缓存/加速"卡）不初始化，与插件"屏蔽清理缓存提醒"意图一致且覆盖面更广；
 * - F6-6 广告推荐 AbstractAdBusinessPresenter.l(kt1, RbiTempBean, ef2, boolean)：AbstractAdBusinessPresenter.java:1803
 *   （public void，内部 zv1.a.m/mt1.t().m 发起广告请求，置空 = 不发起）；
 * - F6-7 结果横幅 PackageInstallerActivity.e2()（L2330，渲染 W0=view_stub_security）/k2()（L2512，渲染
 *   Y0=view_stub_security_safe_mode），字段 private View W0 L302 / Y0 L306——after 置 GONE 隐藏"未发现风险"横幅；
 * - F6-8 分流短路 g82.f(String×4, long, String, int, long, int, int, g82$d, Runnable)：g82.java:150（private void，
 *   12 参）；回调接口 d（二进制名 g82$d）含 default void a(DiversionResp)（g82.java:104-107）——反射调 d.a(null)
 *   直接走失败分支（f() 内部 isSupportHnCompat=false 时的原生路径），断网免死等 2s 超时；
 *   DiversionResp 存在（com/hihonor/packageinstaller/entity/resp/DiversionResp.java）。
 *
 * 未迁移项（以插件源码为准，docs §5.7 已实证）：
 * - "允许降级安装"：README 特性表声称存在但 v4.0 MainHook.java 无任何降级/系统框架 hook——不移植；
 * - "展示安装包信息卡"：v4.0 代码中同样不存在（信息卡为 v3.1 特性、4.0 重构已移除）——不移植；
 * - 历史兼容多版本分支：v4.0 源码全部为单路径精确类名 hook（无 MarketTamer 式弹性签名/多版本并存），
 *   无需裁剪；真机 14.0.37.301 与 MagicOS 11 插件适配版混淆类名一致，8 组全部直移。
 *
 * 与插件的两处行为差异（改写法依据 docs §5.4）：
 * - F6-4 禁网：插件用 before setThrowable(SocketException) 让 connect 抛异常；libxposed 102 无 setThrowable，
 *   拦截器抛异常在 PROTECTIVE 模式下的传播行为未经本地实证（参考项目均只用 PROTECTIVE 且拦截器不抛），
 *   故改用本项目已验证的"void 空实现阻断"语义（intercept 返回 null = 不调原方法）：connect 被短路跳过，
 *   后续读写必然 SocketException，网络同样全断；该语义与 F6-3b/挽留弹窗 BLOCK 同构，行为确定、无静默放行风险。
 * - F6-1/F6-2/F6-8 内部逻辑异常时回退 chain.proceed() 走原流程（插件是静默返回 null 可能卡转圈）——
 *   照本项目"安全默认：失败放行原逻辑"纪律（ThemeStoreHook routeActive/uri 读取失败同款语义）。
 *
 * 开关语义（照 HC 模式，与 ThemeStoreHook 同构）：挂载无条件（onPackageLoaded 时机），每个 intercept 内
 * **现读**远程 prefs（[KEY_HOOK_ENABLED] 总开关 × [KEY_F6_INSTALLER_PURIFY_ENABLED]）决定本次是否生效——
 * 总关 = 原逻辑放行；总开 × F6 开 = 短路/回调/隐藏生效。**F6 默认关**：跳过安全检测削弱恶意应用拦截，
 * 敏感功能，UI 圆点提示默认关闭（docs §5.2）。UI 切开关下一次调用即生效，无需重启进程。
 *
 * 挂载健壮性（照 ThemeStoreHook 模式）：类/方法未解析的挂载项收集，Handler postDelayed 约 500ms 重试一轮、
 * 再失败约 2s 最后一轮，仍失败 WARN 放弃；收尾始终输出 `HonorInstallerHook: installed N/M` 汇总。
 * **挂载成功与拦截触发均为始终输出日志（不受 debug 门控）**——真机从 LSPosed 日志（TAG HonorHook）过滤
 * "[installer]" 确认各 hook 命中（对应插件 "hook ok / skipped / bypassed" 行的定位作用）。
 */
object HonorInstallerHook : TargetHook {

    override val packageName = "com.android.packageinstaller"

    private const val PKG = "com.android.packageinstaller"
    private const val CLS_INSTALLER_ACTIVITY = "$PKG.PackageInstallerActivity"
    private const val CLS_AD_PRESENTER = "com.hihonor.packageinstaller.presenter.AbstractAdBusinessPresenter"
    private const val CLS_SECURITY_ENTRY = "ly1"
    private const val CLS_FINGERPRINT = "qf2"
    private const val CLS_SAFE_MODE = "r12"
    private const val CLS_CLEAN_CACHE = "z62"
    private const val CLS_DIVERSION = "g82"
    private const val CLS_DIVERSION_RESP = "com.hihonor.packageinstaller.entity.resp.DiversionResp"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // 注意：libxposed 102 的 PackageLoadedParam 提供 defaultClassLoader 与 applicationInfo（无 classLoader 属性；apkPath 供 DexKit 兜底，照 HC ScreenRecorderModule.kt:85）
        val loader = param.defaultClassLoader
        apkPath = param.applicationInfo?.sourceDir
        // prefs 不再一次性读取捕获：RemotePrefsHolder 进程级缓存，intercept 内现读、null 自动重试（此调用仅为预热缓存）
        RemotePrefsHolder.obtain(module)

        retryRounds = 0
        okCount = 0
        totalCount = 0
        failedIds.clear()

        // 挂载表 10 项（8 组 hook；安全模式卡片与结果横幅各 2 个挂载点）
        val pending = mutableListOf<MountTask>()
        pending += MountTask(
            kind = MountKind.SECURITY_BYPASS, className = CLS_SECURITY_ENTRY, methodName = "h", paramCount = 2,
            id = "honorhook:installer:ly1.h", description = "F6-1 联网安全检测总入口（ly1.h）→ 官方免检测结果工厂 + 主线程投递回调",
        )
        pending += MountTask(
            kind = MountKind.FINGERPRINT_BYPASS, className = CLS_FINGERPRINT, methodName = "g", paramCount = 2,
            id = "honorhook:installer:qf2.g", description = "F6-2 安装前指纹验证入口（qf2.g）→ 直接回调验证成功",
        )
        pending += MountTask(
            kind = MountKind.CONST, className = CLS_SAFE_MODE, methodName = "j", paramCount = 4,
            value = false, id = "honorhook:installer:r12.j", description = "F6-3a 安全模式卡片显示决策（r12.j）→ 恒返 false",
        )
        pending += MountTask(
            kind = MountKind.BLOCK, className = CLS_SAFE_MODE, methodName = "s", paramCount = 2,
            id = "honorhook:installer:r12.s", description = "F6-3b 安全模式底部弹窗（r12.s）→ 空实现阻断",
        )
        pending += MountTask(
            kind = MountKind.BLOCK, className = "java.net.Socket", methodName = "connect", paramCount = 2,
            id = "honorhook:installer:Socket.connect", description = "F6-4 安装器进程内禁网（Socket.connect 短路，一切联网失败）",
        )
        pending += MountTask(
            kind = MountKind.CONST, className = CLS_CLEAN_CACHE, methodName = "E", paramCount = 1,
            value = true, id = "honorhook:installer:z62.E", description = "F6-5 安全卡片系统初始化闸门（z62.E）→ 恒返 true（含'清理缓存'提醒不出现）",
        )
        pending += MountTask(
            kind = MountKind.CONST, className = CLS_AD_PRESENTER, methodName = "l", paramCount = 4,
            id = "honorhook:installer:adPresenter.l", description = "F6-6 广告推荐条请求入口（AbstractAdBusinessPresenter.l）→ 置空，不发起广告请求",
        )
        pending += MountTask(
            kind = MountKind.HIDE_FIELD_AFTER, className = CLS_INSTALLER_ACTIVITY, methodName = "e2", paramCount = 0,
            value = "W0", id = "honorhook:installer:PIA.e2", description = "F6-7a '未发现风险'横幅（普通样式，e2 → 字段 W0）渲染后 GONE",
        )
        pending += MountTask(
            kind = MountKind.HIDE_FIELD_AFTER, className = CLS_INSTALLER_ACTIVITY, methodName = "k2", paramCount = 0,
            value = "Y0", id = "honorhook:installer:PIA.k2", description = "F6-7b '未发现风险'横幅（安全模式样式，k2 → 字段 Y0）渲染后 GONE",
        )
        pending += MountTask(
            kind = MountKind.DIVERSION_BYPASS, className = CLS_DIVERSION, methodName = "f", paramCount = 12,
            id = "honorhook:installer:g82.f", description = "F6-8 分流请求（g82.f）→ 反射回调 d.a(null) 直接走失败分支（断网免死等 2s）",
        )
        totalCount = pending.size

        attemptMount(pending, module, loader)
    }

    /** 挂载项执行结果：[NOT_RESOLVED] 类/方法未解析到——可随类加载重试；[FAILED] hook 挂载异常——重试无意义。 */
    private enum class MountResult { OK, FAILED, NOT_RESOLVED }

    /**
     * 挂载任务类型：
     * [CONST] 常量短路（返回常值，不调原方法）；
     * [BLOCK] void 空实现阻断（返回 null = 不调原方法，libxposed 语义：void 方法拦截器返回 null 即跳过原文，
     *   AI-lsposed-temlate SKILL.md:102 实证）；
     * [HIDE_FIELD_AFTER] 先 proceed 走原方法，再反射读 thisObject 的 [value] 字段（View）置 GONE（结果横幅）；
     * [SECURITY_BYPASS] 联网安全检测总入口：官方免检测结果工厂 + Proxy 回调 + 主线程投递（照插件 L144-199）；
     * [FINGERPRINT_BYPASS] 安装前指纹入口：直接回调 c() + a(true)（照插件 L201-224）；
     * [DIVERSION_BYPASS] 分流请求短路：反射调第 11 参回调的 a(DiversionResp)（照插件 L38-58）。
     */
    private enum class MountKind { CONST, BLOCK, HIDE_FIELD_AFTER, SECURITY_BYPASS, FINGERPRINT_BYPASS, DIVERSION_BYPASS }

    /** 一次挂载任务的完整描述（类/方法未解析时保留任务本体供懒加载重试）。 */
    private class MountTask(
        val kind: MountKind,
        val className: String,
        val methodName: String,
        val paramCount: Int,
        val id: String,                 // 幂等 ID（框架用它防重复/日志标识）
        val description: String,
        val value: Any? = null,         // CONST: 常返值；HIDE_FIELD_AFTER: 字段名
    )

    /** 重试轮次（已完成的重试数；第 1 轮 500ms 后、第 2 轮 2s 后；照 HC installCustomTileHooks postDelayed 模式）。 */
    private var retryRounds = 0
    private var okCount = 0
    private var totalCount = 0
    private val failedIds = mutableListOf<String>()
    /** 目标 APK 路径（install 取 sourceDir；MethodResolver 第二轨 DexKit 兜底用，null 时跳过）。 */
    private var apkPath: String? = null

    private const val MAX_RETRY_ROUNDS = 2
    private const val RETRY_DELAY_MS = 500L
    private const val RETRY_FINAL_DELAY_MS = 2000L

    /**
     * 挂载一轮 pending 任务（HC postDelayed 懒加载重试模式，installCustomTileHooks L408-416 同构）：
     * 成功/挂载失败的从本轮移除，类/方法未解析的保留 → 全部挂完即输出汇总（installed N/M）；
     * 仍有未解析 → Handler postDelayed 500ms 重试一轮、再失败 2s 最后一轮，仍失败 WARN 放弃并输出汇总。
     */
    private fun attemptMount(
        pending: MutableList<MountTask>,
        module: XposedModule,
        loader: ClassLoader,
    ) {
        val unresolved = mutableListOf<MountTask>()
        for (task in pending) {
            when (mountOne(task, module, loader)) {
                MountResult.OK -> okCount++
                MountResult.FAILED -> failedIds += task.description
                MountResult.NOT_RESOLVED -> unresolved += task
            }
        }
        if (unresolved.isEmpty()) {
            logSummary(module)
        } else if (retryRounds >= MAX_RETRY_ROUNDS) {
            failedIds += unresolved.map { it.description }
            module.log(
                Log.WARN, TAG,
                "部分 hook 最终未挂载（类/方法不存在，已重试 $MAX_RETRY_ROUNDS 轮，放弃）: " +
                    unresolved.joinToString { it.description },
            )
            logSummary(module)
        } else {
            retryRounds++
            val delay = if (retryRounds == 1) RETRY_DELAY_MS else RETRY_FINAL_DELAY_MS
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { attemptMount(unresolved, module, loader) }
                    .onFailure { t -> module.log(Log.ERROR, TAG, "懒加载重试挂载异常", t) }
            }, delay)
        }
    }

    /** 单个挂载任务执行；失败分级：未解析（可随类加载重试）vs hook 异常（永久，重试无意义）。 */
    private fun mountOne(
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountResult {
        // 第一轨直连失败 → MethodResolver 第二轨 DexKit 兜底（对抗混淆漂移；两轨全失败归 NOT_RESOLVED 懒加载重试）
        val method = findMethod(loader, task.className, task.methodName, task.paramCount)
            ?: MethodResolver.resolveMethod(loader, task.className, task.methodName, task.paramCount, apkPath = apkPath)
        if (method == null) {
            module.log(Log.WARN, TAG, "hook 未挂载（类/方法不存在，版本差异，将重试）: ${task.description}")
            return MountResult.NOT_RESOLVED
        }
        return runCatching {
            val builder = module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(task.id)
            builder.intercept { chain -> interceptBody(chain, module, loader, task) }
        }.fold(
            onSuccess = {
                // 挂载成功日志**始终输出**（不受 debug 门控）——真机验证凭它确认命中（对应插件 "hook ... ok" 行）
                module.log(Log.INFO, TAG, "[installer] hook 已挂载: ${task.id}（${task.description}）")
                MountResult.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountResult.FAILED
            },
        )
    }

    /** 挂载汇总（始终输出，不受 debug 门控；照 Rain "installed N/M resolved hook(s)" + ThemeStoreHook 模式）。 */
    private fun logSummary(module: XposedModule) {
        val failurePart = if (failedIds.isEmpty()) "" else " — 失败: ${failedIds.joinToString()}"
        module.log(
            if (failedIds.isEmpty()) Log.INFO else Log.WARN,
            TAG,
            "HonorInstallerHook: installed $okCount/$totalCount$failurePart",
        )
    }

    // ---------------- 拦截器实现（每个 intercept 现读开关；触发日志始终输出） ----------------

    private fun interceptBody(
        chain: XposedInterface.Chain,
        module: XposedModule,
        loader: ClassLoader,
        task: MountTask,
    ): Any? {
        // 每个 intercept 现读远程 prefs（RemotePrefsHolder：进程级缓存，null 时每次自动重读——防 install 期一次性读取失败后 null 冻结）
        val prefs = RemotePrefsHolder.obtain(module)
        if (!featureEnabled(prefs)) return chain.proceed()   // 总开关/F6 关 → 原逻辑放行
        return when (task.kind) {
            // 常量短路：路由激活 → 直接返回 value（不调原方法）
            MountKind.CONST -> {
                module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 常返 ${task.value}")
                task.value
            }
            // void 空实现阻断（F6-3b 底部弹窗 / F6-4 禁网）：不调原方法
            MountKind.BLOCK -> {
                module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → ${if (task.className == "java.net.Socket") "联网已禁止" else "空实现阻断"}")
                null
            }
            // 结果横幅：先 proceed 走原渲染，再反射读字段置 GONE
            MountKind.HIDE_FIELD_AFTER -> {
                val result = chain.proceed()
                val fieldName = task.value as? String
                if (fieldName != null) {
                    val view = runCatching {
                        chain.thisObject?.javaClass?.getDeclaredField(fieldName)?.let { f ->
                            f.isAccessible = true
                            f.get(chain.thisObject) as? View
                        }
                    }.getOrNull()
                    if (view != null) {
                        view.visibility = View.GONE
                        module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 横幅字段 $fieldName 已置 GONE")
                    } else {
                        module.log(Log.WARN, TAG, "[installer] ${task.id} 横幅字段 $fieldName 非 View 或读取失败（本次未隐藏）")
                    }
                }
                result
            }
            // 联网安全检测总入口：构造官方免检测结果并主线程投递回调（插件 L144-199 直移；异常回退原流程）
            MountKind.SECURITY_BYPASS -> try {
                val callback = chain.args?.getOrNull(1)
                if (callback == null) {
                    module.log(Log.WARN, TAG, "[installer] ${task.id} 回调参数为空，回退原检测流程")
                    return chain.proceed()
                }
                val isHn = (chain.thisObject?.javaClass?.getMethod("d")?.invoke(chain.thisObject) as? Boolean) ?: false
                val ly1Class = chain.thisObject!!.javaClass
                val passResult = ly1Class.getMethod("a", Boolean::class.javaPrimitiveType).invoke(null, isHn)
                val oy1Class = loader.loadClass("oy1")
                val jz1Class = loader.loadClass("jz1")
                val jz1Proxy = Proxy.newProxyInstance(loader, arrayOf(jz1Class), InvocationHandler { proxy, m, mArgs ->
                    when (m.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy == mArgs?.firstOrNull()
                        "toString" -> "Jz1Noop"
                        else -> null
                    }
                })
                val deliver = callback.javaClass.getMethod("b", oy1Class, jz1Class)
                Handler(Looper.getMainLooper()).post {
                    runCatching { deliver.invoke(callback, passResult, jz1Proxy) }
                        .onFailure { t -> module.log(Log.WARN, TAG, "[installer] 检测结果投递失败", t) }
                }
                module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 免检测结果已主线程投递（isHn=$isHn）")
                null
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "[installer] ${task.id} 跳过逻辑异常，回退原检测流程", t)
                chain.proceed()
            }
            // 安装前指纹入口：直接回调验证成功（插件 L201-224 直移；异常回退原流程）
            MountKind.FINGERPRINT_BYPASS -> try {
                val cb = chain.args?.getOrNull(1)
                if (cb != null) {
                    cb.javaClass.getMethod("c").invoke(cb)
                    cb.javaClass.getMethod("a", Boolean::class.javaPrimitiveType).invoke(cb, true)
                    module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 指纹验证已直接回调成功")
                }
                null
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "[installer] ${task.id} 指纹跳过异常，回退原流程", t)
                chain.proceed()
            }
            // 分流请求短路：反射调第 11 参回调的 a(DiversionResp=null)（插件 L38-58 直移；异常回退原流程）
            MountKind.DIVERSION_BYPASS -> try {
                val dVar = chain.args?.getOrNull(10)
                if (dVar != null) {
                    val respClz = loader.loadClass(CLS_DIVERSION_RESP)
                    val m = dVar.javaClass.getMethod("a", respClz)
                    m.isAccessible = true
                    m.invoke(dVar, *arrayOfNulls<Any?>(1))
                    module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 分流已走失败分支（断网免死等 2s）")
                }
                null
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "[installer] ${task.id} 分流短路异常，回退原流程", t)
                chain.proceed()
            }
        }
    }

    /** 总开关现读（每次 intercept 调用）：prefs 获取失败时返回 false = 原逻辑放行（安全默认）。 */
    private fun hookEnabled(prefs: SharedPreferences?): Boolean = prefs?.getBoolean(KEY_HOOK_ENABLED, false) ?: false

    /** F6 功能位开关现读：总开关（上位语义：总关 = 全部放行）&& F6 独立开关（默认 false——安全敏感，默认关）。 */
    private fun featureEnabled(prefs: SharedPreferences?): Boolean =
        hookEnabled(prefs) && (prefs?.getBoolean(KEY_F6_INSTALLER_PURIFY_ENABLED, false) ?: false)

    /**
     * 第一轨：直连反射解析目标方法（HookEntry.resolveTargetMethod 第一轨等价物；真机 14.0.37.301 已对全部
     * 8 组目标类/方法实证存在）。类/方法不存在返回 null——mountOne 处接 MethodResolver 第二轨 DexKit 兜底
     * （对抗混淆漂移），两轨全失败归懒加载重试。按方法名 + 参数个数匹配（照旧版 findAndHookMethod 只匹配
     * 名称+参数类型、不匹配返回值的语义；各目标无同名同参个数的 overloading，实证见类注释）。
     */
    private fun findMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int,
    ): Method? = runCatching {
        loader.loadClass(className).declaredMethods.firstOrNull { m ->
            m.name == methodName && m.parameterCount == paramCount
        }
    }.getOrNull()

    private const val TAG = "HonorHook"
}