package top.honorhook.xposed.core

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/** 各目标 hook 共享的挂载基建：懒加载重试、失败分级、统一解析、汇总日志。 */
class MountEngine(private val hookName: String, private val hostPackage: String) {

    /** 失败分级：[FAILED] hook 挂载异常（重试无意义）；[NOT_RESOLVED] 类/方法（或依赖值）未解析（可随类加载重试）。 */
    enum class Result { OK, FAILED, NOT_RESOLVED }

    /** DexKit 兜底特征声明 —— 用于**混淆名目标**（类名/方法名随时可能被 R8 重映射）。 */
    class DexHint(
        val strings: List<String>? = null,
        val paramTypes: List<String>? = null,
        val classStrings: List<String>? = null,
        val classFieldTypes: List<String>? = null,
    )

    /** 各目标挂载任务须实现的最小协议（kind 枚举 / 路由条件等目标私有字段留在目标侧）。 */
    interface Task {
        /** hook 稳定标识（`setId` 用；`域:类.方法` 命名）。 */
        val id: String
        val className: String
        val methodName: String
        val paramCount: Int
        val returnType: String
        /** 人类可读描述（判据日志与失败清单用；含功能分组标注如 "F2 A1"）。 */
        val description: String
        /** 功能点 id（如 "A1"）；"" = 探针/载体类，不参与通路汇总。 */
        val group: String get() = ""
        /** DexKit 兜底特征（混淆名目标用；null = 仅直连轨）。 */
        val dex: DexHint? get() = null
        /** 挂载前惰性解析强制值（依赖目标类加载，如枚举常量）；返回 null = 未就绪 → [Result.NOT_RESOLVED] 重试。 */
        val resolveValue: ((ClassLoader) -> Any?)? get() = null
    }

    /** 共享零逻辑任务（替代各 target 手写 private MountTask）。 */
    class SimpleTask(
        override val id: String,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val description: String,
        override val group: String = "",
        override val dex: DexHint? = null,
        override val resolveValue: ((ClassLoader) -> Any?)? = null,
    ) : Task

    private var retryRounds = 0
    private var okCount = 0
    private var totalCount = 0
    private val failedIds = mutableListOf<String>()
    /** 通路汇总（task.group 非空才计入；总数只在首轮记，与 [totalCount] 同口径——重试轮只传未解析子列表）。 */
    private val groupTotal = LinkedHashMap<String, Int>()
    private val groupOk = HashMap<String, Int>()
    /** 全死通路 ERROR 行的失败明细（group → 失败任务描述）。 */
    private val groupFailed = HashMap<String, MutableList<String>>()
    /** 判据日志节流计数（task.id → 已打印次数）。 */
    private val judgeCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** 回调异常 WARN 节流计数（task.id → 已打印次数；独立于 [judgeCount]，判据节流是"命中"口径，这里是"异常"口径）。 */
    private val callbackWarnCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** 目标 APK 磁盘路径（[begin] 期写入，[MethodResolver] 第二轨 DexKit 兜底用；null 时跳过）。 */
    private var apkPath: String? = null
    /** hook 编写时的宿主版本基线（[begin] 期写入；非空则首轮挂载前做一次版本自检）。 */
    private var expectedHostVersion: String? = null
    private var hostVersionChecked = false
    /** 实测宿主版本（延后自检拿到；汇总行用它标注，一眼看出版本错位）。 */
    private var actualVersion: String? = null

    /** install() 时调用：写入 APK 路径与版本基线并重置计数（防同进程重复 install 计数累积）。 */
    fun begin(apkPath: String?, expectedHostVersion: String? = null) {
        this.apkPath = apkPath
        this.expectedHostVersion = expectedHostVersion
        retryRounds = 0
        okCount = 0
        failedIds.clear()
        groupTotal.clear()
        groupOk.clear()
        groupFailed.clear()
        judgeCount.clear()
        callbackWarnCount.clear()
    }

