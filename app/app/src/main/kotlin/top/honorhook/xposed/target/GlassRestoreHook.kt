package top.honorhook.xposed.target

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import top.honorhook.data.KEY_F7_GLASS_ENABLED
import top.honorhook.data.KEY_HOOK_ENABLED
import top.honorhook.xposed.MethodResolver
import top.honorhook.xposed.RemotePrefsHolder

/**
 * 荣耀 SystemUI 目标（F7 液态玻璃恢复）——移植自 reference/HonorLiquidGlassRestore v2.5.18
 * 单文件 MainHook.java（旧版 Xposed API → libxposed 102 改写，对应关系见 docs/honor-plugins-integration.md §5.4；
 * 面板 10 组 hook 核心直移；锁屏合成链 ~600 行标二期，未移植——见 "二期（未移植）" 节）。
 *
 * 真机基线校验（2026-10-09，system-files 双 SystemUI 包）：
 * - 系统界面_15.0.0.0.apk → **com.hihonor.desktop.systemui**（本目标主包名，appt2 dump badging 实证）；
 * - 系统用户界面_17.0.0.1.apk → com.android.systemui（**magic 包类全部在此包**，本目标别名注册）。
 * 面板 10 组 hook 逐点对照反编译产物（systemui-jadx = 15.0.0.0 全量；17.0.0.1 按需 jadx 单类），
 * **全部命中、签名一致**，迁移/丢弃清单：
 * 1. TransparentModeManager（com.hihonor.systemui.magic.transparentmode，仅 17.0.0.1）：
 *    getTransparentModeState(Configuration,String) L154（大方法，string building + 多调用，AOT 不内联）、
 *    isLiquidBlurType() L176 / isFrostedBlurType() L172（`mIsTransparentModeOn && mBlurType==0x10000`）、
 *    getTransparentBlurRadius(int) L102；字段 mBlurType L9 / mIsTransparentModeOn L11；
 *    位域常量与插件完全一致（0xF0000 mask / 0x10000 FROSTED / 0x20000 LIQUID，源码 `& 983040` 实证）✓
 * 2. BlurParametersConfig（magic.blur，仅 17.0.0.1）：initBulrParams(int,BitmapVisualEffectStyle,boolean) L306
 *    大方法 + 24 个字段全部命中（liauidGlass*×3、control*×4、slider*×2、button*×2、multi*×4、
 *    dynamicCardGradient*×2、refraction/depth/thickness/dispersion/blurRadius/dynamicCardBlurRadius/mContext）✓
 * 3. ShotBlurView（com.android.magic.blur，仅 17.0.0.1）：setBlurParams(float,int) L402 ✓
 * 4. EdgeLightParamEx（com.hihonor.android.graphics.material.atomiceffect——**框架 bootclasspath 类**，
 *    两个 APK dex 均只有引用；uikit 变体 com.hihonor.uikit.hnmagicsystem.graphics.material.atomiceffect.
 *    EdgeLightParamEx 经 HwReflectUtil 反射委托构造框架版，间接实证其存在）：2 参构造器 (int,float) + setColor(int) ✓
 * 5. AbsHighGradeDegreeAlgorithm（uikit transparency，15.0.0.0 L88 / 17.0.0.1 L30 同签名）：
 *    withTransparency(IGradeParamsRepo,int) 大方法 + 字段 mHighGradeParamsRepo（两版都存在，17.0.0.1 L7/L34）；
 *    BlurParam{radius,alphas,RGBs,maskColors} 四字段命中（jadx 实证 L14-19）；ColorHelper.composeColors(int[],int[]) ✓
 * 6. UiModeManagerEx（com.hihonor.android.app——框架类，uikit wrapper 反射委托实证存在）：getTransparentTexture/getTransparentDegree ✓
 * 7. MachineConfig（uikit，15.0.0.0 实证）：getInstance(Context)、isHighMachine/isCompatMachine/getMachineType
 *    + 字段 transparencyMaterialLevel/isSupportTransConfig/machineType 三字段全命中（jadx L9-11）；MachineType.HIGH ✓
 * 8. StyleType（uikit，15.0.0.0 L29）：getMaterialType() + 常量 FROSTED_GLASS_TRANSPARENCY(2)/LIQUID_GLASS_TRANSPARENT(4) ✓
 *    ——**版本差异一处**：插件调 uikit UiModeManagerEx.getTransparentMode 为 callStaticMethod，真机 15/17 该方法是
 *    **instance 方法**（jadx 实证 L45，需 getInstance() 后调用），本移植双轨容错（static 优先 → instance 兜底）
 * 9. TransparencyFeatureConfig（uikit，15.0.0.0 L62）：getConfigTransparentModeMaterial(Context) 恒返 3 ✓
 * 10. ConfigurationEx（com.hihonor.android.content.res——框架类，17.0.0.1 TransparentModeManager L156 直接
 *     `new ConfigurationEx(configuration)` 实证存在）：getTransparentMode() 位改写 ✓
 * 无效点/版本不匹配点：**无**——10 组全部照移；唯一行为差异点 = 上表 #8 的 instance 适配（已处理）。
 *
 * 二期（未移植，标 f7_glass_keyguard 子位）：
 * - 锁屏玻璃位图合成链：KeyguardWallpaper.getCurrentBlurBitmapForBg / processWallpaperChange / ensureLightBitmap /
 *   captureStockBitmap / fireKgReapply / reapplyIconTargets（原 MainHook.java L1043-1582）；
 * - 锁屏快捷按钮玻璃：ViewBlur.blurIcon 5 参重定向 + IconTarget 追踪（原 L1186-1290）；
 * - 锁屏卡 kbshade 配方（initBulrParams 内 case=2 && z=true 分支，原 L478-524）+ NotificationBackgroundViewModel
 *   turnOnViewBlur 边光门（原 L273-293）+ EdgeLightParamEx 构造器 kedge 分支（本文件 EDGE_CTOR 已剔除 sKgCardNow）；
 * - 纯观测 kdiag 链（KeyguardBlurUtils/ViewBlur/KeyguardWallpaper/BokehDrawable/KeyguardBusinessDataProvider/
 *   ToolBoxView/GlassParamEx/HnMaterialManager，原 L1679-1912）；
 * - 对应持久化属性 kbg/kbblur/kveil/kbbright/kedge/keng/kicon/kdiag。
 *
 * Boot-loop 纪律（v2.3 事故史，原 MainHook.java L75-84 安全规则全量继承）：
 * - 禁 Class.forName(initialize=true)：全部类解析走 [loader.loadClass]（不触发 <clinit>）；
 * - 禁硬编码资源 id：nmask 底色调色与面板遮罩全部走反射字段读写，无资源 id 参与（原 kbshade 的
 *   resolveStockColor 属二期，未移植）；
 * - 每个 hook 体独立 try/catch（[mountOne] + 各 intercept body 的 runCatching），单点失败绝不崩宿主；
 * - 未验证 hook 默认关：**F7 默认 false**，开启后再看 [glass] 挂载汇总。
 *
 * AOT 内联免疫设计（照原 MainHook.java L38-40 只挂大方法/构造器 + 改字段不改返回值）：
 * 核心机制 = getTransparentModeState / initBulrParams / withTransparency 三个大方法 after 改写
 * **缓存字段**（mBlurType / 各遮罩色 / blurRadius），后续内联读取点全命中；
 * isLiquidBlurType/isFrostedBlurType/isHighMachine 等小 getter 的常量替换为尽力而为（可能被 AOT 内联绕过，
 * 但同字段改写已让内联读者直接见修正值）；EdgeLightParamEx 走构造器（永不内联）；所有挂点与源码一致未改小。
 *
 * 开关语义（照 HC 模式 + F6 同构）：挂载无条件（onPackageLoaded 时机），每个 intercept 内**现读**远程 prefs
 * （[KEY_HOOK_ENABLED] 总开关 × [KEY_F7_GLASS_ENABLED]）决定本次是否生效——总关 = 原逻辑放行；F7 默认关
 * （boot-loop 事故史，保守）。光学参数（mask/radius/sat/nmask/refract/thick/disp/rim/edge/tblur/folder/degree）
 * **保持 setprop 通道**（persist.sys.lgr.*，2s TTL 缓存，需 root 的 adb 操作），不进 UI——UI 只放 F7 总开关
 * （docs §5.2 决策）。
 *
 * 挂载健壮性（照 HonorInstallerHook 模式）：类/方法未解析的挂载项收集，Handler postDelayed 约 500ms 重试一轮、
 * 再失败约 2s 最后一轮，仍失败 WARN 放弃；收尾始终输出 `GlassRestoreHook: installed N/M` 汇总。
 * **挂载成功始终输出日志**（不受 debug 门控）——真机从 LSPosed 日志（TAG HonorHook）过滤 "[glass]"
 * 确认各 hook 命中（对应插件 "hook ... ok / skip" 行的定位作用）。
 */
