package top.honorhook.xposed.target.xhs

/**
 * 作者主页列表响应里的「标题 → noteId」索引。
 *
 * 为什么需要它（2026-11-23 真机）：详情页在缓存命中时既不发 `imagefeed`（分享链不会登记），
 * Activity 上也没有可读的 noteId 方法（日志 `候选方法=0`），卡片反射同样拿不到 ⇒ 整篇判失败。
 * 但列表接口 `/api/sns/v4/note/user/posted` 的每一项都带 `id` + `display_title`
 * （`.buildtmp/xhs9533-c5/sources/com/xingin/entities/NoteItemBean.java:173`），
 * 而卡片的 contentDescription 就是「笔记,<title>,来自<作者>,<赞数>」——两边靠标题对得上。
 *
 * 代价与边界：标题重名会取到其中一篇（同一作者下重名很少，且该类笔记本来也无法精确定位）；
 * 只做标题→id 的单向索引，不参与作者判定（作者归属仍由 XhsAuthorTrack 负责）。
 */
internal object XhsFeedTitles {

    private const val MAX_ENTRIES = 3000
    private val lock = Any()
    private val byTitle = HashMap<String, String>()

    /** 两个方向都扫：字段顺序在服务端不保证。 */
    private val AB = Regex("\"display_title\"\\s*:\\s*\"([^\"]{0,80})\"[\\s\\S]{0,400}?\"id\"\\s*:\\s*\"([0-9a-f]{24})\"")
    private val BA = Regex("\"id\"\\s*:\\s*\"([0-9a-f]{24})\"[\\s\\S]{0,400}?\"display_title\"\\s*:\\s*\"([^\"]{0,80})\"")

    fun harvest(url: String?, body: String?): Int {
        if (url == null || body == null) return 0
        if (!url.contains("note/user/posted")) return 0
        return runCatching {
            var added = 0
            synchronized(lock) {
                if (byTitle.size > MAX_ENTRIES) byTitle.clear()
                for (m in AB.findAll(body)) {
                    val title = m.groupValues[1].trim()
                    if (title.isNotEmpty() && byTitle.put(title, m.groupValues[2]) != m.groupValues[2]) added++
                }
                for (m in BA.findAll(body)) {
                    val title = m.groupValues[2].trim()
                    if (title.isNotEmpty() && byTitle.put(title, m.groupValues[1]) != m.groupValues[1]) added++
                }
            }
            added
        }.getOrDefault(0)
    }

    /**
     * 用卡片描述反查 noteId：描述形如「笔记,薄荷蓝,来自YORK,5赞」「视频,标题,来自作者」。
     * 取第 2 段当标题；找不到时退回「描述里包含该标题（且标题不短于 4 字）」的唯一匹配。
     */
    fun idForDesc(desc: String?): String? {
        if (desc.isNullOrBlank()) return null
        val title = desc.substringAfter(',', "").substringBefore(',').trim()
        synchronized(lock) {
            if (title.isNotEmpty()) byTitle[title]?.let { return it }
            if (title.length >= 4) {
                val hits = byTitle.entries.filter { title.contains(it.key) || it.key.contains(title) }
                if (hits.size == 1) return hits.first().value
            }
        }
        return null
    }

    fun size(): Int = synchronized(lock) { byTitle.size }

    fun clear() = synchronized(lock) { byTitle.clear() }
}
