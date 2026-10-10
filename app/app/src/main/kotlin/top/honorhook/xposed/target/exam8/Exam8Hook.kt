package top.honorhook.xposed.target.exam8

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.target.TargetHook

/**
 * 成人高考 exam8（com.exam8.chengrenGK）「超级会员」解锁（F1）。
 *
 * 逆向定位（dexdump 实证，APK 5.9.0.0-release）：
 * 会员态由两个**未混淆** bean 承载，UI/特权判定都从它们读，全部走直连反射挂载（类名/方法名稳定，无需 DexKit 兜底）：
 * - [CLS_VIP_USER_STATUS] `com.exam8.newer.tiku.bean.VipUserStatus`：用户会员态。
 *   `VipType`(int) 档位 / `IsValid`(int) 是否有效 / `DaysToExpire`(int) / `ExpireDate`(String)。
 *   由 `MemberActivity$UserVipStatusRunnable` 拉取服务端响应并驱动「我的/会员页」UI（user_state_tv 显示「暂未开通」即读此）。
 * - [CLS_VIP_PRIVILEGE] `com.exam8.newer.tiku.bean.VipPrivilegeRes`：特权资源。
 *   `VipType`(String) 档位 / `HasVipPrivilege`(int) 是否拥有特权 / `UserVipLevel`(int) 等级，UI 特权展示与解锁判定读此。
 *
 * 后端档位编码见 `VipPrivilegeRes.setLevel1_1/2_1/4_1`（按 1/2/4 分组）：1=轻享 2=功勋 **4=超级**
 * ⇒ 强制 VipType 置 4（int 与 String 两处一并改，保持两 bean 一致）。
 *
 * 注意：这是**客户端**解锁——把本地会员态伪造为超级会员，翻转 UI 与客户端功能门。
 * 服务端强校验的能力（如真实付费音视频流、云端题库下发）仍由服务端把关，本 hook 不触及网络协议。
 */
object Exam8Hook : TargetHook {

    override val packageName = "com.exam8.chengrenGK"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("Exam8Hook", packageName)

    /** 超级会员档位 id（后端档位编码 1=轻享 2=功勋 4=超级；待真机诊断日志确认）。 */
    private const val SUPER_VIP_TYPE = 4

    private const val CLS_VIP_USER_STATUS = "com.exam8.newer.tiku.bean.VipUserStatus"
    private const val CLS_VIP_PRIVILEGE = "com.exam8.newer.tiku.bean.VipPrivilegeRes"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        val pending = mutableListOf<MountTask>()
        collectVipUserStatusHooks(pending)   // F1 中央会员态
        collectVipPrivilegeHooks(pending)    // F1 特权资源

        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** hook 编写时的宿主版本基线（dumpsys 实证 5.9.0.0-release）；OTA 超出时 MountEngine 首轮挂载打 WARN。 */
    private const val HOST_VERSION_BASELINE = "5.9.0.0-release"

    // ---------------- F1 VipUserStatus：中央会员态（驱动「我的/会员页」UI） ----------------

    private fun collectVipUserStatusHooks(pending: MutableList<MountTask>) {
        addConst(
            pending, CLS_VIP_USER_STATUS, "getVipType", 0, "int", SUPER_VIP_TYPE,
            "exam8:vipUserStatus.getVipType", "F1 会员档位置超级($SUPER_VIP_TYPE)",
        )
        addConst(
            pending, CLS_VIP_USER_STATUS, "getIsValid", 0, "int", 1,
            "exam8:vipUserStatus.getIsValid", "F1 会员态有效(IsValid=1)",
        )
        addConst(
            pending, CLS_VIP_USER_STATUS, "getDaysToExpire", 0, "int", 3650,
            "exam8:vipUserStatus.getDaysToExpire", "F1 剩余天数拉满(3650)",
        )
        addConst(
            pending, CLS_VIP_USER_STATUS, "getExpireDate", 0, "java.lang.String", "2099-12-31",
            "exam8:vipUserStatus.getExpireDate", "F1 到期日设远(2099-12-31)",
        )
    }

    // ---------------- F1 VipPrivilegeRes：特权资源（UI 特权展示/解锁判定） ----------------

    private fun collectVipPrivilegeHooks(pending: MutableList<MountTask>) {
        addConst(
            pending, CLS_VIP_PRIVILEGE, "getVipType", 0, "java.lang.String", "4",
            "exam8:vipPrivilege.getVipType", "F1 特权档位置超级(\"4\")",
        )
        addConst(
            pending, CLS_VIP_PRIVILEGE, "getHasVipPrivilege", 0, "int", 1,
            "exam8:vipPrivilege.getHasVipPrivilege", "F1 拥有会员特权(HasVipPrivilege=1)",
        )
        addConst(
            pending, CLS_VIP_PRIVILEGE, "getUserVipLevel", 0, "int", SUPER_VIP_TYPE,
            "exam8:vipPrivilege.getUserVipLevel", "F1 用户会员等级置超级($SUPER_VIP_TYPE)",
        )
    }

    // ---------------- 挂载执行：CONST 常量短路（解析/重试/汇总在共享 [engine]） ----------------

    private enum class MountKind { CONST }

    private class MountTask(
        val kind: MountKind,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        val value: Any?,
        override val id: String,
        override val description: String,
    ) : MountEngine.Task

    private fun addConst(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        value: Any?,
        id: String,
        description: String,
    ) {
        pending += MountTask(MountKind.CONST, className, methodName, paramCount, returnType, value, id, description)
    }

    /** 单个挂载任务执行（解析/重试/汇总在共享 [engine]；本函数只做 CONST 的 intercept 构造）。 */
    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result {
        val method = engine.resolveTask(task, module, loader) ?: return MountEngine.Result.NOT_RESOLVED
        return runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(task.id)
                .intercept { chain ->
                    logJudge(module, task)
                    if (DEBUG) {
                        // 诊断：取一次原值观测（确认 hook 命中且看真实档位编码；非会员原值多为 0）
                        runCatching { chain.proceed() }.onSuccess { orig ->
                            module.log(Log.DEBUG, MountEngine.TAG, "exam8 原值 ${task.id} = $orig → 强制 ${task.value}")
                        }
                    }
                    task.value
                }
        }.fold(
            onSuccess = { MountEngine.Result.OK },
            onFailure = { t ->
                module.log(Log.WARN, MountEngine.TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountEngine.Result.FAILED
            },
        )
    }

    /** 诊断日志开关（默认开；确认档位编码后改 false 收静音）。 */
    private const val DEBUG = true
    private val judgeLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 判据日志节流（每 task 前 2 次；真机核对「超级会员已解锁」用，不受 DEBUG 门控）。 */
    private fun logJudge(module: XposedModule, task: MountTask) {
        val n = judgeLogCount.merge(task.id, 1) { a, b -> a + b } ?: 1
        if (n <= 2) {
            module.log(Log.INFO, MountEngine.TAG, "exam8-hook 生效: ${task.id} — ${task.description}")
        }
    }
}
