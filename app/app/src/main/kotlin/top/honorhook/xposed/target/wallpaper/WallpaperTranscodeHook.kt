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
 * mcs 落到 3.1Mbps 默认 ⇒ 转码产物模糊）。挂 mcs `VideoTranscoder#startTranscode(TranscodeConfig)`
 * （onPrepareDone → startTranscode(this.D)，该 config 即唯一生效入口），按 0.3 bit/pixel@fps 补足
 * （≈1080x1920@30 ≈ 18.6 Mbps，clamp 12–40M），不碰分辨率/裁剪（mVideoRect/outW/H 是用户所见即所得）。
 *
 * mcs 库类名不混淆（`com.hihonor.mcs.media.codec`，随宿主版本核对方法签名）。
 */
object WallpaperTranscodeHook : TargetHook {

    override val packageName = "com.hihonor.wallpapereditor"

    private const val TAG = MountEngine.TAG

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_WALLPAPER_TRANSCODE_QUALITY)) {
            logDisabled(module, FeatureCatalog.KEY_WALLPAPER_TRANSCODE_QUALITY, "WallpaperTranscodeHook")
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
                            val fpsField = configCls.getField("fps")
                            val fps = fpsField.getInt(cfg).coerceAtLeast(1)
                            val cur = configCls.getField("bitrate").getInt(cfg)
                            // 0.3bpp（≈18Mbps@1080x1920x30），clamp 12–40M。
                            // fps 不动（v2 曾 ×2 对抗"编码器 fps/2"，2026-10-18 ffprobe 实证推翻并回退：
                            // config 60 → 实编 60fps（无减半，directMode 时间戳直通），bitrate 请求
                            // 18.6M 实编 ~9.96M（疑似编码器 ~10M 封顶）⇒ 帧数翻倍稀释每帧码率，保 30fps
                            // 让预算集中。见 docs/reverse/wallpaper-editor.md §十一。
                            val want = (w.toLong() * h * fps * 3 / 10).toInt().coerceIn(12_000_000, 40_000_000)
                            if (w > 0 && h > 0 && cur < want) {
                                configCls.getField("bitrate").setInt(cfg, want)
                            }
                            module.log(Log.INFO, TAG, "W16 bitrate $cur -> $want, fps $fps (${w}x$h)")
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
