package top.honorhook.xposed.target.xhs

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * X5 批量保存（引擎 XhsHook·X5）：图片笔记长按菜单点「保存」→ 弹窗 [保存本张（原图）/ 保存全部 N 张]。
 *
 * 挂载点 `mh9.b`（图片长按面板「保存」点击 lambda，classes7 jadx 实证 implements Function1+Function0，
 * 逻辑体在 invoke(Object)，方法体含日志串 "download blocked, disableSaveMedia=true" /
 * "onDownloadClick, noteId="——双串 DexKit 锚，混淆免疫）。选中「本张」→ 手动重放原方法
 * （[replaying] 重入门闩防递归）；「全部」→ [runBatch] 逐张走原生 SPI（X3 直链替换对每张生效）。
 *
 * 图片列表数据源 = **运行期类型图搜**（[findImageBeanList]）：从点击 lambda 持有的 controller
 * （mh9.c，字段名混淆按值扫）出发，广度优先扫字段图（≤3 层、判环），找「元素全为
 * com.xingin.entities.ImageBean 且 size≥2」的 List——不赌 NoteFeed/ta9.g/presenter 任一具体
 * 结构（三者字段序全是混淆名，逐一定位脆弱且版本绑定；类型不变式比结构不变式稳）。
 *
 * 边界（有意）：
 * - 图集 ≤1 张：不弹窗直接放行原生保存（无选择意义）
 * - 视频面板不拦：X4 已把原生视频保存改写为直链下载，等效「保存原视频」
 * - Activity 取不到（字段图搜不到 Activity 实例）：放行原生保存，打 WARN（弹窗是拦载体的前提）
 * - 首版历史：画廊三击方案已否决（400ms 窗口苛刻 + 双击缩放冲突，2026-10-14 用户拍板），勿复活
 */
object XhsBatchSave {

    private const val JUDGE_PREFIX = "XhsHook·X5"

    private val engine = MountEngine("XhsHook·X5", "com.xingin.xhs")

    /** 图片长按面板「保存」点击 lambda（逻辑体 invoke(Object)，jadx mh9/b.java:41 实证）。 */
    private const val CLS_SAVE_CLICK_LAMBDA = "mh9.b"
    private const val METHOD_INVOKE = "invoke"

    /** 类型锚点（未混淆全限定名，运行期判型用）。 */
    private const val CLS_IMAGE_BEAN = "com.xingin.entities.ImageBean"
    private const val CLS_SAVE_PROXY = "android.xingin.com.spi.saveimage.ISaveImageProxy"
    private const val CLS_SPI_LOADER = "com.xingin.spi.service.ServiceLoaderKtKt"
    private const val CLS_SPI_CALLBACK = "com.xingin.spi.service.base.SPICallback"

    private const val SAVE_GAP_MS = 300L

    /** 类型图搜深度/广度上界（controller→viewmodel→note 三层足够；防病态深图拖慢点击线程）。 */
    private const val GRAPH_MAX_DEPTH = 3
    private const val GRAPH_MAX_FIELDS_PER_NODE = 64

    /** 手动重放原方法的重入门闩（dialog 回调里 method.invoke 二次进 hook 时放行）。 */
    private val replaying = AtomicBoolean(false)

    /** 宿主 ClassLoader（install 期写入）：宿主类反射与回调代理的类身份全靠它（双 kotlin-stdlib 不相容）。 */
    @Volatile private var hostLoader: ClassLoader? = null

