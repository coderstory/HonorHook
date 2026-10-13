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
 *  5. 默认（AUTO）格式 → `https://sns-img-bd.xhscdn.com/{token}`；显式格式 →
 *     `https://ci.xiaohongshu.com/{token}?imageView2/format/{fmt}`（本项目只用 AUTO，未启用显式分支）。
 *
 * 返回 null = 不适用（非 http / 视频 URL / 非已知 CDN / token 空），调用方保持原值。
 * 视频 URL 旁路（`sns-video` / `/video/` / `.mp4`）绝不参与图片改写。
 */
object XhsImageUrl {

    private val SEG_32HEX = Regex("[a-fA-F0-9]{32}")
    private const val DEFAULT_CDN = "sns-img-bd.xhscdn.com"

    /** 已改写 url 的 LRU 记忆（图片解码热路径，省重复串操作；accessOrder 容量 256）。 */
    private val memo = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 256
    }

    /** 原图直链改写；不适用返回 null。 */
    fun toOriginal(raw: String?): String? {
        if (raw == null || !raw.startsWith("http")) return null
        // 视频链旁路：绝不参与图片改写
        if (raw.contains("sns-video") || raw.contains("/video/") || raw.contains(".mp4")) return null
        synchronized(memo) { memo[raw] }?.let { return it }
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.endsWith(".xhscdn.com") && !host.endsWith(".xiaohongshu.com")) return null
        val parts = uri.path.orEmpty().trimStart('/').split('/')
        val token = (if (parts.size > 2 && SEG_32HEX.matches(parts[1])) parts.drop(2) else parts)
            .joinToString("/").substringBefore('!')
        if (token.isBlank()) return null
        val rebuilt = "https://$DEFAULT_CDN/$token"
        synchronized(memo) { memo[raw] = rebuilt }
        return rebuilt
    }
}
