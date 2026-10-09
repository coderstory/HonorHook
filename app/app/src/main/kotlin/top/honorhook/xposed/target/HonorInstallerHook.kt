package top.honorhook.xposed.target

import android.content.SharedPreferences
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
import top.honorhook.xposed.LogRing
import top.honorhook.xposed.MethodResolver
import top.honorhook.xposed.MountScheduler
import top.honorhook.xposed.RemotePrefsHolder

/**
 * 荣耀安装器目标（F6 安装器净化）——移植自 reference/HonorInstallerPurify v4.0 MainHook.java
 * （Xposed API → libxposed 102 改写，对应关系 docs/honor-plugins-integration.md §5.4）。
 *
 * 真机基线实证（2026-10-09，system-files/installer-jadx，目标 14.0.37.301）：8 组 hook 逐点对照
 * 反编译产物**全部命中、签名一致**（混淆类名 ly1/qf2/r12/z62/g82/sy1/oy1/jz1/kt1/ef2 与插件
 * 适配版本一致）：F6-1 ly1.h(boolean,sy1)（ly1.java:90，结果工厂 ly1.a L21、isHn d() L75、回调
 * sy1.b(oy1,jz1)，jz1 单方法 a(Drawable)）；F6-2 qf2.g(Context,qf2$a)（qf2.java:93，回调 c()/a(boolean)，
 * :19-23；qf2.h L113 设置页入口刻意不挂）；F6-3 r12.j(:213 静态 boolean)/r12.s(:384 静态 void)；
 * F6-4 Socket.connect：java.net 平台类（bootclasspath）恒存在；
 * F6-5 z62.E(SafeModeCards)（:294；构造器 L206 与 N(:836) 开头皆 `if (E(...)) return;`）真机语义＝"卡片
 * 数据非法"判定，恒返 true 使整个安全卡片系统不初始化（覆盖面广于插件的"清理缓存提醒"）；F6-6 AbstractAdBusinessPresenter.l(kt1,RbiTempBean,ef2,boolean)（:1803，
 * 置空＝不发起广告请求）；F6-7 PackageInstallerActivity.e2()（:2330，字段 W0 L302）/k2()（:2512，
 * 字段 Y0 L306）after 置 GONE；F6-8 g82.f(12 参)（:150，回调 d（g82$d）default a(DiversionResp)
 * :104-107，反射调 a(null) 走失败分支，断网免死等 2s）。
 * 未迁移（插件 v4.0 代码不存在，docs §5.7 实证）：允许降级安装、展示安装包信息卡（v3.1 特性已移除）、
 * 历史多版本分支（v4.0 全为单路径精确类名 hook）。
 *
 * 与插件的两处行为差异（docs §5.4）：
 * - F6-4 禁网：插件 before setThrowable(SocketException)；libxposed 102 无 setThrowable 且拦截器抛异常
 *   在 PROTECTIVE 下传播未实证，改 "void 空实现阻断"（返回 null 不调原方法，后续读写必 SocketException，
 *   网络同样全断）——与 F6-3b/BLOCK 同构，行为确定、无静默放行风险。
 * - F6-1/F6-2/F6-8 内部逻辑异常回退 chain.proceed()（插件静默返回 null 可能卡转圈）——照本项目
 *   "失败放行原逻辑"纪律（ThemeStoreHook 同款）。
 *
 * 开关语义（照 HC 模式）：挂载无条件，intercept 现读远程 prefs（[KEY_HOOK_ENABLED]×
 * [KEY_F6_INSTALLER_PURIFY_ENABLED]），总关 = 原逻辑放行；**F6 默认关**（跳过安全检测削弱恶意应用
 * 拦截，安全敏感，UI 圆点提示默认关闭，docs §5.2）。UI 切开关即生效，无需重启进程。
 * 挂载健壮性：未解析项 MountScheduler 后台线程 500ms/2s 两轮重试，收尾输出 `installed N/M`；
 * 挂载与拦截日志始终输出（不受 debug 门控），真机从 LSPosed 日志（TAG HonorHook）过滤 "[installer]"
 * 确认命中（对应插件 "hook ok / skipped / bypassed" 行作用）。
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
        // libxposed 102 PackageLoadedParam：defaultClassLoader + applicationInfo（无 classLoader 属性；apkPath 供 DexKit 兜底，照 HC ScreenRecorderModule.kt:85）
        val loader = param.defaultClassLoader
        apkPath = param.applicationInfo?.sourceDir
        // prefs 不再一次性捕获：RemotePrefsHolder 进程级缓存，intercept 现读（此调用仅为预热）
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

    /** 结果：[NOT_RESOLVED] 类/方法未解析，可随类加载重试；[FAILED] 挂载异常，重试无意义。 */
    private enum class MountResult { OK, FAILED, NOT_RESOLVED }

    /**
     * 挂载任务类型（行为见 interceptBody 各分支）：
     * BLOCK 的 void 拦截器返回 null 即跳过原方法（libxposed 语义，AI-lsposed-temlate SKILL.md:102 实证）；
     * SECURITY_BYPASS 照插件 L144-199、FINGERPRINT_BYPASS 照 L201-224、DIVERSION_BYPASS 照 L38-58 直移。
     */
    private enum class MountKind { CONST, BLOCK, HIDE_FIELD_AFTER, SECURITY_BYPASS, FINGERPRINT_BYPASS, DIVERSION_BYPASS }

    private class MountTask(
        val kind: MountKind,
        val className: String,
        val methodName: String,
        val paramCount: Int,
        val id: String,
        val description: String,
        val value: Any? = null,
    )

    /** 重试轮次（第 1 轮 500ms、第 2 轮 2s；照 HC installCustomTileHooks postDelayed 模式）。 */
    private var retryRounds = 0
    private var okCount = 0
    private var totalCount = 0
    private val failedIds = mutableListOf<String>()
    /** APK 路径（install 取 sourceDir；MethodResolver 第二轨 DexKit 兜底用，null 跳过）。 */
    private var apkPath: String? = null

    private const val MAX_RETRY_ROUNDS = 2
    private const val RETRY_DELAY_MS = 500L
    private const val RETRY_FINAL_DELAY_MS = 2000L

    /** 挂载一轮 pending（HC installCustomTileHooks L408-416 模式；未解析项 500ms/2s 两轮重试后 WARN 放弃，收尾输出汇总）。 */
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
            // 挂载/重试/DexKit 扫描走 MountScheduler 后台线程，不占主线程（见 MountScheduler）
            MountScheduler.postDelayed(delay) {
                runCatching { attemptMount(unresolved, module, loader) }
                    .onFailure { t -> module.log(Log.ERROR, TAG, "懒加载重试挂载异常", t) }
            }
        }
    }

    private fun mountOne(
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountResult {
        // 第一轨直连失败 → 第二轨 DexKit 兜底（对抗混淆漂移）
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
                // 挂载成功日志始终输出（不受 debug 门控）——真机验证凭 [installer] 行确认命中
                module.log(Log.INFO, TAG, "[installer] hook 已挂载: ${task.id}（${task.description}）")
                MountResult.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountResult.FAILED
            },
        )
    }

    /** 挂载汇总（始终输出；照 Rain "installed N/M" + ThemeStoreHook 模式）。 */
    private fun logSummary(module: XposedModule) {
        val failurePart = if (failedIds.isEmpty()) "" else " — 失败: ${failedIds.joinToString()}"
        val msg = "HonorInstallerHook: installed $okCount/$totalCount$failurePart"
        module.log(
            if (failedIds.isEmpty()) Log.INFO else Log.WARN,
            TAG,
            msg,
        )
        // 汇总级双写进环形缓冲（含失败清单；单点"挂载失败"高频 WARN 不进，只进汇总级）
        LogRing.push(module, RING_TAG, msg)
    }

    // ---------------- 拦截器实现（每个 intercept 现读开关；触发日志始终输出） ----------------

    private fun interceptBody(
        chain: XposedInterface.Chain,
        module: XposedModule,
        loader: ClassLoader,
        task: MountTask,
    ): Any? {
        // 每个 intercept 现读远程 prefs（RemotePrefsHolder 进程级缓存，null 自动重读——防 install 期读取失败后 null 冻结）
        val prefs = RemotePrefsHolder.obtain(module)
        if (!featureEnabled(prefs)) return chain.proceed()
        return when (task.kind) {
            MountKind.CONST -> {
                module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 常返 ${task.value}")
                LogRing.push(module, RING_TAG, "[installer] 拦截触发: ${task.id} → 常返 ${task.value}")
                task.value
            }
            MountKind.BLOCK -> {
                val msg = "[installer] 拦截触发: ${task.id} → ${if (task.className == "java.net.Socket") "联网已禁止" else "空实现阻断"}"
                module.log(Log.INFO, TAG, msg)
                LogRing.push(module, RING_TAG, msg)
                null
            }
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
                        LogRing.push(module, RING_TAG, "[installer] 拦截触发: ${task.id} → 横幅字段 $fieldName 已置 GONE")
                    } else {
                        module.log(Log.WARN, TAG, "[installer] ${task.id} 横幅字段 $fieldName 非 View 或读取失败（本次未隐藏）")
                    }
                }
                result
            }
            // 官方免检测结果 + Proxy 回调投递（插件 L144-199 直移；异常回退原流程）
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
                // 回调投递走 MountScheduler 后台线程（不阻塞拦截器）
                MountScheduler.handler.post {
                    runCatching { deliver.invoke(callback, passResult, jz1Proxy) }
                        .onFailure { t -> module.log(Log.WARN, TAG, "[installer] 检测结果投递失败", t) }
                }
                module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 免检测结果已投递（isHn=$isHn）")
                LogRing.push(module, RING_TAG, "[installer] 拦截触发: ${task.id} → 免检测结果已投递（isHn=$isHn）")
                null
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "[installer] ${task.id} 跳过逻辑异常，回退原检测流程", t)
                chain.proceed()
            }
            // 指纹验证：直接回调 c() + a(true)（插件 L201-224 直移；异常回退原流程）
            MountKind.FINGERPRINT_BYPASS -> try {
                val cb = chain.args?.getOrNull(1)
                if (cb != null) {
                    cb.javaClass.getMethod("c").invoke(cb)
                    cb.javaClass.getMethod("a", Boolean::class.javaPrimitiveType).invoke(cb, true)
                    module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 指纹验证已直接回调成功")
                    LogRing.push(module, RING_TAG, "[installer] 拦截触发: ${task.id} → 指纹验证已直接回调成功")
                }
                null
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "[installer] ${task.id} 指纹跳过异常，回退原流程", t)
                chain.proceed()
            }
            // 分流短路：反射调回调 a(null) 走失败分支（插件 L38-58 直移；异常回退原流程）
            MountKind.DIVERSION_BYPASS -> try {
                val dVar = chain.args?.getOrNull(10)
                if (dVar != null) {
                    val respClz = loader.loadClass(CLS_DIVERSION_RESP)
                    val m = dVar.javaClass.getMethod("a", respClz)
                    m.isAccessible = true
                    m.invoke(dVar, *arrayOfNulls<Any?>(1))
                    module.log(Log.INFO, TAG, "[installer] 拦截触发: ${task.id} → 分流已走失败分支（断网免死等 2s）")
                    LogRing.push(module, RING_TAG, "[installer] 拦截触发: ${task.id} → 分流已走失败分支（断网免死等 2s）")
                }
                null
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "[installer] ${task.id} 分流短路异常，回退原流程", t)
                chain.proceed()
            }
        }
    }

    /** 总开关现读（每次 intercept）：prefs 获取失败 false = 原逻辑放行（安全默认）。 */
    private fun hookEnabled(prefs: SharedPreferences?): Boolean = prefs?.getBoolean(KEY_HOOK_ENABLED, false) ?: false

    /** F6 功能位现读：总开关 && F6 独立开关（默认 false——安全敏感，默认关）。 */
    private fun featureEnabled(prefs: SharedPreferences?): Boolean =
        hookEnabled(prefs) && (prefs?.getBoolean(KEY_F6_INSTALLER_PURIFY_ENABLED, false) ?: false)

    /**
     * 第一轨：直连反射解析（HookEntry 第一轨等价物；真机 14.0.37.301 已实证 8 组目标存在）。失败归
     * NOT_RESOLVED 懒加载重试；按方法名+参数个数匹配（照旧版 findAndHookMethod；无同名同参 overloading）。
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

    /** LogRing 环形缓冲行内短标识（UI 日志页按 tag 归类展示）。 */
    private const val RING_TAG = "installer"

    private const val TAG = "HonorHook"
}