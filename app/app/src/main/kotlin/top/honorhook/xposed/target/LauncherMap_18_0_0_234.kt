package top.honorhook.xposed.target

import top.honorhook.FeatureCatalog
import top.honorhook.xposed.data.HookPoint

/**
 * 系统桌面挂载点表（宿主基线 18.0.0.234，2026-10-11 反编译实证）。
 *
 * 单一事实源：新增/调整挂载点只改此表，运行时经 [HostVersion] 选表、[LauncherHook] 按 kind 分派。
 * 逆向依据见 `docs/reverse/launcher.md`（卡片标题）与 `docs/reverse/launcher-folder-keepopen.md`（文件夹）。
 */
val LAUNCHER_POINTS_18_0_0_234 = listOf(
    // L1 卡片标题隐藏：ITheme 出口 ThemeUtilImpl.isHiddenTitle()→true，仅卡片标题空态，不影响桌面图标名。
    // 类在桌面 base 包 classes3.dex（非插件 ClassLoader），直连反射即可命中。
    HookPoint(
        id = "launcher:themeUtil.isHiddenTitle",
        kind = "CONST",
        className = "com.hihonor.android.plugin.service.theme.ThemeUtilImpl",
        methodName = "isHiddenTitle",
        paramCount = 0,
        returnType = "boolean",
        value = true,
        feature = FeatureCatalog.KEY_LAUNCHER_HIDE_TITLE,
        description = "L1 卡片标题隐藏（ThemeUtilImpl.isHiddenTitle→true：主/副标题置空 + 2X2 整行 INVISIBLE；图标名不受影响）",
    ),
    // R1 记录目标：用户在打开的文件夹里点了应用。
    // `Folder.onClick` 的应用分支走到这里（`startActivityByClickIcon(View, ShortcutInfo)`，1091 行，private），
    // 此刻文件夹**必然还开着** —— 这是唯一能无歧义拿到"要重开谁"的时刻（`onPause`/`onStop` 时可能已被关掉）。
    HookPoint(
        id = "launcher:Folder.startActivityByClickIcon",
        kind = "ARM_FOLDER_REOPEN",
        className = "com.hihonor.android.launcher.Folder",
        methodName = "startActivityByClickIcon",
        paramCount = 2,
        returnType = "void",
        feature = FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN,
        description = "R1 记下「从文件夹里打开了应用」的那个文件夹（返回桌面后重开的目标）",
    ),
    // R2 重开：回到桌面时（`Launcher.onResume`）按系统自身路径 `FolderManager.openFolder(FolderIcon)`
    // 重新打开 R1 记下的文件夹。**刻意不在关闭链上做任何拦截** —— 非线性应用关闭动画会把应用图标
    // 收回文件夹位置，此时让文件夹同时停在打开态是自相矛盾的状态（实测：有时不生效、有时只剩一层模糊遮罩）。
    // 走系统自身的开文件夹路径 ⇒ 展开动画/窗口模糊/FolderInfo 状态全由系统负责，模块不碰视觉状态。
    // 宿主 `Launcher.onResume()`（3090 行）无参数、返回 void。
    HookPoint(
        id = "launcher:Launcher.onResume",
        kind = "REOPEN_FOLDER_ON_RESUME",
        className = "com.hihonor.android.launcher.Launcher",
        methodName = "onResume",
        paramCount = 0,
        returnType = "void",
        feature = FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN,
        description = "R2 回到桌面后自动重开该文件夹（走系统自身开文件夹路径，动画与遮罩完整）",
    ),
    // N1 垃圾桶捕获（荣耀 ClearBox 链路）：onClick 是 clear_all_recents_image_button 的唯一 listener
    // （HnRecentsClearBox.onFinishInflate :366-374 绑定）。feature 留空 = 载体点，运行时由
    // keepAtmsEnabled/keepManagerEnabled 组合门控（两开关全关则零干预）。
    HookPoint(
        id = "launcher:HnRecentsClearBox.onClick",
        kind = "ARM_KEEP_CURRENT",
        className = "com.hihonor.android.launcher.quickstep.views.HnRecentsClearBox",
        methodName = "onClick",
        paramCount = 1,
        returnType = "void",
        feature = "",
        description = "N1 荣耀垃圾桶捕获：记保留集（运行任务）与移除集（其余卡片 ids+Task 对象），供 N2 消费",
    ),
    // N2 全杀收口替换：removeHwAllTask 是荣耀清后台唯一收口（RemoveTaskThread → 广播系统管家，
    // ActivityManagerWrapperEx.java:124）。flag 有效时整条替换（A=ATMS 逐个 removeTask / B=逐个
    // sendRemoveTaskToSystemManager 广播），随后反射 countDown sCountDownLatch（public static，
    // 不放行会卡 await 20s），不 proceed；无效透传原生。
    HookPoint(
        id = "launcher:ActivityManagerWrapperEx.removeHwAllTask",
        kind = "REMOVE_HW_ALL_KEEP_CURRENT",
        className = "com.hihonor.android.systemuisharedlib.systemui.shared.system.ActivityManagerWrapperEx",
        methodName = "removeHwAllTask",
        paramCount = 0,
        returnType = "void",
        feature = "",
        description = "N2 全杀收口替换：flag 有效时按开关 A/B 逐个移除移除集并放行 latch，无效透传原生",
    ),
)
