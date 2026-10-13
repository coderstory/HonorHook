package top.honorhook.xposed.target.xhs

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 小红书 `com.xingin.xhs`（基线 9.49.1，versionCode 9491803，2026-10-13 真机拉取）。
 *
 * 五个功能按组各自持引擎与 installed N/M 汇总（照 SystemUiHook 的独立文件分发模式）：
 *   X1 保存解锁（本文件）/ X2 去水印（[XhsWatermark]）/ X3 高清保存（[XhsHdSave]）/
 *   X4 视频直链（[XhsVideoSave]）/ X5 批量保存（[XhsBatchSave]）。
 *
 * 逆向事实源：docs/reverse/xhs.md（§二保存链路 + §三挂载点总表，每点附重定位备注）。
 * 移植源：com.chekayo.xhssavefix 1.2.3 + JoeanAmier/XHS-Downloader（差异清单见 doc §五）。
 *
 * 边界（doc §一）：付费内容（PAID_IMAGE_DOWNLOAD）不绕过；live photo 双文件 zip 分支不覆盖。
 *
 * X1 保存解锁三直连点（类名未混淆；9.33.4→9.49.1 方法号零漂移，jadx 双版本对照实证）：
 *   - `MediaSaveConfig.b()` → false（`@kd.c("disable_save")` 字段 getter；mh9/b 下载门读它拦截保存）
 *   - `FunctionSwitch.a()` → true（`@kd.c("enable")` getter；**必须动态比对 type**——
 *     见 [readTypeValue]，不能见 a() 就 true，否则 `paid_image_download` 等其它 type 被误放）
 *   - `ShareInfoDetail$Operate.a()` → false（disable 位 getter，同 type 判定）
 * type 判定不按方法号（a/b/c 单字母名随版本漂移风险）：优先直读**字段 `type`**（实体字段名
 * R8 未改名），回落按 doc 记载的方法号（FunctionSwitch=c()/Operate=h()）。savefix 按 g() 取
 * type——9.49.1 实读 g() 返回 `tag` 字段、h() 才返回 `type`（savefix 适配版本偏差，见 doc §六）。
 */
object XhsHook : TargetHook {

    override val packageName = "com.xingin.xhs"

    /** X1 引擎名与判据前缀（modlog-f 'XhsHook' 一并过滤到各引擎汇总行与判据行）。 */
    private const val JUDGE_PREFIX = "XhsHook·X1"

    private val engine = MountEngine("XhsHook·X1", packageName)

    // ---- 挂载点类名（未混淆，jadx 实证；方法号见各 Task description）----

    private const val CLS_MEDIA_SAVE_CONFIG = "com.xingin.entities.MediaSaveConfig"
    private const val CLS_FUNCTION_SWITCH = "com.xingin.entities.notedetail.FunctionSwitch"
    private const val CLS_SHARE_OPERATE = "com.xingin.entities.ShareInfoDetail\$Operate"

    /** 服务端下发的下载 type 原文（客户端枚举 notedetail/p.java 不依赖，直比字符串）。 */
    private val DOWNLOAD_TYPES = setOf("image_download", "video_download")

    /** 挂载点 id（mountOne 按 id 分派）。 */
    private const val ID_MSC_DISABLE_SAVE = "xhs:saveUnlock.mediaSaveConfig.b"
    private const val ID_FS_ENABLE = "xhs:saveUnlock.functionSwitch.a"
    private const val ID_OPERATE_DISABLE = "xhs:saveUnlock.shareInfoOperate.a"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader

