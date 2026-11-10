package top.honorhook.ui.photos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 图库 AI 能力调用工具类（UI 侧，业务层用）。
 *
 * 原理：AI 超清/扩图的 visionkit 调用方在图库进程内（引擎是特权应用直调无望，
 * 见 docs/reverse/photos.md §三），本模块经 PhotosAiHook 注入图库后注册了导出广播
 * 通道。调用方只需：发 AI_TASK 广播 → 图库存活时由 hook 执行 → 收 AI_RESULT。
 *
 * 协议：任务带 reqId，结果按 reqId 匹配（hook 侧 reply 原样回显）——图库内
 * SDK 单线程串行执行，但业务层可并发提交，结果不会串台。
 *
 * 注意：图库进程未存活时广播无人收，先 ensureGalleryAlive() 拉起（首次冷启动
 * 后 hook 注册通道约需 1~2s，超时给足余量）。云端管线依赖网络与荣耀权益配额。
 */
object PhotosAiClient {

    private const val ACTION_TASK = "top.honorhook.photos.AI_TASK"
    private const val ACTION_RESULT = "top.honorhook.photos.AI_RESULT"
    private const val GALLERY_PKG = "com.hihonor.photos"

    data class AiResult(val code: Int, val out: String?, val msg: String) {
        val ok: Boolean get() = code == 0
    }

    /** reqId → 挂起点。接收器把结果分发给对应等待者。 */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<AiResult>>()

    private var receiverRegistered = false

    private fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val appCtx = ctx.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_RESULT) return
                    val r = AiResult(
                        code = intent.getIntExtra("code", -999),
                        out = intent.getStringExtra("out"),
                        msg = intent.getStringExtra("msg") ?: "",
                    )
                    intent.getStringExtra("reqId")?.let { id ->
                        pending.remove(id)?.complete(r)
                    }
                }
            }
            val filter = IntentFilter(ACTION_RESULT)
            // 33+ 必须显式声明 exported；发方是图库（跨 uid，setPackage 定向），
            // NOT_EXPORTED 会收不到，必须 EXPORTED
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appCtx.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }

    /**
     * 确保图库进程存活并注册了任务通道。冷启动后给 hook 轮询 Application
     * + 注册广播留 3s 余量（hook 侧 500ms×60 次轮询）。
     */
    suspend fun ensureGalleryAlive(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(GALLERY_PKG)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                ctx.startActivity(it)
            }
            // 进程起来 ≠ 通道就绪；轮询无法从外部探测，按 hook 注册节奏给固定余量
            Thread.sleep(3000)
            true
        }.getOrDefault(false)
    }

    suspend fun enhance(
        ctx: Context,
        path: String,
        out: String,
        photoMode: Boolean = false,
        timeoutMs: Long = 120_000,
    ): AiResult = submit(
        ctx, out, timeoutMs,
        Intent(ACTION_TASK).putExtra("mode", "enhance")
            .putExtra("photoMode", photoMode),
        path,
    )

    /**
     * AI 扩图。[top]/[bottom]/[left]/[right] 为原图尺寸的比例（0~1，
     * 四边至少给一边非 0）。
     */
    suspend fun outpaint(
        ctx: Context,
        path: String,
        out: String,
        top: Float = 0f,
        bottom: Float = 0f,
        left: Float = 0f,
        right: Float = 0f,
        timeoutMs: Long = 120_000,
    ): AiResult = submit(
        ctx, out, timeoutMs,
        Intent(ACTION_TASK).putExtra("mode", "outpaint")
            .putExtra("top", top).putExtra("bottom", bottom)
            .putExtra("left", left).putExtra("right", right),
        path,
    )

    private suspend fun submit(
        ctx: Context,
        out: String,
        timeoutMs: Long,
        task: Intent,
        path: String,
    ): AiResult = withContext(Dispatchers.IO) {
        ensureReceiver(ctx)
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<AiResult>()
        pending[reqId] = deferred
        try {
            ctx.sendBroadcast(
                task.setPackage(GALLERY_PKG)
                    .putExtra("reqId", reqId)
                    .putExtra("path", path)
                    .putExtra("out", out)
            )
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(reqId)
            AiResult(code = -999, out = null, msg = "等待结果失败: ${e.message}")
        }
    }
}
