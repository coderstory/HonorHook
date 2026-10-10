package top.honorhook.xposed.core

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 各目标 hook（ThemeStoreHook / InstallerHook）**共享的挂载基建**：懒加载重试循环、失败分级、
 * 统一方法解析（直连 + DexKit 特征兜底）、汇总日志与 DexKit 特征声明。
 *
 * 目标侧只保留「挂载表构建 + 各 kind 的 intercept 逻辑」（[Task] 协议 + [attemptMount] 的
 * `mountOne` 回调），目标私有字段（kind 枚举、uri 路由、裁剪实现等）不进本类——
 * 2026-10-10 P3 审计：原先两份逐字重复的基建（约 120 行 × 2）收敛于此，改一处即两目标同步生效。
 *
 * 状态语义：模块 dex 被注入到**每个目标进程**独立加载，两目标永不同进程 ⇒ 每目标各持一个
 * [MountEngine] 实例（object 字段）即天然隔离；[begin] 在 install() 时重置计数。
 */
class MountEngine(private val hookName: String, private val hostPackage: String) {

    /** 失败分级：[FAILED] hook 挂载异常（重试无意义）；[NOT_RESOLVED] 类/方法（或依赖值）未解析（可随类加载重试）。 */
    enum class Result { OK, FAILED, NOT_RESOLVED }

    /**
     * DexKit 兜底特征声明 —— 用于**混淆名目标**（类名/方法名随时可能被 R8 重映射）。
     *
     * 四个维度按"抗混淆强度"降序，可任意组合（AND 语义）：
     * 1. [strings]         方法体内引用的**字符串常量** —— 混淆**不改**字符串，最强锚点（如 `u15` 的 SP 文件名 `themename`）；
     * 2. [paramTypes]      **参数类型序列**（DexKit 内部类型名，按声明顺序）—— 参数里的**可读类名**天然稳定
     *    （如 `o52.addDownloadItem(Context, DownloadInfo, int, boolean)` 里的 `common.DownloadInfo`）；
     * 3. [classStrings]    **所在类**引用的字符串常量 —— 类名与方法名都被混淆时的兜底（如 `tryout.a` 的日志 TAG `TryOutHelper`）；
     * 4. [classFieldTypes] **所在类的字段类型集合** —— 字段类型引用的是**类名**，可读类名天然稳定
     *    （如 `common.a` 同时有 `WeakReference` 与 `common.DownloadInfo` 两种字段类型）。
     *
     * 纪律：**特征必须包含至少一项"内容性锚点"**（1/2/3/4 任一），否则默认包类不会走 DexKit 兜底，
     * 以免退化为"全 APK 找同名同签名"的假兜底（详见 [MethodResolver.resolveMethod] 注释）。
     */
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
        /** DexKit 兜底特征（混淆名目标用；null = 仅直连轨）。 */
        val dex: DexHint? get() = null
        /** 挂载前惰性解析强制值（依赖目标类加载，如枚举常量）；返回 null = 未就绪 → [Result.NOT_RESOLVED] 重试。 */
        val resolveValue: ((ClassLoader) -> Any?)? get() = null
    }

    private var retryRounds = 0
    private var okCount = 0
    private var totalCount = 0
    private val failedIds = mutableListOf<String>()
    /** 目标 APK 磁盘路径（[begin] 期写入，[MethodResolver] 第二轨 DexKit 兜底用；null 时跳过）。 */
    private var apkPath: String? = null
    /** hook 编写时的宿主版本基线（[begin] 期写入；非空则首轮挂载前做一次版本自检）。 */
    private var expectedHostVersion: String? = null
    private var hostVersionChecked = false

    /** install() 时调用：写入 APK 路径与版本基线并重置计数（防同进程重复 install 计数累积）。 */
    fun begin(apkPath: String?, expectedHostVersion: String? = null) {
        this.apkPath = apkPath
        this.expectedHostVersion = expectedHostVersion
        retryRounds = 0
        okCount = 0
        failedIds.clear()
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
        checkHostVersionOnce(module)
        totalCount = pending.size
        val unresolved = mutableListOf<T>()
        for (task in pending) {
            when (mountOne(task)) {
                Result.OK -> okCount++
                Result.FAILED -> failedIds += task.description
                Result.NOT_RESOLVED -> unresolved += task
            }
        }
        if (unresolved.isEmpty()) {
            logSummary(module)
            return
        }
        if (retryRounds >= MAX_RETRY_ROUNDS) {
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
        val resolved = task.resolveValue?.invoke(loader)
        if (task.resolveValue != null && resolved == null) {
            module.log(Log.WARN, TAG, "hook 未挂载（依赖项未解析，将重试）: ${task.description}")
            return null
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
        module.log(
            if (failedIds.isEmpty()) Log.INFO else Log.WARN,
            TAG,
            "$hookName: installed $okCount/$totalCount$failurePart",
        )
    }

    /**
     * 宿主版本自检（每进程一次）：hook 挂载点以特定宿主版本反编译实证，宿主 OTA 后混淆名/签名可能漂移——
     * 版本不一致时打 WARN 提醒"判据以 installed N/M 为准"。拿不到 Context（宿主 Application 未起）则跳过。
     */
    private fun checkHostVersionOnce(module: XposedModule) {
        if (hostVersionChecked || expectedHostVersion == null) return
        hostVersionChecked = true
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            val actual = app?.packageManager?.getPackageInfo(hostPackage, 0)?.versionName
            when {
                actual == null -> return
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

        /** 挂载细节日志门控（默认关；汇总与判据日志不受门控）。 */
        const val DEBUG_LOGGING = false
    }
}
