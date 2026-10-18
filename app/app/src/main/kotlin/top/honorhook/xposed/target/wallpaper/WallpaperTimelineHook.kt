package top.honorhook.xposed.target.wallpaper

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 壁纸视频增强（编辑器进程 `com.hihonor.wallpapereditor`，基线 153.0.0.423）。
 *
 * W18 去掉时间轴 10s 选中上限。宿主链条（jadx 实证，详见 docs/reverse/wallpaper-editor.md §十一）：
 * 触摸层 `y7.g0` 先把期望位置写入字段再调 `SingleCutProgressView#setLeftCropPosition(long)` /
 * `#setRightCropPosition(long)`，两方法内的 10000/1000 硬编码把选中窗口 clamp 到 [1s, 10s]
 * （超窗拖动会把对侧柄推走）；`CustomVideoPlayer` 转码时 `startTime/endTime = f7398y/f7399z`
 * 直读同一对字段，无二次校验 ⇒ 松开 UI clamp 即松开产品限制。
 *
 * 手法：hook 两个 set 方法——proceed 前存下期望的左右柄值，原生逻辑跑完后写回
 * （clamp 到 [0, 总时长]）并调 p()/r() 重画两柄。原生 clamp 只会改这两个字段，写回即全废。
 * 时间轴总长本身不钳制（d8.e.o()：total = CustomVideoPlayer.B/1000 = d8.m.i 实际时长），
 * 初始选中 10s 由 CustomVideoPlayer.I 的 min(时长,10s) 给出——拖右柄即可扩展到全长。
 *
 * ⚠ 字段名陷阱（首轮即踩）：jadx 树把短名同型字段重命名为 fNNNNn 合成名（f7262j 等），
 * **非 dex 真名**——运行时 getDeclaredField 必失败（modlog `No field f7262j`）。真名以
 * apktool smali 为准：`j`=左柄 ms、`k`=右柄 ms、`f`=总时长 ms；重画 `p()/r()V` 未被
 * jadx 改名。类名/方法名（setLeftCropPosition 等）未混淆、跨类调用可证。
 */
object WallpaperTimelineHook : TargetHook {

    override val packageName = "com.hihonor.wallpapereditor"

    private const val TAG = "HonorHook"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_WALLPAPER_TIMELINE_UNLIMITED)) {
            logDisabled(module, FeatureCatalog.KEY_WALLPAPER_TIMELINE_UNLIMITED, "WallpaperTimelineHook")
            return
        }
        runCatching {
            val cls = param.defaultClassLoader
                .loadClass("com.hihonor.gallery.wallpaper.ui.SingleCutProgressView")
            val fLeft = cls.getDeclaredField("j").apply { isAccessible = true }    // 左柄 ms
            val fRight = cls.getDeclaredField("k").apply { isAccessible = true }   // 右柄 ms
            val fTotal = cls.getDeclaredField("f").apply { isAccessible = true }   // 总时长 ms
            val redrawLeft = cls.getDeclaredMethod("p").apply { isAccessible = true }
            val redrawRight = cls.getDeclaredMethod("r").apply { isAccessible = true }
            listOf("setLeftCropPosition", "setRightCropPosition").forEach { name ->
                val m = cls.getDeclaredMethod(name, Long::class.javaPrimitiveType)
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallpaper:w18.$name")
                    .intercept(
                        XposedInterface.Hooker { chain ->
                            val v = chain.thisObject ?: return@Hooker chain.proceed()
                            // 调用方已把期望值写进字段；原生 clamp 全部落在这对字段上。
                            val wantL = fLeft.getLong(v)
                            val wantR = fRight.getLong(v)
                            chain.proceed()
                            runCatching {
                                val total = fTotal.getLong(v)
                                val l = wantL.coerceIn(0L, total)
                                val r = wantR.coerceIn(l, total)
                                fLeft.setLong(v, l)
                                fRight.setLong(v, r)
                                redrawLeft.invoke(v)
                                redrawRight.invoke(v)
                                if (r - l > 10_000L) {
                                    // 判据行：仅真的推翻了 10s 上限时打（拖动全程刷屏无意义）
                                    module.log(Log.INFO, TAG, "W18 已放行长选 ${l}ms..${r}ms（${r - l}ms）")
                                }
                            }.onFailure {
                                module.log(Log.WARN, TAG, "W18 写回失败: ${it.message}")
                            }
                        },
                    )
                module.log(Log.INFO, TAG, "W18 已挂载: SingleCutProgressView.$name")
            }
        }.onFailure {
            module.log(Log.WARN, TAG, "W18 挂载失败: ${it.message}", it)
        }
    }
}
