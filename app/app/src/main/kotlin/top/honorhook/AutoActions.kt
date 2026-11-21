package top.honorhook

/**
 * 批量采集的跨进程广播契约（唯一事实源）。
 *
 * **为什么单独一个文件**：常量是 `const val`，编译期内联进调用方字节码，
 * 模块进程引用它们**不会触发任何类加载**。反例是直接引用 hook 侧的
 * `top.honorhook.xposed.target.xhs.XhsAutoEntry`——那个类顶部 import 了
 * `io.github.libxposed.api.XposedModule`（compileOnly，只有 LSPosed 注入进程里存在），
 * 无障碍服务（跑在模块自己的进程）一加载它就 `NoClassDefFoundError` 崩溃，
 * 系统设置里表现为「服务异常」（2026-11-21 真机踩到，非推断）。
 *
 * 通道方向：
 *  - 宿主进程（小红书）→ 模块进程：ACTION_XHS_AUTO_TOGGLE（启停滚动）
 *  - 宿主进程 → 模块进程：ACTION_XHS_AUTO_STATE / _PROGRESS / _DONE（进度与状态回传）
 *  - 模块进程 → 宿主进程：ACTION_XHS_AUTO_STOP（UI 页停止按钮）
 *  - 模块进程 → 宿主进程：ACTION_XHS_AUTO_QUERY（注入后对准真实状态）
 */
object AutoActions {
    const val MODULE_PACKAGE = "io.github.coderstory.honorhook"
    const val HOST_PACKAGE = "com.xingin.xhs"

    const val TOGGLE = "top.honorhook.action.XHS_AUTO_TOGGLE"
    const val STATE = "top.honorhook.action.XHS_AUTO_STATE"
    const val QUERY = "top.honorhook.action.XHS_AUTO_QUERY"
    const val PROGRESS = "top.honorhook.action.XHS_AUTO_PROGRESS"
    const val DONE = "top.honorhook.action.XHS_AUTO_DONE"
    const val STOP = "top.honorhook.action.XHS_AUTO_STOP"

    const val EXTRA_RUNNING = "top.honorhook.extra.RUNNING"
    const val EXTRA_TEXT = "top.honorhook.extra.TEXT"
}
