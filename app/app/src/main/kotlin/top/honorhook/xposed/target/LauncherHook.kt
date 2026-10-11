package top.honorhook.xposed.target

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.data.RemoteConfig

/**
 * 荣耀桌面 `com.hihonor.android.launcher`：卡片标题隐藏（L1）+ 文件夹返回保持打开（L2a/L2b/L3/L4）。
 *
 * 挂载点本体在数据表 [LAUNCHER_POINTS_18_0_0_234]，按 kind 分派的 intercept 在 [mountOne]。
 * 版本分发走 [HostVersion]（空集合 = 默认套，覆盖基线 18.0.0.234）。
 *
 * 关文件夹有四条互不相干的链，逐条堵：
 * ① 返回动画等 5 处读 `getNeedCloseFloderOnSaveState()` —— L2a 恒 false；
 * ② `Launcher.onStop()` 直读 `mNeedCloseFolderOnSaveState` 字段 —— L2b 命中时清字段；
 * ③ 三键 Home 回桌面走 `onNewIntent → doMainActionIntent → doShowWorkspaceAnim → closeFolder` —— L3 置空 folder 实参；
 * ④ **手势上滑从应用回桌面**在 `onGestureStarted` 现场被 quickstep 的例行重置关闭
 *    （`closeOverlay()` 与 `processStateForOverView()` 两条，均不看关闭旗）—— L4 在返回手势链上短路该调用。
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
                // L4 手势返回：从应用侧上滑回桌面时的 overlay/背景状态重置不再关文件夹，直接短路。
                MountKind.KEEP_FOLDER_ON_RETURN_GESTURE -> {
                    if (fromAppReturnGesture()) {
                        engine.judge(module, task, JUDGE_PREFIX, "从应用返回手势的例行重置短路，文件夹保持打开")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                // 诊断：只打「谁调的 closeFolder」一行（调用栈已三次误判，留作常备取证），随后放行。
                MountKind.TRACE_FOLDER_CLOSE -> {
                    module.log(Log.WARN, MountEngine.TAG, "FOLDER-CLOSE 触发方: ${nearestCaller()}")
                    engine.judge(module, task, JUDGE_PREFIX)
                    chain.proceed()
                }
            }
        }

    /**
     * 本次 `Launcher.closeFolder(boolean)` 是否属于「从应用侧返回桌面」的例行重置（L4 判定）。
     *
     * 要求栈上**同时**出现两类帧：
     * ① [OTHER_ACTIVITY_CONSUMER] —— quickstep 在**别的应用处于前台**时接管手势的消费者。
     *    它的存在把场景锁死为"从应用滑回桌面"，桌面内切 Recents（HomeInputConsumer 等）不满足，
     *    故"点外部关文件夹 / 桌面内上滑"等原有行为不受影响。
     * ② [RESET_CALLERS] 之一 —— 离开桌面时才跑的 overlay/状态重置入口，其中的 `closeFolder(false)`
     *    **不看任何关闭旗**，是 L2a/L2b/L3 之外的两条真实关闭链（真机调用栈实证）。
     */
    private fun fromAppReturnGesture(): Boolean {
        var fromApp = false
        var reset = false
        // 两帧顺序不定（消费者在重置入口**下方**，不能提前 return），故整栈扫完再判。
        for (frame in Thread.currentThread().stackTrace) {
            if (frame.className == OTHER_ACTIVITY_CONSUMER) fromApp = true
            else if (RESET_CALLERS[frame.className] == frame.methodName) reset = true
        }
        return fromApp && reset
    }

    /** 最近一个非 hook 胶水/非合成分身的调用方，形如 `类简名.方法(文件:行)`。 */
    private fun nearestCaller(): String =
        Thread.currentThread().stackTrace
            .filter { it.className.startsWith("com.hihonor.") || it.className.startsWith("com.android.") }
            .dropWhile { it.methodName == "closeFolder" }
            .firstOrNull()
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            ?: "未知"

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "launcher-hook"

    /** L2b 目标字段：`Launcher` 的 public 关文件夹旗（onStop 直读；名字含拼写 Floder 的 getter 读同一字段）。 */
    private const val CLOSE_FLAG_FIELD = "mNeedCloseFolderOnSaveState"

    /** L3 目标实参位：`doMainActionIntent(Intent, Folder, ManualStackExpandGroup)` 的 folder。 */
    private const val FOLDER_ARG_INDEX = 1

    /** L4 判定：quickstep「别的应用在前台」时的手势消费者（其在场即"从应用返回桌面"）。 */
    private const val OTHER_ACTIVITY_CONSUMER = "com.android.quickstep.inputconsumers.OtherActivityInputConsumer"

    /** L4 判定：离开桌面时的例行重置入口（类名 → 方法名）。两者都在 launcher 未回前台时执行。 */
    private val RESET_CALLERS = mapOf(
        // ① 手势开始现场重置 overlay：AbsSwipeUpHandler.onGestureStarted → closeOverlay()
        "com.android.quickstep.LauncherActivityInterface" to "closeOverlay",
        // ② launcher 被拉起铺 Recents 背景：prepareRecentsUI → initBackgroundStateUI
        //    → goToState(BACKGROUND_APP) → 本入口（仅 BACKGROUND_APP / QUICK_SWITCH_FROM_HOME 分支关）
        "com.hihonor.android.launcher.extension.quickstep.views.HnRecentsViewStateController" to "processStateForOverView",
    )

    /** hook 编写时的宿主版本基线（dumpsys package 实证）；OTA 超出时 MountEngine 首轮挂载打 WARN。 */
    private const val HOST_VERSION_BASELINE = "18.0.0.234"

    // ---------------- 挂载任务定义（与 ThemeStore/Installer 同构） ----------------

    /** 挂载任务类型：CONST 常量短路；KEEP_FOLDER_ON_STOP 命中清字段后放行；DROP_FOLDER_ON_HOME 命中置空 folder 实参后放行；KEEP_FOLDER_ON_RETURN_GESTURE 判定为从应用返回桌面时直接短路；TRACE_FOLDER_CLOSE 仅打调用方诊断。 */
    private enum class MountKind { CONST, KEEP_FOLDER_ON_STOP, DROP_FOLDER_ON_HOME, KEEP_FOLDER_ON_RETURN_GESTURE, TRACE_FOLDER_CLOSE }

    private class MountTask(
        val kind: MountKind,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        val value: Any?,
        override val id: String,
        override val description: String,
        override val dex: MountEngine.DexHint? = null,
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
        // 同名重载消歧（如 Launcher.closeFolder 的 (Folder)V 与 (Z)V 同为 1 参 void）靠参数类型锚点。
        dex = dexParamTypes?.let { MountEngine.DexHint(paramTypes = it) },
    )
}
