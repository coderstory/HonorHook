package top.honorhook.xposed.target.xhs

import java.net.URI

/**
 * 小红书图片直链改写——**唯一权威规则**（X3 高清保存 / X5 批量直下共用）。
 *
 * 逐条移植 XHS-Downloader `NoteOutput.imageUrls`（reference/xhs/XHS_Downloader_Android
 * app/src/main/java/com/neoruaa/xhsdn/domain/download/NoteOutput.kt:34-45）：
 *  1. 取 `URI.path`（**不含 query**）——imagefeed 图 URL 形如 `host/{token}?imageView2/2/w/576/…`，
 *     压缩命令段带斜杠但落在 query 里；按整串 `split('/')` 才会把 token drop 掉（2026-10-14
 *     真机 0/4 失败实证）。走 path 天然规避。★勿照抄旧的整串 split 写法。
 *  2. `trimStart('/')` 后按 `/` 切段；若 `size > 2 && segments[1]` 匹配 `[a-fA-F0-9]{32}`
 *     （`<prefix>/<fileid32hex>/…` 形态）则丢前 2 段。
 *  3. `join("/")` 后在首个 `!` 处截断 = token。
 *  4. host 必须以 `.xhscdn.com` 或 `.xiaohongshu.com` 结尾（`endsWith`；旧 `contains("xhscdn.com")`
 *     单判据会漏 `ci.xiaohongshu.com`）。
 *  5. 默认（AUTO）格式 → `https://sns-img-bd.xhscdn.com/{token}` + [QUALITY_QUERY]（全尺寸最高画质
 *     直链，见下「为什么必须补回」）；显式格式 → `https://ci.xiaohongshu.com/{token}?imageView2/format/{fmt}`
 *     （本项目只用 AUTO，未启用显式分支）。
 *
 * 返回 null = 不适用（非 http / 视频 URL / 非已知 CDN / token 空），调用方保持原值。
 * 视频 URL 旁路（`sns-video` / `/video/` / `.mp4`）绝不参与图片改写。
 *
 * **为什么必须补回 `?imageView2/2/format/jpg/q/100`**（2026-10-14 真机 curl 实测）：
 * 只保 token、剥光 query 时 CDN 回的是**同尺寸但高压缩重编码**，画质损失大。同一张 4284x5712 图：
 *  - 剥光参数（旧实现）                            1,727,058 B
 *  - `?imageView2/2/w/4284/format/jpg/q/100`      11,114,503 B
 *  - 另一 token 剥光参数                           4,917,390 B
 *  - 另一 token `?imageView2/2/format/jpg/q/100`  12,014,146 B
 *  - 客户端自存同张                                10,186,338 B
 * 两次实测口径一致：**`?imageView2/2/format/jpg/q/100`（连 w 都不用给）返回全尺寸最高画质**；
 * 加 `w/<声明宽度>` 结果相同。故 AUTO 分支恒追加该 query（[QUALITY_QUERY]），不是可选优化。
 */
object XhsImageUrl {

    private val SEG_32HEX = Regex("[a-fA-F0-9]{32}")
    private const val DEFAULT_CDN = "sns-img-bd.xhscdn.com"

    /**
     * 全尺寸最高画质后缀——**必须补回**：只保 token 会招来同尺寸高压缩重编码（字节对比见 object
     * KDoc）。`format/jpg` 恒为 JPEG ⇒ 调用方按 jpg 处理扩展名/MIME 仍正确。
     */
    private const val QUALITY_QUERY = "?imageView2/2/format/jpg/q/100"

    /** 已改写 url 的 LRU 记忆（图片解码热路径，省重复串操作；accessOrder 容量 256）。 */
    private val memo = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 256
    }

    /** 原图直链改写（token + 最高画质 query）；不适用返回 null。 */
    fun toOriginal(raw: String?): String? {
        if (raw == null) return null
        synchronized(memo) { memo[raw] }?.let { return it }
        val token = tokenOf(raw) ?: return null
        val rebuilt = "https://$DEFAULT_CDN/$token$QUALITY_QUERY"
        synchronized(memo) { memo[raw] = rebuilt }
        return rebuilt
    }

    /**
     * 裸链改写（token，不带 query）——供下载侧「失败回落」/排障；不适用返回 null。
     * 与 [toOriginal] 共用同一套 token 规则。
     */
    fun toBare(raw: String?): String? {
        if (raw == null) return null
        val token = tokenOf(raw) ?: return null
        return "https://$DEFAULT_CDN/$token"
    }

    /** token 提取（[toOriginal] / [toBare] 共用）；不适用返回 null。 */
    private fun tokenOf(raw: String): String? {
        if (!raw.startsWith("http")) return null
        // 视频链旁路：绝不参与图片改写
        if (raw.contains("sns-video") || raw.contains("/video/") || raw.contains(".mp4")) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.endsWith(".xhscdn.com") && !host.endsWith(".xiaohongshu.com")) return null
        val parts = uri.path.orEmpty().trimStart('/').split('/')
        val token = (if (parts.size > 2 && SEG_32HEX.matches(parts[1])) parts.drop(2) else parts)
            .joinToString("/").substringBefore('!')
        return token.takeIf { it.isNotBlank() }
    }
}
