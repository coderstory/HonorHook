package top.honorhook.xposed.target.xhs

/**
 * 当前浏览的**作者身份**（2026-11-22 审计 #11：只锁列表页类名不够）。
 *
 * 宿主所有作者主页共用同一个 Activity 类（`NewOtherUserActivity`），所以「列表页类名对上了」不等于
 * 「还是同一个作者」。用户在采集途中从 A 的主页点进 B 或换页，旧代码会把 B 的笔记当成 A 的继续下。
 *
 * 锁定值只有两个来源，都是**宿主自己发的数据**，不猜：
 * ① `/api/sns/v4/note/user/posted` 请求 URL 里的 `user_id`（作者主页列表接口，换作者必换值）；
 * ② 分享页解析出的 `ResolvedNote.authorId`（首篇解析成功时兜底学习）。
 *
 * 判据：锁定值与新出现的不一致 = 换作者 ⇒ 驱动立刻收工并说明原因。
 */
internal object XhsAuthorTrack {

    private val P_USER = Regex("[?&](?:target_)?user_id=([0-9A-Za-z]+)")

    /** 宿主最近一次作者主页列表请求的作者 id（换主页就变）。 */
    @Volatile
    var authorId: String? = null
        private set

    fun onPostedRequest(url: String) {
        // 只看 query：路径里也可能出现 user（`/user/posted`），抠错就等于锁定错作者
        P_USER.find(url)?.groupValues?.getOrNull(1)?.let { authorId = it }
    }
}
