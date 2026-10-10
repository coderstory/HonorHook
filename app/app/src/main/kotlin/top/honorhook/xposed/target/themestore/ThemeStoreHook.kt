package top.honorhook.xposed.target.themestore

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.target.TargetHook

/**
 * 荣耀主题商店目标（F1–F4）：F1 开 VIP / F2 全免费（含下载链修复）/ F3 试用无倒计时 / F4 不恢复默认。
 *
 * **装配 + kind 分派**：挂载表构建在 `CollectorsRes.kt`（ItemInfo/价格/按钮/VIP/试用）与
 * `CollectorsSource.kt`（下载链/云控/槽位/tab 源头裁剪），共享部件在 `ThemeStoreParts.kt`。
 *
 * **设计依据、挂载点理由与真机判据全部归档在 `docs/theme-store-hook-design.md`**，本文件只保留代码。
 * 真机基线主题商店 20.1.63.305（挂载数随挂载表变化，**以真机 `installed N/M` 的 M 为准**）。
 * 新增/调整挂载点前：先更新 design.md 的理由，再改代码（最少 hook 点 / 根源原则，CLAUDE.md §九）。
 */
object ThemeStoreHook : TargetHook {

    override val packageName = "com.hihonor.android.thememanager"

    /** 共享挂载基建（版本自检/重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("ThemeStoreHook", packageName)

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // libxposed 102 的 PackageLoadedParam 只有 defaultClassLoader（HookEntry 同款写法）
        val loader = param.defaultClassLoader
        // DexKit 第二轨兜底（MethodResolver）需要 APK 磁盘路径：param.applicationInfo.sourceDir（HC ScreenRecorderModule.kt:85 实证）
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        // 挂载表 39 项（38 清理基线 + W1 动态壁纸应用路径撕裂修正）；**以真机 M 为准。**
        val pending = mutableListOf<MountTask>()
        collectFreeJudgmentHooks(pending)   // F2 A1-A4 + A11 + T1 + btn-hide:buy
        collectPriceZeroHooks(pending)      // F2 A5
        collectBeanSourceZeroHooks(pending) // F2 A11
        collectRealChainHooks(pending)      // F2 T1（按钮组类型兜底）
        collectDownloadChainHooks(pending)  // F2 H1/H2b + W1
        collectCloudSwitchHooks(pending)    // 云控统一读点（表驱动：CloudSwitchTable）
        collectAdSlotPruneHooks(pending)    // 美豆服务端槽位源头裁剪（表驱动：AdSlotFilter）
        collectBottomTabPruneHooks(pending) // 底部 tab 源头裁剪（表驱动：BottomTabFilter）
        collectVipHooks(pending)            // F1 B1/B2 + VipState getter 短路
        collectRetentionDialogHooks(pending) // F1 挽留弹窗双保险
        collectTrialHooks(pending)          // F3 C1/C5/C6 + F4 C4 + 保守归 F2 C2
        collectApplyStateHooks(pending)     // F4 C7 + F3 C9

        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** 单个挂载任务执行（解析/重试/汇总在共享 [engine]；本函数只做 kind 分派的 intercept 构造）。 */
    private fun mountOne(
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountEngine.Result {
        val method = engine.resolveTask(task, module, loader)
            ?: return MountEngine.Result.NOT_RESOLVED
        // resolveValue 幂等（enumConstants 纯查找，无副作用），此处二次调用仅为取值；null 情况已在 engine 内拦截
        val value = task.resolveValue?.invoke(loader) ?: task.value
        return runCatching {
            val builder = module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(task.id)
            when (task.kind) {
                // 常量短路：命中即返回强制值，不调原方法
                MountKind.CONST -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) value else chain.proceed()
                }
                // 参数强制（setter 写点 / 下载类型路由点）
                MountKind.FORCE_ARG -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        val args = chain.args?.toTypedArray() ?: arrayOfNulls<Any?>(task.forceArgIndex + 1)
                        args[task.forceArgIndex] = value
                        chain.proceedWith(chain.thisObject, args)
                    } else {
                        chain.proceed()
                    }
                }
                // 云控读点：按第 1 参 key 查表，命中即短路返回强制值；未命中放行原逻辑（对非表内 key 零影响）
                MountKind.KEY_OVERRIDE -> builder.intercept { chain ->
                    val key = runCatching { chain.getArg(0) as? String }.getOrNull()
                    val forced = top.honorhook.xposed.data.CloudSwitchTable.forced(key)
                    if (key != null && forced != null) {
                        logCloudOverride(module, key, forced)
                        forced
                    } else {
                        chain.proceed()
                    }
                }
                // 服务端槽位载荷出口裁剪（美豆 S1）：proceed 拿到 SlotData.list 后按表剔营销槽位，失败放行原值
                MountKind.SLOT_PRUNE -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        val orig = chain.proceed()
                        pruneSlotPayload(orig, module) ?: orig
                    } else {
                        chain.proceed()
                    }
                }
                // 服务端底部 tab 配置出口裁剪（S2）：proceed 拿到 tab 列表后按表剔 VIP/福利 tab，失败放行原值
                MountKind.BOTTOM_TAB_PRUNE -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        val orig = chain.proceed()
                        pruneBottomTabPayload(orig, module) ?: orig
                    } else {
                        chain.proceed()
                    }
                }
                // void 空实现阻断（挽留弹窗 F1；判据日志不受 DEBUG_LOGGING 门控）
                MountKind.BLOCK -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        module.log(Log.INFO, MountEngine.TAG, "挽留弹窗已拦截: ${task.methodName}")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                // 放行后 finish（挽留弹窗 F2 兜底）：先 proceed 再 finish，生命周期完整（不 proceed 会让
                // onStart/onDestroy 等在未初始化状态跑，可能 NPE；fanqie MainHook.java:689 先例，HC 的
                // finish+不 proceed 模式不采用）
                MountKind.FINISH_ACTIVITY -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        val result = chain.proceed()
                        (chain.thisObject as? Activity)?.finish()
                        module.log(Log.INFO, MountEngine.TAG, "挽留弹窗已拦截: ${task.methodName}")
                        result
                    } else {
                        chain.proceed()
                    }
                }
                // 前置数据修正：proceed 前修正 this 对象的字段（壁纸 packagePath 路径撕裂修复 W1），再原样放行
                MountKind.PRE_PROCESS -> builder.intercept { chain ->
                    if (routeActive(chain, module, task)) {
                        fixWallpaperPackagePath(chain, module)
                    }
                    chain.proceed()
                }
            }
        }.fold(
            onSuccess = {
                if (MountEngine.DEBUG_LOGGING) {
                    module.log(Log.INFO, MountEngine.TAG, "hook 已挂载: ${task.description} → ${actionDesc(task)} ${value ?: "null"}")
                }
                MountEngine.Result.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, MountEngine.TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountEngine.Result.FAILED
            },
        )
    }

    /** hook 编写时的宿主版本基线（aapt2 实证；OTA 超出此版本时 MountEngine 首轮挂载打 WARN）。 */
    private const val HOST_VERSION_BASELINE = "20.1.63.305"
}
