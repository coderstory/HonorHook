package top.honorhook.xposed.target

/**
 * 目标注册表（融合 Rain va 静态注册表 + HC 布尔防重装，
 * docs/rain-hook-architecture.md §1.2 / docs/hc-hook-structure.md §1.2）。
 *
 * - [find]：按包名查表（Rain va.a 线性查的等价物，Map 直查；目标规模小无需进一步优化）；
 * - [installed]：已完成首次 install 的包集合——同包二次回调跳过 install，
 *   （照 HC 每个 hook 一组 xxxInstalled 布尔防重装的集中形态）。
 */
object TargetRegistry {

    private val targets = mutableMapOf<String, TargetHook>()
    private val installed = mutableSetOf<String>()

    init {
        // 静态注册所有目标（Rain va.<clinit> 硬编码单例的等价物）：
        // 新增目标 = 新建 TargetHook 实现 + 此处追加一行，HookEntry 零改动。
        register(ThemeStoreHook)
        // HonorInstallerHook（com.android.packageinstaller，F6 安装器净化）：
        // 8 组 hook 已按 docs/honor-plugins-integration.md §三 移植完成（真机 14.0.37.301 基线实证全部命中），
        // 配套 scope.list 已声明 com.android.packageinstaller。
        register(HonorInstallerHook)
        // GlassRestoreHook（F7 液态玻璃恢复）：同一 object 注册 3 键（docs §5.1——TargetRegistry 按单包名查，
        // 挂载按类解析分进程命中）。主键 = 真机基线实际包名 com.hihonor.desktop.systemui（系统界面_15.0.0.0.apk
        // manifest 实证）；别名 com.android.systemui（系统用户界面_17.0.0.1.apk，magic 包类全部在此）/
        // com.hihonor.android.launcher（桌面 com.hihonor 进程的 uikit 框架类子集）。配套 scope.list 已声明三包。
        register(GlassRestoreHook)
        GlassRestoreHook.aliases().forEach(::register)
        // MarketHook（F5 市场净化——核心组 15 点）：真机 16.1.9.302 基线实证全部命中
        // （system-files/market-jadx，类/方法/参数个数逐点对照，见 MarketHook 类注释），
        // 配套 scope.list 已声明 com.hihonor.appmarket。
        register(MarketHook)
    }

    /** 注册目标实现；同包重复注册以最后一次为准（正常流程每个目标只注册一次） */
    fun register(target: TargetHook) {
        targets[target.packageName] = target
    }

    /** 按包名查目标；未注册返回 null——进程被注入但无适配器时静默忽略（照 Rain scope 声明但无适配器的情形） */
    fun find(packageName: String): TargetHook? = targets[packageName]

    /** 该包是否已完成首次 install（防重装门控） */
    fun isInstalled(packageName: String): Boolean = packageName in installed

    /** 标记该包已完成 install */
    fun markInstalled(packageName: String) {
        installed += packageName
    }
}