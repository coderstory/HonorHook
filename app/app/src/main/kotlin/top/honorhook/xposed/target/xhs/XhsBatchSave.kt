package top.honorhook.xposed.target.xhs

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.RemoteConfig
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * X5 批量保存（引擎 XhsHook·X5）：图片画廊页三击 = 一键保存全部图。
 *
 * 挂载点（doc §三 X5 + 实读偏差，详见 docs/reverse/xhs.md §六）：
 *   `AsyncImageGalleryController.onAttach(Bundle)`（类名未混淆，partial classes7 实证；方法为
 *   ne7.c 生命周期覆写、declaredMethods 直连可解析）。实读该类**没有** onAttachedToWindow 式
 *   view attach 回调——view 绑在 presenter（aj9.h2 extends pe7.g<ImageGalleryView>）的继承字段
 *   上，且 presenter.didLoad 在 onAttach 内经 dispatchLoad 读 view ⇒ **onAttach 返回时 view
 *   必已绑定**（否则宿主自身已 NPE），故 onAttach after 即"view attach 通路"的稳定直连点。
 *
 * 数据取法（全部类型锚定，不依赖混淆名；触发期实读，天然取当前 note）：
 *   controller → presenter：getPresenter()（ne7.c 未混淆方法）
 *   presenter → gallery view：超类链扫 View 类型字段（pe7.g 字段名为单字母，按类型扫防漂）
 *   presenter → MultiTypeAdapter（drakeet 库未 shade）→ getItems() → 条目内 ImageBean 类型字段
 *   ImageBean：K()=fileid、A0()=显示 url（实读 g.o()=filePath=Uri.parse(imageBean.A0())）、
 *     R()?.o()=滤镜 url——单字母 getter 随版本漂移，漂移时该功能降级（doc X5 失败分级 FAILED 不阻断其余）
 *
 * 保存走原生 SPI（sb9/y onDownloadClick 同链）：ServiceLoaderKtKt.service$default 取
 * ISaveImageProxy → save(activity, fileid, avatar, 图url, 滤镜, disableWaterMark=true, cb, cb)，
 * 逐张 300ms 间隔；X3 的 qc8.i.i 替换对每张同样生效 = 批量也是高清。
 * 防重：同 presenter 一次批量进行中忽略再触发；同 note（fileid 集合指纹）会话内只批一次。
 * 触摸监听恒返回 false（纯观察，不消费宿主手势）。
 */
object XhsBatchSave {

    private const val JUDGE_PREFIX = "XhsHook·X5"

    private val engine = MountEngine("XhsHook·X5", "com.xingin.xhs")

    private const val CLS_GALLERY_CONTROLLER =
        "com.xingin.matrix.notedetail.imagecontent.imagegallery.AsyncImageGalleryController"

    /**
     * onAttach(Bundle) 声明在抽象基类 ag9.b（ag9/b.java:118 实证），AsyncImageGalleryController
     * 自身**未覆写**——挂在子类名上 declaredMethods 直连必落空（首装真机实证：installed 1/1 却
     * 全程零触发，DexKit 兜底解析到了别处）。故挂基类 + 回调内按子类类名过滤。
     */
    private const val CLS_GALLERY_BASE = "ag9.b"
    private const val METHOD_ON_ATTACH = "onAttach"

    /** 类型锚点（均未混淆，jadx 实证）。 */
    private const val CLS_IMAGE_BEAN = "com.xingin.entities.ImageBean"
    private const val CLS_SPI_LOADER = "com.xingin.spi.service.ServiceLoaderKtKt"
    private const val CLS_SPI_CALLBACK = "com.xingin.spi.service.base.SPICallback"
    private const val CLS_SAVE_PROXY = "android.xingin.com.spi.saveimage.ISaveImageProxy"

    private const val TAP_WINDOW_MS = 400L
    private const val TAP_COUNT = 3
    private const val SAVE_GAP_MS = 300L
    private const val NOTE_MEMO_LIMIT = 32

    /** 三击监听已挂的 view（防重复注入）。 */
    private val armedViews = WeakHashMap<View, Boolean>()

