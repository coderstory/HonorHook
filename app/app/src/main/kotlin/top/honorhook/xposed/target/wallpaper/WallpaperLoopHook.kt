package top.honorhook.xposed.target.wallpaper

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.DexKitHelper
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 壁纸视频增强（动态壁纸引擎进程 `com.hihonor.livewallpaper.paradise`，基线 11.0.0.252）。
 *
 * W17 桌面循环播放（v6）。引擎链（静态 + 真机实证，详见 docs/reverse/wallpaper-editor.md §十）：
 * `l3.k#onPlaybackStateChanged(7=STATE_COMPLETED)` → `PlayerStateListener.onPlayerCompleted()`
 * （实现 = n4.i/f/v/y 四个变体之一，锚点串重复、运行时走哪个不确定）→ 回调体内 release = 播一次停。
 *
 * 手法：四个变体的 onPlayerCompleted **全部挂**，桌面态吞回调（release 不发生）+ 反射
 * listener → 宿主 player → `VisionController`（l3.l）→ `seekTo(0)`（int 参数，传 Long 会被
 * IllegalArgumentException 静默吞）——引擎状态 8（SEEK_COMPLETED）→ onPlayerSeekCompleted
 * → `A.start()` 续播 = 循环。锁屏态放行原生（播完释放省电）。
 * 另有 5s 轮询兜底（player 存在、未 release、position 在尾帧 → seekTo(0)）。
 */
object WallpaperLoopHook : TargetHook {

    override val packageName = "com.hihonor.livewallpaper.paradise"

    private const val TAG = "HonorHook"

    /** 轮询兜底用：捕获 VisionControllerImpl（l3.l）实例——经其公开方法反射判态。 */
    @Volatile
    private var controllerRef: Any? = null

