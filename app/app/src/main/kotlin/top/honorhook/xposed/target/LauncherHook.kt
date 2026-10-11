package top.honorhook.xposed.target

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.data.RemoteConfig

/**
 * 荣耀桌面 `com.hihonor.android.launcher`：卡片标题隐藏（L1）+ 文件夹返回保持打开（L2a/L2b/L3）。
 *
 * 挂载点本体在数据表 [LAUNCHER_POINTS_18_0_0_234]，按 kind 分派的 intercept 在 [mountOne]。
 * 版本分发走 [HostVersion]（空集合 = 默认套，覆盖基线 18.0.0.234）。
 *
 * L2 依据：关文件夹由 `Launcher.mNeedCloseFolderOnSaveState` 单旗门控，但**多条路径读取方式不同**——
 * ① `Launcher.onStop()` 直接读字段（L2b 清字段）；② 返回动画等 5 处走 `getNeedCloseFloderOnSaveState()`
 * （L2a 恒 false）；③ **现症根因**：按 Home 返回桌面走 `onNewIntent → doMainActionIntent → doShowWorkspaceAnim
 * → closeFolder(folder, z)`，该链不看任何关闭旗（L3 把 folder 实参置 null）。
 * 详见 `docs/reverse/launcher-folder-keepopen.md`。
 */
object LauncherHook : TargetHook {

    override val packageName = "com.hihonor.android.launcher"

    /** 共享挂载基建（版本自检/重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("LauncherHook", packageName)

    /** 版本选表：默认套 = 基线点；适配其他宿主版本时在其上追加精确套。 */
    private val versionTables = listOf(
        HostVersion.Table(versions = emptySet()) { LAUNCHER_POINTS_18_0_0_234 },
    )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        // 数据表 LAUNCHER_POINTS_18_0_0_234.kt；逐点清点口径以真机 `installed N/M` 的 M 为准。
        val pick = HostVersion.pick(versionTables, HostVersion.resolve(param.applicationInfo?.sourceDir))
        val pending = pick.tasks
            .filter { it.feature.isEmpty() || RemoteConfig.isEnabled(it.feature) }
            .map { it.toMountTask() }

        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** 单个挂载任务执行（解析/重试/汇总在共享 [engine]；本函数只做 kind 分派的 intercept 构造）。 */
    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            when (task.kind) {
                // 常量短路：命中即返回强制值，不调原方法
                MountKind.CONST -> {
                    engine.judge(module, task, JUDGE_PREFIX)
                    task.value
                }
                // onStop 直读字段（不经 getter）：命中先把 mNeedCloseFolderOnSaveState 置 false 再放行，
                // 使 `if (mNeedCloseFolderOnSaveState && ...) closeFolder(false)` 短路。isCaller=launcher 进程本身。
                MountKind.KEEP_FOLDER_ON_STOP -> {
                    val self = chain.thisObject
                    if (self != null) {
                        runCatching { self.javaClass.getField(CLOSE_FLAG_FIELD).setBoolean(self, false) }
                            .onFailure { module.log(Log.WARN, MountEngine.TAG, "onStop 置位 $CLOSE_FLAG_FIELD 失败: ${it.message}") }
                    }
                    engine.judge(module, task, JUDGE_PREFIX)
                    chain.proceed()
                }
                // 按 Home 返回桌面：把 folder 实参换成 null 再放行，令下游 doShowWorkspaceAnim 跳过 closeFolder。
                MountKind.DROP_FOLDER_ON_HOME -> {
                    val argv: Array<Any?> = Array(task.paramCount) { chain.getArg(it) }
                    argv[FOLDER_ARG_INDEX] = null
                    engine.judge(module, task, JUDGE_PREFIX)
                    @Suppress("UNCHECKED_CAST")
                    chain.proceed(argv as Array<Any>)
                }
            }
        }

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "launcher-hook"

    /** L2b 目标字段：`Launcher` 的 public 关文件夹旗（onStop 直读；名字含拼写 Floder 的 getter 读同一字段）。 */
    private const val CLOSE_FLAG_FIELD = "mNeedCloseFolderOnSaveState"

    /** L3 目标实参位：`doMainActionIntent(Intent, Folder, ManualStackExpandGroup)` 的 folder。 */
    private const val FOLDER_ARG_INDEX = 1

    /** hook 编写时的宿主版本基线（dumpsys package 实证）；OTA 超出时 MountEngine 首轮挂载打 WARN。 */
    private const val HOST_VERSION_BASELINE = "18.0.0.234"

    // ---------------- 挂载任务定义（与 ThemeStore/Installer 同构） ----------------

    /** 挂载任务类型：CONST 常量短路；KEEP_FOLDER_ON_STOP 命中清字段后放行；DROP_FOLDER_ON_HOME 命中置空 folder 实参后放行。 */
    private enum class MountKind { CONST, KEEP_FOLDER_ON_STOP, DROP_FOLDER_ON_HOME }

    private class MountTask(
        val kind: MountKind,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        val value: Any?,
        override val id: String,
        override val description: String,
    ) : MountEngine.Task

    /** 数据表挂载点 → 运行时任务（声明式字段落到具体类型）。 */
    private fun HookPoint.toMountTask(): MountTask = MountTask(
        kind = MountKind.valueOf(kind),
        className = className,
        methodName = methodName,
        paramCount = paramCount,
        returnType = returnType,
        value = value,
        id = id,
        description = description,
    )
}
