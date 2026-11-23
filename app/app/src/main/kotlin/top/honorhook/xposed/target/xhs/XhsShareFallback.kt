package top.honorhook.xposed.target.xhs

import com.neoruaa.xhsdn.core.model.MediaCandidate
import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分享页 `__INITIAL_STATE__` 的兜底解析（2026-11-23）。
 *
 * 为什么需要它（真机 + 抓包实证）：
 * - 上游 `XhsNoteParser` 只认两种形态：「桌面版 `note.noteDetailMap[noteId].note`」与
 *   「`noteData.data.noteData` **且该对象自带 noteId**」。
 * - 但小红书对一部分笔记下发的是**移动分享壳**：数据在 `noteData.data.noteData` 里，
 *   图文直链齐全（`imageList[].url` / `infoList[].url`），**唯独没有 `noteId` 字段**。
 *   上游的 noteId 匹配因此失败，整个解析返回空 → 上层抛 `RequiresWebView`。
 *   真机 22:22 那轮 76 篇里 24 篇（约三成）是这种壳。
 * - 判据：`noteId` 字段缺失，但 `imageList` / `video` 存在（本次实测 `汉服设计` 那篇：
 *   `noteDetailMap` 出现 0 次、`noteId` 2 次（都在无关配置里）、`imageList` 1 处）。
 *
 * 版本/形态风险：这里按字段名取值，命中率随宿主/服务端模板变化。**只作为上游失败后的
 * 第二选择**（[XhsSavePivot] 在 `RequiresWebView` 时调用），解析不出就返回 null，绝不猜。
 */
internal object XhsShareFallback {

    fun resolve(html: String?, noteId: String, canonicalUrl: String): ResolvedNote? = try {
        val root = initialState(html) ?: return null
        val note = pickNote(root) ?: return null
        val images = imagesOf(note)
        val videos = videosOf(note)
        if (images.isEmpty() && videos.isEmpty()) {
            null
        } else {
            ResolvedNote(
                canonicalUrl = canonicalUrl,
                title = text(note, "title"),
                description = text(note, "desc"),
                authorName = text(note.optJSONObject("user"), "nickname")
                    ?: text(note.optJSONObject("user"), "nickName"),
                authorId = text(note.optJSONObject("user"), "userId"),
                publishTime = null,
                images = images,
                videos = videos,
                noteId = noteId,
                type = if (videos.isNotEmpty() && images.isEmpty()) "video" else "normal",
            )
        }
    } catch (_: Throwable) {
        null
    }

    // ---- HTML → JSON ----

    private fun initialState(html: String?): JSONObject? {
        if (html.isNullOrBlank()) return null
        val start = html.indexOf("window.__INITIAL_STATE__")
        if (start < 0) return null
        val end = html.indexOf("</script>", start).takeIf { it >= 0 } ?: html.length
        val script = html.substring(start, end)
        val eq = script.indexOf('=').takeIf { it >= 0 } ?: return null
        val literal = firstObject(script.substring(eq + 1)) ?: return null
        val fixed = literal
            .replace(Regex("(?<![\\w$])undefined(?![\\w$])"), "null")
            .replace("new Map([])", "[]")
        return runCatching { JSONObject(fixed) }.getOrNull()
    }

    /** 取第一个括号平衡的 JS 对象字面量（字符串内的括号不计数）。 */
    private fun firstObject(snippet: String): String? {
        var inString = false
        var quote = ' '
        var escaped = false
        var depth = 0
        var begin = -1
        for ((i, c) in snippet.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> inString = false
                }
                continue
            }
            when (c) {
                '"', '\'' -> {
                    inString = true; quote = c
                }
                '{' -> {
                    if (depth == 0) begin = i
                    depth++
                }
                '}' -> if (depth > 0 && --depth == 0 && begin >= 0) return snippet.substring(begin, i + 1)
            }
        }
        return null
    }

    // ---- JSON → 笔记 ----

    /**
     * 依次尝试三种承载位置：桌面 `note.noteDetailMap`、移动 `noteData.data.noteData`、
     * `noteData.data`。返回**第一个真正带媒体列表**的对象（不依赖 noteId，因为它常常缺席）。
     */
    private fun pickNote(root: JSONObject): JSONObject? {
        val candidates = ArrayList<JSONObject>()
        runCatching {
            root.optJSONObject("note")?.optJSONObject("noteDetailMap")?.let { map ->
                for (key in map.keys()) map.optJSONObject(key)?.optJSONObject("note")?.let { candidates += it }
            }
        }
        runCatching {
            val data = root.optJSONObject("noteData")?.optJSONObject("data") ?: return@runCatching
            data.optJSONObject("noteData")?.let { candidates += it }
            candidates += data
        }
        runCatching {
            root.optJSONObject("note")?.optJSONObject("note")?.let { candidates += it }
        }
        return candidates.firstOrNull { it.optJSONArray("imageList") != null || it.optJSONObject("video") != null }
    }

    private fun imagesOf(note: JSONObject): List<ResolvedMedia.Image> {
        val list = note.optJSONArray("imageList") ?: note.optJSONArray("images") ?: JSONArray()
        val out = ArrayList<ResolvedMedia.Image>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val url = firstHttp(
                item.optString("url"),
                item.optString("originalUrl"),
                item.optString("traceId"),
                item.optJSONArray("infoList")?.let { info ->
                    (0 until info.length()).joinToString(" ") { info.optJSONObject(it)?.optString("url").orEmpty() }
                },
            ) ?: continue
            out += ResolvedMedia.Image(
                sourceUrl = url,
                originalUrl = url,
                width = item.optInt("width"),
                height = item.optInt("height"),
                cover = i == 0 && list.length() <= 1 && note.optJSONObject("video") != null,
            )
        }
        return out
    }

    private fun videosOf(note: JSONObject): List<ResolvedMedia.Video> {
        val video = note.optJSONObject("video") ?: return emptyList()
        val stream = video.optJSONObject("media")?.optJSONObject("stream") ?: video.optJSONObject("stream")
        val urls = LinkedHashSet<String>()
        stream?.let { s ->
            for (codec in s.keys()) {
                val arr = s.optJSONArray(codec) ?: continue
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    firstHttp(item?.optString("masterUrl"), item?.optString("url"))
                        ?.let { urls += it }
                }
            }
        }
        firstHttp(video.optString("masterUrl"), video.optString("url"))?.let { urls += it }
        val key = firstNonBlank(video.optString("consumer")?.takeIf { it.startsWith("http") }, video.optString("originVideoKey"))
        if (key != null) urls += if (key.startsWith("http")) key else "https://sns-video-bd.xhscdn.com/${key.trimStart('/')}"
        return urls.filter { it.isNotBlank() }.map { url ->
            ResolvedMedia.Video(
                sourceUrl = url,
                originalUrl = url,
                candidates = listOf(MediaCandidate(url)),
            )
        }
    }

    // ---- 小工具 ----

    /** 去掉小红书 CDN 的 `!h5_1080jpg` / `!style_xxx` 处理后缀，拿原始文件。 */
    private fun firstHttp(vararg candidates: String?): String? = candidates
        .asSequence()
        .filterNotNull()
        .map { it.trim() }
        .map { it.substringBefore(' ').trim() }
        .map { it.replace("\\u002F", "/").replace("\\/", "/") }
        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        ?.substringBefore('!')

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }

    private fun text(obj: JSONObject?, key: String): String? =
        obj?.optString(key)?.takeIf { it.isNotBlank() }
}
