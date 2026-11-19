package top.honorhook.xposed.target.appmarket

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import top.honorhook.xposed.target.appmarket.TamerUtil.callMethod
import top.honorhook.xposed.target.appmarket.TamerUtil.className

/**
 * 推送族拦截（tamer 1.4.11 PushBlocker 移植，2026-11-19）。
 *
 * push（默认开）：运营/营销推送短路；type 以 "1" 开头 = 更新提醒族放行（tamer 语义，杀营销留更新）。
 * msg_center（默认关）：站内消息/红点族 MsgGroupViewModel#k/repository.a#y →null（16.1.11.302 上
 * tp5/rk4/v56 已删、MsgGroupViewModel#l 已消失，只留两活点 + Activity 封锁）。
 * update_notify（默认关）：dda#h（原 UpdateNotifyHandler#g）→null。all_notify（默认关）：NotificationControllerImpl#v→null。
 */
object TamerPushHooks : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"

    private fun task(id: String, cls: String, method: String, argc: Int, desc: String) =
        MountEngine.SimpleTask(
            id = id, className = cls, methodName = method, paramCount = argc, returnType = "void",
            description = desc,
        )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val pending = mutableListOf<MountEngine.SimpleTask>()

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_PUSH)) {
            pending += listOf(
                task("market-tamer:push.service.c", "$PKG.base.support.push.honor.AppMarketPushMessageService", "c", 1, "推送处理总入口（probePushType，非更新族→null）"),
                task("market-tamer:push.notif.a", "$PKG.base.support.push.notification.a", "a", 3, "ActivityPushNotificationService 分发（非更新族→null）"),
                task("market-tamer:push.topTrigger", "$PKG.business.notification.topnotify.TopNotifyManager", "trigger", 1, "顶栏推送 .EVENT→null"),
                task("market-tamer:push.topZ", "$PKG.business.notification.topnotify.TopNotifyManager", "z", 1, "顶栏推送 TopNotifyPushData→null"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_PUSH, "TamerPushHooks")
        }

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_MSG_CENTER)) {
            pending += listOf(
                // 16.1.11.302：tp5/rk4/v56 类已删（混淆名复用）、MsgGroupViewModel#l 已消失——只留两个活点
                // + Activity 级封锁（TamerActivityBlocker msg_center 表）。
                task("market-tamer:msgc.mgvm.k", "$PKG.msgcenterview.MsgGroupViewModel", "k", 1, "消息分组→null"),
                task("market-tamer:msgc.repo.y", "$PKG.msgcenterview.repository.a", "y", 2, "消息仓库→null"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_MSG_CENTER, "TamerPushHooks")
        }

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_UPDATE_NOTIFY)) {
            // 16.1.11.302：UpdateNotifyHandler 被混淆为根包 dda（.source "UpdateNotifyHandler.kt" 锚
            // + h(String,String)V 形状，adapt-report.md §5）；混淆名跨版本会漂，漂了 NOT_RESOLVED 跳过。
            pending += task("market-tamer:updnotify.h", "dda", "h", 2, "更新提醒通知 handler→null")
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_UPDATE_NOTIFY, "TamerPushHooks")
        }

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_ALL_NOTIFY)) {
            pending += task("market-tamer:allnotify.v", "$PKG.business.notification.controller.NotificationControllerImpl", "v", 4, "通知创建总入口→null")
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_ALL_NOTIFY, "TamerPushHooks")
        }

        if (pending.isEmpty()) return

        val session = MountEngine.open(
            "TamerPushHooks", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) { chain ->
                runCatching {
                    val blocked = when (t.id) {
                        "market-tamer:push.service.c" ->
                            chain.args.size == 1 && !isUpdateFamily(probePushType(chain.args[0]))
                        "market-tamer:push.notif.a" -> {
                            val svc = chain.args.getOrNull(1)
                            if (svc != null && className(svc).endsWith("ActivityPushNotificationService") &&
                                !isUpdateFamily(callMethod(svc, "D")?.toString())
                            ) true else null // null = 非目标分发，放行
                        }
                        "market-tamer:push.topTrigger" ->
                            className(chain.args.getOrNull(0)).endsWith(".EVENT")
                        "market-tamer:push.topZ" ->
                            className(chain.args.getOrNull(0)).endsWith("TopNotifyPushData")
                        else -> true // msg_center/update_notify/all_notify 族：无条件 null
                    }
                    if (blocked == true) {
                        session.judge(module, t, "market-tamer", "推送已拦")
                        null
                    } else {
                        chain.proceed()
                    }
                }.getOrElse {
                    module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer push 拦截异常（已吞，放行原方法）: ${it.message}")
                    chain.proceed()
                }
            }
        }
    }

    /** "1" 开头 = 更新提醒族放行（tamer 语义）。 */
    private fun isUpdateFamily(type: String?): Boolean = type != null && type.startsWith("1")

    /** tamer probePushType：getter 链 → JSON 字符串正则兜底。 */
    private fun probePushType(obj: Any?): String? {
        if (obj == null) return null
        callMethod(obj, "getPushNotifyType")?.let { return it.toString() }
        callMethod(obj, "pushNotifyType")?.let { return it.toString() }
        callMethod(obj, "action")?.let { return it.toString() }
        val s = obj.toString()
        for (key in listOf("pushNotifyType", "PushNotifyType", "action")) {
            val marker = "\"$key\":"
            val i = s.indexOf(marker)
            if (i >= 0) {
                var j = i + marker.length
                while (j < s.length && (s[j] == ' ' || s[j] == '"')) j++
                val sb = StringBuilder()
                while (j < s.length && s[j] != '"' && s[j] != ',' && s[j] != '}') {
                    sb.append(s[j]); j++
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        }
        return null
    }
}
