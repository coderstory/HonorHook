package top.honorhook.xposed.target

import top.honorhook.xposed.data.HookPoint

/**
 * 包管理器 14.0.22.326 专属挂载表（2026-10-11 反编译 PI_14022 逐点重定位；手写表）。
 *
 * 基线 14.0.37.301 的 11 点 → 本版 8 点 + 3 点无对应物：
 * - 云控 3 点（CommonConfigManager.j/g/h，R2/R3）：旧版无 `hncust/commonconfig` 模块，
 *   三方跳转拦截名单与安全模式云控默认值机制不存在 → 诚实跳过。
 * - 广告数据源：`AdRespV4.getAdAppList` 旧版世代为 `AdRespV2.getLocalUseAdInfos`（类漂移，同语义）。
 * - AB 请求：`sx1.Q(Z)V` 旧版为 `lq0.r()V`（方法名/签名漂移；锚点串三处全在 r() 内实证）。
 * - 安全弹窗门：`n12.f` → `is0.c`（方法名漂移，参数序列一致）。
 * - 提示卡：`r12.j`（4 参）→ `js0.f`（3 参，参数结构漂移）。
 * - 推广卡：`r12.s` → `js0.k`（方法名漂移）。
 * - 检测分发器：`ly1.h(boolean, sy1)`（回调式）→ `br0.b(Z, String, String, String):er0`
 *   （返回式；通过结果用同类静态工厂 `br0.a(Z)` 构造，包管理器 PackageInstallerActivity:6968 唯一调用点）。
 * - 指纹：`qf2.g` → `c01.d`（方法名漂移，PwdControlUtil TAG 类级锚点仍在）。
 */
internal val INSTALLER_POINTS_14_0_22_326: List<HookPoint> = listOf(
    HookPoint(
        id = "ad-request:AdCondition.checkIsSupportAdRequest",
        kind = "CONST",
        feature = "installer.ad",
        className = "no0",
        methodName = "b",
        paramCount = 1,
        returnType = "boolean",
        value = false,
        dexStrings = listOf("checkIsSupportAdRequest() oobeSwitch = false"),
        dexParamTypes = listOf("java.lang.String"),
        description = "R4 广告请求条件恒 false（wt1 实现类漂移为 no0；V3/V4 链路均不发请求）",
    ),
    HookPoint(
        id = "ad-source:AdRespV2.getLocalUseAdInfos",
        kind = "CONST",
        feature = "installer.ad",
        className = "com.hihonor.packageinstaller.entity.resp.AdRespV2",
        methodName = "getLocalUseAdInfos",
        paramCount = 0,
        returnType = "java.util.List",
        value = emptyList<Any>(),
        description = "R1 安装页广告数据源恒空表（AdRespV4 世代前身为 AdRespV2；唯一消费点 jy0:852 空表走不渲染分支）",
    ),
    HookPoint(
        id = "ab-request:AbExpUtils.updateAbExpStrategy",
        kind = "BLOCK",
        feature = "installer.ad",
        className = "lq0",
        methodName = "r",
        paramCount = 0,
        returnType = "void",
        dexStrings = listOf("updateAbExpStrategy() network close"),
        description = "R4 AB 实验请求不发（sx1.Q 旧版为 lq0.r()V；PackageInstallerActivity:10976 唯一调用点）",
    ),
    HookPoint(
        id = "installer:SafeModeDialogGate.isShowGuideOpenSafeModeDialog",
        kind = "CONST",
        feature = "installer.no_safe_prompt",
        className = "is0",
        methodName = "c",
        paramCount = 4,
        returnType = "boolean",
        value = false,
        dexStrings = listOf("isShowGuideOpenSafeModeDialog input params not available. triggerType = "),
        dexParamTypes = listOf(
            "int",
            "com.hihonor.packageinstaller.entity.resp.SafeModeRise",
            "com.hihonor.packageinstaller.rbi.RbiTempBean",
            "boolean",
        ),
        description = "R5 安全模式引导弹窗不展示（n12.f 旧版为 is0.c，参数序列一致）",
    ),
    HookPoint(
        id = "installer:SafeModeCard.isSupportSafeModeCardShow",
        kind = "CONST",
        feature = "installer.no_safe_prompt",
        className = "js0",
        methodName = "f",
        paramCount = 3,
        returnType = "boolean",
        value = false,
        dexStrings = listOf("isSupportSafeModeCardShow() pageIndex="),
        dexParamTypes = listOf(
            "com.hihonor.packageinstaller.utils.CardTipsRecord",
            "java.lang.String",
            "boolean",
        ),
        description = "R5 安装页安全模式提示卡不展示（r12.j 4 参 → js0.f 3 参，尾部 String 参数移除）",
    ),
    HookPoint(
        id = "installer:SafeModeCard.setSafeModeBottomSheetShow",
        kind = "BLOCK",
        feature = "installer.no_safe_prompt",
        className = "js0",
        methodName = "k",
        paramCount = 2,
        returnType = "void",
        dexStrings = listOf("setSafeModeBottomSheetShow() first return"),
        dexParamTypes = listOf(
            "com.android.packageinstaller.PackageInstallerActivity",
            "com.hihonor.packageinstaller.rbi.RbiTempBean",
        ),
        description = "R5 安全模式底部推广卡不展示（r12.s 旧版为 js0.k，空实现阻断）",
    ),
    HookPoint(
        id = "installer:SecurityCheck.bypass",
        kind = "CHECK_BYPASS_RET",
        feature = "installer.skip_check",
        className = "br0",
        methodName = "b",
        paramCount = 4,
        returnType = "er0",
        description = "R6 联网安全检测直通（ly1.h 回调式 → br0.b 返回式：不调原方法，经静态工厂 br0.a(isHn) 构造通过结果返回）",
    ),
    HookPoint(
        id = "installer:FingerprintAuth.bypass",
        kind = "FINGERPRINT_BYPASS",
        feature = "installer.skip_check",
        className = "c01",
        methodName = "d",
        paramCount = 2,
        returnType = "void",
        dexClassStrings = listOf("PwdControlUtil"),
        dexParamTypes = listOf("android.content.Context", "c01\$a"),
        description = "R6 安装指纹校验直接通过（qf2.g 旧版为 c01.d；回调 c()+a(true) 语义不变）",
    ),
)
