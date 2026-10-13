package top.honorhook.xposed.target

import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * U7 状态栏图标替换（移植自 com.mod.dualsignal 3.3.4 的 hookWifiRes/WifiPatch/WifiPatchTa/
 * getWifiIcon/getThemeIcon/ensureWifiZip，用户要求整合，私用）。
 *
 * 三挂载点均为 framework 层公开 API（未混淆，declaredMethods 直连）——状态栏图标 drawable
 * 读取的必经点（原插件实证选点，DualSignalHook.java L94-238/L221-238）：
 *   Resources.getDrawable(int, Theme) / Resources.getDrawableForDensity(int, int, Theme) /
 *   TypedArray.getDrawable(int)。
 * after 语义：proceed 放行宿主原读取，命中内置资产再改返回值（原 WifiPatch setResult 同语义；
 * intercept 返回值即最终返回值，非空方法返回 Drawable 子类合法，见 SystemUiHook 2026-10-11
 * 崩溃实证注释——那条是 void 方法返回值类型不匹配，此处非 void 不适用）。
 *
 * 双 gate 回落链（原 WifiPatch L94-110 语义）：wifi 开关开 → 查 wifi/<entry>.png；
 * miss 且 status 开关开 → 查 icons/<entry>.png。TypedArray 轨只查 wifi 目录、只受 wifi 开关
 * 门（原 WifiPatchTa L112-130 语义）——注意该轨 args[0] 是 TypedArray 内属性索引而非 resId，
 * 先 getResourceId(index, 0) 转 resId（原 L120 同款），resId==0 放行。
 *
 * 与原插件差异（全部有意）：无 toast、不写 Download 日志文件、无 maybeRestartForWifi 杀进程
 * （本模块开关 = 下次注入才生效）；开关安装期读死等价原插件"改配置须重启"语义；Bitmap 按
 * entry 名缓存且 wifi/icons 两目录分开（ConcurrentHashMap——Resources.getDrawable 回调来自
 * 多线程）；entry 名解析失败静默放行（PROTECTIVE 下不该炸宿主）。
 * assets 来源：reference/dualsignal/base.apk（3.3.4）解包 72 张原样入库（wifi 59 + icons 13）。
 * 判据：modlog-f 'SystemUiHook' 过滤 `SystemUiHook·U7: installed 3/3` 与 `替换 <entry>` 行。
 */
object StatusBarIconFeature {

    private const val HOST_PACKAGE = "com.android.systemui"

    /** 引擎名与判据前缀都含 "SystemUiHook"：modlog-f 'SystemUiHook' 可一并过滤到汇总行与判据行。 */
    private const val JUDGE_PREFIX = "SystemUiHook·U7"

    private val engine = MountEngine("SystemUiHook·U7", HOST_PACKAGE)

    // ---- 挂载点（framework 公开 API，未混淆，declaredMethods 直连）----

    private const val CLS_RESOURCES = "android.content.res.Resources"
    private const val CLS_TYPED_ARRAY = "android.content.res.TypedArray"

    // ---- 模块内资产路径（module APK 的 assets/，与上游解包目录结构原样同构）----

    private const val ASSET_WIFI_PREFIX = "assets/wifi/"
    private const val ASSET_STATUS_PREFIX = "assets/icons/"

    /** 原插件解码常量：inDensity 480（hdpi 基准，系统按目标屏密度自动缩放）。 */
    private const val BITMAP_IN_DENSITY = 480

    // 开关安装期读死（只由 install() 写一次，回调只读）
    @Volatile private var wifiEnabled = false
    @Volatile private var statusEnabled = false

    /** 模块自身 APK 磁盘路径（install 期取，ensureModuleZip 懒开用）。 */
    @Volatile private var moduleApkPath: String? = null

    // Bitmap 按 entry 名缓存；wifi/icons 两目录分开（同 entry 名语义不同，勿共用）
    private val wifiCache = ConcurrentHashMap<String, Bitmap>()
    private val statusCache = ConcurrentHashMap<String, Bitmap>()

