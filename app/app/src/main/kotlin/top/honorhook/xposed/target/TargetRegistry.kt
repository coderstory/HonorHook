package top.honorhook.xposed.target

/**
 * 目标注册表（融合 Rain va 静态注册表 + HC 布尔防重装；docs/rain-hook-architecture.md §1.2）。
 * [find] 按包名查表；[installed] 是完成首次 install 的**目标对象**集合——对象级判定：
 * 同一对象注册多个包名键（GlassRestoreHook 三键一组）共享安装状态，防别名包共享进程双装。
 */
object TargetRegistry {

    private val targets = mutableMapOf<String, TargetHook>()
    private val installed = mutableSetOf<TargetHook>()

    init {
        // 静态注册（Rain va.<clinit> 硬编码单例的等价物）：新增目标 = 新建 TargetHook + 此处一行，HookEntry 零改动。
        register(ThemeStoreHook)
        // F6 安装器净化（真机 14.0.37.301 基线实证 8 组全命中；scope.list 已声明 com.android.packageinstaller）
        register(HonorInstallerHook)
        // GlassRestoreHook（F7 液态玻璃）：3 键全 PkgAlias（docs §5.1）。主键 = 真机基线包名
        // com.hihonor.desktop.systemui（15.0.0.0.apk manifest 实证）；别名 com.android.systemui
        // （17.0.0.1.apk 内 magic 包类）、com.hihonor.android.launcher（uikit 框架类子集）；scope.list 已声明三包。
        // PkgAlias equals/hashCode 基于同一 delegate：Set 去重方向无关，任意装载顺序防共享进程双挂；
        // groupedTargetCount() 稳定归 4 组（= 应用 tab 4 行口径，HomeScreen.adaptedCount）。
        GlassRestoreHook.registryKeys().forEach(::register)
        // MarketHook（F5 市场净化 15 点）：真机 16.1.9.302 基线实证全命中（system-files/market-jadx 逐点对照）
        register(MarketHook)
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
     * 按对象去重的目标组数（= 应用 tab 行数口径；玻璃三键同 delegate 稳定归 1 组，与装载顺序无关）。
     */
    fun groupedTargetCount(): Int = targets.values.toSet().size
}