package top.honorhook.xposed.target.xhs

import android.net.Uri
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
 * 清晰度差距根源（doc §二）：`qc8.i.i` 拿显示 URL（`sns-webpic-*` 带 `!` 裁剪后缀）进 Fresco，
 * 存的是压缩位图。XHS-Downloader 实证（source/application/image.py:54）：
 * `token = "/".join(url.split("/")[5:]).split("!")[0]` → `https://sns-img-bd.xhscdn.com/{token}` 原图直链。
 *
 * 挂载点 `qc8.i.i`（11 参 `(Activity, String×4, boolean, Function1×2, boolean×3)`，jadx 实读）：
 * args[3] = str3 = `Uri.parse(args[3])` 进 Fresco 的解码源图 url——before 改参即改解码源。
 * 混淆名，DexKit 串锚四串全在 classes6 dex 字符串池（2026-10-13 grep 实证）。
 * 覆盖面：笔记图（SPI 路径 SaveImageProxyImpl.save → qc8.i.e→h→g→i）+ 评论图（iu4/l 直调
 * qc8.i.h，h 是 i 的桥）——一处 hook 双覆盖（doc §三 X3）。
 * 边界：live photo（vivo/oppo 双文件 zip 分支）不走 i.i 单图解码，不覆盖。
 */
object XhsHdSave {

    private const val JUDGE_PREFIX = "XhsHook·X3"

    private val engine = MountEngine("XhsHook·X3", "com.xingin.xhs")

    private const val CLS_QC8_I = "qc8.i"
    private const val METHOD_SAVE_IMAGE = "i"
    private const val PARAM_COUNT = 11

    /** args[3] 解码源 url 的参数下标（Uri.parse(str3) 处，jadx qc8/i.java:265）。 */
    private const val ARG_URL_INDEX = 3

    /** 原图直链 CDN host（XHS-Downloader __generate_auto_link 实证）。 */
    private const val CDN_HOST = "sns-img-bd.xhscdn.com"

    /** 触发重建的 host 白名单（doc 规则 1：其它 CDN 一律原样）。 */
    private const val HOST_XHSCDN = "xhscdn.com"
    private const val HOST_XHS = "xiaohongshu.com"

    /** 已替换 url 的 LRU 记忆（doc 规则 4：省重复串操作；accessOrder 容量 256）。 */
    private val replacedMemo = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 256
    }

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf("com.xingin.xhs"))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_QC8_I,
                methodName = METHOD_SAVE_IMAGE,
                paramCount = PARAM_COUNT,
                returnType = "void",
                id = "xhs:hdSave.qc8i.i",
                description = "X3 高清保存·qc8.i.i args[3] 图 url → token 重建原图直链（笔记图+评论图单一收口）",
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
            val rebuilt = if (original != null && original.startsWith("http")) rebuildToOriginal(original) else null
            if (rebuilt != null && rebuilt != original) {
                engine.judge(module, task, JUDGE_PREFIX, "解码源已换原图直链")
            }
            if (rebuilt == null) {
                chain.proceed()
            } else {
                val args = Array(PARAM_COUNT) { i ->
                    if (i == ARG_URL_INDEX) rebuilt else chain.getArg(i)
                }
                // before 改参轨：proceed(args) = 以新参调用原方法并返回其结果（void 忽略）。
                // 勿用 proceedWith(result, args)——那是「跳过原方法、以 result 充当返回值」的
                // 结果改写轨（HonorAdsHook 短路语义），用于改参会令保存执行链整体不执行。
                chain.proceed(args)
            }
            null
        }

    /**
     * 显示 URL → 原图直链。不命中返回 null（原样）。
     * 规则（doc §三 X3）：
     *   1. 非 http/https 或 host 不含 xhscdn.com/xiaohongshu.com → 原样
     *   2. host 已是 sns-img-bd.xhscdn.com 且 path 无 `!` → 原样（防重入）
     *   3. token = path 最后一个分段截到第一个 `!` → `https://sns-img-bd.xhscdn.com/{token}`
     *      （偏差原因见 doc §三 X3 规则 3：XHS-Downloader 的 split("/")[5:] 依赖 Web 端深路径，
     *      客户端浅路径 URL 会取空令本功能静默 no-op；改取末段后深路径与之等价、浅路径才能命中）
     */
    private fun rebuildToOriginal(url: String): String? {
        synchronized(replacedMemo) { replacedMemo[url] }?.let { return it }
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (HOST_XHSCDN !in host && HOST_XHS !in host) return null
        if (host == CDN_HOST && uri.path?.contains('!') != true) return null
        val path = uri.path
        if (path.isNullOrEmpty()) return null
        val token = path.substringAfterLast('/').substringBefore('!')
        if (token.isEmpty()) return null
        val rebuilt = "https://$CDN_HOST/$token"
        synchronized(replacedMemo) { replacedMemo[url] = rebuilt }
        return rebuilt
    }
}