    /** 模块 APK 懒开一次（双检；getInputStream 并发调用与上游 ZipFile 用法同款）。 */
    @Volatile private var moduleZip: ZipFile? = null

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        wifiEnabled = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_ICON_WIFI)
        statusEnabled = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_ICON_STATUS)
        // 双保险（SystemUiHook 分发层已判过"任一开"并负责 logDisabled，这里全关静默不装）
        if (!wifiEnabled && !statusEnabled) return
        val loader = param.defaultClassLoader
        // API 102：XposedModule.getModuleApplicationInfo() = 当前模块自身的 ApplicationInfo
        //（非 PackageLoadedParam 那个宿主 info）；ZipFile 直接开模块包取 assets
        moduleApkPath = module.getModuleApplicationInfo()?.sourceDir
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(HOST_PACKAGE))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_RESOURCES,
                methodName = "getDrawableForDensity",
                paramCount = 3,
                returnType = "android.graphics.drawable.Drawable",
                id = "systemui:iconRes.getDrawableForDensity",
                description = "U7 图标替换·Resources.getDrawableForDensity(int,int,Theme)",
            ),
            MountEngine.SimpleTask(
                className = CLS_RESOURCES,
                methodName = "getDrawable",
                paramCount = 2,
                returnType = "android.graphics.drawable.Drawable",
                id = "systemui:iconRes.getDrawable",
                description = "U7 图标替换·Resources.getDrawable(int,Theme)",
            ),
            MountEngine.SimpleTask(
                className = CLS_TYPED_ARRAY,
                methodName = "getDrawable",
                paramCount = 1,
                returnType = "android.graphics.drawable.Drawable",
                id = "systemui:iconRes.TypedArray.getDrawable",
                description = "U7 图标替换·TypedArray.getDrawable(int)（仅 wifi 轨）",
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // after 语义：先放行宿主原读取，命中内置资产再改返回值（未命中返回宿主原值）
            val result = chain.proceed()
            if (task.className == CLS_TYPED_ARRAY) {
                val ta = chain.thisObject as? TypedArray
                // args[0] 是 TypedArray 属性索引（getDrawable(int index) 的 index，非 resId），
                // 先 getResourceId(index, 0) 转 resId（原 WifiPatchTa L120 同语义）；index 非法或
                // resId==0（属性非资源引用）放行宿主原值
                val index = chain.getArg(0) as? Int
                val resId = index?.let { i -> ta?.let { runCatching { it.getResourceId(i, 0) }.getOrNull() } } ?: 0
                val replacement =
                    if (ta != null && resId != 0 && wifiEnabled) lookup(ta.resources, resId, wifiOnly = true, task, module) else null
                replacement ?: result
            } else {
                val res = chain.thisObject as? Resources
                val resId = chain.getArg(0) as? Int
                val replacement =
                    if (res != null && resId != null) lookup(res, resId, wifiOnly = false, task, module) else null
                replacement ?: result
            }
        }

    /**
     * 双 gate 回落链（原 WifiPatch/getWifiIcon/getThemeIcon 语义）：wifi 门开先查 wifi 目录，
     * miss 且 status 门开再查 icons 目录；[wifiOnly]=TypedArray 轨，只查 wifi 目录。
     * lookup 内任何异常吞掉返回 null（放行宿主原值，不打扰宿主）。
     */
    private fun lookup(
        resources: Resources,
        resId: Int,
        wifiOnly: Boolean,
        task: MountEngine.SimpleTask,
        module: XposedModule,
    ): BitmapDrawable? {
        val entry = runCatching { resources.getResourceEntryName(resId) }.getOrNull() ?: return null
        if (wifiEnabled) {
            getIcon(ASSET_WIFI_PREFIX, entry, wifiCache)?.let {
                engine.judge(module, task, JUDGE_PREFIX, "替换 $entry")
                return BitmapDrawable(resources, it)
            }
        }
        if (!wifiOnly && statusEnabled) {
            getIcon(ASSET_STATUS_PREFIX, entry, statusCache)?.let {
                engine.judge(module, task, JUDGE_PREFIX, "替换 $entry")
                return BitmapDrawable(resources, it)
            }
        }
        return null
    }

    /** 查 zip 内 <prefix><entry>.png 并解码（缓存命中免 IO；inDensity 480 原常量）。 */
    private fun getIcon(prefix: String, entry: String, cache: ConcurrentHashMap<String, Bitmap>): Bitmap? {
        cache[entry]?.let { return it }
        val zip = ensureModuleZip() ?: return null
        return runCatching {
            val ze: ZipEntry? = zip.getEntry("$prefix$entry.png")
            ze?.let {
                zip.getInputStream(it).use { input ->
                    val opts = BitmapFactory.Options().apply { inDensity = BITMAP_IN_DENSITY }
                    // 解码失败的坏图不进缓存：每 miss 都重试一次而非永久钉死 null
                    BitmapFactory.decodeStream(input, null, opts)?.also { cache[entry] = it }
                }
            }
        }.getOrNull()
    }

    private fun ensureModuleZip(): ZipFile? {
        moduleZip?.let { return it }
        synchronized(this) {
            moduleZip?.let { return it }
            return runCatching {
                moduleApkPath?.let { ZipFile(it) }
            }.getOrNull()?.also { moduleZip = it }
        }
    }
}
