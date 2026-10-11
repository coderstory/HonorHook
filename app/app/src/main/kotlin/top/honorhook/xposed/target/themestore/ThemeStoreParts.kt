package top.honorhook.xposed.target.themestore

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint

/**
 * 主题商店 hook 的**共享部件**：目标类名常量、挂载任务类型、数据表→任务映射与运行时工具。
 * 挂载点本体在数据表 `ThemeStoreMap_*.kt`（tools/gen_hook_map.py 生成），装配与 kind 分派在 [ThemeStoreHook]。
 * 设计依据归档于 `docs/theme-store.md`；删除历史见 `docs/hook-inventory-audit.md` §五。
 */
import top.honorhook.xposed.data.HookPoint
/** 全模块统一日志 TAG（与 MountEngine.TAG 同值；包级常量供同包文件免导入使用）。 */
internal const val TAG = "HonorHook"

internal const val PKG = "com.hihonor.android.thememanager"

// 目标类（报告 §6；jadx 已复核签名）
internal const val CLS_ITEM_INFO = "$PKG.common.ItemInfo"
internal const val CLS_ITEM_BEAN = "$PKG.hitop.entity.res.BaseResItemBean"
internal const val CLS_VIP_MANAGER = "$PKG.vip.manager.VipManager"
internal const val CLS_VIP_STATE = "$PKG.vip.data.VipState"
// VipModel（服务端响应 → VipState 唯一转换器）曾挂 2 个 VIP_STATE_FIX 漏斗点 + 挽留弹窗休眠链等，
// 已于 2026-10-09/10 三轮审计删除——历史依据归档见 docs/hook-inventory-audit.md §五。

// 2026-10 反查新增（docs/reverse/theme-store.md §五 T1，jadx 实证）
internal const val CLS_INCENTIVE_DATA = "$PKG.incentive.IncentiveData"
// 详情页 Activity：仅用于「购买」按钮隐藏（changeVipBtnsVisibility 第 1 参 buy，:2837 定义 / :2920 设 mBuyBtn）。
// 已删的按钮策略类目标（needShowBuyButton 门 / MineVipCardView T8）历史见 docs/hook-inventory-audit.md §五。
internal const val CLS_PREVIEW = "$PKG.OnlinePreviewBaseActivity"
// 在线壁纸预览页（类名未混淆）；W1 挂 applyLiveWallpaperPrepare 用，见 collectDownloadChainHooks。
internal const val CLS_WALLPAPER_PREVIEW_LOW = "$PKG.wallpaper.OnlineWallpaperPreviewLowActivity"

// 2026-10 挽留弹窗拦截（docs/reverse/theme-store.md §现象四 4.5，jadx 实证）：
// Manager 是 Activity 的 Kotlin object 嵌套静态类（Activity.java:150-163；showDialog L158-161
// ARouter 带 Parcelable DIALOG_PARAMS）；F2 目标即 Activity 自身（onCreate L861 protected，extends FragmentActivity）
internal const val CLS_RETENTION_DIALOG_ACTIVITY = "$PKG.overallretentiondialog.ui.OverallRetentionDialogActivity"
internal const val CLS_RETENTION_DIALOG_MANAGER = "$PKG.overallretentiondialog.ui.OverallRetentionDialogActivity\$OverallRetentionDialogManager"

/** 2088-08-08 00:00:00 东八区毫秒时间戳——会员到期时间的落地值（Long.MAX 会让日期格式化/剩余天数判定出现异常值）。 */
internal const val TS_2088_08_08_MILLIS = 3_742_732_800_000L
/** 2088-08-08 展示字符串（与服端 displayTime 同格式 "yyyy-MM-dd HH:mm:ss"，即 fn5 默认解析格式）。 */
internal const val TS_2088_08_08_STR = "2088-08-08 00:00:00"

// CLS_TRYOUT / CLS_DOWNLOAD_MODEL / CLS_SHAREPREF_UTILS 是**混淆名**
// （类名或方法名会被 R8 重映射）。每个挂载点都配了 DexKit 内容锚点（字符串常量 / 参数类型 / 类字段类型），
// 直连反射失败时按特征兜底；锚点与依据写在各自 collect* 函数的 DexHint 处。
internal const val CLS_TRYOUT = "$PKG.tryout.a"
internal const val CLS_INCENTIVE_INFO = "$PKG.incentive.entity.TimeLimitIncentiveInfo"

internal const val CLS_THEME_RES_MANAGER = "$PKG.theme.apply.ThemeResManager"
internal const val CLS_THEME_APPLY_STATE = "$PKG.theme.apply.ThemeApplyState"

// 2026-10 下载链（docs/reverse/theme-store.md §4）：DownloadInfo（uri L145 public String /
// isPayedItem L939 只看 price）。已删 H2（`o52.addDownloadItem`，被 H2b 完全覆盖）历史依据见 audit §三.2。
internal const val CLS_DOWNLOAD_INFO = "$PKG.common.DownloadInfo"
// DownloadModel builder 产物（package 内混淆名）；DexKit 锚点 = 类字段类型 WeakReference + common.DownloadInfo。
internal const val CLS_DOWNLOAD_MODEL = "$PKG.common.a"