object GlassRestoreHook : TargetHook {

    /** 真机基线实际包名（系统界面_15.0.0.0.apk manifest 实证）；同名实现另注册 com.android.systemui / com.hihonor.android.launcher 两键，见 [aliases]。 */
    override val packageName = "com.hihonor.desktop.systemui"

    /** com.android.systemui / com.hihonor.android.launcher 别名注册（docs §5.1：同一 object 注册 3 键——TargetRegistry 按单包名查；挂载按类解析，进程天然各取所需）。 */
    fun aliases(): List<TargetHook> = listOf("com.android.systemui", "com.hihonor.android.launcher")
        .map { PkgAlias(it, this) }

    /** 包名委托适配器：install 委托给同一实现（其结果按 [param.packageName] 与类解析分进程命中）。 */
    private class PkgAlias(
        override val packageName: String,
        private val delegate: TargetHook,
    ) : TargetHook {
        override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) =
            delegate.install(module, param)

        // 与委托对象等值（组归属语义，非引用身份）：同一 object 的 3 包名键视为同一目标组——
        // ① TargetRegistry.installed 对象级判定跨键生效（别名共享进程时第二个键回调不重复 install，防 double mount）；
        // ② groupedTargetCount() 玻璃三键归 1 组（=4，与应用 tab 4 行口径一致）。
        override fun equals(other: Any?): Boolean =
            other === delegate || (other is PkgAlias && other.delegate === delegate)

