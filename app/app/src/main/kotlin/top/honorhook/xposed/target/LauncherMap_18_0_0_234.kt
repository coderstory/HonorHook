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
)
