package top.honorhook.xposed.target

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 *
 * ⑤ 文件夹背景遮罩（L5）：文件夹打开时那层压暗/模糊**不是 Folder 画的**，是桌面全局的
 *    「背景模糊」——`DragLayer.mBlurEffect`（`DragLayer.setBlur(Drawable, boolean)`，非动态模糊机型）
 *    或 `LevelAnimationMgr` 的窗口模糊场景 `1=folder`（动态模糊机型，由 SF 合成）。
 *    返回桌面时的状态转场会把它清掉（`Workspace.doAnimatorListenerEndWork` / `LauncherStateTransitionAnimation`
 *    的 `setBlurEffect(null, false)`，以及 app 模糊退场 `changeScene(2, 0, false, …)`），这些调用**都不看文件夹开关**，
 *    故 L4 保住文件夹后遮罩仍丢。对策：L3/L4 命中时开一个「遮罩保持窗口」，窗口内
 *    ① `DragLayer.setBlur(null, …)` 直接短路（不清）；② 记录 `LevelAnimationMgr.changeScene` 场景轨迹备查。
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
                    openBackdropHoldWindow()
                    engine.judge(module, task, JUDGE_PREFIX)
                    @Suppress("UNCHECKED_CAST")
                    chain.proceed(argv as Array<Any>)
                }
                // L4 手势返回：从应用侧上滑回桌面时的 overlay/背景状态重置不再关文件夹，直接短路。
                MountKind.KEEP_FOLDER_ON_RETURN_GESTURE -> {
                    if (fromAppReturnGesture()) {
                        openBackdropHoldWindow()
                        engine.judge(module, task, JUDGE_PREFIX, "从应用返回手势的例行重置短路，文件夹保持打开")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                // L5 背景遮罩：返回转场里的 setBlur(null) 是"撤遮罩"，窗口内命中即短路（不清 = 遮罩留住）。
                MountKind.BLOCK_BLUR_CLEAR -> {
                    if (chain.getArg(0) == null && inBackdropHoldWindow()) {
                        module.log(Log.WARN, MountEngine.TAG, "BACKDROP 拦截清空: DragLayer.setBlur(null) ← ${nearestCaller("setBlur")}")
                        engine.judge(module, task, JUDGE_PREFIX, "返回转场不再撤掉文件夹背景遮罩")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                // L5 动态模糊分支：真机实证该机遮罩走窗口模糊（`isDynamicBlurFolder` = 空间层级动画 ∧ 窗口模糊
                // ∧ 非编辑模式，本机成立），清遮罩的那步是「退出应用模糊」`changeScene(2, 0, false, …)`。
                // 返回转场里把它改写成「进文件夹模糊」——文件夹还开着，背景本就该停在文件夹模糊上。
                MountKind.RESTORE_FOLDER_BLUR -> {
                    val scene = chain.getArg(0) as? Int ?: -1
                    val enter = chain.getArg(2) as? Boolean ?: false
                    if (!inBackdropHoldWindow()) {
                        chain.proceed()
                    } else if (scene == APP_BLUR_SCENE && !enter) {
                        val argv: Array<Any?> = Array(task.paramCount) { chain.getArg(it) }
                        argv[0] = FOLDER_BLUR_SCENE
                        argv[2] = true
                        module.log(Log.WARN, MountEngine.TAG, "BLUR-SCENE 改写: app 退场 → folder 进场 ← ${nearestCaller("changeScene")}")
                        engine.judge(module, task, JUDGE_PREFIX, "返回转场背景回到文件夹模糊")
                        @Suppress("UNCHECKED_CAST")
                        chain.proceed(argv as Array<Any>)
                        reassertFolderBlur(chain.thisObject, module)
                    } else {
                        module.log(
                            Log.WARN, MountEngine.TAG,
                            "BLUR-SCENE scene=$scene enter=$enter dur=${chain.getArg(3)} ← ${nearestCaller("changeScene")}",
                        )
                        chain.proceed()
                    }
                }
                // 诊断：只打「谁调的 closeFolder」一行（调用栈已三次误判，留作常备取证），随后放行。
                MountKind.TRACE_FOLDER_CLOSE -> {
                    module.log(Log.WARN, MountEngine.TAG, "FOLDER-CLOSE 触发方: ${nearestCaller("closeFolder")}")
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

    /** 最近一个非 hook 胶水/非合成分身的调用方，形如 `类简名.方法(文件:行)`；[skip] 用于跳过被 hook 的方法自身帧。 */
    private fun nearestCaller(skip: String): String =
        Thread.currentThread().stackTrace
            .filter { it.className.startsWith("com.hihonor.") || it.className.startsWith("com.android.") }
            .dropWhile { it.methodName == skip }
            .firstOrNull()
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            ?: "未知"

    /**
     * 开启「背景遮罩保持窗口」。
     *
     * L3/L4 决定让文件夹留在打开态的同一时刻起，返回转场会陆续清掉那层模糊/压暗；
     * 窗口内的 `DragLayer.setBlur(null, …)`（撤遮罩）需要拦下。窗口只覆盖这一次返回转场，
     * 之后用户主动关文件夹（点外部空白等）时的清空照常放行。
     */
    private fun openBackdropHoldWindow() {
        holdBackdropUntil = SystemClock.uptimeMillis() + BACKDROP_HOLD_MS
    }

    private fun inBackdropHoldWindow(): Boolean = SystemClock.uptimeMillis() < holdBackdropUntil

    /**
     * 转场收尾后重申「文件夹模糊」。
     *
     * 真机轨迹显示：app 模糊退场之后紧跟 `HnLauncherRecentsView.clearWindowBlurAndScale` 的
     * `changeScene(0, 0, false, 0, true)`（约 +600ms），会把背景打回无模糊——这一步同样不看文件夹开关。
     * 故在窗口尾部补一次「进文件夹模糊」；`changeScene(1, 0, true, 0)` 幂等，落在已进场景上无副作用。
     */
    private fun reassertFolderBlur(instance: Any?, module: XposedModule) {
        val looper = Looper.getMainLooper() ?: return
        Handler(looper).postDelayed({
            runCatching {
                instance?.javaClass?.getMethod(
                    "changeScene",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )?.invoke(instance, FOLDER_BLUR_SCENE, 0, true, 0L, false)
            }.onFailure { module.log(Log.WARN, MountEngine.TAG, "重申文件夹模糊失败: ${it.message}") }
        }, BACKDROP_REASSERT_DELAY_MS)
    }

    /** 遮罩保持窗口截止时刻（uptimeMillis）；0 = 未开启。 */
    @Volatile
    private var holdBackdropUntil = 0L

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

    /** 背景遮罩保持窗口时长：覆盖一次「返回桌面」转场（含 app 模糊退场与工作区状态动画）。 */
    private const val BACKDROP_HOLD_MS = 2500L

    /** 补正「进文件夹模糊」的延时：压在返回转场尾部（`clearWindowBlurAndScale` 的 scene=0 之后）。 */
    private const val BACKDROP_REASSERT_DELAY_MS = 800L

    /** `LevelAnimationMgr` 场景号：0=recents / 1=folder / 2=application。 */
    private const val FOLDER_BLUR_SCENE = 1
    private const val APP_BLUR_SCENE = 2

    // ---------------- 挂载任务定义（与 ThemeStore/Installer 同构） ----------------

    /** 挂载任务类型：CONST 常量短路；KEEP_FOLDER_ON_STOP 清字段后放行；DROP_FOLDER_ON_HOME 置空 folder 实参；KEEP_FOLDER_ON_RETURN_GESTURE 返回手势链短路；BLOCK_BLUR_CLEAR 遮罩窗口内不清背景模糊；RESTORE_FOLDER_BLUR 返回转场改写为进文件夹模糊；TRACE_FOLDER_CLOSE 仅诊断。 */
    private enum class MountKind { CONST, KEEP_FOLDER_ON_STOP, DROP_FOLDER_ON_HOME, KEEP_FOLDER_ON_RETURN_GESTURE, BLOCK_BLUR_CLEAR, RESTORE_FOLDER_BLUR, TRACE_FOLDER_CLOSE }

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