    /**
     * 挂载一轮 pending（HC installCustomTileHooks postDelayed 懒加载重试同款）。
     * [mountOne] 由目标侧提供（各 kind 的 intercept 逻辑），返回 [Result] 分级驱动重试。
     * 泛型 [T] 保留目标侧 [Task] 具体类型，免回调强转。
     */
    fun <T : Task> attemptMount(
        pending: List<T>,
        module: XposedModule,
        loader: ClassLoader,
        mountOne: (T) -> Result,
    ) {
        // 首轮挂载由各 hook 的 install() 在 onPackageLoaded 线程（宿主主线程）同步触发；直连 miss 后
        // MethodResolver 第二轨会同步做 DexKit 磁盘扫描（秒级）。失效是常态 ⇒ DexKit 兜底是常态路径
        // ⇒ 宿主主线程零同步磁盘 IO 是验收不变量：非挂载线程一律转投 MountScheduler 后台执行。
        // 重试轮本就在 scheduler 线程（递归自然 inline），这里只在首轮命中。
        if (!MountScheduler.handler.looper.isCurrentThread) {
            MountScheduler.postDelayed(0) {
                runCatching { attemptMount(pending, module, loader, mountOne) }
                    .onFailure { t -> module.log(Log.ERROR, TAG, "$hookName 挂载调度异常", t) }
            }
            return
        }
        // 自检延后到后台线程：PackageLoadedParam 时机 Application 尚未创建，直查必拿 null；
        // 挂载首轮全成功时无重试轮，故不能依赖重试驱动（hostVersionChecked 未置位但也没有下一轮）。
        MountScheduler.postDelayed(1000) {
            runCatching { checkHostVersionOnce(module) }
        }
        // 只在首轮记总数：重试轮传入的是 unresolved 子列表，若逐轮覆盖，判据行会变成
        // "installed 累计成功数/本轮待挂数"（okCount 跨轮累计），M 失去意义。
        if (retryRounds == 0) {
            totalCount = pending.size
            for (task in pending) {
                if (task.group.isNotEmpty()) groupTotal[task.group] = (groupTotal[task.group] ?: 0) + 1
            }
        }
        val unresolved = mutableListOf<T>()
        for (task in pending) {
            // per-task try/catch 隔离：单任务异常不阻断同批其他任务，汇总行始终输出
            val result = runCatching { mountOne(task) }.getOrElse {
                module.log(Log.ERROR, TAG, "$hookName 挂载异常（任务隔离）: ${task.description}", it)
                Result.FAILED
            }
            when (result) {
                Result.OK -> {
                    okCount++
                    if (task.group.isNotEmpty()) groupOk[task.group] = (groupOk[task.group] ?: 0) + 1
                }
                Result.FAILED -> {
                    failedIds += task.description
                    if (task.group.isNotEmpty()) groupFailed.getOrPut(task.group) { mutableListOf() }.add(task.description)
                }
                Result.NOT_RESOLVED -> unresolved += task
            }
        }
        if (unresolved.isEmpty()) {
            logSummary(module)
            return
        }
        if (retryRounds >= MAX_RETRY_ROUNDS) {
            unresolved.forEach { u ->
                if (u.group.isNotEmpty()) groupFailed.getOrPut(u.group) { mutableListOf() }.add(u.description)
            }
            failedIds += unresolved.map { it.description }
            module.log(
                Log.WARN, TAG,
                "$hookName 部分 hook 最终未挂载（类/方法不存在，已重试 $MAX_RETRY_ROUNDS 轮，放弃）: " +
                    unresolved.joinToString { it.description },
            )
            logSummary(module)
            return
        }
        retryRounds++
        val delay = if (retryRounds == 1) RETRY_DELAY_MS else RETRY_FINAL_DELAY_MS
        MountScheduler.postDelayed(delay) {
            runCatching { attemptMount(unresolved, module, loader, mountOne) }
                .onFailure { t -> module.log(Log.ERROR, TAG, "$hookName 懒加载重试挂载异常", t) }
        }
    }

    /**
     * 统一方法解析：① [Task.resolveValue] 惰性值（依赖类加载，未就绪 → 重试）；
     * ② [MethodResolver.resolveMethod] 双轨（直连反射 + DexKit 特征兜底）。
     * 返回 `null` = [Result.NOT_RESOLVED]（标准 WARN 日志已打）。
     */
    fun resolveTask(task: Task, module: XposedModule, loader: ClassLoader): Method? {
        // resolveValue 是目标侧惰性 lambda，可能抛异常（如目标类未加载时枚举反射失败）——那本质是
        // 依赖项未就绪（NOT_RESOLVED，可随类加载重试），不能冒泡成挂载异常 → FAILED（重试无意义）。
        task.resolveValue?.let { resolveValue ->
            val resolved = runCatching { resolveValue(loader) }.getOrElse {
                module.log(Log.WARN, TAG, "hook 未挂载（依赖项未解析，将重试）: ${task.description} — ${it.message}")
                return null
            }
            if (resolved == null) {
                module.log(Log.WARN, TAG, "hook 未挂载（依赖项未解析，将重试）: ${task.description}")
                return null
            }
        }
        val method = MethodResolver.resolveMethod(
            loader, task.className, task.methodName, task.paramCount, task.returnType, apkPath,
            task.dex?.strings, task.dex?.paramTypes, task.dex?.classStrings, task.dex?.classFieldTypes,
        )
        if (method == null) {
            module.log(Log.WARN, TAG, "hook 未挂载（类/方法不存在，版本差异，将重试）: ${task.description}")
        }
        return method
    }

