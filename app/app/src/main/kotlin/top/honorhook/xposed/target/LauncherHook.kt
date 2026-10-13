package top.honorhook.xposed.target

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.data.RemoteConfig

/**
 * 荣耀桌面 `com.hihonor.android.launcher`：卡片标题隐藏（L1）+ 文件夹「返回后自动重开」（R1/R2）。
 *
 * 挂载点本体在数据表 [LAUNCHER_POINTS_18_0_0_234]，按 kind 分派的 intercept 在 [mountOne]。
 *
 * **为什么是「重开」而不是「拦住不让关」**：关文件夹由 quickstep 的应用切换转场驱动，
 * 路径有四条（`closeOverlay` / `processStateForOverView` / `onStop` / `closeFolder` 的四个重载），
 * 而且**非线性应用关闭动画**会把应用图标收回到文件夹所在的位置——此时让文件夹同时停在打开态
 * 是自相矛盾的状态，两者互相踩，表现就是「有时不生效 / 有时只剩一层模糊遮罩」。
 * 逐条补钩子只能减轻症状。故改为**顺着系统状态机走**：应用切换全程不干预
 * （图标回收动画、背景模糊都由系统原生完成），回到桌面后再用系统自身的开文件夹路径把它打开
 * （`FolderManager.openFolder(FolderIcon)`，与手点文件夹图标同一条路 ⇒ 展开动画与遮罩天然完整）。
 *
 * 代价：返回桌面时会多一次文件夹展开动画（两端都是原生动画，衔接自然）。
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
        hostLoader = loader
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))

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
                // R1 记录目标：用户在打开的文件夹里点了应用。此刻文件夹必然还开着，把它的视图记下来。
                MountKind.ARM_FOLDER_REOPEN -> {
                    chain.thisObject?.let { reopenTarget = it }
                    engine.judge(module, task, JUDGE_PREFIX, "已记下文件夹，返回桌面后重开")
                    chain.proceed()
                }
                // R2 重开：`Launcher.onResume` = 回到桌面。先让宿主自己的 onResume 跑完，再延迟重开。
                MountKind.REOPEN_FOLDER_ON_RESUME -> {
                    val target = reopenTarget
                    reopenTarget = null
                    chain.proceed()
                    if (target != null) postReopen(target, module, attempt = 1)
                }
                // K1 垃圾桶捕获：`dismissAllTasks` 是垃圾桶清后台的专属入口（RecentsView.java:3717，
                // 全工程唯一调用方），此刻 recents 里还留着全部任务。反射取 getRunningTaskView()
                // 的 getTaskIds()（quickstep 原生维护的"进 overview 前的前台任务"）= 保留集；
                // 其余卡片 = 移除集。任何一步失败或保留集为空（罕见：running task 已不在列表）= 存 null，
                // 本次清后台全程走原生行为。
                MountKind.ARM_KEEP_CURRENT -> {
                    val captured = captureKeepSets(chain.thisObject)
                    keepTaskIds = captured?.first
                    killTaskIds = captured?.second
                    keepCapturedAtMs = if (captured != null) SystemClock.elapsedRealtime() else 0L
                    if (captured != null) {
                        engine.judge(
                            module, task, JUDGE_PREFIX,
                            "捕获: 保留 ${captured.first.size} 任务(id=${captured.first.joinToString(",")})，移除 ${captured.second.size} 个",
                        )
                    }
                    chain.proceed()
                }
                // K2 全杀收口替换：`removeAllRecentTasks`（:3634）无排除参数（最终 binder
                // removeAllVisibleRecentTasks 无参），只能整条替换。真实执行顺序（launcher
                // 18.0.0.234 反编译实证，RecentsView.lambda$createAllTasksDismissAnimation$28）：
                // removeAllRecentTasks 被 post 到 UI_HELPER_EXECUTOR，startHome() 主线程先行
                // ⇒ 本 hook 晚于 K3 执行。故只消费 killTaskIds、互不清对方集合（两种顺序均正确）；
                // 时间戳在双方都消费完后才清。kill 为空数组也走替换分支（原生全杀会连保留集一起杀）。
                // 反射失败也消费后透传原生全杀（fail-safe：宁可杀掉保留任务，不留脏标记）。
                MountKind.REMOVE_ALL_KEEP_CURRENT -> {
                    val kill = killTaskIds
                    if (kill == null || keepFlagStale()) {
                        // 未捕获/已超时/已消费：垃圾桶 flow 之外的任何 removeAllRecentTasks 调用方
                        // 一律透传，绝不拦截。
                        chain.proceed()
                    } else {
                        killTaskIds = null
                        if (keepTaskIds == null) {
                            keepCapturedAtMs = 0L
                        }
                        if (removeTasksIndividually(kill) { reason ->
                                module.log(Log.WARN, MountEngine.TAG, "RECENTS-KEEP 逐个移除失败: $reason")
                            }
                        ) {
                            engine.judge(module, task, JUDGE_PREFIX, "已逐个移除 ${kill.size} 个任务，原生全杀未执行（保留集见 K1 捕获行）")
                        } else {
                            // 已动手才失败 ⇒ 透传原生全杀兜底（部分任务可能已被移除，原生全杀收尾幂等）。
                            chain.proceed()
                        }
                    }
                }
                // K3 回桌面步替换：startHome（:2696）另有点 overview 空白回桌面等调用方
                // （:1674/:1676）⇒ 必须门控。真实执行顺序里 K3 先于 K2（见 K2 注释）⇒ 只消费
                // keepTaskIds，killTaskIds 留给晚到的 K2；时间戳在双方都消费完后才清。
                // 门控有效时替换为 startActivityFromRecents(保留id, null)（recents 点卡片回
                // app 同一条路）；失败/无效透传原生回桌面（回落：保留 id 已死/分身异常 → 静默降级）。
                MountKind.START_TASK_INSTEAD_HOME -> {
                    val keep = keepTaskIds
                    if (keep == null || keep.isEmpty() || keepFlagStale()) {
                        chain.proceed()
                    } else {
                        keepTaskIds = null
                        if (killTaskIds == null) {
                            keepCapturedAtMs = 0L
                        }
                        var started: Int? = null
                        for (id in keep) {
                            if (startTaskFromRecents(id) { reason ->
                                    module.log(Log.WARN, MountEngine.TAG, "RECENTS-KEEP 热回任务 $id 失败: $reason")
                                }
                            ) {
                                started = id
                                break
                            }
                        }
                        if (started != null) {
                            engine.judge(module, task, JUDGE_PREFIX, "已回到保留任务 id=$started")
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        }

    /**
     * 延迟重开文件夹。
     *
     * 应用关闭动画约 300~500ms，太早调用会被 `FolderManager.canOpenFolder`（动画互斥）挡掉，
     * 故首轮等 [REOPEN_DELAY_MS]，未成功再试一次（共 [REOPEN_MAX_ATTEMPTS] 次）。
     * 每次动手前先确认当前没有别的文件夹开着——有就不动，避免顶掉用户当下的状态
     * （例如返回桌面后立刻又点开了另一个文件夹）。
     */
    private fun postReopen(folder: Any, module: XposedModule, attempt: Int) {
        val looper = Looper.getMainLooper() ?: return
        Handler(looper).postDelayed({
            if (folderIsOpen() == true) {
                module.log(Log.INFO, MountEngine.TAG, "FOLDER-REOPEN 已有文件夹打开，跳过重开")
                return@postDelayed
            }
            val ok = openFolderViaSystem(folder) { reason ->
                module.log(Log.WARN, MountEngine.TAG, "FOLDER-REOPEN 重开失败: $reason")
            }
            if (ok) {
                module.log(Log.INFO, MountEngine.TAG, "FOLDER-REOPEN 已按系统路径重开文件夹（第 $attempt 次）")
            } else if (attempt < REOPEN_MAX_ATTEMPTS) {
                postReopen(folder, module, attempt + 1)
            } else {
                module.log(Log.WARN, MountEngine.TAG, "FOLDER-REOPEN 重试 $attempt 次仍未成功，放弃")
            }
        }, REOPEN_DELAY_MS)
    }

    /**
     * 用系统自身的路径重开文件夹：`FolderManager.getInstance().openFolder(FolderIcon)`。
     *
     * 与用户手点文件夹图标是同一条路（`FolderIcon` → `FolderManager.openFolder`），
     * 展开动画、窗口模糊、`FolderInfo.isOpened` 状态全部由系统负责，模块不碰任何视觉状态。
     * 返回 `false` 时通过 [onFail] 报出原因（挂载日志里可查）。
     */
    private fun openFolderViaSystem(folder: Any, onFail: (String) -> Unit): Boolean = runCatching {
        val loader = hostLoader ?: return@runCatching false.also { onFail("无宿主 ClassLoader") }
        val icon = folder.javaClass.getMethod("getFolderIcon").invoke(folder)
            ?: folder.javaClass.getMethod("getInfo").invoke(folder)
                ?.let { it.javaClass.getMethod("getFolderIcon").invoke(it) }
            ?: return@runCatching false.also { onFail("拿不到 FolderIcon") }
        val managerCls = Class.forName(FOLDER_MANAGER_CLASS, false, loader)
        val manager = managerCls.getMethod("getInstance").invoke(null)
            ?: return@runCatching false.also { onFail("FolderManager 实例为 null") }
        managerCls.getMethod("openFolder", Class.forName(FOLDER_ICON_CLASS, false, loader))
            .invoke(manager, icon)
        true
    }.getOrElse {
        onFail("异常 ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /**
     * 当前是否有文件夹处于打开态（`Launcher.getWorkspace().getOpenFolder() != null`）。
     *
     * 只用于「重开前先看看是不是已经有文件夹开着」，不参与任何拦截。
     * 反射访问（宿主类不在模块编译期），返回 `null` = 查不出来（按「没开」处理）。
     */
    private fun folderIsOpen(): Boolean? = runCatching {
        val loader = hostLoader ?: return@runCatching null
        val launcher = Class.forName(LAUNCHER_CLASS, false, loader).getMethod("getLauncher").invoke(null)
            ?: return@runCatching false
        val workspace = launcher.javaClass.getMethod("getWorkspace").invoke(launcher)
            ?: return@runCatching false
        workspace.javaClass.getMethod("getOpenFolder").invoke(workspace) != null
    }.getOrNull()

    /**
     * K1 捕获：在 `RecentsView.dismissAllTasks` 入口收集保留集与移除集。
     *
     * 保留集 = `getRunningTaskView().getTaskIds()`（:2779，public；quickstep 原生维护的
     * "进 overview 前的前台任务"，语义正好是需求里的"当前 app"）；移除集 = 其余全部卡片
     * 的 `getTaskIds()`（`getTaskViewCount`/`requireTaskViewAt`，:2233/:3896，全 public，零字段反射）。
     * 全部 public API 直连反射，不碰混淆面。返回 `null` = 任一环节失败或保留集为空
     * （running task 已不在列表的罕见态）⇒ 本次清后台对原生行为零干预。
     */
    private fun captureKeepSets(recentsView: Any?): Pair<IntArray, IntArray>? = runCatching {
        if (recentsView == null) return@runCatching null
        val running = recentsView.javaClass.getMethod("getRunningTaskView").invoke(recentsView)
            ?: return@runCatching null
        val keep = (running.javaClass.getMethod("getTaskIds").invoke(running) as IntArray)
            .filter { it >= 0 }   // getTaskIds 可能含 -1（TaskView.java:575），过滤后再判空
        if (keep.isEmpty()) return@runCatching null
        val keepSet = keep.toHashSet()
        val count = recentsView.javaClass.getMethod("getTaskViewCount").invoke(recentsView) as Int
        val requireTaskViewAt = recentsView.javaClass
            .getMethod("requireTaskViewAt", Int::class.javaPrimitiveType)
        val kill = (0 until count).flatMap { i ->
            val view = requireTaskViewAt.invoke(recentsView, i) ?: return@flatMap emptyList<Int>()
            (view.javaClass.getMethod("getTaskIds").invoke(view) as IntArray)
                .filter { it >= 0 && it !in keepSet }
        }.toIntArray()
        keep.toIntArray() to kill
    }.getOrNull()

    /**
     * flag 是否已超时。垃圾桶 flow 若被中断（动画异常、进程切换）残留脏标记，10s 内必失效，
     * 防止污染之后的任何 `removeAllRecentTasks`/`startHome` 调用方。
     */
    private fun keepFlagStale(): Boolean =
        keepCapturedAtMs <= 0L || SystemClock.elapsedRealtime() - keepCapturedAtMs > KEEP_FLAG_TIMEOUT_MS

    /**
     * K2 逐个移除：`ActivityManagerWrapperEx.getInstance().removeTask(int)`（shlib :87，
     * 与单卡划走同 API）。单 id 失败不中断循环；实例/方法反射失败整体返回 `false`
     * ⇒ 调用侧透传原生全杀（fail-safe）。
     */
    private fun removeTasksIndividually(taskIds: IntArray, onFail: (String) -> Unit): Boolean = runCatching {
        val loader = hostLoader ?: return@runCatching false.also { onFail("无宿主 ClassLoader") }
        val cls = Class.forName(WRAPPER_EX_CLASS, false, loader)
        val wrapper = cls.getMethod("getInstance").invoke(null)
            ?: return@runCatching false.also { onFail("ActivityManagerWrapperEx 实例为 null") }
        val removeTask = cls.getMethod("removeTask", Int::class.javaPrimitiveType)
        taskIds.forEach { id ->
            runCatching { removeTask.invoke(wrapper, id) }
                .onFailure { onFail("removeTask($id) 异常 ${it.javaClass.simpleName}: ${it.message}") }
        }
        true
    }.getOrElse {
        onFail("异常 ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /**
     * K3 热回保留任务：`ActivityManagerWrapperEx.startActivityFromRecents(int, ActivityOptions)`
     * （基类 `ActivityManagerWrapper` :142 对 null options 有处理），与 recents 点卡片回 app 同一条
     * binder（ATMS.startActivityFromRecents）⇒ launcher 进程有权限、热启动不冷启。
     * 只把「抛异常」判为失败；wrapper 内部吞掉的 RemoteException（若有）无法在此感知。
     */
    private fun startTaskFromRecents(taskId: Int, onFail: (String) -> Unit): Boolean = runCatching {
        val loader = hostLoader ?: return@runCatching false.also { onFail("无宿主 ClassLoader") }
        val cls = Class.forName(WRAPPER_EX_CLASS, false, loader)
        val wrapper = cls.getMethod("getInstance").invoke(null)
            ?: return@runCatching false.also { onFail("ActivityManagerWrapperEx 实例为 null") }
        val optionsCls = Class.forName("android.app.ActivityOptions")
        cls.getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, optionsCls)
            .invoke(wrapper, taskId, null)
        true
    }.getOrElse {
        onFail("异常 ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /** 宿主类加载器（挂载时捕获），供反射查宿主类。 */
    @Volatile
    private var hostLoader: ClassLoader? = null

    /** 「从文件夹里打开了应用」时记下的文件夹视图；在下一次回到桌面时消费（消费即清空）。 */
    @Volatile
    private var reopenTarget: Any? = null

    /**
     * 垃圾桶 flow 的一组临时标记（K1 捕获 → K3/K2 **各自只消费自己的集合** → 双方都消费完
     * 才清时间戳 → 超时兜底清）。
     *
     * **为什么拆分消费（2026-10-13 缺陷修复）**：真实执行顺序是 K3 先、K2 后——宿主把
     * `removeAllRecentTasks()` post 到 UI_HELPER_EXECUTOR 后台线程，`startHome()` 在主线程
     * 立即执行（RecentsView.lambda$createAllTasksDismissAnimation$28，launcher 18.0.0.234
     * 反编译实证）。初版实现按「K2 先于 K3」的错误假设共用一组 flag 且消费即全清 ⇒ K3 热回
     * 成功清空 flag，晚到的 K2 读空透传原生全杀，把刚热回去的 app 一并杀掉（实测无效果的
     * 根因）。拆分后 K3→K2、K2→K3 两种顺序均正确，单边失效时另一边照常消费自己的集合。
     *
     * **为什么需要门控**：`RecentsView.startHome()`（:2696）另有点 overview 空白回桌面等
     * 常规调用方（:1674/:1676），`removeAllRecentTasks` 收口无排除参数只能整条替换——
     * 不门控就会把「点空白回桌面」「其他全杀入口」一并改掉。两组 id 一起置位（同一次
     * K1 捕获），保证 K2（杀谁）与 K3（回谁）来自同一次垃圾桶点击。
     */
    /** K1 捕获的保留集（清后台时的前台任务 id，getTaskIds 过滤 -1 后）。 */
    @Volatile
    private var keepTaskIds: IntArray? = null

    /** K1 捕获的移除集（保留集之外的全部任务 id）。 */
    @Volatile
    private var killTaskIds: IntArray? = null

    /** K1 捕获时刻（elapsedRealtime ms），0 = 无效；[KEEP_FLAG_TIMEOUT_MS] 超时兜底。 */
    @Volatile
    private var keepCapturedAtMs: Long = 0L

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "launcher-hook"

    /** 反射入口类名。 */
    private const val LAUNCHER_CLASS = "com.hihonor.android.launcher.Launcher"
    private const val FOLDER_MANAGER_CLASS = "com.hihonor.android.launcher.folder.FolderManager"
    private const val FOLDER_ICON_CLASS = "com.hihonor.android.launcher.FolderIcon"

    /** 重开延时：等应用关闭动画走完（`canOpenFolder` 与动画互斥，太早会被挡掉）。 */
    private const val REOPEN_DELAY_MS = 320L

    /** 重开尝试次数上限（首轮 + 重试）。 */
    private const val REOPEN_MAX_ATTEMPTS = 2

    /** shlib `ActivityManagerWrapperEx`（/system/framework/HnSystemUISharedLib.jar，与 launcher 同 classloader 链）。 */
    private const val WRAPPER_EX_CLASS =
        "com.hihonor.android.systemuisharedlib.systemui.shared.system.ActivityManagerWrapperEx"

    /** 垃圾桶 flow flag 超时：动画 300ms + 收口/回桌连续执行远小于此；中断残留 10s 内必失效。 */
    private const val KEEP_FLAG_TIMEOUT_MS = 10_000L

    // ---------------- 挂载任务定义（与 ThemeStore/Installer 同构） ----------------

    /**
     * 挂载任务类型：CONST 常量短路；ARM_FOLDER_REOPEN 记下打开的文件夹；REOPEN_FOLDER_ON_RESUME 回到桌面时重开；
     * ARM_KEEP_CURRENT / REMOVE_ALL_KEEP_CURRENT / START_TASK_INSTEAD_HOME = 清后台保留当前应用三步
     * （垃圾桶入口捕获 → 全杀收口替换 → 回桌面步替换，docs/reverse/launcher-recents-keepcurrent.md）。
     */
    private enum class MountKind {
        CONST, ARM_FOLDER_REOPEN, REOPEN_FOLDER_ON_RESUME,
        ARM_KEEP_CURRENT, REMOVE_ALL_KEEP_CURRENT, START_TASK_INSTEAD_HOME,
    }

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