    /** 批量进行中标记（同批未完成再触发忽略；NoteFeed 会话指纹去重已删——重存同 note 是合法需求）。 */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        hostLoader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf("com.xingin.xhs"))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_SAVE_CLICK_LAMBDA,
                methodName = METHOD_INVOKE,
                paramCount = 1,
                returnType = "java.lang.Object",
                id = "xhs:batchSave.saveClick.invoke",
                description = "X5 批量保存·图片长按面板「保存」点击拦截弹窗（本张/全部）",
                // 方法体内日志双串（jadx mh9/b.java:55/63 实证）；类里另有 0 参 invoke 委托，
                // 挂 1 参逻辑体即覆盖（0 参无独立逻辑）。若真机零触发再核委托关系（doc §六记录）。
                dex = DexHint(
                    strings = listOf(
                        "download blocked, disableSaveMedia=true",
                        "onDownloadClick, noteId=",
                    ),
                ),
            ),
        )
        engine.attemptMount(pending, module, hostLoader!!) { task -> mountOne(task, module) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        engine.mount(task, module, hostLoader!!) { chain ->
            // 重放放行：dialog「保存本张」里 method.invoke 二次进 hook，直接走原逻辑
            if (replaying.get()) return@mount chain.proceed()

            val controller = extractController(chain.thisObject)
            val activity = controller?.let { findActivity(it) }
            engine.judge(module, task, JUDGE_PREFIX, "保存点击已拦截（controller=${controller != null} activity=${activity != null}）")
            if (activity == null) {
                // 弹窗载体都没有：放行原生（已带解锁+水印+X3），WARN 留痕
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX Activity 未取到，放行原生保存")
                return@mount chain.proceed()
            }

            // 捕获重放三要素再进 dialog 闭包——Chain 是回调期对象，intercept 返回后访问
            // 其状态不保证有效（X4 教训同族：作用域对象生命周期）；dialog 期只用本地捕获值
            val replayMethod = chain.executable as? Method
            val replaySelf = chain.thisObject
            val replayArg = chain.getArg(0)
            mainHandler.post {
                showSaveDialog(activity, replayMethod, replaySelf, replayArg, controller!!, task, module)
            }
            hostUnit() ?: Any()   // 非 void：勿返 null（X4 闪退教训）；hostUnit 实际恒非空，兜底保险
        }

    /** 点击 lambda（mh9.b）的 controller 字段（字段名 d 混淆，按「首个非 null 实例字段值」取）。 */
    private fun extractController(lambda: Any?): Any? {
        if (lambda == null) return null
        return runCatching {
            lambda.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                runCatching {
                    f.isAccessible = true
                    f.get(lambda)
                }.getOrNull()
            }
        }.getOrNull()
    }

    /**
     * 类型图搜 ImageBean 列表：BFS 扫字段值（≤[GRAPH_MAX_DEPTH] 层，IdentityHashMap 判环），
     * 命中「非空 List 且元素全为 ImageBean」。String/数字等叶子不展开；只认 List 类型（数组/其它容器不认）。
     */
    private fun findImageBeanList(root: Any): List<Any> {
        val visited = IdentityHashMap<Any, Boolean>()
        var frontier = listOf(root)
        repeat(GRAPH_MAX_DEPTH) {
            val next = mutableListOf<Any>()
            for (node in frontier) {
                if (visited.put(node, true) != null) continue
                val fields = runCatching { node.javaClass.declaredFields }.getOrNull() ?: continue
                for (f in fields.take(GRAPH_MAX_FIELDS_PER_NODE)) {
                    val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                    if (v is List<*>) {
                        if (v.isNotEmpty() && v.all { it != null && it.javaClass.name == CLS_IMAGE_BEAN }) {
                            return v.filterNotNull()
                        }
                    } else if (v.javaClass.name.startsWith("java.")) {
                        continue   // JDK 叶子（String/Number/…）不展开
                    } else {
                        next += v
                    }
                }
            }
            frontier = next
        }
        return emptyList()
    }

    /** 类型图搜 Activity 实例（controller 持有宿主页引用；同 BFS 参数）。 */
    private fun findActivity(root: Any): Activity? {
        val visited = IdentityHashMap<Any, Boolean>()
        var frontier = listOf(root)
        repeat(GRAPH_MAX_DEPTH) {
            val next = mutableListOf<Any>()
            for (node in frontier) {
                if (visited.put(node, true) != null) continue
                if (node is Activity) return node
                val fields = runCatching { node.javaClass.declaredFields }.getOrNull() ?: continue
                for (f in fields.take(GRAPH_MAX_FIELDS_PER_NODE)) {
                    val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                    if (v is Context) {
                        (v as? Context)?.unwrapActivity()?.let { return it }
                    } else if (!v.javaClass.name.startsWith("java.")) {
                        next += v
                    }
                }
            }
            frontier = next
        }
        return null
    }

    /** 弹窗：本张=重放原方法；全部=批量。张数按下按钮时现查（图搜结果进 toast）。 */
    private fun showSaveDialog(
        activity: Activity,
        replayMethod: Method?,
        replaySelf: Any?,
        replayArg: Any?,
        controller: Any,
        task: MountEngine.SimpleTask,
        module: XposedModule,
    ) {
        runCatching {
            android.app.AlertDialog.Builder(activity)
                .setTitle("保存")
                .setItems(arrayOf("保存本张（原图）", "保存全部", "取消")) { _, which ->
                    when (which) {
                        0 -> replayOriginal(replayMethod, replaySelf, replayArg, module)
                        1 -> triggerBatch(activity, controller, task, module)
                        else -> {}
                    }
                }
                .setCancelable(true)
                .show()
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 弹窗失败，本张降级原生保存: ${it.message}")
            replayOriginal(replayMethod, replaySelf, replayArg, module)
        }
    }

    /** 手动重放原 invoke（重入门闩防递归）。 */
    private fun replayOriginal(replayMethod: Method?, replaySelf: Any?, replayArg: Any?, module: XposedModule) {
        if (replayMethod == null || replaySelf == null) return
        replaying.set(true)
        try {
            replayMethod.isAccessible = true
            replayMethod.invoke(replaySelf, replayArg)
        } catch (t: Throwable) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 本张重放失败: ${t.message}")
        } finally {
            replaying.set(false)
        }
    }

    private fun triggerBatch(activity: Activity, controller: Any, task: MountEngine.SimpleTask, module: XposedModule) {
        val beans = findImageBeanList(controller)
        if (beans.isEmpty()) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 图搜未命中 List<ImageBean>（深度 $GRAPH_MAX_DEPTH 内），批量放弃")
            runCatching {
                android.widget.Toast.makeText(activity, "未取到图片列表，批量不可用", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        val images = beans.mapNotNull { bean ->
            val fileid = runCatching { bean.javaClass.getMethod("K").invoke(bean) as? String }.getOrNull()
            val url = runCatching { bean.javaClass.getMethod("A0").invoke(bean) as? String }.getOrNull()
            val filterUrl = runCatching {
                (bean.javaClass.getMethod("R").invoke(bean))?.let { fm ->
                    fm.javaClass.getMethod("o").invoke(fm) as? String
                }
            }.getOrNull()
            if (!fileid.isNullOrEmpty() || !url.isNullOrEmpty()) {
                ImageEntry(fileid.orEmpty(), url.orEmpty(), filterUrl)
            } else null
        }
        if (images.isEmpty()) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX ImageBean 取值失败（K/A0/R 漂移？），批量放弃")
            return
        }
        // inFlight 以 note 指纹防并发（不去重会话——重存同 note 合法）
        val key = images.joinToString("|") { it.fileid }.hashCode()
        if (!inFlight.add(key)) return
        engine.judge(module, task, JUDGE_PREFIX, "批量保存 ${images.size} 张")
        runBatch(activity, images, module) {
            inFlight.remove(key)
        }
    }

    private class ImageEntry(val fileid: String, val url: String, val filterUrl: String?)

    /** 逐张 300ms 间隔走原生 SPI 保存（disableWaterMark=true 与 X2a 同语义），Toast 汇总「已保存 N/M」。 */
    private fun runBatch(activity: Activity, images: List<ImageEntry>, module: XposedModule, onDone: () -> Unit) {
        val proxy = resolveSaveProxy() ?: run {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX ISaveImageProxy 不可取，批量放弃")
            onDone()
            return
        }
        val saveMethod = proxy.javaClass.methods.firstOrNull {
            it.name == "save" && it.parameterCount == 8
        } ?: run {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX save(8参) 未找到，批量放弃")
            onDone()
            return
        }
        var success = 0
        var done = 0
        // 回调代理的 loader 必须是宿主的：kotlin.jvm.functions.Function1 若用模块侧同名类，
        // 宿主 invoke 时类型身份不符 ClassCastException（双 kotlin-stdlib 不相容）。
        val hostL = hostLoader ?: activity.javaClass.classLoader
        val fn1Cls = runCatching { Class.forName("kotlin.jvm.functions.Function1", true, hostL) }.getOrNull()
        val successCb = fn1Cls?.let {
            java.lang.reflect.Proxy.newProxyInstance(hostL, arrayOf(it)) { _, _, _ -> done++; success++; null }
        }
        val errorCb = fn1Cls?.let {
            java.lang.reflect.Proxy.newProxyInstance(hostL, arrayOf(it)) { _, _, _ -> done++; null }
        }
        saveMethod.isAccessible = true
        images.forEachIndexed { index, entry ->
            mainHandler.postDelayed({
                try {
                    // 回调代理不可用时传 null：宿主 null 回调有兜底（sb9/y 同款 null 直传实证）
                    saveMethod.invoke(
                        proxy, activity, entry.fileid, "", entry.url, entry.filterUrl.orEmpty(),
                        true, successCb, errorCb,
                    )
                } catch (t: Throwable) {
                    done++
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 第 ${index + 1} 张保存失败: ${t.message}")
                }
                if (done >= images.size) {
                    runCatching {
                        android.widget.Toast.makeText(activity, "已保存 $success/${images.size}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 批量保存完成 $success/${images.size}")
                    onDone()
                }
            }, index * SAVE_GAP_MS)
        }
    }

    /** 取原生 SPI 实例（sb9/y 同链 service$default(Class,null,null,3,null)，回落 java ServiceLoader）。类加载一律经宿主 loader。 */
    private fun resolveSaveProxy(): Any? = runCatching {
        val loader = hostLoader ?: return null
        val proxyCls = Class.forName(CLS_SAVE_PROXY, true, loader)
        runCatching {
            val loaderCls = Class.forName(CLS_SPI_LOADER, true, loader)
            val cbCls = Class.forName(CLS_SPI_CALLBACK, true, loader)
            loaderCls.methods.firstOrNull { it.name == "service\$default" && it.parameterCount == 5 }?.let { m ->
                m.isAccessible = true
                m.invoke(null, proxyCls, null, null, 3, null)
            }
        }.getOrNull() ?: java.util.ServiceLoader.load(proxyCls, loader).firstOrNull()
    }.getOrNull()

    /** 宿主侧 kotlin.Unit.INSTANCE（拦截路径返回值；勿返 null——非 void 方法返回值即最终返回值）。 */
    private fun hostUnit(): Any? = runCatching {
        hostLoader?.loadClass("kotlin.Unit")?.getDeclaredField("INSTANCE")?.get(null)
    }.getOrNull()

    private fun Context.unwrapActivity(): Activity? {
        var c: Context = this
        var depth = 0
        while (c is ContextWrapper && depth < 8) {
            if (c is Activity) return c
            c = c.baseContext
            depth++
        }
        return null
    }
}
