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
 *  - 模块进程 → 宿主进程：ACTION_XHS_AUTO_STOP（停止）/ _PAUSE（暂停·继续）/ _RETRY（重试失败篇）
 *  - 模块进程 → 宿主进程：ACTION_XHS_AUTO_QUERY（注入后对准真实状态）
 *  - 模块进程 → 宿主进程：ACTION_XHS_AUTO_CONFIG（每篇之间的随机节奏范围，分秒）
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
    /** 暂停/继续（模块进程 → 宿主进程）。 */
    const val PAUSE = "top.honorhook.action.XHS_AUTO_PAUSE"
    /** 重试本轮失败的篇（模块进程 → 宿主进程）。 */
    const val RETRY = "top.honorhook.action.XHS_AUTO_RETRY"
    const val CONFIG = "top.honorhook.action.XHS_AUTO_CONFIG"

    const val EXTRA_RUNNING = "top.honorhook.extra.RUNNING"
    const val EXTRA_TEXT = "top.honorhook.extra.TEXT"
    /** 节奏范围单位是**分秒（0.1 秒）**：UI 步进 0.5s，整秒存不下。 */
    const val EXTRA_PACE_MIN_DS = "top.honorhook.extra.PACE_MIN_DS"
    const val EXTRA_PACE_MAX_DS = "top.honorhook.extra.PACE_MAX_DS"
}
