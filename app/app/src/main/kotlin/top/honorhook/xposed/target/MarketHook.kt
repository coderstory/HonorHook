package top.honorhook.xposed.target

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.ArrayList
import top.honorhook.data.KEY_F5_MARKET_ENABLED
import top.honorhook.data.KEY_HOOK_ENABLED
import top.honorhook.xposed.MethodResolver
import top.honorhook.xposed.RemotePrefsHolder

/**
 * 荣耀应用市场目标（F5 市场净化——核心组）——移植自 reference/HonorMarketTamer（API 102 改写，
 * 对应关系见 docs/honor-plugins-integration.md §5.4；旧版 XSP/conf 三级副本配置机制整体废弃，
 * 远程 prefs 现读替代）。
 *
 * 真机基线校验（2026-10-09，system-files/market-jadx，目标 = 真机实际安装的应用市场 16.1.9.302）：
 * 核心组 15 个挂载点逐点对照反编译产物（类存在性 + 方法名 + 参数个数），**全部命中**（见挂载表）。
 * 插件适配至 16.1.8.305，本版本对照结论：
 * - 开屏：v3.a#d(3)（v3/a.java:90）、utils.a#o(3)（utils/a.java:395，视图工厂，cached/v3/event 三路径
 *   共同终点）存在；CachedSplashScreenService#d(1)/e(0) 失效（外类已化名 cached.a，d/e 签名变
 *   2 参/3 参）、kg7.l / fg7.l 失效（kg7 已是 synthetic bridge lambda 类）——不迁（宁缺毋滥）；
 * - 弹窗：OperationDialog#j0(3)/i0(3) 失效（j0 变 2 参、i0 消失）；显示入口已改名 k0(3)
 *   （OperationDialog.java:334，签名与 305 版 j0 完全一致：FragmentActivity,OperationVO,Runnable，
 *   内部 b0(activity) 弹框）——以 k0 为 9.302 锚点（版本适配，同插件逐版本改名手法）；
 * - 推送：AppMarketPushMessageService#c(1)（c(ix4)，:28）、push.notification.a#a(3)
 *   （a(Context,ActivityPushNotificationService,boolean)，:46）、TopNotifyManager#trigger(1)
 *   （trigger(EVENT)，:1298）全部存在；TopNotifyManager#z(1)（:1039）低杠杆，二期候选；
 * - 自启：PowerConnectedReceiver#onReceive(2)（:108）、WifiStateChangeReceiver#onReceive(2)
 *   （:163，基类覆盖 8 个空子类）、BootController#J(3)（:283，W1/W2/W3 被动启动汇聚）、
 *   WakeMarketProvider#call(3)（:157）全部存在；onCreate(0)（:189）与 call 同 Provider，二期候选；
 * - 热词：CommonMainTitleView#A(5)（静态 A(view,List,String,String,String)，:372）存在；
 *   F()（:1063）为轮换出口双保险，A 置空列表后无数据可轮，二期候选；
 * - 工具页：ToolPageActivityEntryView#b(1)（b(String)，:76）存在；
 * - 推荐流（广告流）：MarketManageViewModel#n(4)（n(MutableLiveData,int,String,boolean)，:183，
 *   首屏/刷新/加载更多唯一网络出口）、MarketManageFragment#Z1(4)（Z1(BaseResp4参)，:1521，
 *   响应汇聚点；W1/V1 已变 1 参无关方法）、SearchActivationViewModel#o(6)
 *   （o(long,int,int,String,String,Continuation)，:1373，搜索发现页首屏+加载更多唯一出口）
 *   全部存在；BaseResp（setErrorCode:74/setData:70）与 MultiAssemblyDataResp
 *   （setAssemblyList:96/setAssemblyNewList:101/setAssemblyOffset:105）均存在；
 * - 搜索结果广告位（AssSearchResultAdapter#w1/G0 + CommAssAdapter#A0）：9.302 中已改名
 *   （现 B1(L,boolean)/L0(L,boolean)，归属未实证）——版本漂移，不迁（宁缺毋滥），二期；
 * - BaseUikitDialogFragment#c0(2)（桌面卡片推广/个性化广告弹窗）：9.302 变 1 参（c0(FragmentActivity)，:97）
 *   ——签名不匹配，二期；
 * - ActivityBlocker（37 组 Activity 屏蔽表）、健康传感器闸门、SystemUI 指纹震动：二期（红线范围）。
 *
 * 细分开关（28+）一期不迁移：F5 总开关开启即按 MarketTamer 推荐配置整体生效（docs §5.2），
 * 个别子开关如需细调，二期按需补键（intercept 现读，加键无成本）。
 *
 * 开关语义（照 HC 模式，与 HonorInstallerHook 同构）：挂载无条件（onPackageLoaded 时机），每个
 * intercept 现读远程 prefs（[KEY_HOOK_ENABLED] 总开关 × [KEY_F5_MARKET_ENABLED]）决定本次是否生效——
 * 总关 = 原逻辑放行。**F5 默认关**（改动面大：开屏/弹窗/推送/自启/推荐流，默认关，docs §5.2）。
 * UI 切开关下一次调用即生效，无需重启进程。
 *
 * 挂载健壮性（照 ThemeStoreHook 模式）：类/方法未解析的挂载项收集，Handler postDelayed 约 500ms
 * 重试一轮、再失败约 2s 最后一轮，仍失败 WARN 放弃；收尾始终输出 `MarketHook: installed N/M` 汇总。
 * 挂载成功与拦截触发均为始终输出日志（不受 debug 门控）——真机从 LSPosed 日志（TAG HonorHook）过滤
 * "[market]" 确认各 hook 命中（对应插件 "armed / suppressed" 行的定位作用）。
 */
