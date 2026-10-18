package top.honorhook.xposed.target.wallpaper

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.DexKitHelper
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 壁纸视频增强（编辑器进程 `com.hihonor.wallpapereditor`，基线 153.0.0.423）。
 *
 * W16 转码码率补足：宿主 `CustomVideoPlayer.L()` 组装 `TranscodeConfig` 时**不设 bitrate**（=0，
 * mcs 落到低默认码率 ⇒ 转码产物模糊）。挂 mcs `VideoTranscoder#startTranscode(TranscodeConfig)`
 * （onPrepareDone → startTranscode(this.D)，该 config 即唯一生效入口），按 0.2 bit/pixel@fps 补足
 * （约 1080x2400@30 ≈ 15.5 Mbps），不碰分辨率/裁剪（mVideoRect/outW/H 是用户所见即所得）。
 *
 * mcs 库类名不混淆（`com.hihonor.mcs.media.codec`，随宿主版本核对方法签名）。
 */
object WallpaperTranscodeHook : TargetHook {

    override val packageName = "com.hihonor.wallpapereditor"

    private const val TAG = MountEngine.TAG

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_WALLPAPER_VIDEO_ENHANCE)) {
            logDisabled(module, FeatureCatalog.KEY_WALLPAPER_VIDEO_ENHANCE, "WallpaperTranscodeHook")
            return
        }
        val loader = param.defaultClassLoader
        runCatching {
            val transcoder = loader.loadClass("com.hihonor.mcs.media.codec.frameextractor.VideoTranscoder")
            val configCls = loader.loadClass("com.hihonor.mcs.media.codec.frameextractor.TranscodeConfig")
            val start = transcoder.declaredMethods.firstOrNull { m ->
                m.name == "startTranscode" && m.parameterCount == 1 && m.parameterTypes[0] == configCls
            } ?: run {
                module.log(Log.WARN, TAG, "W16 未挂载：startTranscode(TranscodeConfig) 不存在")
                return
            }
            module.hook(start)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("wallpaper:w16.bitrate")
                .intercept(
                    XposedInterface.Hooker { chain ->
                        val cfg = chain.getArg(0) ?: return@Hooker chain.proceed()
                        runCatching {
                            val w = configCls.getField("outWidth").getInt(cfg)
                            val h = configCls.getField("outHeight").getInt(cfg)
                            val fps = configCls.getField("fps").getInt(cfg).coerceAtLeast(1)
                            val cur = configCls.getField("bitrate").getInt(cfg)
                            val want = (w.toLong() * h * fps / 5).toInt().coerceIn(8_000_000, 40_000_000)
                            if (w > 0 && h > 0 && cur < want) {
                                configCls.getField("bitrate").setInt(cfg, want)
                                module.log(Log.INFO, TAG, "W16 bitrate $cur -> $want (${w}x$h@$fps)")
                            }
                        }
                        chain.proceed()
                    },
                )
            module.log(Log.INFO, TAG, "W16 已挂载: VideoTranscoder.startTranscode")
        }.onFailure { t ->
            module.log(Log.WARN, TAG, "W16 挂载失败: ${t.message}", t)
        }
    }
}
