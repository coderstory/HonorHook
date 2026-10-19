package top.honorhook.xposed.target.xhs

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.RemoteConfig

/**
 * X3 高清保存（引擎 XhsHook·X3）：保存走原版无压缩图。
 *
 * 清晰度差距根源（doc §二）：`nd8.i.i`（9.49.1 为 qc8.i.i）拿显示 URL（`sns-webpic-*` 带 `!` 裁剪后缀）进 Fresco，
 * 存的是压缩位图。改写规则统一在 [XhsImageUrl]（权威源 XHS_Downloader_Android `NoteOutput.imageUrls`）。
 *
 * 挂载点 `nd8.i.i`（11 参 `(Activity, String×4, boolean, Function1×2, boolean×3)`，jadx 实读：
 * 9.50.0 `.buildtmp/xhs950-partial/sources/nd8/i.java:240`，与 9.49.1 qc8.i 方法级逐字对齐）：
 * args[3] = str3 = `Uri.parse(str3)` 进 Fresco 的解码源图 url（nd8/i.java:245）——before 改参即改解码源。
 * 混淆名，9.50.0 R8 换包 qc8.i→nd8.i（findings-x3 2026-10-19：旧 `Lqc8/i;` 全 24 个 dex 串池
 * 0 命中）。DexKit 串锚四串全在 classes6 dex 字符串池且同在 nd8.i 方法体内（:258/:277/:279/:190）。
 * 覆盖面：笔记图（SPI 路径 SaveImageProxyImpl.save → nd8.i.e→h→g→i，SaveImageProxyImpl 类未换包）+
 * 评论图（cv4/l 直调 nd8.i.h，h 是 i 的桥；旧 iu4/l→新 cv4/l 逐参同形）——一处 hook 双覆盖
 * （doc §三 X3）。边界：live photo（vivo/oppo 双文件 zip 分支）不走 i.i 单图解码，不覆盖。
 */
object XhsHdSave {

    private const val JUDGE_PREFIX = "XhsHook·X3"

    private val engine = MountEngine("XhsHook·X3", "com.xingin.xhs")

    /** 9.50.0 R8 换包 qc8.i→nd8.i（方法/字段名不变；nd8/i.java:240 jadx 实证，findings-x3 2026-10-19）。 */
    private const val CLS_ND8_I = "nd8.i"
    private const val METHOD_SAVE_IMAGE = "i"
    private const val PARAM_COUNT = 11

    /** args[3] 解码源 url 的参数下标（Uri.parse(str3) 处；9.49.1 qc8/i.java:265 → 9.50.0 nd8/i.java:245 不变）。 */
    private const val ARG_URL_INDEX = 3

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf("com.xingin.xhs"))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_ND8_I,
                methodName = METHOD_SAVE_IMAGE,
                paramCount = PARAM_COUNT,
                returnType = "void",
                id = "xhs:hdSave.qc8i.i",
                description = "X3 高清保存·nd8.i.i args[3] 图 url → token 重建原图直链（笔记图+评论图单一收口）",
                dex = DexHint(
                    classStrings = listOf(
                        "saveImageBitmapNull",
                        "getBitmapFromFEPlatform failed",
                        "fetch image failed",
                        "SaveImagesHelper",
                    ),
                ),
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // before 语义：替换 args[3] 后按新参数 proceed；void 方法回调恒返回 null
            val original = runCatching { chain.getArg(ARG_URL_INDEX) as? String }.getOrNull()
            // X3 专用 q/100 直链（X5 已改裸链优先；XhsImageUrl 无粘性降级表——2026-10-15 基准切换删除）
            val rebuilt = XhsImageUrl.toOriginal(original)
            if (rebuilt != null && rebuilt != original) {
                engine.judge(module, task, JUDGE_PREFIX, "解码源已换原图直链")
                val args = Array(PARAM_COUNT) { i ->
                    if (i == ARG_URL_INDEX) rebuilt else chain.getArg(i)
                }
                // before 改参轨：proceed(args) = 以新参调用原方法并返回其结果（void 忽略）。
                // 勿用 proceedWith(result, args)——那是「跳过原方法、以 result 充当返回值」的
                // 结果改写轨（HonorAdsHook 短路语义），用于改参会令保存执行链整体不执行。
                chain.proceed(args)
            } else {
                // 不适用 / 改写结果与原值相同（已是原图直链）：原样放行
                chain.proceed()
            }
            null
        }
}
