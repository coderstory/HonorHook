package top.honorhook.xposed.target.xhs

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.core.MountScheduler
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import java.lang.reflect.Modifier

/**
 * X2 去水印（引擎 XhsHook·X2）：作者水印 + AI「生成」标签。
 *
 * 三个子点（doc §三 X2a/X2b/X2c）：
 *   - X2a `MediaSaveConfig.c()` → true：`@kd.c("disable_watermark")` getter，未混淆直连，
 *     9.33.4→9.49.1 方法号零漂移（jadx 双版本对照），9.50.0 仍存活零漂移（findings-x2b
 *     实证：注解 :18、`public final boolean c()` :46）。合成入口 nd8.j.a 的 z（作者水印）由此关。
 *   - X2b `nd8.j.b(Context, boolean)` → null：AI 水标位图构造（inflate→view→Bitmap）。
 *     混淆名，9.50.0 R8 全量重命名 qc8.j→nd8.j（同 classes6.dex 内换包，方法名/签名/方法体
 *     语义与 9.49.1 逐字节级对齐；dexdump c6.txt:901382 类头实证，b() 方法体 inflate id
 *     0x7f0c0f79→0x7f0c1b79 平移、alpha 0.56 同旧，findings-x2b 2026-10-19）。串锚 = 同类
 *     方法体日志串 "disableAiWaterMark is "（nd8.j.a 实读，全 APK 唯一）+
 *     "andr_image_save_white_bg_optimized"（全 APK 唯一）+ tag "SaveImagesHelper"（跨类，
 *     作 conjunction）。NOT_RESOLVED 降级 = AIGC 帖仍画 AI 标签，可接受（作者水印走
 *     X2a/X2c 不受影响）。
 *   - X2c ImageBean disable_ai getter → true：**方法号已实证漂移**（9.33.4 u() → 9.49.1 v()；
 *     savefix 1.2.3 裸挂 u() 在 9.49.1 会撞到 u0()（original String getter）类型炸）。故
 *     **运行时注解反射定位**（[discoverDisableAiGetter]）：扫 `@kd.c("disable_ai")` 字段，
 *     再用「字段置真探针」找读它的无参 boolean getter——注解串不漂，字段名/方法号全免疫。
 *     适配盲区：adapt_new_version.py 无静态挂载点可核对，本 target 点数少计 1（doc 口径）。
 */
object XhsWatermark {

    private const val JUDGE_PREFIX = "XhsHook·X2"


    private const val CLS_MEDIA_SAVE_CONFIG = "com.xingin.entities.MediaSaveConfig"
    /** X2b 载体：9.50.0 R8 重命名 qc8.j→nd8.j（同 classes6.dex 内换包；dexdump c6.txt:901382 类头实证，findings-x2b 2026-10-19）。
     *  版本坐标走 [XhsPoints]（"x2b"：9.49.x = qc8.j，2026-10-20 拍板旧宿主保持旧坐标）。 */

    /** X2c 载体类（未混淆；方法号漂移实证，不做静态挂载点）。 */
    private const val CLS_IMAGE_BEAN = "com.xingin.entities.ImageBean"

    /** kd.c JSON 注解（RUNTIME retention，value() + alternate()，jadx classes12 实证）。 */
    private const val CLS_KD_C = "kd.c"
    private const val ANN_DISABLE_AI = "disable_ai"