    /** 挂载汇总始终输出，不受 [DEBUG_LOGGING] 门控（Rain "installed N/M" 汇总行模式，真机判据）。 */
    fun logSummary(module: XposedModule) {
        val failurePart = if (failedIds.isEmpty()) "" else " — 失败: ${failedIds.joinToString()}"
        val hostPart = actualVersion?.let { "（宿主 $it）" } ?: ""
        module.log(
            if (failedIds.isEmpty()) Log.INFO else Log.WARN,
            TAG,
            "$hookName: installed $okCount/$totalCount$hostPart$failurePart",
        )
        // 通路汇总（存在非空 group 才输出；单行紧凑 "; " 拼接）。判读单位 = 功能点至少一条通路存活，
        // 全灭（ok=0）才升级 ERROR 附失败明细——部分存活是失效常态，不制造噪音。
        if (groupTotal.isNotEmpty()) {
            module.log(
                Log.INFO, TAG,
                "$hookName 通路: " + groupTotal.entries.joinToString("; ") { (g, total) -> "$g: ${groupOk[g] ?: 0}/$total" },
            )
            groupTotal.forEach { (g, total) ->
                if ((groupOk[g] ?: 0) == 0) {
                    module.log(
                        Log.ERROR, TAG,
                        "$hookName 通路 $g 全灭 0/$total — 失败: ${groupFailed[g]?.joinToString() ?: "无明细"}",
                    )
                }
            }
        }
    }

    /**
     * 单任务挂载统一模板：解析（[resolveTask]）→ 挂 [PROTECTIVE] → 结果分级。
     *
     * 各目标只需提供 [intercept]（kind 分派逻辑），解析、异常兜底与分级由本模板统一负责：
     * 挂载异常记 WARN 并返回 [Result.FAILED]（重试无意义），不阻断同批其他任务。
     */
    fun mount(
        task: Task,
        module: XposedModule,
        loader: ClassLoader,
        intercept: XposedInterface.Hooker,
    ): Result {
        val method = resolveTask(task, module, loader) ?: return Result.NOT_RESOLVED
        // 回调期留痕（评审 #19）：PROTECTIVE 语义下框架吞 intercept 异常并回退原调用，模块侧零痕迹。
        // 这里只包 try/catch 记 WARN 后**原样 rethrow**——行为零变化；proceed 自身异常恒传播，不吞不包。
        val watched = XposedInterface.Hooker { chain ->
            try {
                intercept.intercept(chain)
            } catch (t: Throwable) {
                val n = callbackWarnCount.merge(task.id, 1) { a, b -> a + b } ?: 1
                if (n <= CALLBACK_WARN_PER_TASK) {
                    module.log(Log.WARN, TAG, "$hookName 回调异常（框架将回退原调用）: ${task.id} — ${t.message}", t)
                }
                throw t
            }
        }
        return runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(task.id)
                .intercept(watched)
        }.fold(
            onSuccess = { Result.OK },
            onFailure = { t ->
                module.log(Log.WARN, TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                Result.FAILED
            },
        )
    }

    /**
     * 判据日志（每任务前 [JUDGE_LOG_PER_TASK] 次；真机核对"功能已生效"用，不受 [DEBUG_LOGGING] 门控）。
     * @param prefix 目标侧日志前缀（如 `installer-hook`），沿用各历史口径便于按前缀过滤。
     * @param detail 目标侧补充数据（如"剔除副卡后 1/2 卡"），非空时附在行尾。
     */
    fun judge(module: XposedModule, task: Task, prefix: String, detail: String? = null) {
        val n = judgeCount.merge(task.id, 1) { a, b -> a + b } ?: 1
        if (n <= JUDGE_LOG_PER_TASK) {
            val suffix = if (detail.isNullOrEmpty()) "" else "（$detail）"
            module.log(Log.INFO, TAG, "$prefix 生效: ${task.id} — ${task.description}$suffix")
        }
    }

    /**
     * 宿主版本自检（每进程一次）：hook 挂载点以特定宿主版本反编译实证，宿主 OTA 后混淆名/签名可能漂移——
     * 版本不一致时打 WARN 提醒"判据以 installed N/M 为准"。拿不到 Context（宿主 Application 未起）则跳过。
     */
    private fun checkHostVersionOnce(module: XposedModule) {
        if (hostVersionChecked || expectedHostVersion == null) return
        runCatching {
            // PackageLoadedParam 时机 Application 往往尚未创建：拿不到就不置位，随下一轮重试再查
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return
            val actual = app.packageManager.getPackageInfo(hostPackage, 0)?.versionName ?: return
            hostVersionChecked = true
            actualVersion = actual
            when {
                actual == expectedHostVersion ->
                    module.log(Log.INFO, TAG, "$hookName 宿主版本与 hook 基线一致: $actual")
                else -> module.log(
                    Log.WARN, TAG,
                    "$hookName 宿主版本 $actual 与 hook 基线 $expectedHostVersion 不一致——挂载点可能漂移，" +
                        "判据以 installed N/M 与失败清单为准",
                )
            }
        }
    }

    companion object {
        const val TAG = "HonorHook"
        const val MAX_RETRY_ROUNDS = 2
        const val RETRY_DELAY_MS = 500L
        const val RETRY_FINAL_DELAY_MS = 2000L

        /** 判据日志每任务打印上限（超出的命中不再打，防高频读点刷屏）。 */
        const val JUDGE_LOG_PER_TASK = 2

        /** 回调异常 WARN 每任务打印上限（同 JUDGE_LOG_PER_TASK 口径；高频死点只告警前 3 次防刷屏）。 */
        const val CALLBACK_WARN_PER_TASK = 3

        /** 挂载细节日志门控（默认关；汇总与判据日志不受门控）。 */
        const val DEBUG_LOGGING = false
    }
}
