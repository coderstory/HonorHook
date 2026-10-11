package top.honorhook.xposed.target

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
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

    /** 宿主类加载器（挂载时捕获），供反射查宿主类。 */
    @Volatile
    private var hostLoader: ClassLoader? = null

    /** 「从文件夹里打开了应用」时记下的文件夹视图；在下一次回到桌面时消费（消费即清空）。 */
    @Volatile
    private var reopenTarget: Any? = null

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "launcher-hook"

    /** hook 编写时的宿主版本基线（dumpsys package 实证）；OTA 超出时 MountEngine 首轮挂载打 WARN。 */
    private const val HOST_VERSION_BASELINE = "18.0.0.234"

    /** 反射入口类名。 */
    private const val LAUNCHER_CLASS = "com.hihonor.android.launcher.Launcher"
    private const val FOLDER_MANAGER_CLASS = "com.hihonor.android.launcher.folder.FolderManager"
    private const val FOLDER_ICON_CLASS = "com.hihonor.android.launcher.FolderIcon"

    /** 重开延时：等应用关闭动画走完（`canOpenFolder` 与动画互斥，太早会被挡掉）。 */
    private const val REOPEN_DELAY_MS = 320L

    /** 重开尝试次数上限（首轮 + 重试）。 */
    private const val REOPEN_MAX_ATTEMPTS = 2

    // ---------------- 挂载任务定义（与 ThemeStore/Installer 同构） ----------------

    /** 挂载任务类型：CONST 常量短路；ARM_FOLDER_REOPEN 记下打开的文件夹；REOPEN_FOLDER_ON_RESUME 回到桌面时重开。 */
    private enum class MountKind { CONST, ARM_FOLDER_REOPEN, REOPEN_FOLDER_ON_RESUME }

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
