package top.honorhook.xposed.target.exam8

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
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
 *
 * F2「阅后即焚」（YHJF=14）见 [collectBurnAfterReadingHooks]：短路 `VipUtils.hasPrivilege(14)` 过 UI/功能门。
 * 该功能另有**单次付费**路径 `orderState == 1`（服务端订单态），本 hook 不涉及其校验。
 */
object Exam8Hook : TargetHook {

    override val packageName = "com.exam8.chengrenGK"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("Exam8Hook", packageName)

    /** 超级会员档位 id（后端档位编码 1=轻享 2=功勋 4=超级；待真机诊断日志确认）。 */
    private const val SUPER_VIP_TYPE = 4

    private const val CLS_VIP_USER_STATUS = "com.exam8.newer.tiku.bean.VipUserStatus"
    private const val CLS_VIP_PRIVILEGE = "com.exam8.newer.tiku.bean.VipPrivilegeRes"

    /** F2 阅后即焚：特权位 [VipUtils.YHJF]，见 `com.exam8.tiku.util.VipUtils.hasPrivilege(int)`。 */
    private const val CLS_VIP_UTILS = "com.exam8.tiku.util.VipUtils"
    private const val PRIVILEGE_BURN_AFTER_READING = 14

    /** 剩余天数拉满值（覆盖 F2 `hasPrivilege` 的 `RemainDays >= 0` 与展示）。 */
    private const val FAR_FUTURE_DAYS = 3650

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        val pending = mutableListOf<MountTask>()
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_EXAM8_SUPER_VIP)) {
            collectVipUserStatusHooks(pending)   // F1 中央会员态
            collectVipPrivilegeHooks(pending)    // F1 特权资源
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_EXAM8_BURN)) {
            collectBurnAfterReadingHooks(pending) // F2 阅后即焚特权门
        }

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

    // ---------------- F2 阅后即焚（YHJF=14）：特权门 + 剩余天数 ----------------

    /**
     * F2「阅后即焚」解锁。
     *
     * 逆向定位（jadx 实证，APK 5.9.0.0-release）：页面 `com.exam8.newer.tiku.test_activity.ExamSprintFragment`
     * 内所有「是否已解锁」判定都是 `VipUtils.hasPrivilege(14) || orderState == 1`——
     * 前者是**会员特权覆盖**（本次目标），后者是**单次付费订单**（`立即抢购` 买的就是它，服务端按 UserId 查订单表）。
     *
     * `hasPrivilege(int)` 原始实现：
     * `HasSubjectConfig() && getVipLevelByPrivilege(i) > 0 && VipPrivilege.getUserVipLevel() >= 该等级 && getRemainDays() >= 0`
     * 其中 `getVipLevelByPrivilege` 依赖**服务端下发**的 `VipSubjectConfig`（非会员时可能不含 14），
     * 故本组直接短路 hasPrivilege，并补 `RemainDays`/`HasSubjectConfig` 兜底。
     *
     * 边界（未实测，不打包票）：`orderState` 由 `http://api.wantiku.com/api/Sprint/Papers?UserId=&SubjectId=`
     * 下发，属服务端订单态。若服务端在 hasPrivilege 为真时仍按订单卡内容下放，则本组只能过 UI 门、拿不到题目正文。
     * 真机判据：进「阅后即焚」点试卷，看题目/答案是否完整（UI 门过但正文空 = 服务端锁死）。
     */
    private fun collectBurnAfterReadingHooks(pending: MutableList<MountTask>) {
        // 主门：仅对特权位 14（阅后即焚）短路 true；其他特权位放行原逻辑，避免误伤无关功能判定
        addConditional(
            pending, CLS_VIP_UTILS, "hasPrivilege", 1, "boolean", true, PRIVILEGE_BURN_AFTER_READING,
            "exam8:vipUtils.hasPrivilege", "F2 阅后即焚特权门(14)=true",
        )
        // 兜底：剩余天数（hasPrivilege 第四个条件 & 「VIP 有效期」展示）
        addConst(
            pending, CLS_VIP_PRIVILEGE, "getRemainDays", 0, "int", FAR_FUTURE_DAYS,
            "exam8:vipPrivilege.getRemainDays", "F2 剩余天数拉满($FAR_FUTURE_DAYS)",
        )
        // 兜底：学科配置开关（hasPrivilege 第一条件）
        addConst(
            pending, CLS_VIP_PRIVILEGE, "getHasSubjectConfig", 0, "boolean", true,
            "exam8:vipPrivilege.getHasSubjectConfig", "F2 学科配置开关=true",
        )
    }

    // ---------------- 挂载执行：CONST 常量短路 / CONDITIONAL 按参短路（解析/重试/汇总在共享 [engine]） ----------------

    private enum class MountKind { CONST, CONDITIONAL }

    private class MountTask(
        val kind: MountKind,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        val value: Any?,
        /** CONDITIONAL 专用：仅当第 0 参等于此值才短路，否则放行原方法。 */
        val matchArg: Int?,
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
        pending += MountTask(MountKind.CONST, className, methodName, paramCount, returnType, value, null, id, description)
    }

    /** 带参短路：仅第 0 参 == [matchArg] 时返回 [value]，其余走原逻辑。 */
    private fun addConditional(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        value: Any?,
        matchArg: Int,
        id: String,
        description: String,
    ) {
        pending += MountTask(MountKind.CONDITIONAL, className, methodName, paramCount, returnType, value, matchArg, id, description)
    }

    /**
     * 单个挂载任务执行：CONST / CONDITIONAL 的 intercept 逻辑交给共享 [MountEngine.mount]
     * （解析 / 异常兜底 / 结果分级都在那边）。
     */
    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // CONDITIONAL：参数未命中则放行原方法（只短路目标特权位，不误伤其他判定）
            val missed = task.kind == MountKind.CONDITIONAL &&
                (chain.args.firstOrNull() as? Int) != task.matchArg
            if (missed) {
                chain.proceed()
            } else {
                engine.judge(module, task, JUDGE_PREFIX)
                if (DEBUG) {
                    // 诊断原值：仅每个 task 首次命中时取一次（节流，避免列表滚动刷屏）
                    logOrigOnce(module, task, chain)
                }
                task.value
            }
        }

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "exam8-hook"

    /** 原值诊断开关（默认关；置 true 时每 task 首次命中多付一次 proceed 打原值，核对档位编码用）。 */
    private const val DEBUG = false
    private val origLogCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 原值诊断节流（每 task 仅首次命中取一次原值；后续命中直接放行不取原值，省日志与 proceed 开销）。 */
    private fun logOrigOnce(module: XposedModule, task: MountTask, chain: XposedInterface.Chain) {
        val n = origLogCount.merge(task.id, 1) { a, b -> a + b } ?: 1
        if (n > 1) return
        runCatching { chain.proceed() }.onSuccess { orig ->
            module.log(Log.DEBUG, MountEngine.TAG, "exam8 原值 ${task.id} = $orig → 强制 ${task.value}")
        }
    }
}
