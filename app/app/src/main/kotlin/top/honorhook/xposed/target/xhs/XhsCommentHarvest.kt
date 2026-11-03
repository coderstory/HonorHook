package top.honorhook.xposed.target.xhs

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 评论图被动采集（2026-11-03 用户需求：评论图原图字节并入 X5 批量）。
 *
 * **为什么是被动采集**：评论接口（app 移动端 /api/sns 下 comment 族端点）需宿主自带签名头/登录态，
 * 模块主动构造调用有 461 风控风险（2026-11-03 用户拍板选被动）；而用户能长按评论图 =
 * 评论区响应必然流经本模块 X4 okhttp 拦截器（rewriteVideoSave 全 /api/sns/ 扫描面）——
 * 在那里顺带正则抽取图片链零新增 hook 点。没打开过评论区 = 无缓存 = 批量只下笔记图（诚实降级）。
 *
 * URL 形态：评论 JSON `pictures[].url` = `sns-webpic-qc.xhscdn.com/{token}!裁剪后缀` 显示链，
 * 与笔记图同 token 方案——下游统一经 [XhsImageUrl.toBare]（AUTO 主链）/ raw 回落，落 CDN 原始字节。
 *
 * 归属：请求 URL query `note_id`（评论接口必带，宿主 videofeed 同口径）；query 缺失时按
 * noteId 就近 lookback（harvestNoteShare 同款区域回扫）。存储上限照 [XhsVideoSave.noteShares]
 * 风格：超限整体清空（防长会话无界增长）。
 */
object XhsCommentHarvest {

    private const val MAX_NOTES = 100

    /** 评论图片显示链（`"url":"https://sns-webpic*.xhscdn.com/..."`；host 宽匹配，token 规则在 XhsImageUrl 兜）。 */
    private val P_PIC_URL: Pattern = Pattern.compile("\"url\"\\s*:\\s*\"(https://sns-webpic[^\"]+)\"")

    /** noteId（评论接口请求 query / body 内 16-32hex id）。 */
    private val P_NOTE_ID: Pattern = Pattern.compile("[?&]note_id=([0-9a-f]{16,32})|\"note_id\"\\s*:\\s*\"([0-9a-f]{16,32})\"")

    /** noteId → 评论图显示链集合（保序去重）。 */
    private val byNote = ConcurrentHashMap<String, LinkedHashSet<String>>()

    /** 拦截器侧采集入口（okhttp 线程；任何异常吞掉——采集绝不波及下载/宿主链路）。 */
    fun harvest(url: String, body: String) {
        runCatching {
            val nid = P_NOTE_ID.matcher(url).takeIf { it.find() }?.group()?.substringAfterLast('=')?.takeIf { it.length >= 16 }
                ?: run {
                    // query 缺失：body 内 noteId 就近兜底（取最后一个，与 xsec_token lookback 同向）
                    val m = P_NOTE_ID.matcher(body)
                    var last: String? = null
                    while (m.find()) last = m.group(1) ?: m.group(2)
                    last
                } ?: return
            val set = byNote.getOrPut(nid) { LinkedHashSet() }
            var m = P_PIC_URL.matcher(body)
            while (m.find()) {
                set.add(m.group(1) ?: continue)
            }
            if (set.isEmpty()) byNote.remove(nid)   // 评论翻页中间态（纯文本页）不留空壳
            if (byNote.size > MAX_NOTES) {
                byNote.clear(); byNote[nid] = set
            }
        }
    }

    /** X5 批量消费：该笔记已采集的评论图显示链（快照副本；保留缓存，同会话重复保存不丢）。 */
    fun urlsOf(noteId: String): List<String> =
        runCatching { byNote[noteId]?.toList().orEmpty() }.getOrDefault(emptyList())
}