        override fun hashCode(): Int = delegate.hashCode()
    }

    // ---------- 目标类（与源码 MainHook.java L90-115 逐字一致；框架类 com.hihonor.android.* 为 bootclasspath，进程内可加载） ----------
    private const val TMM_CLASS = "com.hihonor.systemui.magic.transparentmode.TransparentModeManager"
    private const val BPC_CLASS = "com.hihonor.systemui.magic.blur.BlurParametersConfig"
    private const val SHOTBLUR_CLASS = "com.android.magic.blur.ShotBlurView"
    private const val EDGE_LIGHT_CLASS = "com.hihonor.android.graphics.material.atomiceffect.EdgeLightParamEx"
    private const val DEGREE_ALGO_CLASS = "com.hihonor.uikit.transparency.data.algorithm.degree.AbsHighGradeDegreeAlgorithm"
    private const val COLOR_HELPER_CLASS = "com.hihonor.uikit.transparency.utils.ColorHelper"
    private const val CFG_CLASS = "com.hihonor.uikit.transparency.configuration.TransparencyFeatureConfig"
    private const val MACHINE_CLASS = "com.hihonor.uikit.transparency.configuration.MachineConfig"
    private const val MACHINE_TYPE_CLASS = "com.hihonor.uikit.transparency.configuration.MachineType"
    private const val STYLE_TYPE_CLASS = "com.hihonor.uikit.transparency.style.StyleType"
    private const val UIMODE_WRAPPER_CLASS = "com.hihonor.uikit.hnmagicsystem.app.UiModeManagerEx"
    private const val UIMODE_FW_CLASS = "com.hihonor.android.app.UiModeManagerEx"
    private const val CONFIG_EX_CLASS = "com.hihonor.android.content.res.ConfigurationEx"

    // ---------- 材质位常量（真机 17.0.0.1 源码 `& 983040` 实证一致） ----------
    private const val BLUR_TYPE_MASK = 0xF0000
    private const val BLUR_TYPE_FROSTED = 0x10000
    private const val BLUR_TYPE_LIQUID = 0x20000

    // ---------- 光学参数属性（persist.sys.lgr.*，2s TTL；只移植面板相关，锁屏 k* 属性归二期） ----------
    private const val DEGREE_PROP = "persist.sys.lgr.degree"
    private const val RADIUS_PROP = "persist.sys.lgr.radius"
    private const val MASK_PROP = "persist.sys.lgr.mask"
    private const val SAT_PROP = "persist.sys.lgr.sat"
    private const val NTF_MASK_PROP = "persist.sys.lgr.nmask"
    private const val REFRACT_PROP = "persist.sys.lgr.refract"
    private const val EDGE_PROP = "persist.sys.lgr.edge"
    private const val THICK_PROP = "persist.sys.lgr.thick"
    private const val DISP_PROP = "persist.sys.lgr.disp"
    private const val RIM_PROP = "persist.sys.lgr.rim"
    private const val FOLDER_PROP = "persist.sys.lgr.folder"
    private const val TBLUR_PROP = "persist.sys.lgr.tblur"

    private const val DEFAULT_DEGREE = 85
    private const val DEFAULT_RADIUS = 0.7f
    private const val DEFAULT_MASK = 0.7f
    private const val DEFAULT_SAT = 1.2f
    private const val DEFAULT_NTF_MASK = 0.4f
    private const val DEFAULT_REFRACT = 1.0f
    private const val DEFAULT_EDGE = 1.4f
    private const val DEFAULT_RIM = 1.0f
    private const val DEFAULT_TBLUR = 0.6f
    private const val DEFAULT_THICK = 1.4f
    private const val DEFAULT_DISP = 0.25f
    private const val DEFAULT_FOLDER = 1.0f

    // ---------- 日志计数节流（照源码各 sXxxCount，只打前几次防刷屏） ----------
    private var sFlipLogged = false
    private var sTextureLogged = false
    private var sDegreeLogged = false
    private var sConfigLogged = false
    private var sConfigDiagCount = 0
    private var sTextureDiagCount = 0
    private var sStateCount = 0
    private var sRadiusCount = 0
    private var sMaskCount = 0
    private var sNtfCount = 0
    private var sRefractLogged = 0
    private var sEdgeCount = 0
    private var sRimCount = 0
    private var sFolderLogged = 0
    private var sTileBlurLogged = 0
    private var sUikitCount = 0
    private var sPanelCount = 0

    // ---------- 属性缓存（2s TTL：setprop 无需重启即生效，清空属性回落烤入默认值） ----------
    private var sPropCacheAt = Long.MIN_VALUE
    private var sRadiusScale = DEFAULT_RADIUS
    private var sMaskScale = DEFAULT_MASK
    private var sSatScale = DEFAULT_SAT
    private var sNtfMask = DEFAULT_NTF_MASK
    private var sRefractScale = DEFAULT_REFRACT
    private var sEdgeScale = DEFAULT_EDGE
    private var sThickScale = DEFAULT_THICK
    private var sDispScale = DEFAULT_DISP
    private var sRimScale = DEFAULT_RIM
    private var sFolderMode = false
    private var sTileBlurScale = DEFAULT_TBLUR
    private var sMinDegree = DEFAULT_DEGREE

    // 枚举常量解析缓存（进程内稳定，挂载/触发时懒解析）
    @Volatile private var sMachineHigh: Any? = null
    @Volatile private var sStyleLiquid: Any? = null
    @Volatile private var sStyleFrosted: Any? = null

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // 注意：libxposed 102 的 PackageLoadedParam 只有 defaultClassLoader（无 classLoader 属性，ThemeStoreHook 同款写法）
        val loader = param.defaultClassLoader
        // DexKit 兜底用的目标 APK 路径（MethodResolver 第二轨依赖；null 时仅直连重试）
        apkPath = param.applicationInfo?.sourceDir
        // 远程 prefs 不走 install 期一次性捕获（RemotePrefsHolder：进程级缓存 + 失败自动重读自愈——
        // 修复「install 期读取失败 → null 被闭包捕获冻结」缺陷，与 ThemeStoreHook 同构；此处调用仅作缓存预热，
        // 实际读取在各 intercept 函数体内现读 obtain()）
        RemotePrefsHolder.obtain(module)

        retryRounds = 0
        okCount = 0
        totalCount = 0
        failedIds.clear()
        sMinDegree = readDegreeProp(loader) // 首次兜底读取（此后每 2s 由 refreshProps 热刷新覆盖，见 refreshProps）

        // 挂载表 18 项 = 面板 10 组 hook（docs §4.4；锁屏链 4 组二期未入表）
        val pending = mutableListOf<MountTask>()
        // 组 1-3：TransparentModeManager（核心 LIQUID 状态）
        pending += MountTask(
            kind = MountKind.STATE_FIX, className = TMM_CLASS, methodName = "getTransparentModeState", paramCount = 2,
            id = "honorhook:glass:tmm.state", description = "G1 LIQUID 材质位核心（getTransparentModeState 大方法 after 改写 mBlurType 字段 = 0x20000）",
        )
        pending += MountTask(
            kind = MountKind.LIQUID_BOOL, className = TMM_CLASS, methodName = "isLiquidBlurType", paramCount = 0,
            id = "honorhook:glass:tmm.liquid", description = "G2a 液态判定兜底（isLiquidBlurType after：透明开且原返 false 时强 true）",
        )
        pending += MountTask(
            kind = MountKind.CONST, className = TMM_CLASS, methodName = "isFrostedBlurType", paramCount = 0,
            value = false, id = "honorhook:glass:tmm.frosted", description = "G2b 磨砂判定恒 false（isFrostedBlurType returnConstant(false)）",
        )
        pending += MountTask(
            kind = MountKind.RADIUS_SCALE, className = TMM_CLASS, methodName = "getTransparentBlurRadius", paramCount = 1,
            id = "honorhook:glass:tmm.radius", description = "G3 面板背景模糊半径倍率（getTransparentBlurRadius after x radius）",
        )
        // 组 4：BlurParametersConfig 磁贴玻璃参数
        pending += MountTask(
            kind = MountKind.INIT_BLUR, className = BPC_CLASS, methodName = "initBulrParams", paramCount = 3,
            id = "honorhook:glass:bpc.initBlur", description = "G4 磁贴/通知卡玻璃参数批量缩放（initBulrParams 大方法 after：mask×10 字段+6 数组、nmask 底调、refract/thick/disp、folder 配方、tblur 二次模糊；kbshade 锁屏分支二期未含）",
        )
        // 组 5：ShotBlurView 面板遮罩/光泽
        pending += MountTask(
            kind = MountKind.PANEL_VEIL, className = SHOTBLUR_CLASS, methodName = "setBlurParams", paramCount = 2,
            id = "honorhook:glass:shotblur.veil", description = "G5 面板遮罩/光泽（setBlurParams before：sat 缩放 arg0、mask alpha 缩放 arg1——最大杠杆）",
        )
        // 组 6：EdgeLightParamEx 边光（构造器 + 上色）
        pending += MountTask(
            kind = MountKind.EDGE_CTOR, className = EDGE_LIGHT_CLASS, methodName = "EdgeLightParamEx", paramCount = 2,
            ctor = true, id = "honorhook:glass:edge.ctor", description = "G6a 边光宽度倍率（EdgeLightParamEx 2 参构造器 before：6.0f x edge，构造器不内联；kedge 锁屏分支二期未含）",
        )
        pending += MountTask(
            kind = MountKind.EDGE_COLOR, className = EDGE_LIGHT_CLASS, methodName = "setColor", paramCount = 1,
            id = "honorhook:glass:edge.color", description = "G6b 边光亮度（setColor before：alpha x rim；hookAllMethods 语义单签名命中）",
        )
        // 组 7：uikit 控件透明参数
        pending += MountTask(
            kind = MountKind.DEGREE_SCALE, className = DEGREE_ALGO_CLASS, methodName = "withTransparency", paramCount = 2,
            id = "honorhook:glass:uikit.degree", description = "G7 控件透明参数缩放（AbsHighGradeDegreeAlgorithm.withTransparency 大方法 after：currentTransparency 重置 + light/darkBlurParam 缩放）",
        )
        // 组 8：框架数据源
        pending += MountTask(
            kind = MountKind.TEXTURE_FIX, className = UIMODE_FW_CLASS, methodName = "getTransparentTexture", paramCount = 0,
            id = "honorhook:glass:fw.texture", description = "G8a 框架数据源恒 LIQUID（com.hihonor.android.app.UiModeManagerEx.getTransparentTexture after → 2）",
        )
        pending += MountTask(
            kind = MountKind.DEGREE_RAISE, className = UIMODE_FW_CLASS, methodName = "getTransparentDegree", paramCount = 0,
            id = "honorhook:glass:fw.degree", description = "G8b 框架透明度抬升（getTransparentDegree after → 最低 85）",
        )
        // 组 9：机型强制 HIGH（MachineConfig 字段 + 判定 + 类型）
        pending += MountTask(
            kind = MountKind.MACHINE_FIELDS, className = MACHINE_CLASS, methodName = "getInstance", paramCount = 1,
            id = "honorhook:glass:machine.instance", description = "G9a 机型字段强制 HIGH（getInstance after：transparencyMaterialLevel=0 / isSupportTransConfig=true / machineType=null 重算）",
        )
        pending += MountTask(
            kind = MountKind.CONST, className = MACHINE_CLASS, methodName = "isHighMachine", paramCount = 0,
            value = true, id = "honorhook:glass:machine.high", description = "G9b 高档机判定恒 true（isHighMachine）",
        )
        pending += MountTask(
            kind = MountKind.CONST, className = MACHINE_CLASS, methodName = "isCompatMachine", paramCount = 0,
            value = false, id = "honorhook:glass:machine.compat", description = "G9c 兼容机判定恒 false（isCompatMachine）",
        )
        pending += MountTask(
            kind = MountKind.MACHINE_TYPE_HIGH, className = MACHINE_CLASS, methodName = "getMachineType", paramCount = 0,
            id = "honorhook:glass:machine.type", description = "G9d 机型类型恒 HIGH（getMachineType after → MachineType.HIGH）",
        )
        // 组 10：材质类型改判 + 材质位配置 + 系统配置位
        pending += MountTask(
            kind = MountKind.STYLE_FLIP, className = STYLE_TYPE_CLASS, methodName = "getMaterialType", paramCount = 0,
            id = "honorhook:glass:style.flip", description = "G10a 材质类型改判（StyleType.getMaterialType after：透明模式下 FROSTED→LIQUID，mode 读取已适配真机 instance 方法）",
        )
        pending += MountTask(
            kind = MountKind.CONST, className = CFG_CLASS, methodName = "getConfigTransparentModeMaterial", paramCount = 1,
            value = 3, id = "honorhook:glass:cfg.material", description = "G10b 材质特效位恢复（getConfigTransparentModeMaterial 恒返 3）",
        )
        pending += MountTask(
            kind = MountKind.CONFIG_BIT, className = CONFIG_EX_CLASS, methodName = "getTransparentMode", paramCount = 0,
            id = "honorhook:glass:config.bit", description = "G10c 系统配置位改判（ConfigurationEx.getTransparentMode after：BLUR_TYPE_MASK 内 FROSTED 位 → LIQUID）",
        )
        totalCount = pending.size

        attemptMount(pending, module, loader)
    }

    /** 挂载项执行结果：[NOT_RESOLVED] 类/方法未解析到——可随类加载重试；[FAILED] hook 挂载异常——重试无意义。 */
    private enum class MountResult { OK, FAILED, NOT_RESOLVED }

    /**
     * 挂载任务类型：
     * [CONST] 常量短路（恒返 value，不调原方法——isFrostedBlurType/isHighMachine/isCompatMachine/材质特效位）；
     * [STATE_FIX] getTransparentModeState after：改写 mBlurType 字段（唯一核心机制，改字段不改返回值）；
     * [LIQUID_BOOL] isLiquidBlurType after：透明开且原返 false 时强 true；
     * [RADIUS_SCALE] getTransparentBlurRadius after：返回值 x radius 倍率；
     * [INIT_BLUR] initBulrParams after：批量字段缩放（面板部分，kbshade 锁屏分支二期未含）；
     * [PANEL_VEIL] setBlurParams before：改参（sat / mask alpha）；
     * [EDGE_CTOR] EdgeLightParamEx 2 参构造器 before：thickness 6.0 x edge（构造器不内联；防叠乘只缩放 6.0 原值）；
     * [EDGE_COLOR] setColor before：alpha x rim；
     * [DEGREE_SCALE] withTransparency after：currentTransparency 重置 + light/darkBlurParam 缩放（ColorHelper 重算）；
     * [TEXTURE_FIX] 框架 getTransparentTexture after 恒 2；[DEGREE_RAISE] 框架 getTransparentDegree after 抬升；
     * [MACHINE_FIELDS] getInstance after：3 字段改写；[MACHINE_TYPE_HIGH] getMachineType after 恒 HIGH；
     * [STYLE_FLIP] getMaterialType after：FROSTED→LIQUID（模式读 uikit wrapper，static/instance 双轨）；
     * [CONFIG_BIT] ConfigurationEx.getTransparentMode after：FROSTED 位→LIQUID。
     */
    private enum class MountKind {
        CONST, STATE_FIX, LIQUID_BOOL, RADIUS_SCALE, INIT_BLUR, PANEL_VEIL, EDGE_CTOR, EDGE_COLOR,
        DEGREE_SCALE, TEXTURE_FIX, DEGREE_RAISE, MACHINE_FIELDS, MACHINE_TYPE_HIGH, STYLE_FLIP, CONFIG_BIT,
    }

    /** 一次挂载任务的完整描述（类/方法未解析时保留任务本体供懒加载重试；[ctor]=true 时按构造器匹配）。 */
    private class MountTask(
        val kind: MountKind,
        val className: String,
        val methodName: String,
        val paramCount: Int,
        val id: String,                 // 幂等 ID（框架用它防重复/日志标识）
        val description: String,
        val value: Any? = null,         // CONST: 常返值
        val ctor: Boolean = false,      // true = 构造器挂载（methodName 仅作描述）
    )

    /** 重试轮次（已完成的重试数；第 1 轮 500ms 后、第 2 轮 2s 后；照 HC installCustomTileHooks postDelayed 模式）。 */
    private var retryRounds = 0
    private var okCount = 0
    private var totalCount = 0
    private val failedIds = mutableListOf<String>()

    private const val MAX_RETRY_ROUNDS = 2
    private const val RETRY_DELAY_MS = 500L
    private const val RETRY_FINAL_DELAY_MS = 2000L

    /** 挂载一轮 pending 任务（HC postDelayed 懒加载重试模式，与 HonorInstallerHook.attemptMount 同构）： */
    private fun attemptMount(
        pending: MutableList<MountTask>,
        module: XposedModule,
        loader: ClassLoader,
    ) {
        val unresolved = mutableListOf<MountTask>()
        for (task in pending) {
            when (mountOne(task, module, loader)) {
                MountResult.OK -> okCount++
                MountResult.FAILED -> failedIds += task.description
                MountResult.NOT_RESOLVED -> unresolved += task
            }
        }
        if (unresolved.isEmpty()) {
            logSummary(module)
        } else if (retryRounds >= MAX_RETRY_ROUNDS) {
            failedIds += unresolved.map { it.description }
            module.log(
                Log.WARN, TAG,
                "部分 hook 最终未挂载（类/方法不存在，已重试 $MAX_RETRY_ROUNDS 轮，放弃）: " +
                    unresolved.joinToString { it.description },
            )
            logSummary(module)
        } else {
            retryRounds++
            val delay = if (retryRounds == 1) RETRY_DELAY_MS else RETRY_FINAL_DELAY_MS
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { attemptMount(unresolved, module, loader) }
                    .onFailure { t -> module.log(Log.ERROR, TAG, "懒加载重试挂载异常", t) }
            }, delay)
        }
    }

    /** 单个挂载任务执行；失败分级：未解析（可随类加载重试）vs hook 异常（永久，重试无意义）。 */
    private fun mountOne(
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountResult {
        val executable = if (task.ctor) {
            // 构造器保持直连 findCtor（resolveCtor 与直连等价，DexKit 兜底收益低——最小改动）
            findCtor(loader, task.className, task.paramCount)
        } else {
            // 直连失败 → MethodResolver 双轨兜底（第二轨 DexKit 按特征查磁盘 dex，对抗 OTA 混淆漂移；
            // apkPath 缺失时 resolveMethod 自动跳过第二轨，等价纯直连重试）
            findMethod(loader, task.className, task.methodName, task.paramCount)
                ?: MethodResolver.resolveMethod(loader, task.className, task.methodName, task.paramCount, apkPath = apkPath)
        }
        if (executable == null) {
            module.log(Log.WARN, TAG, "hook 未挂载（类/方法不存在，版本差异，将重试）: ${task.description}")
            return MountResult.NOT_RESOLVED
        }
        return runCatching {
            val builder = module.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE) // 拦截器异常不炸宿主进程（boot-loop 纪律）
                .setId(task.id)
            builder.intercept { chain -> interceptBody(chain, module, loader, task) }
        }.fold(
            onSuccess = {
                // 挂载成功日志**始终输出**（不受 debug 门控）——真机验证凭它确认命中（对应插件 "hook ... ok" 行）
                module.log(Log.INFO, TAG, "[glass] hook 已挂载: ${task.id}（${task.description}）")
                MountResult.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountResult.FAILED
            },
        )
    }

    /** 挂载汇总（始终输出；对应插件 "installed N/M resolved hook(s)" 行）。 */
    private fun logSummary(module: XposedModule) {
        val failurePart = if (failedIds.isEmpty()) "" else " — 失败: ${failedIds.joinToString()}"
        module.log(
            if (failedIds.isEmpty()) Log.INFO else Log.WARN,
            TAG,
            "GlassRestoreHook: installed $okCount/$totalCount$failurePart",
        )
    }

    // ---------------- 拦截器实现（每个 intercept 现读开关；各触发日志按源码计数节流） ----------------

    private fun interceptBody(
        chain: XposedInterface.Chain,
        module: XposedModule,
        loader: ClassLoader,
        task: MountTask,
    ): Any? {
        // 每次触发现读远程 prefs（RemotePrefsHolder 进程级缓存，拿到后零开销）——不等 install 期一次性捕获：
        // 修复「install 期读取失败 → null 被闭包捕获冻结」缺陷（null 时每次触发自动重读自愈，成功一次即缓存）
        val prefs = RemotePrefsHolder.obtain(module)
        if (!featureEnabled(prefs)) return chain.proceed()   // 总开关/F7 关 → 原逻辑放行
        return when (task.kind) {
            // 常量短路：不调原方法，恒返 value（returnConstant 语义；isFrostedBlurType/isHighMachine/isCompatMachine/材质特效位）
            MountKind.CONST -> task.value

            // G1 核心：after 改写字段而非返回值——后续内联读取点全命中（AOT 内联免疫设计）
            MountKind.STATE_FIX -> after(chain, module, "state") { r ->
                val reason = chain.args?.getOrNull(1) ?: "?"
                val old = intField(chain.thisObject, "mBlurType")
                setInt(chain.thisObject, "mBlurType", BLUR_TYPE_LIQUID)
                if (sStateCount < 6) {
                    sStateCount++
                    module.log(Log.INFO, TAG, "[glass] state: reason=$reason on=$r mBlurType=$old -> $BLUR_TYPE_LIQUID")
                }
                r
            }
            // G2a 液态判定兜底：透明开且原返 false 时强 true（mIsTransparentModeOn 字段现读）
            MountKind.LIQUID_BOOL -> after(chain, module, "liquid") { r ->
                val on = boolField(chain.thisObject, "mIsTransparentModeOn") ?: false
                if (on && r != true) true else r
            }
            // G3 面板模糊半径 x radius 倍率
            MountKind.RADIUS_SCALE -> after(chain, module, "radius") { r ->
                val orig = r as? Float ?: return@after r
                refreshProps(loader)
                if (sRadiusScale >= 0.999f) return@after r
                val scaled = max(1.0f, orig * sRadiusScale)
                if (sRadiusCount < 6) {
                    sRadiusCount++
                    module.log(Log.INFO, TAG, "[glass] radius: case=${chain.args?.getOrNull(0) ?: "?"} $orig -> $scaled")
                }
                if (scaled != orig) scaled else r
            }
            // G4 磁贴/通知卡玻璃参数批量缩放（面板部分；mask 优先、nmask 绝对底调、refract/thick/disp、folder 配方、tblur）
            MountKind.INIT_BLUR -> after(chain, module, "initBlur") { r ->
                refreshProps(loader)
                val o = chain.thisObject
                if (sMaskScale < 0.999f) {
                    runCatching {
                        scaleAlphaField(o, "liauidGlassMaskColor", sMaskScale)
                        scaleAlphaField(o, "liauidGlassNtfOverlayColor", sMaskScale)
                        scaleAlphaField(o, "controlMaskColor", sMaskScale)
                        scaleAlphaField(o, "controlOverlayColor", sMaskScale)
                        scaleAlphaField(o, "controlNtfMaskColor", sMaskScale)
                        scaleAlphaField(o, "controlNtfverlayColor", sMaskScale)
                        scaleAlphaField(o, "sliderMaskColor", sMaskScale)
                        scaleAlphaField(o, "sliderOverlayColor", sMaskScale)
                        scaleAlphaField(o, "buttonMaskColor", sMaskScale)
                        scaleAlphaField(o, "buttonOverlayColor", sMaskScale)
                        scaleAlphaArray(o, "multiMaskColors", sMaskScale)
                        scaleAlphaArray(o, "multiControlMaskColors", sMaskScale)
                        scaleAlphaArray(o, "multiSliderMaskColors", sMaskScale)
                        scaleAlphaArray(o, "multiButtonMaskColors", sMaskScale)
                        scaleAlphaArray(o, "dynamicCardGradientMaskColor", sMaskScale)
                        scaleAlphaArray(o, "dynamicCardGradientMaskColorOther", sMaskScale)
                        if (sMaskCount < 3) {
                            sMaskCount++
                            module.log(Log.INFO, TAG, "[glass] mask: initBulrParams case=${chain.args?.getOrNull(0) ?: "?"} alpha x$sMaskScale")
                        }
                    }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] mask scale failed: $t") }
                }
                if (sNtfMask > 0.001f) {
                    // nmask 绝对底色调色（在通用缩放之后，卡片底调独立于 mask prop）
                    runCatching {
                        val tint = ((sNtfMask * 255.0f + 0.5f).toInt() shl 24) or 0x2E2E2E
                        val overlay = ((sNtfMask * 0.5f * 255.0f + 0.5f).toInt() shl 24) or 0x2E2E2E
                        setInt(o, "controlNtfMaskColor", tint)
                        setInt(o, "controlNtfverlayColor", overlay)
                        if (sNtfCount < 3) {
                            sNtfCount++
                            module.log(Log.INFO, TAG, "[glass] ntf-tint: card mask=0x${tint.toString(16)} overlay=0x${overlay.toString(16)} (case=${chain.args?.getOrNull(0) ?: "?"})")
                        }
                    }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] ntf tint failed: $t") }
                }
                runCatching {
                    scaleFloatField(o, "refraction", sRefractScale)
                    scaleFloatField(o, "depth", sRefractScale)
                    scaleFloatField(o, "thickness", sThickScale)
                    scaleFloatField(o, "dispersion", sDispScale)
                    if (sRefractLogged < 3) {
                        sRefractLogged++
                        module.log(Log.INFO, TAG, "[glass] refract: x$sRefractScale thick x$sThickScale disp x$sDispScale")
                    }
                }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] refract scale failed: $t") }
                if (sFolderMode && (chain.args?.getOrNull(0) as? Int)?.let { it >= 2 } == true) {
                    // 文件夹配方绝对值（LiquidLargeOnHighGeneral：0.3/0.54/0.62/0.2；case>=2 = 液态分支）
                    runCatching {
                        setFloat(o, "refraction", 0.3f)
                        setFloat(o, "depth", 0.54f)
                        setFloat(o, "thickness", 0.62f * sThickScale)
                        setFloat(o, "dispersion", 0.2f)
                        if (sFolderLogged < 3) {
                            sFolderLogged++
                            module.log(Log.INFO, TAG, "[glass] folder: liquid optics -> 0.3/0.54/${0.62f * sThickScale}/0.2 (case=${chain.args?.getOrNull(0)}, thick x$sThickScale)")
                        }
                    }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] folder optics failed: $t") }
                }
                if (abs(sTileBlurScale - 1.0f) > 0.001f) {
                    // 二次模糊：每块磁贴/卡片重糊面板位图（残余磨砂感，独立于背景 radius prop）
                    runCatching {
                        val br = floatField(o, "blurRadius") ?: return@runCatching
                        val nbr = max(1.0f, br * sTileBlurScale)
                        setFloat(o, "blurRadius", nbr)
                        val dbr = floatField(o, "dynamicCardBlurRadius") ?: 0.0f
                        if (dbr > 0.01f) setFloat(o, "dynamicCardBlurRadius", max(1.0f, dbr * sTileBlurScale))
                        if (sTileBlurLogged < 3) {
                            sTileBlurLogged++
                            module.log(Log.INFO, TAG, "[glass] tblur: case=${chain.args?.getOrNull(0) ?: "?"} blurRadius $br -> $nbr dynamicCard $dbr x$sTileBlurScale")
                        }
                    }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] tblur scale failed: $t") }
                }
                r
            }
            // G5 面板遮罩/光泽（最大杠杆）：before 改参（sat 缩放 arg0、mask alpha 缩放 arg1）
            MountKind.PANEL_VEIL -> beforeArgs(chain, module, "panel", { args ->
                val orig = args.getOrNull(1) as? Int ?: return@beforeArgs null
                refreshProps(loader)
                if (args.size >= 1 && args[0] is Float && abs(sSatScale - 1.0f) > 0.001f) {
                    args[0] = (args[0] as Float) * sSatScale
                }
                if (sPanelCount < 4) {
                    sPanelCount++
                    module.log(Log.INFO, TAG, "[glass] panel: setBlurParams sat=${args[0]} mask=0x${orig.toString(16)} x$sMaskScale")
                }
                if (sMaskScale < 0.999f) {
                    args[1] = scaleAlpha(orig, sMaskScale)
                }
                args.toTypedArray()
            })
            // G6a 边光宽度：构造器 before 缩放 6.0 原值（构造器不内联；防叠乘只缩放 stock 常量）
            MountKind.EDGE_CTOR -> beforeArgs(chain, module, "edgeCtor", { args ->
                val orig = args.getOrNull(1) as? Float ?: return@beforeArgs null
                refreshProps(loader)
                // 原代码此处有 sKgCardNow → kedge 锁屏分支（二期，未移植）
                if (abs(sEdgeScale - 1.0f) <= 0.001f) return@beforeArgs null
                if (abs(orig - 6.0f) > 0.01f) return@beforeArgs null   // copy 模式防叠乘
                args[1] = orig * sEdgeScale
                if (sEdgeCount < 3) {
                    sEdgeCount++
                    module.log(Log.INFO, TAG, "[glass] edge-rim: thickness $orig -> ${orig * sEdgeScale}")
                }
                args.toTypedArray()
            })
            // G6b 边光亮度：setColor before alpha x rim
            MountKind.EDGE_COLOR -> beforeArgs(chain, module, "edgeColor", { args ->
                val c = args.getOrNull(0) as? Int ?: return@beforeArgs null
                refreshProps(loader)
                val alpha = (c ushr 24) and 0xFF
                if (sRimCount < 4) {
                    sRimCount++
                    module.log(Log.INFO, TAG, "[glass] rim: setColor arg=0x${c.toString(16)} alpha=$alpha scale=$sRimScale")
                }
                if (sRimScale >= 0.999f || alpha == 0) return@beforeArgs null
                args[0] = ((alpha * sRimScale).roundToInt() shl 24) or (c and 0xFFFFFF)
                args.toTypedArray()
            })
            // G7 uikit 控件透明参数：after 重置 currentTransparency + 缩放 light/darkBlurParam（ColorHelper 重算防叠乘）
            MountKind.DEGREE_SCALE -> after(chain, module, "uikit") { r ->
                refreshProps(loader)
                if (sRadiusScale >= 0.999f && sMaskScale >= 0.999f) return@after r
                runCatching {
                    setInt(chain.thisObject, "currentTransparency", -1)
                    val repo = objField(chain.thisObject, "mHighGradeParamsRepo") ?: return@runCatching
                    scaleBlurParam(loader, repo, "lightBlurParam", module)
                    scaleBlurParam(loader, repo, "darkBlurParam", module)
                    if (sUikitCount < 6) {
                        sUikitCount++
                        module.log(Log.INFO, TAG, "[glass] uikit: repo=${repo.javaClass.simpleName} radius x$sRadiusScale mask x$sMaskScale")
                    }
                }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] uikit scale failed: $t") }
                r
            }
            // G8a 框架数据源恒 LIQUID
            MountKind.TEXTURE_FIX -> after(chain, module, "texture") { r ->
                val orig = r as? Int ?: return@after r
                if (sTextureDiagCount < 5) {
                    sTextureDiagCount++
                    module.log(Log.INFO, TAG, "[glass] diag: getTransparentTexture raw=$orig")
                }
                if (orig != 2) {
                    if (!sTextureLogged) {
                        sTextureLogged = true
                        module.log(Log.INFO, TAG, "[glass] ok: framework texture $orig -> 2 (LIQUID)")
                    }
                    2
                } else r
            }
            // G8b 框架透明度抬升到 minDegree（默认 85）
            MountKind.DEGREE_RAISE -> after(chain, module, "degree") { r ->
                val orig = r as? Int ?: return@after r
                if (orig < sMinDegree) {
                    if (!sDegreeLogged) {
                        sDegreeLogged = true
                        module.log(Log.INFO, TAG, "[glass] ok: framework degree $orig -> $sMinDegree")
                    }
                    sMinDegree
                } else r
            }
            // G9a 机型字段强制 HIGH（字段改写；getMachineType 读字段重算 → HIGH）
            MountKind.MACHINE_FIELDS -> after(chain, module, "machine") { r ->
                val inst = r ?: return@after r
                runCatching {
                    setInt(inst, "transparencyMaterialLevel", 0)
                    setBoolean(inst, "isSupportTransConfig", true)
                    setObject(inst, "machineType", null)
                }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] machine field fix failed: $t") }
                r
            }
            // G9d 机型类型恒 HIGH（MachineType.HIGH 枚举常量懒解析缓存）
            MountKind.MACHINE_TYPE_HIGH -> after(chain, module, "machineType") { r ->
                val high = machineHigh(loader) ?: return@after r
                if (r != high) high else r
            }
            // G10a 材质类型改判：透明模式下 FROSTED→LIQUID（uikit wrapper 模式读取，static/instance 双轨——真机为 instance，见类注释）
            MountKind.STYLE_FLIP -> after(chain, module, "styleFlip") { r ->
                val liquid = styleLiquid(loader) ?: return@after r
                val frosted = styleFrosted(loader) ?: return@after r
                if (r != frosted) return@after r
                val mode = resolveUikitMode(loader)
                if (mode == 1) {
                    if (!sFlipLogged) {
                        sFlipLogged = true
                        module.log(Log.INFO, TAG, "[glass] ok: material type flipped FROSTED -> LIQUID (transparency on)")
                    }
                    liquid
                } else r
            }
            // G10c 系统配置位改判：BLUR_TYPE_MASK 内 FROSTED 位 → LIQUID
            MountKind.CONFIG_BIT -> after(chain, module, "configBit") { r ->
                val orig = r as? Int ?: return@after r
                if (sConfigDiagCount < 5) {
                    sConfigDiagCount++
                    module.log(Log.INFO, TAG, "[glass] diag: getTransparentMode raw=0x${orig.toString(16)}")
                }
                if ((orig and BLUR_TYPE_MASK) == BLUR_TYPE_FROSTED) {
                    val fixed = (orig and BLUR_TYPE_MASK.inv()) or BLUR_TYPE_LIQUID
                    if (!sConfigLogged) {
                        sConfigLogged = true
                        module.log(Log.INFO, TAG, "[glass] ok: config transparentMode 0x${orig.toString(16)} -> 0x${fixed.toString(16)} (FROSTED->LIQUID bit)")
                    }
                    fixed
                } else r
            }
        }
    }

    /** after 形态：先 proceed 走原方法，再执行 [transform] 视需要改写结果（异常只记录、返回原结果——失败放行原逻辑纪律）。 */
    private inline fun after(
        chain: XposedInterface.Chain,
        module: XposedModule,
        stage: String,
        transform: (Any?) -> Any?,
    ): Any? {
        val result = chain.proceed()
        return runCatching { transform(result) }
            .onFailure { t -> module.log(Log.WARN, TAG, "[glass] $stage hook 后处理异常（回退原结果）: $t") }
            .getOrDefault(result)
    }

    /** before 形态：拷贝参数列表，[transform] 返回新参数数组（null = 原样 proceed）；异常回退原调用。 */
    private inline fun beforeArgs(
        chain: XposedInterface.Chain,
        module: XposedModule,
        stage: String,
        transform: (MutableList<Any?>) -> Array<Any?>?,
    ): Any? {
        val args = (chain.args ?: emptyList()).toMutableList<Any?>()
        return runCatching {
            val replaced = transform(args)
            chain.proceed(replaced ?: chain.args?.toTypedArray<Any?>() ?: emptyArray())
        }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] $stage hook 参数改写异常（回退原调用）: $t") }
            .getOrElse { chain.proceed() }
    }

    // ---------------- 面板参数缩放辅助（照源码 L440-695 直移） ----------------

    /** G7 用：BlurParam{radius,alphas,RGBs,maskColors} 字段缩放 + ColorHelper.composeColors 重算遮罩色。 */
    private fun scaleBlurParam(loader: ClassLoader, repo: Any, field: String, module: XposedModule) {
        runCatching {
            val p = objField(repo, field) ?: return@runCatching
            if (sRadiusScale < 0.999f) {
                val r = floatField(p, "radius") ?: return@runCatching
                setFloat(p, "radius", max(0.5f, r * sRadiusScale))
            }
            if (sMaskScale < 0.999f) {
                val alphas = objField(p, "alphas") as? IntArray ?: return@runCatching
                val rgbs = objField(p, "RGBs") as? IntArray ?: return@runCatching
                if (alphas.size != rgbs.size) return@runCatching
                val na = IntArray(alphas.size) { i -> (alphas[i] * sMaskScale).roundToInt().coerceIn(0, 255) }
                setObject(p, "alphas", na)
                val mask = callStatic(loader, COLOR_HELPER_CLASS, "composeColors", arrayOf(rgbs, na))
                setObject(p, "maskColors", mask)
            }
        }.onFailure { t -> module.log(Log.WARN, TAG, "[glass] uikit repo($field) scale failed: $t") }
    }

    private fun scaleAlphaField(o: Any?, field: String, scale: Float) {
        runCatching {
            val c = intField(o, field) ?: return
            setInt(o, field, scaleAlpha(c, scale))
        }
    }

    private fun scaleFloatField(o: Any?, field: String, scale: Float) {
        runCatching {
            val v = floatField(o, field) ?: return
            setFloat(o, field, v * scale)
        }
    }

    private fun scaleAlphaArray(o: Any?, field: String, scale: Float) {
        runCatching {
            val arr = objField(o, field) as? IntArray ?: return
            for (i in arr.indices) arr[i] = scaleAlpha(arr[i], scale)
        }
    }

    private fun scaleAlpha(color: Int, scale: Float): Int {
        val a = (color ushr 24) and 0xFF
        val na = (a * scale).roundToInt()
        return (na shl 24) or (color and 0x00FFFFFF)
    }

    // ---------------- 反射字段/方法辅助（XposedHelpers 等价物；全部 runCatching 单点容错） ----------------

    private fun fld(o: Any?, name: String): Field? = runCatching {
        o?.javaClass?.getDeclaredField(name)?.also { it.isAccessible = true }
    }.getOrNull()

    private fun intField(o: Any?, name: String): Int? = runCatching { fld(o, name)?.getInt(o) }.getOrNull()
    private fun floatField(o: Any?, name: String): Float? = runCatching { fld(o, name)?.getFloat(o) }.getOrNull()
    private fun boolField(o: Any?, name: String): Boolean? = runCatching { fld(o, name)?.getBoolean(o) }.getOrNull()
    private fun objField(o: Any?, name: String): Any? = runCatching { fld(o, name)?.get(o) }.getOrNull()

    private fun setInt(o: Any?, name: String, v: Int) = runCatching { fld(o, name)?.setInt(o, v) }
    private fun setFloat(o: Any?, name: String, v: Float) = runCatching { fld(o, name)?.setFloat(o, v) }
    private fun setBoolean(o: Any?, name: String, v: Boolean) = runCatching { fld(o, name)?.setBoolean(o, v) }
    private fun setObject(o: Any?, name: String, v: Any?) = runCatching { fld(o, name)?.set(o, v) }

    private fun callStatic(loader: ClassLoader, className: String, methodName: String, args: Array<Any?>): Any? = runCatching {
        val clz = loader.loadClass(className)
        val types = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        val m = clz.getMethod(methodName, *types)
        m.invoke(null, *args)
    }.getOrNull()

    /** uikit UiModeManagerEx.getTransparentMode：真机 15.0.0.0/17.0.0.1 为 instance 方法（getInstance 后调用）；老版本为 static——双轨容错（版本差异适配点）。 */
    private fun resolveUikitMode(loader: ClassLoader): Int? = runCatching {
        val wrapper = loader.loadClass(UIMODE_WRAPPER_CLASS)
        val m = wrapper.declaredMethods.firstOrNull { it.name == "getTransparentMode" && it.parameterCount == 0 }
            ?: throw NoSuchMethodException("getTransparentMode")
        val target: Any? = if (Modifier.isStatic(m.modifiers)) {
            null
        } else {
            val gi = wrapper.declaredMethods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }
                ?: throw NoSuchMethodException("getInstance")
            gi.isAccessible = true
            gi.invoke(null)
        }
        m.isAccessible = true
        m.invoke(target) as? Int
    }.getOrNull()

    // ---------------- 枚举常量懒解析（mount 期 NOT_RESOLVED 之外的运行期兜底：解析失败 = 该 hook 本次不生效） ----------------
    // 纪律（boot-loop 事故史）：[loader.loadClass] 不触发类初始化，但 `.enumConstants`/`values()` 会触发目标枚举
    // 的 <clinit>（静态初始化）——这三个函数**仅允许在 intercept 期调用**：调用点（MACHINE_TYPE_HIGH / STYLE_FLIP
    // 分支）已处于 featureEnabled 门控之后，且整条链被挂载期 PROTECTIVE 模式 + 运行期 runCatching 双重包裹，
    // <clinit> 触发即是最坏后果。**禁止从挂载/重试线程（attemptMount/mountOne）调用**——挂载期若走枚举解析
    // 即把 <clinit> 提前到进程注入阶段，扩大 boot-loop 事故面。首次解析失败（runCatching 兜底）返回 null =
    // 该 hook 本次不生效（原逻辑放行，下次触发重试）。

    private fun machineHigh(loader: ClassLoader): Any? {
        sMachineHigh?.let { return it }
        val high = runCatching {
            loader.loadClass(MACHINE_TYPE_CLASS).enumConstants.firstOrNull { (it as Enum<*>).name == "HIGH" }
        }.getOrNull()
        if (high != null) sMachineHigh = high
        return high
    }

    // 同上纪律：enumConstants 触发 StyleType <clinit>，仅允许 intercept 期调用（featureEnabled 门控后、PROTECTIVE + runCatching 包裹），禁挂载/重试线程调用
    private fun styleLiquid(loader: ClassLoader): Any? {
        sStyleLiquid?.let { return it }
        val v = runCatching {
            loader.loadClass(STYLE_TYPE_CLASS).enumConstants.firstOrNull { (it as Enum<*>).name == "LIQUID_GLASS_TRANSPARENT" }
        }.getOrNull()
        if (v != null) sStyleLiquid = v
        return v
    }

    // 同上纪律：enumConstants 触发 StyleType <clinit>，仅允许 intercept 期调用（featureEnabled 门控后、PROTECTIVE + runCatching 包裹），禁挂载/重试线程调用
    private fun styleFrosted(loader: ClassLoader): Any? {
        sStyleFrosted?.let { return it }
        val v = runCatching {
            loader.loadClass(STYLE_TYPE_CLASS).enumConstants.firstOrNull { (it as Enum<*>).name == "FROSTED_GLASS_TRANSPARENCY" }
        }.getOrNull()
        if (v != null) sStyleFrosted = v
        return v
    }

    // ---------------- 属性读取（照源码 L295-320 / L776-802：SystemProperties 反射 + 2s TTL；loadClass 不触发 <clinit>——boot-loop 纪律） ----------------

    private fun refreshProps(loader: ClassLoader) {
        val now = SystemClock.elapsedRealtime()
        if (now - sPropCacheAt >= 0 && now - sPropCacheAt < 2000L) return
        sPropCacheAt = now
        sRadiusScale = readFloatProp(loader, RADIUS_PROP, DEFAULT_RADIUS, 0.3f, 2.0f)
        sMaskScale = readFloatProp(loader, MASK_PROP, DEFAULT_MASK, 0.0f, 1.0f)
        sSatScale = readFloatProp(loader, SAT_PROP, DEFAULT_SAT, 0.8f, 2.0f)
        sNtfMask = readFloatProp(loader, NTF_MASK_PROP, DEFAULT_NTF_MASK, 0.0f, 1.0f)
        sRefractScale = readFloatProp(loader, REFRACT_PROP, DEFAULT_REFRACT, 0.2f, 3.0f)
        sEdgeScale = readFloatProp(loader, EDGE_PROP, DEFAULT_EDGE, 0.5f, 3.0f)
        sThickScale = readFloatProp(loader, THICK_PROP, DEFAULT_THICK, 0.2f, 3.0f)
        sDispScale = readFloatProp(loader, DISP_PROP, DEFAULT_DISP, 0.0f, 3.0f)
        sRimScale = readFloatProp(loader, RIM_PROP, DEFAULT_RIM, 0.15f, 1.0f)
        sFolderMode = readFloatProp(loader, FOLDER_PROP, DEFAULT_FOLDER, 0.0f, 1.0f) > 0.5f
        sTileBlurScale = readFloatProp(loader, TBLUR_PROP, DEFAULT_TBLUR, 0.1f, 2.0f)
        // degree 与其余 11 属性同走 2s TTL 热刷新（修复「install 期一次性读」后 setprop persist.sys.lgr.degree
        // 改了不生效——与类注释「2s TTL setprop 通道」矛盾；install 期的首次读取保留作兜底）
        sMinDegree = readDegreeProp(loader)
    }

    private fun readFloatProp(loader: ClassLoader, name: String, def: Float, min: Float, max: Float): Float = runCatching {
        val m = loader.loadClass("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
        val v = m.invoke(null, name, def.toString()) as? String ?: return@runCatching def
        v.toFloat().coerceIn(min, max)
    }.getOrDefault(def)

    private fun readDegreeProp(loader: ClassLoader): Int = runCatching {
        val m = loader.loadClass("android.os.SystemProperties")
            .getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
        val v = (m.invoke(null, DEGREE_PROP, DEFAULT_DEGREE) as? Int) ?: return@runCatching DEFAULT_DEGREE
        v.coerceIn(60, 100)
    }.getOrDefault(DEFAULT_DEGREE)

    // ---------------- 目标解析（类/方法不存在返回 null → NOT_RESOLVED 懒加载重试；loadClass 不触发初始化——boot-loop 纪律） ----------------

    /** 目标 APK 的 sourceDir（install 期记录，覆盖式更新；null 时 [MethodResolver] 跳过 DexKit 兜底，等价纯直连） */
    private var apkPath: String? = null

    private fun findMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int,
    ): Method? = runCatching {
        loader.loadClass(className).declaredMethods.firstOrNull { m ->
            m.name == methodName && m.parameterCount == paramCount
        }
    }.getOrNull()

    private fun findCtor(
        loader: ClassLoader,
        className: String,
        paramCount: Int,
    ): Constructor<*>? = runCatching {
        loader.loadClass(className).declaredConstructors.firstOrNull { c ->
            c.parameterCount == paramCount
        }
    }.getOrNull()

    /** 总开关现读（每次 intercept 调用）：prefs 获取失败时返回 false = 原逻辑放行（安全默认）。 */
    private fun hookEnabled(prefs: SharedPreferences?): Boolean = prefs?.getBoolean(KEY_HOOK_ENABLED, false) ?: false

    /** F7 功能位开关现读：总开关（上位语义：总关 = 全部放行）&& F7 独立开关（默认 false——boot-loop 事故史，保守默认关）。 */
    private fun featureEnabled(prefs: SharedPreferences?): Boolean =
        hookEnabled(prefs) && (prefs?.getBoolean(KEY_F7_GLASS_ENABLED, false) ?: false)

    private const val TAG = "HonorHook"
}