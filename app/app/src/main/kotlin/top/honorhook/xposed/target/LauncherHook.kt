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
import top.honorhook.xposed.core.MountScheduler
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.data.RemoteConfig
import java.util.concurrent.CountDownLatch

/**
 * 荣耀桌面 `com.hihonor.android.launcher`：卡片标题隐藏（L1）+ 文件夹「返回后自动重开」（R1/R2）
 * + 清后台保留当前 app（N1/N2，荣耀 ClearBox 链路）。
 *
 * 挂载点本体在数据表 [LAUNCHER_POINTS_18_0_0_234]，按 kind 分派的 intercept 在 [mountOne]。
 *
 * **荣耀 ClearBox 清后台链路（N1/N2）**：垃圾桶按钮（`clear_all_recents_image_button`）的唯一
 * listener 是 `HnRecentsClearBox.onClick(View)`（:406，onFinishInflate :366-374 绑定）；清后台唯一
 * 收口是 shlib 的 `ActivityManagerWrapperEx.removeHwAllTask()`（:124，`RemoveTaskThread` →
 * `HwRecentsTaskUtils.sendRemoveAllTask` 广播系统管家）。quickstep 的 `dismissAllTasks`/`startHome`
 * 与 shlib `removeAllRecentTasks` 在荣耀上永不触发——旧 K1/K2/K3 三点已删（真机 dump +
 * 反编译直证，docs/reverse/launcher-recents-keepcurrent.md §一）。N1 在 onClick 捕获保留集
 * （running task）与移除集（其余卡片 ids + Task 对象）；N2 在 removeHwAllTask 整条替换：
 * A=ATMS 逐个 `removeTask`（单卡划走同 API）/ B=逐个 `sendRemoveTaskToSystemManager` 广播管家，
 * A/B 互斥、同开 A 优先。无论成败都要反射 countDown `HnRecentsClearBox.sCountDownLatch`
 * （public static，:158）——A/B 都产生不了管家那条 REPLY_TRIM_ALL 回执（原生唯一 countDown 在
 * 广播 receiver :174），不放行宿主线程会卡在 `await(20000ms)`。热回走主线程 600ms 延迟
 * （dismiss 动画 300ms + resetAfterDismiss 收尾）`startActivityFromRecents(保留id, null)`。
 * 语义代价：管家负责的缓存清理/内存释放 toast 不再发生。详见 `docs/reverse/launcher-recents-keepcurrent.md`。
 *
 * **为什么文件夹是「重开」而不是「拦住不让关」**：关文件夹由 quickstep 的应用切换转场驱动，
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

    /** 共享挂载基建（版本自检/重试循环/失败分级/汇总日志/方法解析）。闸门 = 功能开关（表级过滤下沉到引擎）。 */
    private val engine = MountEngine("LauncherHook", packageName, RemoteConfig)

    /** 版本选表：默认套 = 基线点；适配其他宿主版本时在其上追加精确套。 */
    private val versionTables = listOf(
        HostVersion.Table(versions = emptySet()) { LAUNCHER_POINTS_18_0_0_234 },
    )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        hostLoader = loader
        // 同 theme：版本读 + 选表 + 挂载整体挪到后台线程（宿主主线程零同步磁盘 IO；版本读是同步 IPC）。
        MountScheduler.postDelayed(0L) {
            val apkPath = param.applicationInfo?.sourceDir
            // 数据表 LAUNCHER_POINTS_18_0_0_234.kt；逐点清点口径以真机 `installed N/M` 的 M 为准。
            val pick = HostVersion.pick(versionTables, HostVersion.resolveHostVersion(apkPath, packageName))
            // 期望版本 = 基线：launcher 只有默认套，偏离基线 = 按基线盲挂。
            engine.begin(apkPath, FeatureCatalog.baselineVersionOf(packageName))
            val pending = pick.tasks
                .map { it.toMountTask() }

            engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
        }
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
                // N1 垃圾桶捕获（荣耀 ClearBox 链路）：`HnRecentsClearBox.onClick(View)`（:406）是
                // clear_all_recents_image_button 的唯一 listener（onFinishInflate :366-374 绑定），
                // 进 hook 时 recents 里还留着清后台前的全部任务。开关全关（A/B 都关）直接置 null
                // 跳过捕获（省一次反射链），对原生零干预。
                MountKind.ARM_KEEP_CURRENT -> {
                    val armed = RemoteConfig.isEnabled(FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_ATMS) ||
                        RemoteConfig.isEnabled(FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER)
                    val captured = if (armed) captureKeepSets(chain.thisObject) else null
                    keepTaskIds = captured?.first
                    killTaskIds = captured?.second
                    killTaskObjs = captured?.third
                    keepCapturedAtMs = if (captured != null) SystemClock.elapsedRealtime() else 0L
                    if (captured != null) {
                        engine.judge(
                            module, task, JUDGE_PREFIX,
                            "捕获: 保留 ${captured.first.size} 任务(id=${captured.first.joinToString(",")})，" +
                                "移除 ${captured.second.size} 卡/${captured.third.size} Task 对象",
                        )
                    }
                    chain.proceed()
                }
                // N2 全杀收口替换：`ActivityManagerWrapperEx.removeHwAllTask()`（:124，public 无参 void）
                // 是荣耀清后台唯一收口（RemoveTaskThread → sendRemoveAllTask 广播系统管家）。
                // flag 有效时整条替换，不 proceed；无效（未捕获/已超时/开关全关）透传原生全杀。
                // 消费即全清：ClearBox 链路里 removeHwAllTask 是单点同步收口（旧 quickstep 链路的
                // K2/K3 线程交错问题在此不存在），kill/keep 集先接局部变量再置空字段——postHotReturn
                // 是 600ms 后的主线程动作，期间任何再入不该读到半清状态。
                MountKind.REMOVE_HW_ALL_KEEP_CURRENT -> {
                    val atms = RemoteConfig.isEnabled(FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_ATMS)
                    val manager = RemoteConfig.isEnabled(FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER)
                    val keep = keepTaskIds
                    val kill = killTaskIds
                    val killObjs = killTaskObjs
                    val stale = keepFlagStale()
                    keepTaskIds = null
                    killTaskIds = null
                    killTaskObjs = null
                    keepCapturedAtMs = 0L
                    if ((!atms && !manager) || keep == null || kill == null || stale) {
                        // 垃圾桶 flow 之外的任何 removeHwAllTask 调用方一律透传，绝不拦截。
                        chain.proceed()
                    } else {
                        if (atms && manager) {
                            module.log(Log.WARN, MountEngine.TAG, "RECENTS-KEEP 两开关同开，按直杀(A)执行")
                        }
                        // 无论 removed 成败都要放行 latch：A 移除与 B 广播都不产生管家那条
                        // REPLY_TRIM_ALL 回执（原生唯一 countDown 在广播 receiver，:174），
                        // 不放行宿主线程卡 await 20s。
                        val removed = if (atms) {
                            removeTasksIndividually(kill) { reason ->
                                module.log(Log.WARN, MountEngine.TAG, "RECENTS-KEEP 逐个移除失败: $reason")
                            }
                        } else {
                            removeTasksViaManager(killObjs) { reason ->
                                module.log(Log.WARN, MountEngine.TAG, "RECENTS-KEEP 管家广播失败: $reason")
                            }
                        }
                        countDownLatchForRemoveTask { reason ->
                            module.log(Log.WARN, MountEngine.TAG, "RECENTS-KEEP latch 放行失败: $reason")
                        }
                        if (removed) {
                            val via = if (atms) "直杀A(ATMS removeTask)" else "管家广播B(sendRemoveTaskToSystemManager)"
                            val removedCount = if (atms) kill.size else (killObjs?.size ?: 0)
                            engine.judge(
                                module, task, JUDGE_PREFIX,
                                "已按${via}移除 $removedCount 个任务（移除集 ${kill.size} id），保留 id=${keep.joinToString(",")}",
                            )
                            postHotReturn(keep, module)
                        } else {
                            // 已动手才失败 ⇒ 透传原生全杀兜底（部分任务可能已被移除，原生全杀收尾幂等）。
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
     * N1 捕获：从 `HnRecentsClearBox`（[clearBox]，`chain.thisObject`）反射取 `mRecentsView`
     * （:139，public；运行时类型 `HnLauncherRecentsView`，继承 quickstep `RecentsView`），
     * 收集保留集与移除集。
     *
     * 保留集 = `getRunningTaskView().getTaskIds()`（:2779，public；quickstep 原生维护的
     * "进 overview 前的前台任务"，语义正好是需求里的"当前 app"）；移除集 = 遍历
     * `getTaskViewCount()`/`requireTaskViewAt(i)`（:2233/:3896，public）——`getTaskIds()`
     * （TaskView.java:575，可能含 -1）过滤后不在保留集的 id 进移除集；**贡献了移除 id 的卡**
     * 再取 `getTask()`（shlib Task，可能 null）收进 killTaskObjs 供 B 路径广播（B 只用对象
     * 不用 ids，与 killTaskIds 顺序对应不上没关系）。运行卡贡献 0 个移除 id ⇒ 其 Task 不进
     * 移除对象集，B 不会广播到保留任务。全部 public API 直连反射，不碰混淆面。
     * 返回 `null` = 任一环节失败或保留集为空（running task 已不在列表的罕见态）
     * ⇒ 本次清后台对原生行为零干预。
     */
    private fun captureKeepSets(clearBox: Any?): Triple<IntArray, IntArray, List<Any>>? = runCatching {
        if (clearBox == null) return@runCatching null
        val recentsView = clearBox.javaClass.getField("mRecentsView").get(clearBox)
            ?: return@runCatching null
        val running = recentsView.javaClass.getMethod("getRunningTaskView").invoke(recentsView)
            ?: return@runCatching null
        val keep = (running.javaClass.getMethod("getTaskIds").invoke(running) as IntArray)
            .filter { it >= 0 }   // getTaskIds 可能含 -1（TaskView.java:575），过滤后再判空
        if (keep.isEmpty()) return@runCatching null
        val keepSet = keep.toHashSet()
        val count = recentsView.javaClass.getMethod("getTaskViewCount").invoke(recentsView) as Int
        val requireTaskViewAt = recentsView.javaClass
            .getMethod("requireTaskViewAt", Int::class.javaPrimitiveType)
        val killIds = mutableListOf<Int>()
        val killObjs = mutableListOf<Any>()
        for (i in 0 until count) {
            val view = requireTaskViewAt.invoke(recentsView, i) ?: continue
            val ids = (view.javaClass.getMethod("getTaskIds").invoke(view) as IntArray)
                .filter { it >= 0 && it !in keepSet }
            if (ids.isEmpty()) continue
            killIds.addAll(ids)
            // 同卡 Task 对象（B 路径 sendRemoveTaskToSystemManager(Context, Task) 入参）；null 的卡跳过
            runCatching { view.javaClass.getMethod("getTask").invoke(view) }
                .getOrNull()?.let { killObjs.add(it) }
        }
        Triple(keep.toIntArray(), killIds.toIntArray(), killObjs)
    }.getOrNull()

    /**
     * flag 是否已超时。垃圾桶 flow 若被中断（动画异常、进程切换）残留脏标记，10s 内必失效，
     * 防止污染之后的任何 `removeHwAllTask` 调用方。
     */
    private fun keepFlagStale(): Boolean =
        keepCapturedAtMs <= 0L || SystemClock.elapsedRealtime() - keepCapturedAtMs > KEEP_FLAG_TIMEOUT_MS

    /**
     * A 路径逐个移除（N2 消费）：`ActivityManagerWrapperEx.getInstance().removeTask(int)`
     * （shlib :87，与单卡划走同 API）。单 id 失败不中断循环；实例/方法反射失败整体返回 `false`
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
     * 热回保留任务（N2 替换后经 [postHotReturn] 调）：`ActivityManagerWrapperEx
     * .startActivityFromRecents(int, ActivityOptions)`（基类 `ActivityManagerWrapper` :142 对
     * null options 有处理），与 recents 点卡片回 app 同一条 binder（ATMS.startActivityFromRecents）
     * ⇒ launcher 进程有权限、热启动不冷启。只把「抛异常」判为失败；wrapper 内部吞掉的
     * RemoteException（若有）无法在此感知。
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

    /**
     * B 路径逐个广播（N2 消费）：`HwRecentsTaskUtils.sendRemoveTaskToSystemManager(Context, Task)`
     * （shlib :178，public static；荣耀原生单任务清卡就是它，Task = shlib
     * `...recents.model.Task`，N1 捕获时从各移除卡 `getTask()` 收集）。
     *
     * Context 用 `android.app.AppGlobals.getInitialApplication()` 反射拿（launcher 进程的
     * Application，等价原生调用侧的 context）。列表 null/空 = 返回 `true`——没得杀也算完成，
     * 不打扰原生（judge 行会注明移除数）；单 task 广播失败不中断循环；类/方法反射失败 =
     * 返回 `false` ⇒ 调用侧透传原生全杀兜底。
     */
    private fun removeTasksViaManager(taskObjs: List<Any>?, onFail: (String) -> Unit): Boolean = runCatching {
        if (taskObjs.isNullOrEmpty()) return@runCatching true
        val loader = hostLoader ?: return@runCatching false.also { onFail("无宿主 ClassLoader") }
        val context = Class.forName("android.app.AppGlobals")
            .getMethod("getInitialApplication").invoke(null)
            ?: return@runCatching false.also { onFail("AppGlobals.getInitialApplication() 为 null") }
        val cls = Class.forName(HW_RECENTS_TASK_UTILS_CLASS, false, loader)
        val taskCls = Class.forName(HW_TASK_CLASS, false, loader)
        val send = cls.getMethod("sendRemoveTaskToSystemManager", android.content.Context::class.java, taskCls)
        taskObjs.forEach { task ->
            runCatching { send.invoke(null, context, task) }
                .onFailure { onFail("sendRemoveTaskToSystemManager(task) 异常 ${it.javaClass.simpleName}: ${it.message}") }
        }
        true
    }.getOrElse {
        onFail("异常 ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /**
     * 放行垃圾桶 flow 的收尾 latch：`HnRecentsClearBox.sCountDownLatch`（public static field，
     * :158，初始 count=1；唯一 countDown 在系统管家「清后台回执」广播 receiver，:174）。
     * A 直杀与 B 广播都不产生那条回执 ⇒ 不手动 countDown，宿主线程卡在 `await(20000ms)`。
     * 非 null 且 count>0 时 `countDown()`；拿不到字段/异常只 [onFail] 不抛（latch 卡 20s 后
     * 宿主自己超时放行，属可接受降级）。
     */
    private fun countDownLatchForRemoveTask(onFail: (String) -> Unit) {
        val latch = runCatching {
            val loader = hostLoader ?: return@runCatching null
            Class.forName(CLEAR_BOX_CLASS, false, loader)
                .getField("sCountDownLatch")
                .get(null) as? CountDownLatch
        }.getOrNull()
        when {
            latch == null -> onFail("拿不到 sCountDownLatch（无 ClassLoader 或字段异常）")
            latch.count > 0 -> latch.countDown()
        }
    }

    /**
     * 主线程延迟热回保留任务：`Handler(Looper.getMainLooper()).postDelayed([HOT_RETURN_DELAY_MS])`
     * 后遍历 [keep] 逐个 [startTaskFromRecents]，第一个成功即停。
     *
     * **为什么 600ms**：原生垃圾桶 flow = dismiss 动画 300ms + `resetAfterDismiss` 收尾
     * （清卡片视图/退出 overview），太早 `startActivityFromRecents` 会被收尾顶掉。
     * **热回失败不补任何动作**：原生 `HnLauncherRecentsView.onAllTasksRemoved`（:152）→
     * `goToState(LauncherState.NORMAL)` 的回桌面已在跑，回落 = 停在桌面，可接受。
     */
    private fun postHotReturn(keep: IntArray, module: XposedModule) {
        val looper = Looper.getMainLooper() ?: return
        Handler(looper).postDelayed({
            var startedId: Int? = null
            for (id in keep) {
                if (startTaskFromRecents(id) { reason ->
                        module.log(Log.WARN, MountEngine.TAG, "RECENTS-KEEP 热回任务 $id 失败: $reason")
                    }
                ) {
                    startedId = id
                    break
                }
            }
            if (startedId != null) {
                module.log(Log.INFO, MountEngine.TAG, "RECENTS-KEEP 已热回保留任务 id=$startedId")
            } else {
                module.log(Log.WARN, MountEngine.TAG, "RECENTS-KEEP 保留任务全部热回失败（原生回桌面已在跑，停留桌面）")
            }
        }, HOT_RETURN_DELAY_MS)
    }

    /** 宿主类加载器（挂载时捕获），供反射查宿主类。 */
    @Volatile
    private var hostLoader: ClassLoader? = null

    /** 「从文件夹里打开了应用」时记下的文件夹视图；在下一次回到桌面时消费（消费即清空）。 */
    @Volatile
    private var reopenTarget: Any? = null

    /**
     * 垃圾桶 flow 的一组临时标记（N1 捕获 → N2 唯一消费方消费即全清 → 超时兜底）。
     *
     * **为什么不再需要旧 K2/K3 式「拆分消费」（2026-10-13 缺陷修复的结论随之作废）**：
     * 旧 quickstep 链路里 `removeAllRecentTasks()` post 到后台线程、`startHome()` 主线程先行，
     * 两个消费点线程交错才需要互不清对方集合；荣耀 ClearBox 链路里 `removeHwAllTask` 是
     * **唯一收口、单点同步执行**（RemoveTaskThread 线程），N1/N2 一写一读，消费即全清即可。
     *
     * **为什么需要门控**：`removeHwAllTask` 收口无排除参数只能整条替换——不门控就会把
     * 「开关全关的垃圾桶清后台」与 flow 之外的任何调用方一并改掉。N1 在两开关全关时
     * 不捕获（零反射开销），N2 无 flag 一律透传。
     */
    /** N1 捕获的保留集（清后台时的前台任务 id，getTaskIds 过滤 -1 后）。 */
    @Volatile
    private var keepTaskIds: IntArray? = null

    /** N1 捕获的移除集（保留集之外的全部任务 id，A 路径逐个 removeTask 用）。 */
    @Volatile
    private var killTaskIds: IntArray? = null

    /**
     * N1 捕获的移除集 shlib Task 对象（B 路径 `sendRemoveTaskToSystemManager(Context, Task)`
     * 入参；运行卡不收，可能少于 [killTaskIds]，B 只用对象不用 ids）。
     */
    @Volatile
    private var killTaskObjs: List<Any>? = null

    /** N1 捕获时刻（elapsedRealtime ms），0 = 无效；[KEEP_FLAG_TIMEOUT_MS] 超时兜底。 */
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

    /** 垃圾桶按钮唯一 listener（launcher base，HnRecentsClearBox.java:406 onClick）。 */
    private const val CLEAR_BOX_CLASS =
        "com.hihonor.android.launcher.quickstep.views.HnRecentsClearBox"

    /** shlib 单任务广播工具（荣耀原生单卡清卡通道，HwRecentsTaskUtils.java:178 public static）。 */
    private const val HW_RECENTS_TASK_UTILS_CLASS =
        "com.hihonor.android.systemuisharedlib.systemui.shared.recents.hwutil.HwRecentsTaskUtils"

    /** shlib Task 模型（sendRemoveTaskToSystemManager 第二入参类型，TaskView.getTask() 运行时类型）。 */
    private const val HW_TASK_CLASS =
        "com.hihonor.android.systemuisharedlib.systemui.shared.recents.model.Task"

    /** 垃圾桶 flow flag 超时：捕获与收口在 RemoveTaskThread 上连续执行远小于此；中断残留 10s 内必失效。 */
    private const val KEEP_FLAG_TIMEOUT_MS = 10_000L

    /** 热回延时：dismiss 动画 300ms + resetAfterDismiss 收尾，太早会被收尾顶掉。 */
    private const val HOT_RETURN_DELAY_MS = 600L

    // ---------------- 挂载任务定义（与 ThemeStore/Installer 同构） ----------------

    /**
     * 挂载任务类型：CONST 常量短路；ARM_FOLDER_REOPEN 记下打开的文件夹；REOPEN_FOLDER_ON_RESUME 回到桌面时重开；
     * ARM_KEEP_CURRENT / REMOVE_HW_ALL_KEEP_CURRENT = 清后台保留当前应用两步
     * （N1 荣耀垃圾桶 onClick 捕获 → N2 全杀收口 removeHwAllTask 替换，docs/reverse/launcher-recents-keepcurrent.md）。
     */
    internal enum class MountKind {
        CONST, ARM_FOLDER_REOPEN, REOPEN_FOLDER_ON_RESUME,
        ARM_KEEP_CURRENT, REMOVE_HW_ALL_KEEP_CURRENT,
    }

    internal class MountTask(
        val kind: MountKind,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        val value: Any?,
        override val id: String,
        override val description: String,
        override val dex: MountEngine.DexHint? = null,
        /** 所属功能开关 key（空 = 常挂）；由引擎按闸门统一过滤。 */
        override val feature: String = "",
        /** 功能点 id（通路汇总用）；空 = 载体/探针行，不参与汇总。 */
        override val group: String = "",
    ) : MountEngine.Task

    /** 数据表挂载点 → 运行时任务（声明式字段落到具体类型）。 */
    internal fun HookPoint.toMountTask(): MountTask = MountTask(
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
        feature = feature,
        group = group,
    )
}
