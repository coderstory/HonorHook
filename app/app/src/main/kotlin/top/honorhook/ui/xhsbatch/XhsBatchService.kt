package top.honorhook.ui.xhsbatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 前台服务：批量下载任务的保活壳（2026-11-11 用户反馈「后台不会继续下载」）。
 *
 * 为什么需要：下载循环跑在进程内协程里，App 退后台后 WebView/网络调度会被冻结，
 * 厂商 ROM 杀后台更狠。前台服务（dataSync 类型）向系统声明「正在干活」，进程不被冻结。
 *
 * 归属关系：controller 是单例（Holder），Service 只是「保活 + 进度通知」的呈现层——
 * Service 被杀不影响已开始的下载（进程不死），Service 挂了任务继续，只是没了通知。
 */
object XhsBatchControllerHolder {
    @Volatile
    private var instance: XhsBatchController? = null

    fun get(context: Context): XhsBatchController =
        instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val store = FileXhsBatchTaskStore(app)
                // 进程冷启动归一化（2026-11-11 用户反馈「重开还显示下载中」）：
                // 走到这里 = 旧进程必死 ⇒ 存量 Running/Fetching/Paused 都是残留态，一律落 Interrupted
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    store.list().filter {
                        it.state == XhsBatchState.Running || it.state == XhsBatchState.Fetching ||
                            it.state == XhsBatchState.Paused
                    }.forEach { r -> store.upsert(r.copy(state = XhsBatchState.Interrupted)) }
                }
                XhsBatchController(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                    taskStore = store,
                    appContext = app,
                ).also { instance = it }
            }
        }
}

class XhsBatchService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notifyJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val controller = XhsBatchControllerHolder.get(this)
        // 防重复 collect（审计 P2：每次 onStartCommand 都新增一个 collect 会叠加通知更新）
        if (notifyJob?.isActive != true) {
            notifyJob = serviceScope.launch {
                controller.progress.collectLatest { p ->
                    updateNotification(p)
                    // 终态（无进行中任务）即撤岗；进程内任务继续收尾，服务只管前台身份
                    val terminal = p.state != XhsBatchState.Running && p.state != XhsBatchState.Fetching &&
                        p.state != XhsBatchState.Paused
                    // 队列还有待执行任务时保持前台（下一个任务马上会跑）
                    if (terminal && !controller.hasQueued()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        // START_STICKY：ROM 杀进程后系统择机拉起，Holder 归一化 Interrupted → 用户可一键恢复
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                // IMPORTANCE_DEFAULT：LOW 档在荣耀 ROM 上被折叠进「静默通知」且实测一条都不展示
                //（numEnqueuedByApp=462 numPostedByApp=0，2026-11-11 dumpsys 实锤），提档保常驻通知可见
                NotificationChannel(CHANNEL_ID, "小红书批量下载", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        startForeground(NOTIFY_ID, buildNotification("小红书批量下载", "任务进行中", null))
    }

    private fun updateNotification(p: XhsBatchProgress) {
        val text = "已处理 ${p.notesProcessed}/${p.notesFound} · 图 ${p.imagesDone} · 视频 ${p.videosDone} · " +
            "%.1fMB".format(p.totalBytes / 1024f / 1024f)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification("小红书批量下载", text, p.userId))
    }

    private fun buildNotification(title: String, text: String, userId: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(if (userId != null) "$text（$userId）" else text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "xhs_batch"
        private const val NOTIFY_ID = 0x9D51

        fun start(context: Context) {
            val i = Intent(context, XhsBatchService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
