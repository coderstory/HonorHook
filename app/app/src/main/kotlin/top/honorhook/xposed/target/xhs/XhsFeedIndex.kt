package top.honorhook.xposed.target.xhs

import java.util.concurrent.ConcurrentHashMap

/**
 * 作者主页笔记索引（2026-11-21 架构定案：浏览即登记、零长按）。
 *
 * **静态依据**（语料 `.buildtmp/xhs9533*` 反编译，宿主 9.53.3；字段名按 9.54 实测复核）：
 *  - 接口 `/api/sns/v4/note/user/posted` 声明在 `com.xingin.matrix.profile.services.NoteServices`
 *    （参数 user_id/cursor/num/use_cursor…），返回 `q1a.x1`：`notes: List<NoteItemBean>` + `has_more`
 *  - 列表项 `com.xingin.entities.NoteItemBean`（继承 `MultiItemBean`，主键 JSON 键是 **`id`**）带
 *    `@kd.c("xsec_token")` 与 `@kd.c("share_info")` → 响应体里就同时有 noteId 与下载凭据
 *  ⇒ 用户把作者主页**滚一遍**，全部笔记即被登记，不需要点开、长按、保存菜单任何一步。
 *
 * 为什么不能只信响应体里的 `"id"`：正文中 24hex 字符串不止笔记主键（话题/图片 token 也可能是
 * 24hex）。故登记时与 [XhsVideoSave] 旁路采集到的凭据表取交集——**有 token 或 share link 的 id
 * 才算可下载笔记**，其余留给 [XhsBatchDriver] 计入「无分享链」失败，不静默吞。
 *
 * 进程内内存态（随进程消失）：采集本就是「打开宿主→滚一遍→下完」，跨会话持久化无收益。
 */
object XhsFeedIndex {

    private const val MAX_NOTES = 2000
    private val P_HEX24 = Regex("[0-9a-f]{24}")
    private val P_USER_ID = Regex("[?&](?:target_)?user_id=([0-9A-Za-z]+)")

    /** 发现顺序（LinkedHashMap 保序；ConcurrentHashMap 不保序，故用同步包装）。 */
    private val ordered = LinkedHashMap<String, Unit>()

    /** 最近一次 /posted 请求的 user_id（= 当前浏览的作者；驱动只认这一批，避免混入推荐流）。 */
    @Volatile private var authorId: String? = null

    /** 卸载/切换作者时清空（主页切人后旧索引不该再被消费）。 */
    fun reset() {
        synchronized(ordered) { ordered.clear() }
    }

    fun author(): String? = authorId

    /** 已登记的笔记 id 快照（发现序）。 */
    fun snapshot(): List<String> = synchronized(ordered) { ordered.keys.toList() }

    /** 该 id 是否已登记。 */
    fun contains(id: String): Boolean = synchronized(ordered) { ordered.containsKey(id) }

    /**
     * 拦截器旁路入口（okhttp 线程；任何异常吞掉——索引失败绝不影响宿主请求链路）。
     * @return 本次新登记的笔记数（0 = 非列表响应或没有新笔记）
     */
    fun harvest(url: String, body: String): Int = runCatching {
        if (!url.contains("note/user/posted")) return 0
        P_USER_ID.find(url)?.groupValues?.getOrNull(1)?.let { authorId = it }
        val ids = P_HEX24.findAll(body).map { it.value }.toSet()
        if (ids.isEmpty()) return 0
        var added = 0
        synchronized(ordered) {
            for (id in ids) {
                if (id in ordered) continue
                // 凭据门：无 token 也无 share link 的 id 不登记（多半是话题/图片 token，不是笔记）
                if (!XhsVideoSave.hasCredentials(id)) continue
                if (ordered.size >= MAX_NOTES) break
                ordered[id] = Unit
                added++
            }
        }
        added
    }.getOrDefault(0)
}
