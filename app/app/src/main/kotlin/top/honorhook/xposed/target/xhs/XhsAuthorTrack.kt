package top.honorhook.xposed.target.xhs

/**
 * 当前浏览的**作者身份**与**页面上下文**。
 *
 * 为什么不能看页面：本机（宿主 9.54.0）作者主页的 Activity 就是 `NoteDetailActivity`，
 * 且主页 / 详情页 / 相关推荐三者的视图元素重叠（用户截图为证）。2026-11-22 一天里「看页面」的判据
 * 错了三次（Activity 类名 → 粉丝文案 → 笔记/收藏 tab），所以上下文只由**宿主自己发的请求**决定：
 *
 * - `/api/sns/v4/note/user/posted`（作者主页列表）→ 记作者 id + 「现在在主页」
 * - `imagefeed` / `/note/detail`（某一篇笔记）→ 「现在在看详情」
 *
 * 两个坑（2026-11-22 审计 P0，都已处理）：
 * ① 信号必须挂在**请求**上而不是响应上：响应为空/`peekBodyString` 失败（「服务异常」时就是这样）
 *    会让信号整条丢失。
 * ② 「主页 → 详情 → 返回主页」时宿主走缓存，**不再发 posted 请求** ⇒ 严格判据永久为假，
 *    按钮会自己消失。所以按钮/起跑用「静默期」宽判据（详情请求已静默一段时间），
 *    而驱动确认回到列表时用 [markProfile] 显式打点，把严格判据顶回来。
 *
 * 时钟用 [android.os.SystemClock.elapsedRealtime]：NTP 校时/用户改时间会让 `currentTimeMillis`
 * 直接翻面（审计 P1）。
 */
internal object XhsAuthorTrack {

    private val P_USER = Regex("[?&](?:target_)?user_id=([0-9A-Za-z]+)")

    /** 详情请求静默超过这个时长，就当用户已经回到主页（返回不发请求，只能这样兜）。 */
    private const val DETAIL_QUIET_MS = 8_000L

    private val lock = Any()

    /** 宿主最近一次作者主页列表请求的作者 id（换主页就变）。 */
    @Volatile
    var authorId: String? = null
        private set

    private var postedAt = 0L
    private var detailAt = 0L

    /** 主页列表请求到达时的回调（给 UI 补一次按钮注入用；信号常常晚于首屏布局）。 */
    @Volatile
    var onProfileSignal: (() -> Unit)? = null

    /**
     * 所有 `/api/sns/` 请求的统一入口：**请求发出即可**判定上下文，不等响应。
     * 由 [top.honorhook.xposed.target.xhs.XhsVideoSave] 在 `proceed` 之前调用。
     */
    fun onRequest(url: String?) {
        if (url.isNullOrEmpty()) return
        var becameProfile = false
        if (url.contains("note/user/posted")) {
            P_USER.find(url)?.groupValues?.getOrNull(1)?.let {
                synchronized(lock) {
                    authorId = it
                    postedAt = android.os.SystemClock.elapsedRealtime()
                }
                becameProfile = true
            }
        }
        if (url.contains("imagefeed") || url.contains("/note/detail")) {
            synchronized(lock) { detailAt = android.os.SystemClock.elapsedRealtime() }
        }
        if (becameProfile) runCatching { onProfileSignal?.invoke() }
    }

    /** 兼容旧调用点：等价于 [onRequest]。 */
    fun onPostedRequest(url: String) = onRequest(url)

    /** 兼容旧调用点：等价于 [onRequest]。 */
    fun onDetailRequest() = synchronized(lock) { detailAt = android.os.SystemClock.elapsedRealtime() }

    /**
     * 严格判据：最近一次宿主请求就是「作者主页列表」。
     * 只用于**日志**与内部判断，不再直接拿来拒绝起跑——「返回主页」场景它会是假的。
     */
    fun isProfileContext(): Boolean = synchronized(lock) { authorId != null && postedAt > detailAt }

    /**
     * 宽判据：主页列表请求比详情请求更新，**或**详情请求已静默 [DETAIL_QUIET_MS]。
     * 按钮注入与起跑用它：宁可多给一次机会（起跑后作者校验会兜底），也不要把「回到主页」判死。
     */
    fun isProfileContextOrQuiet(): Boolean = synchronized(lock) {
        if (authorId == null) return false
        postedAt > detailAt || System.currentTimeMillis() >= 0 && quiet()
    }

    private fun quiet(): Boolean {
        val d = detailAt
        if (d == 0L) return false
        return android.os.SystemClock.elapsedRealtime() - d > DETAIL_QUIET_MS
    }

    /** 驱动确认「已经回到列表」时调用：把「现在在主页」顶到当下。 */
    fun markProfile() {
        synchronized(lock) { postedAt = android.os.SystemClock.elapsedRealtime() }
    }

    /** 取证用：`posted=… detail=…`（相对开机毫秒），真机上能一眼看出上下文是怎么翻的。 */
    fun snapshot(): String = synchronized(lock) {
        "author=${authorId ?: "null"} posted=${postedAt} detail=${detailAt}"
    }
}
