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
 * W17 桌面循环播放：引擎 `n4.y`（PlayerStateListener）#onPlayerCompleted 播完即 release + 复位
 * （播一次停）。桌面态（keyguard 未锁）改为吞掉回调、`seekTo(0)`——引擎 seek 完成回调里自动
 * `start()`（onPlayerSeekCompleted 实证）⇒ 无缝循环。锁屏态保持原生（播完释放，省电）。
 *
 * 定位：DexKit 锚点 `onPlayerCompleted goingToSleep`；多 Player 变体各有 listener 类，按
 * **字段类型 = n4.z**（直板机 player，getCurJson 锚点定位）过滤。
 */
object WallpaperLoopHook : TargetHook {

    override val packageName = "com.hihonor.livewallpaper.paradise"

    private const val TAG = "HonorHook"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_WALLPAPER_VIDEO_ENHANCE)) {
            logDisabled(module, FeatureCatalog.KEY_WALLPAPER_VIDEO_ENHANCE, "WallpaperLoopHook")
            return
        }
        val loader = param.defaultClassLoader
        val apk = param.applicationInfo.sourceDir
        runCatching {
            // 先定位直板机 player 类 n4.z（getCurJson 日志串唯一直板变体）
            val zCls = DexKitHelper.withBridge(apk) { bridge ->
                bridge.findMethod(
                    FindMethod.create().searchPackages("n4").matcher(
                        MethodMatcher.create().usingStrings("getCurJson keyguardLocked = %b, curJson = %s"),
                    ),
                ).asSequence()
                    .mapNotNull { hit -> runCatching { hit.getMethodInstance(loader).declaringClass }.getOrNull() }
                    .firstOrNull()
            } ?: run {
                module.log(Log.WARN, TAG, "W17 未挂载：n4.z 未定位（锚点失效）")
                return
            }
            val completed = DexKitHelper.withBridge(apk) { bridge ->
                bridge.findMethod(
                    FindMethod.create().searchPackages("n4").matcher(
                        MethodMatcher.create()
                            .usingStrings("onPlayerCompleted goingToSleep = %b, which = %d ")
                            .paramTypes(),
                    ),
                ).asSequence()
                    .mapNotNull { hit -> runCatching { hit.getMethodInstance(loader) }.getOrNull() }
                    .firstOrNull { it.declaringClass.declaredFields.any { f -> f.type == zCls } }
            } ?: run {
                module.log(Log.WARN, TAG, "W17 未挂载：onPlayerCompleted 未定位")
                return
            }
            module.hook(completed)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("wallpaper:w17.loop")
                .intercept(
                    XposedInterface.Hooker { chain ->
                        val listener = chain.thisObject
                        val z = listener?.javaClass?.declaredFields
                            ?.firstOrNull { it.type == zCls }?.apply { isAccessible = true }?.get(listener)
                        if (z != null && !isKeyguardLockedNow()) {
                            // 桌面态：吞掉 release，seekTo(0) 触发引擎 seekCompleted → start() = 循环
                            val controller = findVisionController(z)
                            val seek = controller?.javaClass?.methods?.firstOrNull {
                                it.name == "seekTo" && it.parameterCount == 1 && it.parameterTypes[0] == Long::class.java
                            }
                            if (controller != null && seek != null) {
                                seek.invoke(controller, 0L)
                                module.log(Log.INFO, TAG, "W17 桌面循环 seekTo(0)")
                                return@Hooker null
                            }
                        }
                        chain.proceed()
                    },
                )
            module.log(Log.INFO, TAG, "W17 已挂载: ${completed.declaringClass.name}.onPlayerCompleted")
        }.onFailure { t ->
            module.log(Log.WARN, TAG, "W17 挂载失败: ${t.message}", t)
        }
    }

    /**
     * keyguard 状态直查系统服务（引擎 z.f3496k 初始化同样来自 KeyguardManager 包装 e4.c.q，语义等价）。
     * 不反射 z 的 AtomicBoolean 字段：z 同时持有 keyguardLocked 与 goingToSleep 两个同类型字段，
     * 按类型定位会拿错。判不了按锁屏处理（保守：保持原生停止行为）。
     */
    private fun isKeyguardLockedNow(): Boolean = runCatching {
        val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication")
            .invoke(null) as? android.content.Context
        val km = ctx?.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        km?.isKeyguardLocked ?: true
    }.getOrDefault(true)

    /** z.A（VisionController）——沿父类链按类型名定位。 */
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
}
