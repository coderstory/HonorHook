package top.honorhook.xposed.core

import android.os.Handler
import android.os.HandlerThread

/**
 * 挂载调度器：共享单 HandlerThread（"HonorHook-mount"）后台线程。
 * 背景：挂载/懒加载重试/DexKit 秒级全 dex 索引扫描此前各 hook 用主线程 postDelayed 做重试轮——
 * 版本漂移场景 10~18 任务 × 重试轮 × 秒级扫描 = 主线程卡死 → ANR → 系统重启 → 再卡（boot-loop 邻域风险）。
 * 依据：hook 挂载不碰 UI，libxposed 的 hook 注册（module.hook + intercept）与 DexKitBridge 查询均线程安全，
 * 可任意线程调用。仅外移调度线程，不改任何挂载/拦截语义（CLAUDE.md §九「最小侵入/逻辑外移」）。
 * 供各 TargetHook 统一使用：[postDelayed] 挂载重试轮，[handler] post 一次性动作。
 */
object MountScheduler {

    private val thread = HandlerThread("HonorHook-mount").apply { start() }

    /** 后台挂载线程的 Handler（挂载/重试任务按投递顺序串行执行）。 */
    val handler: Handler = Handler(thread.looper)

    /** 在挂载后台线程延后 [delayMillis] 毫秒执行 [action]。 */
    fun postDelayed(delayMillis: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMillis)
    }
}