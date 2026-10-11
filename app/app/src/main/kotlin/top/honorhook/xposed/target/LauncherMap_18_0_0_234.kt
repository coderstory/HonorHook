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
    // L2a 返回动画消费点：5 处 `if (getNeedCloseFloderOnSaveState()) closeFolder(...)`（swipe-home /
    // icon-back / exit-app 动画等）。恒返回 false 使其全部跳过。方法名拼写为 Floder（宿主原样）。
    HookPoint(
        id = "launcher:Launcher.getNeedCloseFloderOnSaveState",
        kind = "CONST",
        className = "com.hihonor.android.launcher.Launcher",
        methodName = "getNeedCloseFloderOnSaveState",
        paramCount = 0,
        returnType = "boolean",
        value = false,
        feature = FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN,
        description = "L2a 返回动画不再关文件夹（getNeedCloseFloderOnSaveState→false：swipe-home/icon-back/exit-app 等消费点跳过 closeFolder）",
    ),
    // L2b 主关闭路径：`Launcher.onStop()` 里【直接读字段】`mNeedCloseFolderOnSaveState`
    // （`if (mNeedCloseFolderOnSaveState && mNeedCloseFolderWhenGoingLazyMode) closeFolder(false)`），
    // 两字段默认 true 且 doResumeWork 每次回前台重新武装 ⇒ 条件恒真。点开文件夹内应用令 launcher 退后台
    // 即触发 onStop 静默关文件夹——这是「返回桌面时文件夹已关」的现症根因，钩 getter 无效（不走 getter）。
    // 对策：命中 onStop 时先把该字段置 false 再放行。
    HookPoint(
        id = "launcher:Launcher.onStop",
        kind = "KEEP_FOLDER_ON_STOP",
        className = "com.hihonor.android.launcher.Launcher",
        methodName = "onStop",
        paramCount = 0,
        returnType = "void",
        feature = FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN,
        description = "L2b onStop 不再关文件夹（置 mNeedCloseFolderOnSaveState=false 后放行，跳过 closeFolder(false)）",
    ),
    // L3 现症根因：按 Home 返回桌面时 `onNewIntent`（action=MAIN）→ `doMainActionIntent(intent, openFolder, …)`
    // → `doShowWorkspaceAnim(z, folder, …)` → `closeFolder(folder, z)`。此链**不经任何关闭旗**，
    // 故 L2a/L2b 都拦不住（真机日志：onNewIntent 后 3ms 即 onCloseFolder）。对策：命中时把 folder 实参置 null
    // 再放行——只跳过关文件夹，工作区动画等其余逻辑不变。
    HookPoint(
        id = "launcher:Launcher.doMainActionIntent",
        kind = "DROP_FOLDER_ON_HOME",
        className = "com.hihonor.android.launcher.Launcher",
        methodName = "doMainActionIntent",
        paramCount = 3,
        returnType = "void",
        feature = FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN,
        description = "L3 按 Home 返回桌面不再收起文件夹（doMainActionIntent 的 folder 实参置 null，跳过 closeFolder）",
    ),
    // L4 手势返回路径（现症真凶，真机诊断栈实证）：手势导航下从应用上滑回桌面，在**手势开始的那一刻**
    // 由 quickstep 的例行重置关掉文件夹，两条链都不看关闭旗：
    //   ① `AbsSwipeUpHandler.onGestureStarted → LauncherActivityInterface.closeOverlay() → closeFolder(false)`
    //   ② `prepareRecentsUI → initBackgroundStateUI → goToState(BACKGROUND_APP)
    //      → HnRecentsViewStateController.processStateForOverView() → closeFolder(false)`
    // L2a/L2b 全拦不住（不看旗）；L3 只覆盖 Intent(MAIN) 回桌面（三键 Home），手势返回不经此链。
    // 对策：命中 `Launcher.closeFolder(boolean)` 且栈上同时出现「别的应用在前台的手势消费者」与上述两入口之一时
    // **直接返回不执行**；点外部关闭、onStop、返回动画等其余 closeFolder 调用照常。
    HookPoint(
        id = "launcher:Launcher.closeFolder",
        kind = "KEEP_FOLDER_ON_RETURN_GESTURE",
        className = "com.hihonor.android.launcher.Launcher",
        methodName = "closeFolder",
        paramCount = 1,
        returnType = "void",
        // Launcher 有 4 个 closeFolder 重载，(Folder)V 与 (Z)V 同为「1 参 + void」——必须锁参数类型。
        dexParamTypes = listOf("boolean"),
        feature = FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN,
        description = "L4 从应用上滑回桌面不再收起文件夹（返回手势重置链上的 Launcher.closeFolder 短路）",
    ),
    // DX 诊断：FolderManager.closeFolder(Folder, Z) 是唯一汇聚点（IAction.onCloseFolder 回调在此）。
    // 命中即打最近调用方一行 + 放行——「谁关了文件夹」这一问已三次误判，留作常备取证。
    HookPoint(
        id = "launcher:FolderManager.closeFolder",
        kind = "TRACE_FOLDER_CLOSE",
        className = "com.hihonor.android.launcher.folder.FolderManager",
        methodName = "closeFolder",
        paramCount = 2,
        returnType = "void",
        feature = FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN,
        description = "DX 诊断：打印 FolderManager.closeFolder 最近调用方（定位自动关闭触发者）",
    ),
)