// 云控统一读点：SharepreferenceUtils（混淆名 u15，默认包）——全部云控布尔开关的唯一出口。
// 强制项数据在 CloudSwitchTable（key 与逻辑分离），此处只挂读点、查表短路。
internal const val CLS_SHAREPREF_UTILS = "u15"

// 美豆（bonus/金币/福利）入口——服务端下发的**槽位**；旧口径（collect 层拦截）已废弃改源头，
// 历史见 docs/theme-store.md §九。
internal const val CLS_SLOT_DATA_MATE = "$PKG.hitop.mate.SlotData"

// 底部导航 tab —— AMS systemParam 在线配置 JSON；唯一域模型出口 = getBottomTabConfigs()。
internal const val CLS_BOTTOM_TAB_CONFIGS = "$PKG.hitop.entity.BottomTabConfigs"
// 主页 Activity（未混淆 keep 名单）——createTabs 的宿主，S2b 前置裁剪挂载点。
internal const val CLS_MAIN_ACTIVITY = "$PKG.HwThemeManagerActivity"

/** 挂载任务类型：[CONST] 常量短路；[FORCE_ARG] 第 forceArgIndex 参强制； */
internal enum class MountKind { CONST, FORCE_ARG, KEY_OVERRIDE, SLOT_PRUNE, BOTTOM_TAB_PRUNE, BLOCK, FINISH_ACTIVITY, PRE_PROCESS, VIP_STATE_FIX }

/** DownloadInfo 实例获取路径（uriEmptyRoute 条件路由）：[THIS] H1；[GET_DOWNLOAD_INFO] H2b 反射 getDownloadInfo()。 */
internal enum class UriOwner { THIS, GET_DOWNLOAD_INFO }

internal class MountTask(
    val kind: MountKind,
    override val className: String,
    override val methodName: String,
    override val paramCount: Int,
    override val returnType: String,
    val value: Any?,
    override val id: String,
    override val description: String,
    override val resolveValue: ((ClassLoader) -> Any?)? = null,
    /** 条件路由（下载链 H1/H2b）：仅 uri 为空（null/空串）时短路/改参，非空放行（不误伤正常免费/已购项）。 */
    val uriEmptyRoute: Boolean = false,
    /** DownloadInfo 实例获取路径（见 [UriOwner]）。 */
    val uriOwner: UriOwner = UriOwner.THIS,
    /** [MountKind.FORCE_ARG] 强制参数下标（0 起；默认 0 = 既有 setter 首参语义不变）。 */
    val forceArgIndex: Int = 0,
    /** DexKit 兜底特征（混淆名目标用；null = 仅直连轨；四维强度与纪律见 [DexHint]）。 */
    override val dex: DexHint? = null,
) : MountEngine.Task

/** 数据表挂载点 → 运行时任务（HookPoint 的声明式字段在此落到具体类型）。 */
internal fun HookPoint.toMountTask(): MountTask = MountTask(
    kind = MountKind.valueOf(kind),
    className = className,
    methodName = methodName,
    paramCount = paramCount,
    returnType = returnType,
    value = value,
    id = id,
    description = description,
    resolveValue = enumRef?.let { ref ->
        { loader: ClassLoader ->
            runCatching {
                loader.loadClass(ref.substringBeforeLast('.')).enumConstants
                    ?.firstOrNull { (it as? Enum<*>)?.name == ref.substringAfterLast('.') }
            }.getOrNull()
        }
    },
    uriEmptyRoute = uriEmptyRoute,
    uriOwner = UriOwner.valueOf(uriOwner),
    forceArgIndex = forceArgIndex,
    dex = toDexHint(),
)

private fun HookPoint.toDexHint(): DexHint? =
    if (dexStrings == null && dexParamTypes == null && dexClassStrings == null && dexClassFieldTypes == null) null
    else DexHint(dexStrings, dexParamTypes, dexClassStrings, dexClassFieldTypes)

// ---------------- 跨文件运行时工具（路由判定 / 反射缓存 / 判据日志） ----------------

/** 下载链路由命中日志节流计数（task.id → 已打印次数）。下载是用户动作、可重复触发，不节流会刷屏。 */
private val routeLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

/**
 * 路由激活判定：uriEmptyRoute=false 时恒激活；=true 时额外要求 DownloadInfo.uri 为空才激活
 * （非空放行；uri 读取失败按版本漂移放行，安全默认）。触发时输出 INFO 判据日志
 * （每 task 前 2 次，不受 DEBUG_LOGGING 门控 —— 与其余判据日志同一节流口径）。
 */
internal fun routeActive(
    chain: XposedInterface.Chain,
    module: XposedModule,
    task: MountTask,
): Boolean {
    if (!task.uriEmptyRoute) return true
    val blank = isUriBlank(chain, task)
    if (blank) {
        val n = routeLogCount.merge(task.id, 1) { a, b -> a + b } ?: 1
        if (n <= ROUTE_LOG_PER_TASK) {
            module.log(
                Log.INFO, MountEngine.TAG,
                "theme-download-chain 路由触发: ${task.id}（DownloadInfo.uri 为空 → ${actionDesc(task)}）",
            )
        }
    }
    return blank
}

