package top.honorhook.xposed.target

/**
 * 目标注册表（融合 Rain va 静态注册表 + HC 布尔防重装；docs/rain-hook-architecture.md §1.2）。
 * [find] 按包名查表；[installed] 是完成首次 install 的**目标对象**集合——对象级判定：
 * 同一对象注册多个包名键时共享安装状态，防别名包共享进程双装。
 */
object TargetRegistry {

    private val targets = mutableMapOf<String, TargetHook>()
    private val installed = mutableSetOf<TargetHook>()

    init {
        // 静态注册（Rain va.<clinit> 硬编码单例的等价物）：新增目标 = 新建 TargetHook + 此处一行，HookEntry 零改动。
        // scope.list 须同步声明目标包名（targets 与 scope 不一致时 HookEntry 收不到该包的回调）。
        register(ThemeStoreHook)
        register(InstallerHook)
    }

    /** 注册目标（同包重复注册以最后一次为准）。 */
    fun register(target: TargetHook) {
        targets[target.packageName] = target
    }

    /** 按包名查目标（未注册返回 null：被注入但无适配器时静默，照 Rain 同情形）。 */
    fun find(packageName: String): TargetHook? = targets[packageName]

    /**
     * 目标对象是否已装（对象级防重装门控）：多键共享安装状态，别名包共享进程时防 double mount。
     */
    fun isInstalled(target: TargetHook): Boolean = target in installed

    fun markInstalled(target: TargetHook) {
        installed += target
    }

    /**
     * 按对象去重的目标组数（= 应用 tab 行数口径；多键目标同 delegate 稳定归 1 组，与装载顺序无关）。
     */
    fun groupedTargetCount(): Int = targets.values.toSet().size
}