    private const val ID_MSC_DISABLE_WM = "xhs:noWatermark.mediaSaveConfig.c"
    private const val ID_AI_BITMAP = "xhs:noWatermark.qc8j.b"
    private const val ID_DISABLE_AI = "xhs:noWatermark.imageBean.disableAi"

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apkPath = param.applicationInfo?.sourceDir
        // 读版本 → 选坐标 → begin → 挂载，整链挪到挂载后台线程（与 InstallerHook 同范式）：
        // 版本读取走公开 PM 取法 = 同步 IPC（system_server 解析 APK），放宿主主线程会卡宿主启动；
        // begin 与 attemptMount 同线程 ⇒ 引擎状态无跨线程竞态。
        MountScheduler.postDelayed(VERSION_PROBE_DELAYS_MS[0]) {
            selectPointsAndMount(module, loader, apkPath, 0)
        }
    }

    /**
     * 版本探针重试延迟（毫秒；下标 = 第几次尝试）：版本读不到就下一轮再试，重试用尽按基线坐标盲挂。
     * 旧宿主（9.49.1）靠这层拿到覆盖套坐标（x2b = qc8.j），否则会挂基线坐标 = 旧宿主上这些类不存在。
     */
    private val VERSION_PROBE_DELAYS_MS = longArrayOf(0, 200, 600)

    /** 读版本 → 选坐标 → 挂载（后台线程）。 */
    private fun selectPointsAndMount(
        module: XposedModule,
        loader: ClassLoader,
        apkPath: String?,
        attempt: Int,
    ) {
        // 版本源用公开 API 双取法（HostVersion.resolveHostVersion）；旧取法（ActivityThread.currentPackageManager）
        // 现代 Android/AOSP 无此成员（真机 + AOSP 14 源码全文 0 命中）⇒ 恒 null ⇒ 9.49.1 覆盖套从未生效过，一直走基线。
        val hostVersion = HostVersion.resolveHostVersion(apkPath, "com.xingin.xhs")
        if (hostVersion == null && attempt + 1 < VERSION_PROBE_DELAYS_MS.size) {
            MountScheduler.postDelayed(VERSION_PROBE_DELAYS_MS[attempt + 1]) {
                selectPointsAndMount(module, loader, apkPath, attempt + 1)
            }
            return
        }
        val baseline = FeatureCatalog.baselineVersionOf("com.xingin.xhs")
        if (hostVersion == null) {
            module.log(
                Log.WARN, MountEngine.TAG,
                "宿主版本读取失败（公开取法都没读到版本号），按基线 $baseline 坐标盲挂，判据以 installed N/M 为准",
            )
        }
        // 版本自检的期望值 = 实际用的坐标版本（读到版本就用它）：旧宿主挂覆盖套坐标时不该报「漂移」。
        val session = MountEngine.open("XhsHook·X2", "com.xingin.xhs", apkPath, hostVersion ?: baseline)
        session.attemptMount(buildPending(hostVersion), module, loader) { task -> mountOne(session, task, module, loader) }
    }

    /**
     * 挂载点集合（按宿主版本选坐标）：[hostVersion] 为 null = 版本读不到 ⇒ 基线坐标。
     *
     * 坐标语义见 [XhsPoints]（覆盖套显式 null = 该版本无此点 ⇒ 该点不进 pending）。
     * 单测直调本函数（见 XhsPendingTest）——纯逻辑，不碰 Android。
     */
    internal fun buildPending(hostVersion: String?): List<MountEngine.Task> {
        val clsX2b = XhsPoints.cls(hostVersion, "x2b")

        val pending = mutableListOf<MountEngine.Task>()
        pending += MountEngine.SimpleTask(
            className = CLS_MEDIA_SAVE_CONFIG,
            methodName = "c",
            paramCount = 0,
            returnType = "boolean",
            id = ID_MSC_DISABLE_WM,
            description = "X2a 去水印·MediaSaveConfig.c()→true（@kd.c(disable_watermark) getter）",
        )
        if (clsX2b != null) {
            pending += MountEngine.SimpleTask(
                className = clsX2b,
                methodName = "b",
                paramCount = 2,
                returnType = "android.graphics.Bitmap",
                id = ID_AI_BITMAP,
                description = "X2b 去水印·AI 水标位图构造→null（坐标见 XhsPoints x2b；串锚 disableAiWaterMark is + andr_image_save_white_bg_optimized）",
                dex = DexHint(classStrings = listOf("disableAiWaterMark is ", "andr_image_save_white_bg_optimized", "SaveImagesHelper")),
            )
        }
        // X2c：动态发现任务——className/methodName 由 resolveValue 期注解反射填入（见 X2cTask）
        pending += X2cTask()
        return pending
    }

    private fun mountOne(session: MountSession, task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        when (task.id) {
            ID_MSC_DISABLE_WM -> session.mount(task, module, loader) { chain ->
                // X2a：恒 true（宿主读它决定是否画作者水印）。boolean 回调返回 Boolean。
                true
            }
            ID_AI_BITMAP -> session.mount(task, module, loader) { chain ->
                // X2b：AI 水标位图不构造 → 合成时 bitmap4 == null 不画。回调返回 null（引用类型）。
                null
            }
            else -> session.mount(task, module, loader) { chain ->
                // X2c：disable_ai getter after→true（UI/合成两路都读它决定是否贴 AI「生成」标签）
                val result = runCatching { chain.proceed() as? Boolean }.getOrNull() ?: false
                if (!result) {
                    session.judge(module, task, JUDGE_PREFIX, "disable_ai 已置真")
                }
                true
            }
        }

    /**
     * X2c 动态任务：resolveValue 期完成注解反射定位（类未加载/注解不匹配 → null = NOT_RESOLVED
     * 随类加载重试，最终放弃走汇总行失败清单——doc「静默降级为 AI 标签可能保留」口径）。
     */
    private class X2cTask : MountEngine.Task {
        override val id = ID_DISABLE_AI
        override val description =
            "X2c 去水印·ImageBean disable_ai getter→true（运行时注解反射定位，无静态挂载点）"
        override val paramCount = 0
        override val returnType = "boolean"
        override val group = ""

        /** 定位成功后填 getter 方法名（直连轨按名复解析同一方法）；未定位 = 直连 miss 走 DexKit（无锚）= NOT_RESOLVED。 */
        @Volatile var resolvedGetter: String? = null

        override val className: String
            get() = CLS_IMAGE_BEAN
        override val methodName: String
            get() = resolvedGetter ?: ""

        override val resolveValue: ((ClassLoader) -> Any?)? = { loader ->
            // resolveValue 只是就绪门：MountEngine.resolveTask 丢弃返回值，随后仍按 methodName
            // 解析 ⇒ 必须在此写回 resolvedGetter，methodName 的 getter 才能非空、直连轨才命得中
            // （否则恒 "" → 直连 0 命中 → 无锚 DexKit → NOT_RESOLVED 空转到重试上限）。
            discoverDisableAiGetter(loader)?.also { resolvedGetter = it }
        }
    }

    /**
     * 定位 disable_ai getter：
     * ① 扫 ImageBean.declaredFields 找 `@kd.c("disable_ai")` 字段（jadx 实证 field=disableAi）；
     * ② 无参构造一个实例（Kotlin 默认值构造器，jadx 实证 public ImageBean()）；
     * ③ 差分探针找 getter：对候选无参非静态 boolean 方法，字段置 true 后 invoke 须 == true、
     *    置 false 后 invoke 须 == false，两态都符合才认定——只测 true 态会把「默认值恰为 true
     *    的其它布尔字段 getter」误认（firstOrNull 挂错且强制 true 有副作用风险）。实例本地
     *    新建可丢弃，不复位。不依赖任何命名/方法号约定（doc：默认 getX() 命名不可靠）。
     * 任一步失败返回 null = NOT_RESOLVED（服务端改字段名/结构大改，静默降级）。
     */
    private fun discoverDisableAiGetter(loader: ClassLoader): String? = runCatching {
        val cls = loader.loadClass(CLS_IMAGE_BEAN)
        val annCls = loader.loadClass(CLS_KD_C)
        val field = cls.declaredFields.firstOrNull { f ->
            f.annotations.any { ann ->
                val jAnn = ann as java.lang.annotation.Annotation
                jAnn.annotationType() == annCls &&
                    annCls.getMethod("value").invoke(jAnn) == ANN_DISABLE_AI
            }
        } ?: return null
        val instance = cls.getDeclaredConstructor().newInstance()
        field.isAccessible = true
        fun probe(m: java.lang.reflect.Method, value: Boolean): Boolean = runCatching {
            field.setBoolean(instance, value)
            m.isAccessible = true
            m.invoke(instance) == value
        }.getOrDefault(false)
        val getter = cls.declaredMethods.firstOrNull { m ->
            !m.isSynthetic && !Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 0 && m.returnType == Boolean::class.javaPrimitiveType &&
                probe(m, true) && probe(m, false)
        } ?: return null
        getter
    }.getOrNull()?.name
}