private const val ROUTE_LOG_PER_TASK = 2

/**
 * 条件路由的 uri 判空：按 [UriOwner] 取 DownloadInfo 实例，反射读 public String uri。四分支：
 * ① 实例 null → 视为空；② 字段 null/空串/"null" 占位/无 "://" → 空（触发路由，真免费 CDN 直链必含
 * "://" 不受影响）；③ 反射异常（版本漂移）→ 放行原逻辑。
 */
internal fun isUriBlank(chain: XposedInterface.Chain, task: MountTask): Boolean {
    val info: Any? = when (task.uriOwner) {
        UriOwner.THIS -> chain.thisObject
        UriOwner.GET_DOWNLOAD_INFO -> runCatching {
            chain.thisObject?.javaClass?.getMethod("getDownloadInfo")?.invoke(chain.thisObject)
        }.getOrElse { return false }  // ③ 探测异常（版本漂移）→ 放行（安全默认）
    }
    val owner = info ?: return true  // ① 实例为 null：视为空（原入口本就提前 return，路由无害）
    val uri = runCatching { owner.javaClass.getField("uri").get(owner) }
        .getOrElse { return false }  // ③ 反射异常（NoSuchField/版本漂移）→ 放行（安全默认）
    val s = uri as? String ?: return false  // 非 String 字段（版本漂移）→ 放行
    return s.isNullOrEmpty() || s == "null" || !s.contains("://")  // ② 空/占位/非 URL → 触发路由
}

internal fun actionDesc(task: MountTask): String = when (task.kind) {
    MountKind.CONST -> "常返 ${task.value}"
    MountKind.FORCE_ARG -> "第 ${task.forceArgIndex + 1} 参强制 ${task.value}"
    MountKind.KEY_OVERRIDE -> "云控 key 命中即短路（表见 CloudSwitchTable）"
    MountKind.SLOT_PRUNE -> "SlotData.list 按 AdSlotFilter 表裁剪"
    MountKind.BOTTOM_TAB_PRUNE -> "BottomTabConfigs.list 按 BottomTabFilter 表裁剪"
    MountKind.BLOCK -> "空实现阻断（不调原方法）"
    MountKind.FINISH_ACTIVITY -> "放行原方法后 finish Activity"
    MountKind.PRE_PROCESS -> "前置修正 this 数据后放行原方法"
    MountKind.VIP_STATE_FIX -> "VipState 实例字段改写（after，null 自建）"
}

/** 反射方法缓存（`类名#方法名` → Method），避免在 getList 这类的取数路径上反复查找。 */
private val reflMethodCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>>()

internal fun cachedMethod(o: Any, name: String): java.lang.reflect.Method? = reflMethodCache.computeIfAbsent(
    "${o.javaClass.name}#$name",
) { key ->
    java.util.Optional.ofNullable(
        runCatching { o.javaClass.getMethod(key.substringAfterLast('#')) }.getOrNull(),
    )
}.orElse(null)

internal fun reflectGet(o: Any, name: String): Any? =
    runCatching { cachedMethod(o, name)?.invoke(o) }.getOrNull()

/** 云控强制命中计数（key → 已打印次数）。读点高频（多处循环内读取），每 key 只打前 [CLOUD_LOG_PER_KEY] 次防刷屏。 */
private val cloudHitLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

private const val CLOUD_LOG_PER_KEY = 2

/** 云控强制命中判据日志（节流；真机确认表驱动拦截生效用）。 */
internal fun logCloudOverride(module: XposedModule, key: String, value: Boolean) {
    val n = cloudHitLogCount.merge(key, 1) { a, b -> a + b } ?: 1
    if (n <= CLOUD_LOG_PER_KEY) {
        module.log(Log.INFO, MountEngine.TAG, "cloud-switch 强制: $key → $value")
    }
}

/** 槽位裁剪判据日志（节流，每 key 前 2 次；真机核对"美豆入口已从载荷剔除"用）。 */
private val slotPruneLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

internal fun logSlotPrune(module: XposedModule, kind: String, value: String, title: String?) {
    val key = "$kind:$value"
    val n = slotPruneLogCount.merge(key, 1) { a, b -> a + b } ?: 1
    if (n <= 2) {
        module.log(Log.INFO, MountEngine.TAG, "美豆槽位已从服务端载荷剔除: $kind=$value title=$title（AdSlotFilter）")
    }
}

/** 底部 tab 裁剪判据日志（每 tab 前 2 次；真机核对"VIP tab 已从服务端配置剔除"用）。 */
private val bottomTabPruneLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

internal fun logBottomTabPrune(module: XposedModule, rule: top.honorhook.xposed.data.BottomTabFilter.Rule) {
    val n = bottomTabPruneLogCount.merge(rule.name, 1) { a, b -> a + b } ?: 1
    if (n <= 2) {
        module.log(
            Log.INFO, MountEngine.TAG,
            "底部 tab 已从服务端配置剔除: ${rule.name}(bottomTabId=${rule.id})（BottomTabFilter）",
        )
    }
}
