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
        // 自检延后到后台线程：PackageLoadedParam 时机 Application 尚未创建，直查必拿 null；
        // 挂载首轮全成功时无重试轮，故不能依赖重试驱动（hostVersionChecked 未置位但也没有下一轮）。
        MountScheduler.postDelayed(1000) {
            runCatching { checkHostVersionOnce(module) }
        }
        // 只在首轮记总数：重试轮传入的是 unresolved 子列表，若逐轮覆盖，判据行会变成
        // "installed 累计成功数/本轮待挂数"（okCount 跨轮累计），M 失去意义。
        if (retryRounds == 0) totalCount = pending.size
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
        runCatching {
            // PackageLoadedParam 时机 Application 往往尚未创建：拿不到就不置位，随下一轮重试再查
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return
            val actual = app.packageManager.getPackageInfo(hostPackage, 0)?.versionName ?: return
            hostVersionChecked = true
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

        /** 挂载细节日志门控（默认关；汇总与判据日志不受门控）。 */
        const val DEBUG_LOGGING = false
    }
}