    /** W19 去抖：三变体（z/w/c0）对同一事件各发一次 F()，同毫秒交替 start/pause——
     *  300ms 后到覆盖 + 同值跳过，bust 收敛到最后一个可见性值。主线程 Handler 串行执行。
     *  lazy：Handler 不能在类加载期建（JVM 单测 Looper not-mocked，TargetRegistry 注册链会炸），
     *  首次 F() 回调（主线程）才初始化。 */
    private val visibilityHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var pendingVisible: Boolean? = null
    private var appliedVisible: Boolean? = null

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_WALLPAPER_LOOP)) {
            logDisabled(module, FeatureCatalog.KEY_WALLPAPER_LOOP, "WallpaperLoopHook")
            return
        }
        installCompletedSwap(module, param)
        installVisibilityGuard(module, param)
        startPollingLoop(module)
    }

    /**
     * W19 隐藏暂停（2026-10-18）：宿主 `n4.z/n4.w#F(boolean)`（= onVisibilityChanged 实现）
     * 只处理 firstApply，**无视可见性**——app 在前台时桌面视频照播（真机 21:14–21:15 实证，
     * W17 循环让隐藏期解码无限持续）。手法：同款日志串锚点全命中挂 F，proceed 保原生
     * firstApply 路径，再按可见性补 pause/start——VisionController（l3.l，pluginsdk 接口）
     * 自带 start/pause。锁屏态不碰（keyguard 流程保持原生，与 W17 同原则）。
     */
    private fun installVisibilityGuard(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apk = param.applicationInfo.sourceDir
        // 锚点串在 n4.z（手机）/n4.w（Pad）两个 player 变体重复 ⇒ 全挂，活一个即生效
        val visibilityAll = DexKitHelper.withBridge(apk) { bridge ->
            bridge.findMethod(
                FindMethod.create().searchPackages("n4").matcher(
                    MethodMatcher.create()
                        .usingStrings("onVisibilityChanged: v = %b, firstApply = %b")
                        .paramTypes("boolean"),
                ),
            ).asSequence()
                .mapNotNull { hit -> runCatching { hit.getMethodInstance(loader) }.getOrNull() }
                .toList()
        }.orEmpty()
        if (visibilityAll.isEmpty()) {
            module.log(Log.WARN, TAG, "W19 visibility 挂点未定位（锚点失效，随引擎版本适配）")
            return
        }
        visibilityAll.forEach { vis ->
            runCatching {
                module.hook(vis)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallpaper:w19.visibility")
                    .intercept(
                        XposedInterface.Hooker { chain ->
                            chain.proceed() // 原生 firstApply 分支先走
                            val visible = chain.getArg(0) as? Boolean ?: return@Hooker null
                            if (!isDesktopNow()) return@Hooker null // 锁屏流程不碰
                            val player = chain.thisObject ?: return@Hooker null
                            val controller = findVisionController(player) ?: return@Hooker null
                            if (visible == pendingVisible || visible == appliedVisible) return@Hooker null
                            pendingVisible = visible
                            visibilityHandler.removeCallbacksAndMessages(null)
                            visibilityHandler.postDelayed({
                                val v = pendingVisible ?: return@postDelayed
                                pendingVisible = null
                                appliedVisible = v
                                val name = controller.javaClass.methods.firstOrNull {
                                    (if (v) it.name == "start" else it.name == "pause") &&
                                        it.parameterCount == 0
                                } ?: return@postDelayed
                                runCatching {
                                    name.invoke(controller)
                                    module.log(Log.INFO, TAG, "W19 ${if (v) "回桌面续播" else "隐藏暂停"}")
                                }.onFailure {
                                    module.log(Log.WARN, TAG, "W19 ${if (v) "start" else "pause"} 异常: ${it.message}")
                                }
                            }, 300L)
                            null
                        },
                    )
                module.log(Log.INFO, TAG, "W19 已挂载: ${vis.declaringClass.name}.F(boolean)")
            }
        }
    }

    private fun installCompletedSwap(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apk = param.applicationInfo.sourceDir
        // 锚点串在 n4.i/f/v/y 四个 listener 变体中重复（真机两轮命中类不同实证）⇒ 全挂。
        val completedAll = DexKitHelper.withBridge(apk) { bridge ->
            bridge.findMethod(
                FindMethod.create().searchPackages("n4").matcher(
                    MethodMatcher.create()
                        .usingStrings("onPlayerCompleted goingToSleep = %b, which = %d ")
                        .paramTypes(),
                ),
            ).asSequence()
                .mapNotNull { hit -> runCatching { hit.getMethodInstance(loader) }.getOrNull() }
                .toList()
        }.orEmpty()
        if (completedAll.isEmpty()) {
            module.log(Log.WARN, TAG, "W17 completed 挂点未定位（锚点失效，随引擎版本适配）")
            return
        }
        completedAll.forEach { completed ->
            runCatching {
                module.hook(completed)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallpaper:w17.completed")
                    .intercept(
                        XposedInterface.Hooker { chain ->
                            if (isDesktopNow()) {
                                // controller 链：listener 字段 → 宿主 player → 其 VisionController 字段。
                                val listener = chain.thisObject
                                val controller = listener?.javaClass?.declaredFields
                                    ?.asSequence()
                                    ?.mapNotNull { f ->
                                        runCatching { f.isAccessible = true; f.get(listener) }.getOrNull()
                                    }
                                    ?.mapNotNull { host -> findVisionController(host) }
                                    ?.firstOrNull()
                                // l3.l.seekTo(int)：invoke 必须传 Int（Long 会抛 IAE 被吞）
                                val seek = controller?.javaClass?.methods?.firstOrNull {
                                    it.name == "seekTo" && it.parameterCount == 1 &&
                                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                                }
                                if (controller != null && seek != null) {
                                    controllerRef = controller
                                    runCatching {
                                        seek.invoke(controller, 0)
                                        module.log(Log.INFO, TAG, "W17 seekTo(0) 已执行")
                                    }.onFailure {
                                        module.log(Log.WARN, TAG, "W17 seekTo 异常: ${it.message}")
                                    }
                                    return@Hooker null
                                }
                                module.log(
                                    Log.WARN, TAG,
                                    "W17 controller/seek 未就绪（controller=${controller != null}），放行原生",
                                )
                            }
                            chain.proceed()
                        },
                    )
                module.log(Log.INFO, TAG, "W17 已挂载: ${completed.declaringClass.name}.onPlayerCompleted")
            }
        }
    }

    /** 轮询兜底：completed 回调万一不来（变体再增/锚点漂移），5s 扫一次尾帧态并 seek。 */
    private fun startPollingLoop(module: XposedModule) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val task = object : Runnable {
            override fun run() {
                runCatching {
                    val c = controllerRef ?: return@runCatching
                    val cls = c.javaClass
                    fun call0(name: String): Long = cls.declaredMethods.firstOrNull {
                        it.name == name && it.parameterCount == 0
                    }?.let { runCatching { it.invoke(c) as? Long }.getOrNull() } ?: -1L
                    fun callB(name: String): Boolean = cls.declaredMethods.firstOrNull {
                        it.name == name && it.parameterCount == 0
                    }?.let { runCatching { it.invoke(c) as? Boolean }.getOrNull() } ?: true
                    val released = callB("isPlayerReleased")
                    val playing = callB("isPlaying")
                    val pos = call0("getCurrentPosition")
                    val dur = call0("getVideoDuration")
                    if (!released && !playing && dur > 0 && pos >= dur - 500 && isDesktopNow()) {
                        cls.declaredMethods.firstOrNull {
                            it.name == "seekTo" && it.parameterCount == 1 &&
                                it.parameterTypes[0] == Int::class.javaPrimitiveType
                        }?.let { runCatching { it.invoke(c, 0) } }
                        module.log(Log.INFO, TAG, "W17 轮询循环 seekTo(0)（pos=$pos dur=$dur）")
                    }
                }
                handler.postDelayed(this, 5_000L)
            }
        }
        handler.postDelayed(task, 5_000L)
    }

    /** player（任意变体）的 VisionController 字段（l3.l 实例）——沿父类链按类型名定位。 */
    private fun findVisionController(player: Any): Any? {
        var cls: Class<*>? = player.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { f ->
                if (f.type.name.contains("VisionController")) {
                    runCatching {
                        f.isAccessible = true
                        return f.get(player)
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    /** 桌面态 = keyguard 未锁（引擎初始化同源 KeyguardManager；判不了按锁屏保守放行）。 */
    private fun isDesktopNow(): Boolean = runCatching {
        val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication")
            .invoke(null) as? android.content.Context
        val km = ctx?.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        km?.isKeyguardLocked == false
    }.getOrDefault(false)
}