object MarketHook : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"
    private const val CLS_SPLASH_V3 = "$PKG.main.splash.v3.a" // V3 开屏服务（Kotlin object）
    private const val CLS_SPLASH_VIEW_FACTORY = "$PKG.main.splash.utils.a" // 开屏广告视图工厂
    private const val CLS_OP_DIALOG = "$PKG.operation.ui.OperationDialog" // 运营弹窗
    private const val CLS_PUSH_SERVICE = "$PKG.base.support.push.honor.AppMarketPushMessageService"
    private const val CLS_PUSH_NOTIFY = "$PKG.base.support.push.notification.a"
    private const val CLS_TOP_NOTIFY = "$PKG.business.notification.topnotify.TopNotifyManager"
    private const val CLS_POWER_RECEIVER = "$PKG.receiver.PowerConnectedReceiver"
    private const val CLS_WIFI_RECEIVER = "$PKG.receiver.wifi.WifiStateChangeReceiver"
    private const val CLS_BOOT_CONTROLLER = "$PKG.boot.BootController"
    private const val CLS_WAKE_PROVIDER = "$PKG.boot.provider.WakeMarketProvider"
    private const val CLS_MAIN_TITLE_VIEW = "$PKG.widgets.CommonMainTitleView"
    private const val CLS_TOOLPAGE_ENTRY = "$PKG.operation.toolpage.ToolPageActivityEntryView"
    private const val CLS_MINE_VM = "$PKG.mine.viewmodel.MarketManageViewModel"
    private const val CLS_MINE_FRAG = "$PKG.mine.MarketManageFragment"
    private const val CLS_SEARCH_VM = "$PKG.search.model.SearchActivationViewModel"
    private const val CLS_BASE_RESP = "$PKG.network.base.BaseResp"
    private const val CLS_MULTI_ASSEMBLY_RESP = "$PKG.network.response.MultiAssemblyDataResp"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // libxposed 102 的 PackageLoadedParam 提供 defaultClassLoader 与 applicationInfo（apkPath 供 DexKit 兜底，照 HC ScreenRecorderModule.kt:85）
        val loader = param.defaultClassLoader
        apkPath = param.applicationInfo?.sourceDir
        // prefs 不再一次性读取捕获：RemotePrefsHolder 进程级缓存，intercept 内现读、null 自动重试（此调用仅为预热缓存）
        RemotePrefsHolder.obtain(module)

        retryRounds = 0
        okCount = 0
        totalCount = 0
        failedIds.clear()

        // 挂载表 15 项（核心组 15 个高价值挂载点；真机 16.1.9.302 逐点实证，见类注释）
        val pending = mutableListOf<MountTask>()
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_SPLASH_V3, methodName = "d", paramCount = 3,
            id = "honorhook:market:splash.v3.d",
            description = "开屏 V3 数据入口（v3.a#d(String,String,cont) → null，协程空分支原生收尾进主页）",
        )
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_SPLASH_VIEW_FACTORY, methodName = "o", paramCount = 3,
            id = "honorhook:market:splash.utils.o",
            description = "开屏广告视图工厂（utils.a#o(Activity,SplashBase,a) → null，cached/v3/event 三路径共同终点，this.m=null 无广告收尾）",
        )
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_OP_DIALOG, methodName = "k0", paramCount = 3,
            id = "honorhook:market:opDialog.k0",
            description = "运营弹窗显示入口（OperationDialog#k0(FragmentActivity,OperationVO,Runnable) → null；16.1.9.302 中 305 版 j0 改名 k0）",
        )
        pending += MountTask(
            kind = MountKind.PUSH_TYPE_AWARE, className = CLS_PUSH_SERVICE, methodName = "c", paramCount = 1,
            id = "honorhook:market:push.c",
            description = "营销推送到达入口（AppMarketPushMessageService#c → null；pushNotifyType 以 1 开头的更新族放行）",
        )
        pending += MountTask(
            kind = MountKind.NOTIFY_TYPE_AWARE, className = CLS_PUSH_NOTIFY, methodName = "a", paramCount = 3,
            id = "honorhook:market:notify.a",
            description = "推送→系统通知展示总入口（notification.a#a 3 参静态 → null；服务类型 D() 以 1 开头的更新族放行）",
        )
        pending += MountTask(
            kind = MountKind.NULL_IF_ARG_SUFFIX, className = CLS_TOP_NOTIFY, methodName = "trigger", paramCount = 1,
            id = "honorhook:market:topNotify.trigger",
            description = "顶部运营横幅（TopNotifyManager#trigger(EVENT) → null）",
            argIndex = 0, suffix = ".EVENT",
        )
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_POWER_RECEIVER, methodName = "onReceive", paramCount = 2,
            id = "honorhook:market:wake.power",
            description = "插电拉活+广告跟踪广播（PowerConnectedReceiver.onReceive → null）",
        )
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_WIFI_RECEIVER, methodName = "onReceive", paramCount = 2,
            id = "honorhook:market:wake.wifi",
            description = "WiFi 状态拉活广播（WifiStateChangeReceiver.onReceive → null，基类一个点覆盖 8 个空子类）",
        )
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_BOOT_CONTROLLER, methodName = "J", paramCount = 3,
            id = "honorhook:market:wake.boot",
            description = "被动启动总入口（BootController#J(mu,boolean,suspend) → null；W1/W2/W3 最终汇聚，不拉活）",
        )
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_WAKE_PROVIDER, methodName = "call", paramCount = 3,
            id = "honorhook:market:wake.providerCall",
            description = "外部应用 content:// 唤醒（WakeMarketProvider#call(String,String,Bundle) → null）",
        )
        pending += MountTask(
            kind = MountKind.ARG_EMPTY_LIST, className = CLS_MAIN_TITLE_VIEW, methodName = "A", paramCount = 5,
            id = "honorhook:market:rollword.A",
            description = "搜索框滚动热词数据入口（静态 A(view,List,String,String,String) → 热词列表换空，不再轮播）",
            argIndex = 1,
        )
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_TOOLPAGE_ENTRY, methodName = "b", paramCount = 1,
            id = "honorhook:market:toolpage.b",
            description = "工具页运营活动入口（ToolPageActivityEntryView#b(String) → null，视图停在构造器 GONE 态）",
        )
        pending += MountTask(
            kind = MountKind.NULL, className = CLS_MINE_VM, methodName = "n", paramCount = 4,
            id = "honorhook:market:feed.mineReqCore",
            description = "「我的」页推荐流请求核心（MarketManageViewModel#n(LiveData,int,String,boolean) → null，首屏/刷新/加载更多唯一网络出口，feed 整段不渲染）",
        )
        pending += MountTask(
            kind = MountKind.ARG_EMPTY_SUCCESS_RESP, className = CLS_MINE_FRAG, methodName = "Z1", paramCount = 4,
            id = "honorhook:market:feed.mineRespSink",
            description = "「我的」页推荐响应汇聚点（MarketManageFragment#Z1(BaseResp4参) → 成功空响应，走空数据分支干净收起）",
            argIndex = 0,
        )
        pending += MountTask(
            kind = MountKind.RESULT_ERROR_RESP, className = CLS_SEARCH_VM, methodName = "o", paramCount = 6,
            id = "honorhook:market:feed.searchSource",
            description = "搜索发现页推荐请求源头（SearchActivationViewModel#o(6参) → BaseResp errorCode=-1，零流量、协程立即完成）",
        )
        totalCount = pending.size

        attemptMount(pending, module, loader)
    }

    /** 挂载项执行结果：[NOT_RESOLVED] 类/方法未解析到——可随类加载重试；[FAILED] hook 挂载异常——重试无意义。 */
    private enum class MountResult { OK, FAILED, NOT_RESOLVED }

    /**
     * 挂载任务类型（策略对应插件各 Blocker 的「before 置 null / 改参」形态，docs §5.4）：
     * [NULL] 短路置 null（不调原方法）——开屏/弹窗/推送/自启/工具页/推荐流请求核心；
     * [NULL_IF_ARG_SUFFIX] 首参类型类名以 [suffix] 结尾时短路置 null（TopNotifyManager#trigger 的 EVENT 限定）；
     * [ARG_EMPTY_LIST] 把 [argIndex] 参替换为空 List 后放行（热词数据入口 → 原生 "data empty" 分支）；
     * [ARG_EMPTY_SUCCESS_RESP] 把 [argIndex] 参替换为 errorCode=0 的空成功响应后放行（推荐响应汇聚点
     *   → 原生空数据分支，避免 error 态残留「加载失败」占位）；
     * [RESULT_ERROR_RESP] 返回 errorCode=-1 的 BaseResp（请求源头 → 与原生 execute 失败分支同形态，
     *   下游全链路判空安全）；
     * [PUSH_TYPE_AWARE] 推送到达（c 1 参）：pushNotifyType 以 "1" 开头的更新族放行；类型探测失败亦放行
     *   （fail-open，宁放过不误杀更新通知），其余置 null；
     * [NOTIFY_TYPE_AWARE] 系统通知展示（a 3 参静态）：服务对象 D() 类型以 "1" 开头的更新族放行；
     *   类型探测失败亦放行（fail-open），其余置 null。
     */
    private enum class MountKind { NULL, NULL_IF_ARG_SUFFIX, ARG_EMPTY_LIST, ARG_EMPTY_SUCCESS_RESP, RESULT_ERROR_RESP, PUSH_TYPE_AWARE, NOTIFY_TYPE_AWARE }

    /** 一次挂载任务的完整描述（类/方法未解析时保留任务本体供懒加载重试）。 */
    private class MountTask(
        val kind: MountKind,
        val className: String,
        val methodName: String,
        val paramCount: Int,
        val id: String,                 // 幂等 ID（框架用它防重复/日志标识）
        val description: String,
        val argIndex: Int = 0,          // ARG_* 系列：替换第几参
        val suffix: String? = null,     // NULL_IF_ARG_SUFFIX：参数类名尾缀限定
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
     * 挂载一轮 pending 任务（HC postDelayed 懒加载重试模式，与 HonorInstallerHook 完全同构）：
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
                // 挂载成功日志**始终输出**（不受 debug 门控）——真机验证凭它确认命中（对应插件 "hookedAll ... armed" 行）
                module.log(Log.INFO, TAG, "[market] hook 已挂载: ${task.id}（${task.description}）")
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
            "MarketHook: installed $okCount/$totalCount$failurePart",
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
        if (!featureEnabled(prefs)) return chain.proceed()   // 总开关/F5 关 → 原逻辑放行
        return when (task.kind) {
            // 短路置 null：不调原方法（开屏/弹窗/自启/工具页/推荐流请求核心；对应插件 before setResult(null)）
            MountKind.NULL -> {
                module.log(Log.INFO, TAG, "[market] 拦截触发: ${task.id} → 置 null")
                null
            }
            // 首参类型限定短路：只有参数类名以 suffix 结尾才拦截（TopNotifyManager.trigger 只拦 EVENT 类型）
            MountKind.NULL_IF_ARG_SUFFIX -> {
                val arg = chain.args?.getOrNull(task.argIndex)
                if (arg != null && arg.javaClass.name.endsWith(task.suffix ?: "")) {
                    module.log(Log.INFO, TAG, "[market] 拦截触发: ${task.id} → 置 null（arg=${arg.javaClass.name}）")
                    null
                } else {
                    chain.proceed()
                }
            }
            // 参数替换空 List 后放行（热词数据入口：startHotWordsRolling 记 "data empty" 不再轮播）
            MountKind.ARG_EMPTY_LIST -> {
                val args = chain.args ?: return chain.proceed()
                if (args.size != task.paramCount) return chain.proceed()
                val newArgs = args.toTypedArray()
                newArgs[task.argIndex] = ArrayList<Any>()
                module.log(Log.INFO, TAG, "[market] 拦截触发: ${task.id} → 热词列表换空")
                chain.proceedWith(chain.thisObject, newArgs)
            }
            // 参数替换成功空响应后放行（推荐响应汇聚点：走原生 assemblyList.isEmpty() 空数据分支，
            // 不落 error 分支——避免「加载失败，点击重试」占位；构造失败兜底置 null）
            MountKind.ARG_EMPTY_SUCCESS_RESP -> {
                val args = chain.args ?: return chain.proceed()
                if (args.size != task.paramCount) return chain.proceed()
                val empty = buildEmptySuccessResp(loader)
                val newArgs = args.toTypedArray()
                newArgs[task.argIndex] = empty
                module.log(Log.INFO, TAG, "[market] 拦截触发: ${task.id} → 响应替换为成功空响应${if (empty == null) "（构造失败，置 null 兜底）" else ""}")
                chain.proceedWith(chain.thisObject, newArgs)
            }
            // 返回 errorCode=-1 的空 BaseResp（搜索发现页请求源头：与 App 自身 execute 失败分支同形态，
            // 下游 u1()/t()/A1 全链路判空安全；构造失败兜底 null）
            MountKind.RESULT_ERROR_RESP -> {
                val resp = buildErrorResp(loader)
                module.log(Log.INFO, TAG, "[market] 拦截触发: ${task.id} → 请求源头短路（BaseResp errorCode=-1${if (resp == null) "，构造失败返回 null" else ""}）")
                resp
            }
            // 推送到达入口（c 1 参）：类型探测失败（fail-open：宁可放过不可误杀更新通知）与更新族
            // （pushNotifyType 以 "1" 开头）放行，其余营销推送置 null
            MountKind.PUSH_TYPE_AWARE -> {
                val (type, action) = probePushType(chain.args?.getOrNull(0))
                if (type == null) {
                    module.log(Log.WARN, TAG, "[market] ${task.id} 类型不可读，放行（fail-open：宁可放过不可误杀更新通知）")
                    chain.proceed()
                } else if (type.startsWith("1")) {
                    module.log(Log.INFO, TAG, "[market] ${task.id} 放行（更新族）type=$type action=$action")
                    chain.proceed()
                } else {
                    module.log(Log.INFO, TAG, "[market] 拦截触发: ${task.id} → 推送已抑制 type=$type action=$action")
                    null
                }
            }
            // 系统通知展示总入口（a 3 参静态）：仅拦 ActivityPushNotificationService 通道；服务类型探测
            // 失败（fail-open）与更新族（D() 以 "1" 开头）放行，其余置 null
            MountKind.NOTIFY_TYPE_AWARE -> {
                val p1 = chain.args?.getOrNull(1)
                if (p1 == null || !p1.javaClass.name.endsWith("ActivityPushNotificationService")) {
                    return chain.proceed()
                }
                val type = runCatching { p1.javaClass.getMethod("D").invoke(p1)?.toString() }.getOrNull()
                if (type == null) {
                    module.log(Log.WARN, TAG, "[market] ${task.id} 服务类型不可读，放行（fail-open：宁可放过不可误杀更新通知）")
                    chain.proceed()
                } else if (type.startsWith("1")) {
                    module.log(Log.INFO, TAG, "[market] ${task.id} 放行（更新族）type=$type")
                    chain.proceed()
                } else {
                    module.log(Log.INFO, TAG, "[market] 拦截触发: ${task.id} → 通知展示已阻断 type=$type")
                    null
                }
            }
        }
    }

    /** 总开关现读（每次 intercept 调用）：prefs 获取失败时返回 false = 原逻辑放行（安全默认）。 */
    private fun hookEnabled(prefs: SharedPreferences?): Boolean = prefs?.getBoolean(KEY_HOOK_ENABLED, false) ?: false

    /** F5 功能位开关现读：总开关（上位语义：总关 = 全部放行）&& F5 独立开关（默认 false——改动面大，默认关）。 */
    private fun featureEnabled(prefs: SharedPreferences?): Boolean =
        hookEnabled(prefs) && (prefs?.getBoolean(KEY_F5_MARKET_ENABLED, false) ?: false)

    /** 反射探测推送消息的 pushNotifyType / action；字段/方法/JSON 串三层回退（插件 probePushType 直移）。 */
    private fun probePushType(msg: Any?): Pair<String?, String?> {
        var type: String? = null
        var action: String? = null
        if (msg == null) return null to null
        for (m in arrayOf("getPushNotifyType", "pushNotifyType")) {
            type = runCatching { msg.javaClass.getMethod(m).invoke(msg)?.toString() }.getOrNull()
            if (type != null) break
        }
        action = runCatching { msg.javaClass.getMethod("getAction").invoke(msg)?.toString() }.getOrNull()
        if (type == null) {
            // 参数可能是原始 JSON 字符串：用纯字符串查找提取字段
            val s = msg.toString()
            type = extractJsonString(s, "pushNotifyType") ?: extractJsonString(s, "PushNotifyType")
            if (action == null) action = extractJsonString(s, "action")
        }
        return type to action
    }

    /** 从 JSON 文本里取字段值（支持字符串与数值两种值形态；无转义处理，够用于日志与分类）。 */
    private fun extractJsonString(json: String, key: String): String? {
        val needle = "\"$key\":"
        val i = json.indexOf(needle)
        if (i < 0) return null
        // 跳过冒号后的空白，定位值起点
        var start = i + needle.length
        while (start < json.length && (json[start] == ' ' || json[start] == '\t' || json[start] == '\r' || json[start] == '\n')) start++
        if (start >= json.length) return null
        if (json[start] == '"') {
            // 字符串值：截到下一个 '"'
            val end = json.indexOf('"', start + 1)
            if (end < 0) return null
            return json.substring(start + 1, end)
        }
        // 数值/布尔值（如 "pushNotifyType":1001）：截到下一个 ',' 或 '}'，trim 返回
        var end = start
        while (end < json.length && json[end] != ',' && json[end] != '}') end++
        val raw = json.substring(start, end).trim()
        return raw.ifEmpty { null }
    }

    /** 构造 errorCode=-1 的 BaseResp（SearchActivationViewModel#o 请求源头短路；失败返回 null 由下游判空兜底）。 */
    private fun buildErrorResp(loader: ClassLoader): Any? = runCatching {
        val respClass = loader.loadClass(CLS_BASE_RESP)
        val resp = respClass.getDeclaredConstructor().newInstance()
        respClass.getMethod("setErrorCode", Int::class.javaPrimitiveType).invoke(resp, -1)
        resp
    }.getOrNull()

    /** 构造 errorCode=0、data=空 assemblyList 的成功响应（Z1 汇聚点走原生空数据分支；失败返回 null）。 */
    private fun buildEmptySuccessResp(loader: ClassLoader): Any? = runCatching {
        val respClass = loader.loadClass(CLS_BASE_RESP)
        val dataClass = loader.loadClass(CLS_MULTI_ASSEMBLY_RESP)
        val data = dataClass.getDeclaredConstructor().newInstance()
        dataClass.getMethod("setAssemblyList", List::class.java).invoke(data, ArrayList<Any>())
        dataClass.getMethod("setAssemblyNewList", List::class.java).invoke(data, ArrayList<Any>())
        dataClass.getMethod("setAssemblyOffset", Int::class.javaPrimitiveType).invoke(data, 0)
        val resp = respClass.getDeclaredConstructor().newInstance()
        respClass.getMethod("setErrorCode", Int::class.javaPrimitiveType).invoke(resp, 0)
        respClass.getMethod("setData", Any::class.java).invoke(resp, data)
        resp
    }.getOrNull()

    /**
     * 第一轨：直连反射解析目标方法（HookEntry.resolveTargetMethod 第一轨等价物；真机 16.1.9.302 已对全部
     * 15 个目标类/方法实证存在）。类/方法不存在返回 null——mountOne 处接 MethodResolver 第二轨 DexKit 兜底
     * （对抗 OTA 混淆漂移），两轨全失败归懒加载重试。按方法名 + 参数个数匹配（照旧版 findAndHookMethod
     * 语义；各目标无同名同参个数的 overloading，实证见类注释）。
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