    /** 批量进行中的 presenter（identityHashCode；进行中再触发忽略）。 */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** 已批量过的 note 指纹（fileid 串 hash；会话内有界）。 */
    private val savedNotes = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 宿主 ClassLoader（install 期写入）：ISaveImageProxy / ServiceLoaderKtKt 是宿主类，
     * 模块 loader 看不见——resolveSaveProxy 的 Class.forName 必须经它（用模块 loader =
     * ClassNotFound = 批量永远「ISaveImageProxy 不可取」）。
     */
    @Volatile private var hostLoader: ClassLoader? = null

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        hostLoader = loader
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf("com.xingin.xhs"))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_GALLERY_BASE,
                methodName = METHOD_ON_ATTACH,
                paramCount = 1,
                returnType = "void",
                id = "xhs:batchSave.galleryController.onAttach",
                description = "X5 批量保存·基类 ag9.b.onAttach(Bundle) 后按子类过滤挂画廊三击（onAttach 返回时 view 必已绑 presenter）",
                // 锚点：paramTypes Bundle 精确签名 + 同类 FunctionReferenceImpl 调试串（混淆不改，
                // ag9/b.java:42/56 实证）；基类被多个 controller 子类共享，回调内类名过滤
                dex = DexHint(
                    paramTypes = listOf("android.os.Bundle"),
                    classStrings = listOf(
                        "handleNoteDetailActions",
                        "handleNoteDetailActionsAfterPresenterActive",
                    ),
                ),
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            chain.proceed()
            val controller = chain.thisObject ?: return@mount null
            // 基类共享：只武装图片画廊这一支子类，其余 controller attach 静默放行
            if (controller.javaClass.name != CLS_GALLERY_CONTROLLER) return@mount null
            val presenter = runCatching {
                controller.javaClass.getMethod("getPresenter").invoke(controller)
            }.getOrNull()
            val view = presenter?.let { findViewField(it) }
            if (presenter == null || view == null) {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX presenter/view 未绑定，本画廊未武装三击")
                return@mount null
            }
            if (armedViews.put(view, true) != true) {
                armTripleTap(view, presenter, module)
                engine.judge(module, task, JUDGE_PREFIX, "画廊三击已武装")
            }
            null   // void 方法：必须显式 null
        }

    /** presenter 超类链扫 View 类型字段（pe7.g 的字段名单字母随版本漂，按类型取值）。 */
    private fun findViewField(presenter: Any): View? = runCatching {
        var c: Class<*>? = presenter.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { f ->
                android.view.View::class.java.isAssignableFrom(f.type)
            }?.let { f ->
                f.isAccessible = true
                return f.get(presenter) as? View
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    /** 挂三击判定：自有 tap 计数（400ms 窗口）；不消费事件，尾部委托宿主原有触摸监听（防顶掉画廊自有逻辑）。 */
    private fun armTripleTap(view: View, presenter: Any, module: XposedModule) {
        var lastTapAt = 0L
        var taps = 0
        // prev 监听读取走反射（View.getOnTouchListener API 28+，minSdk 之下编译不过）
        val prev = runCatching {
            View::class.java.getMethod("getOnTouchListener").invoke(view) as? View.OnTouchListener
        }.getOrNull()
        view.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val now = android.os.SystemClock.uptimeMillis()
                taps = if (now - lastTapAt <= TAP_WINDOW_MS) taps + 1 else 1
                lastTapAt = now
                if (taps >= TAP_COUNT) {
                    taps = 0
                    try {
                        onTripleTap(v, presenter, module)
                    } catch (t: Throwable) {
                        module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 批量触发失败: $t")
                    }
                }
            }
            prev?.onTouch(v, event) ?: false
        }
    }

    private fun onTripleTap(view: View, presenter: Any, module: XposedModule) {
        val presenterKey = System.identityHashCode(presenter)
        if (presenterKey in inFlight) return
        val activity = view.context.unwrapActivity() ?: return
        val images = collectImages(presenter)
        if (images.isEmpty()) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 未取到 ImageBean 列表，本 note 放弃")
            return
        }
        val noteKey = images.joinToString("|") { it.fileid.orEmpty() }.hashCode()
        if (noteKey in savedNotes) return
        if (inFlight.add(presenterKey)) {
            savedNotes += noteKey
            if (savedNotes.size > NOTE_MEMO_LIMIT) savedNotes.clear()
            engine.judge(module, batchTask, JUDGE_PREFIX, "三击触发批量保存 ${images.size} 张")
            runBatch(activity, images, module)
        }
    }

    /** 供 judge 用的任务描述（engine.judge 需要 Task 引擎计数按 id 节流）。 */
    private val batchTask = MountEngine.SimpleTask(
        className = CLS_GALLERY_CONTROLLER,
        methodName = METHOD_ON_ATTACH,
        paramCount = 1,
        returnType = "void",
        id = "xhs:batchSave.galleryController.onAttach",
        description = "X5 批量保存·三击触发",
    )

    /**
     * 当前 note 的 ImageBean 列表（类型锚定，全部运行期实读）：
     * presenter（aj9.h2）字段扫 MultiTypeAdapter（drakeet 库未 shade）→ getItems() →
     * 条目内扫 ImageBean 类型字段（y wrapper 的 b 字段，jadx e3(y) 实读 yVar.b.x0()/A0()）。
     * 不走 controller.R→NoteFeed.N0() 路径：holder/NoteFeed 的取值方法为单字母混淆名，防漂。
     */
    private fun collectImages(presenter: Any): List<ImageEntry> {
        // presenter 超类链扫 MultiTypeAdapter 字段（drakeet 库未 shade）；aj9.h2 系 presenter 的
        // 字段可能落在父类，declaredFields 只扫自身类会漏（同 findImageBeanValue 的超类链写法）
        val adapter = runCatching {
            var c: Class<*>? = presenter.javaClass
            while (c != null) {
                c.declaredFields.firstOrNull { f ->
                    f.type.name == "com.drakeet.multitype.MultiTypeAdapter"
                }?.let { f ->
                    f.isAccessible = true
                    return@runCatching f.get(presenter)
                }
                c = c.superclass
            }
            null
        }.getOrNull() ?: return emptyList()
        val items = runCatching {
            adapter.javaClass.getMethod("getItems").invoke(adapter) as? List<*>
        }.getOrNull().orEmpty()
        val out = mutableListOf<ImageEntry>()
        for (item in items) {
            val bean = item?.let { it::class.java.findImageBeanValue(it) } ?: continue
            val fileid = runCatching { bean.javaClass.getMethod("K").invoke(bean) as? String }.getOrNull()
            val url = runCatching { bean.javaClass.getMethod("A0").invoke(bean) as? String }.getOrNull()
            val filterUrl = runCatching {
                (bean.javaClass.getMethod("R").invoke(bean))?.let { fm ->
                    fm.javaClass.getMethod("o").invoke(fm) as? String
                }
            }.getOrNull()
            if (!fileid.isNullOrEmpty() || !url.isNullOrEmpty()) {
                out.add(ImageEntry(fileid.orEmpty(), url.orEmpty(), filterUrl))
            }
        }
        return out
    }

    /** 条目（y/dra.b 等 wrapper）内扫 ImageBean 类型字段取值；无则 null。 */
    private fun Class<*>.findImageBeanValue(item: Any): Any? {
        var c: Class<*>? = this
        while (c != null) {
            c.declaredFields.firstOrNull { f -> f.type.name == CLS_IMAGE_BEAN }?.let { f ->
                f.isAccessible = true
                return f.get(item)
            }
            c = c.superclass
        }
        return null
    }

    private class ImageEntry(val fileid: String, val url: String, val filterUrl: String?)

    /** 逐张 300ms 间隔走原生 SPI 保存（disableWaterMark=true 与 X2a 同语义），Toast 汇总「已保存 N/M」。 */
    private fun runBatch(activity: Activity, images: List<ImageEntry>, module: XposedModule) {
        val proxy = resolveSaveProxy() ?: run {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX ISaveImageProxy 不可取，批量放弃")
            return
        }
        val saveMethod = proxy.javaClass.methods.firstOrNull {
            it.name == "save" && it.parameterCount == 8
        } ?: run {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX save(8参) 未找到，批量放弃")
            return
        }
        var success = 0
        var done = 0
        // 回调代理的 loader 必须是宿主的（activity.classLoader）：kotlin.jvm.functions.Function1
        // 若用模块侧同名类，宿主 invoke 时类型身份不符会 ClassCastException。
        val fn1Cls = Class.forName("kotlin.jvm.functions.Function1", true, activity.javaClass.getClassLoader())
        // success/失败回调分两个代理（宿主每张恰调其一），避免在 invoke 内区分参数
        val successCb = Proxy.newProxyInstance(activity.javaClass.getClassLoader(), arrayOf(fn1Cls)) { _, _, _ ->
            done++; success++; null
        }
        val errorCb = Proxy.newProxyInstance(activity.javaClass.getClassLoader(), arrayOf(fn1Cls)) { _, _, _ ->
            done++; null
        }
        saveMethod.isAccessible = true
        images.forEachIndexed { index, entry ->
            mainHandler.postDelayed({
                try {
                    saveMethod.invoke(
                        proxy, activity, entry.fileid, "", entry.url, entry.filterUrl.orEmpty(),
                        true, successCb, errorCb,
                    )
                } catch (t: Throwable) {
                    done++
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 第 ${index + 1} 张保存失败: ${t.message}")
                }
                if (done >= images.size) {
                    inFlight.clear()
                    runCatching {
                        android.widget.Toast.makeText(activity, "已保存 $success/${images.size}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 批量保存完成 $success/${images.size}")
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
