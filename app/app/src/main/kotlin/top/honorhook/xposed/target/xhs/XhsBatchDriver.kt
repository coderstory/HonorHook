package top.honorhook.xposed.target.xhs

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.runBlocking
import top.honorhook.AutoActions
import top.honorhook.xposed.core.MountEngine

/**
 * 批量采集驱动（2026-11-21 架构定案，跑在**宿主进程**里）。
 *
 * 旧方案（点卡片→详情→长按→保存菜单→返回）已被用户实测否掉：每篇都要靠 UI 手势命中原生
 * 长按面板，9.54 的详情页里没有稳定锚点（`desc=图片` 不存在），一篇失败即整轮崩。现方案**不点任何
 * 东西**：用户只是把作者主页往下滚，[XhsFeedIndex] 从 `/note/user/posted` 响应旁路登记 noteId，
 * 本驱动逐个走 [XhsSavePivot.resolveByShareLink] → [XhsBatchSave.downloadNoteSync] 静默落盘。
 *
 * 为什么更稳：全程只依赖「列表接口在、响应带凭据」这一个静态事实（[XhsFeedIndex] 注释里有反编译
 * 依据），不依赖任何视图锚点、不打开详情页；去重（[XhsDownloadLedger]）、720p/5MB 过滤、文件命名
 * 全部复用既有落盘管线。
 *
 * 停下来（用户 2026-11-21 要求「半途取消」）的两个条件：按钮切「取消」→ [stop]；或连续
 * [IDLE_ROUNDS] 轮没有新笔记（= 作者到底/页面没再加载）。
 */
object XhsBatchDriver {

    private const val JUDGE_PREFIX = "XhsHook·Batch"
    /** 连续 N 轮（1s/轮）没有新笔记就收工：滚动加载一页通常 1~3s，留足 20s 容忍慢网。 */
    private const val IDLE_ROUNDS = 20

    @Volatile private var running = false
    @Volatile private var stopRequested = false

    /** 运行态变化回调（宿主进程内、任意线程调用）——悬浮按钮据此刷新「采集/取消」。 */
    @Volatile var stateListener: (() -> Unit)? = null

    fun isRunning(): Boolean = running

    fun start(module: XposedModule, ctx: Context) {
        if (running) return
        running = true
        stopRequested = false
        val app = ctx.applicationContext
        Thread({ loop(module, app) }, "HonorHook-xhs-collect").start()
        stateListener?.invoke()
        // 让模块侧无障碍服务开始滚页面（新笔记靠滚动加载，登记发生在请求旁路里）
        notifyModule(app, AutoActions.STATE, true, "开始采集，正在滚动主页…")
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 采集开始（等主页滚动登记笔记）")
    }

    fun stop(module: XposedModule?, ctx: Context?) {
        if (!running) return
        stopRequested = true
        // 立即停滚（已登记的笔记还会继续落盘，那是用户已"发现"的内容，不算取消掉的部分）
        ctx?.let { notifyModule(it, AutoActions.STATE, false, "已取消滚动，正在收尾…") }
        module?.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 收到取消，正在收尾")
    }

    private fun loop(module: XposedModule, ctx: Context) {
        val processed = HashSet<String>()
        var ok = 0
        var skipped = 0
        var failed = 0
        var idle = 0
        try {
            while (!stopRequested) {
                val ids = XhsFeedIndex.snapshot()
                val next = ids.firstOrNull { it !in processed }
                if (next == null) {
                    if (++idle > IDLE_ROUNDS) break
                    if (idle == 1) report(ctx, "已登记 ${ids.size} 条，等页面继续加载…", ok, skipped, failed, 0)
                    Thread.sleep(1000)
                    continue
                }
                idle = 0
                processed += next
                if (stopRequested) break

                // ① 去重（台账口径与用户手动下载完全一致）
                val hit = runCatching { XhsDownloadLedger.downloaded(ctx, next) }.getOrNull()
                if (hit != null && hit.first > 0) {
                    skipped++
                    report(ctx, "已下载过，跳过", ok, skipped, failed, ids.size)
                    continue
                }

                val activity = XhsBatchSave.activityOrNull()
                if (activity == null) {
                    failed++
                    report(ctx, "宿主不在前台，跳过 1 条", ok, skipped, failed, ids.size)
                    continue
                }

                // ② 凭据 → 分享链 → 上游解析（全反射/网络，失败只记失败数，不中断整轮）
                val urls = runCatching { noteUrls(next, module) }.getOrNull().orEmpty()
                if (urls.isEmpty()) {
                    failed++
                    report(ctx, "解析不到图片，失败 +1", ok, skipped, failed, ids.size)
                    continue
                }

                // ③ 静默落盘（复用外挂直下管线：裸链原图 / HEIC 转码 / 720p+5MB 过滤 / 台账记账）
                val saved = runCatching { XhsBatchSave.downloadNoteSync(activity, urls, module, next) }
                    .getOrDefault(false)
                if (saved) ok++ else failed++
                report(ctx, if (saved) "已保存 1 篇（${urls.size} 张）" else "下载失败 1 篇", ok, skipped, failed, ids.size)
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 采集中断: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            running = false
            stateListener?.invoke()
            val summary = "采集结束：成功 $ok · 跳过 $skipped · 失败 $failed"
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX $summary")
            report(ctx, summary, ok, skipped, failed, 0)
            notifyModule(ctx, AutoActions.DONE, false, summary)
        }
    }

    /** 单个 noteId → 可下载图片 URL（正文图 + 评论图，口径与 X5 点击轨一致）。 */
    private fun noteUrls(noteId: String, module: XposedModule): List<String> {
        val note = runBlocking { XhsSavePivot.resolveByShareLink(noteId, module) } ?: return emptyList()
        return xhsContentImageUrls(note, noteId) + XhsCommentHarvest.urlsOf(noteId)
    }

    /** 进度回传模块进程（UI 页与通知栏以外的唯一可见渠道；服务不可用时静默丢弃）。 */
    private fun report(ctx: Context, text: String, ok: Int, skipped: Int, failed: Int, total: Int) {
        notifyModule(ctx, AutoActions.PROGRESS, true, text, ok, skipped, failed, total)
    }

    private fun notifyModule(
        ctx: Context,
        action: String,
        running: Boolean,
        text: String,
        ok: Int = 0,
        skipped: Int = 0,
        failed: Int = 0,
        total: Int = 0,
    ) {
        runCatching {
            ctx.sendBroadcast(
                Intent(action)
                    .setPackage(AutoActions.MODULE_PACKAGE)
                    .putExtra(AutoActions.EXTRA_RUNNING, running)
                    .putExtra(AutoActions.EXTRA_TEXT, text)
                    .putExtra("ok", ok).putExtra("skipped", skipped)
                    .putExtra("failed", failed).putExtra("total", total),
            )
        }
    }
}