        // X2 去水印（独立文件，自带引擎 SystemUiHook 式汇总）
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_XHS_NO_WATERMARK)) {
            XhsWatermark.install(module, param)
        } else {
            logDisabled(module, FeatureCatalog.KEY_XHS_NO_WATERMARK, "XhsHook")
        }
        // X3 高清保存
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_XHS_HD_SAVE)) {
            XhsHdSave.install(module, param)
        } else {
            logDisabled(module, FeatureCatalog.KEY_XHS_HD_SAVE, "XhsHook")
        }
        // X4 视频直链
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_XHS_VIDEO_SAVE)) {
            XhsVideoSave.install(module, param)
        } else {
            logDisabled(module, FeatureCatalog.KEY_XHS_VIDEO_SAVE, "XhsHook")
        }
        // X5 批量保存
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_XHS_BATCH_SAVE)) {
            XhsBatchSave.install(module, param)
        } else {
            logDisabled(module, FeatureCatalog.KEY_XHS_BATCH_SAVE, "XhsHook")
        }

        // X1 保存解锁（本文件内联；3 点同属一个开关组，任一未解析随类加载重试）
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_XHS_SAVE_UNLOCK)) {
            logDisabled(module, FeatureCatalog.KEY_XHS_SAVE_UNLOCK, "XhsHook")
            return
        }
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_MEDIA_SAVE_CONFIG,
                methodName = "b",
                paramCount = 0,
                returnType = "boolean",
                id = ID_MSC_DISABLE_SAVE,
                description = "X1a 保存解锁·MediaSaveConfig.b()→false（@kd.c(disable_save) getter；mh9/b 下载门据此拦截）",
            ),
            MountEngine.SimpleTask(
                className = CLS_FUNCTION_SWITCH,
                methodName = "a",
                paramCount = 0,
                returnType = "boolean",
                id = ID_FS_ENABLE,
                description = "X1b 保存解锁·FunctionSwitch.a()→true（@kd.c(enable) getter；运行时比对 type∈image/video_download）",
            ),
            MountEngine.SimpleTask(
                className = CLS_SHARE_OPERATE,
                methodName = "a",
                paramCount = 0,
                returnType = "boolean",
                id = ID_OPERATE_DISABLE,
                description = "X1c 保存解锁·ShareInfoDetail\$Operate.a()→false（disable 位 getter；同 type 判定）",
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        when (task.id) {
            ID_FS_ENABLE -> mountFunctionSwitch(task, module, loader)
            ID_OPERATE_DISABLE -> mountShareOperate(task, module, loader)
            else -> engine.mount(task, module, loader) { chain ->
                // X1a：b() 返 true 即「作者关保存」，after 改 false。boolean 回调返回 Boolean。
                val result = runCatching { chain.proceed() as? Boolean }.getOrNull() ?: false
                if (result) {
                    engine.judge(module, task, JUDGE_PREFIX, "disable_save 已解锁")
                }
                false
            }
        }

    /**
     * X1b：FunctionSwitch.a() 命中 false 时动态读 type，只对 image/video_download 强制 true。
     * 其它 type（如 paid_image_download 付费图）原样放行——付费边界（doc §一）。
     */
    private fun mountFunctionSwitch(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            val result = runCatching { chain.proceed() as? Boolean }.getOrNull() ?: false
            if (result) return@mount true
            val type = readTypeValue(chain.thisObject, fallbackMethod = "c")
            if (type in DOWNLOAD_TYPES) {
                engine.judge(module, task, JUDGE_PREFIX, "type=$type 已解锁")
                true
            } else {
                false
            }
        }

    /** X1c：Operate.a()（disable 位）true 且 type 命中下载类 → 改 false。 */
    private fun mountShareOperate(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            val result = runCatching { chain.proceed() as? Boolean }.getOrNull() ?: false
            if (!result) return@mount false
            val type = readTypeValue(chain.thisObject, fallbackMethod = "h")
            if (type in DOWNLOAD_TYPES) {
                engine.judge(module, task, JUDGE_PREFIX, "type=$type 已解锁")
                false
            } else {
                true
            }
        }

    /**
     * 读实体 type 字段值：优先直读字段 `type`（实体字段名 R8 未改名，jadx 实证），回落方法号
     * （doc §三 X1b/X1c 记载）。字段名/方法号都漂移 = 返回 null = 不命中 = 放行原判定（安全侧）。
     */
    private fun readTypeValue(target: Any?, fallbackMethod: String): String? {
        if (target == null) return null
        runCatching {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                val f = runCatching { c.getDeclaredField("type") }.getOrNull()
                if (f != null) {
                    f.isAccessible = true
                    return f.get(target) as? String
                }
                c = c.superclass
            }
        }
        return runCatching {
            target.javaClass.getMethod(fallbackMethod).invoke(target) as? String
        }.getOrNull()
    }
}
