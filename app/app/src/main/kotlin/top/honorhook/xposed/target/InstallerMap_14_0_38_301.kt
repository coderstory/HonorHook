package top.honorhook.xposed.target

import top.honorhook.FeatureCatalog
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.data.InstallerConfigTable

/**
 * 包管理器 14.0.38.301 专属挂载表（2026-10-20 反编译 installer-38 逐点重定位；手写表）。
 *
 * 与基线 14.0.37.301 的 11 点对照（**方法名/所在类整体漂移**是这一版的主特征）：
 * - 广告请求：`lt1.b(String)Z` → `p81.a(String)Z`（锚点串仍在，参数类型一致）。
 * - AB 请求：`sx1.Q(Z)V` → `kc1.u(Z)V`（锚点串仍在）。
 * - 云控 j：`CommonConfigManager.j(String)Z` → `e(String)Z`。
 * - 云控 h：`CommonConfigManager.h(Rsp)String` → `c(Rsp)String`。
 * - 云控 g()（无参懒加载读点）：新版本已内联消失（类内只剩 `c(Rsp)` 一个 String 返回点，即旧 h）
 *   → **删点**；`c()` 由 h 点覆盖，语义不变（都是强制安全模式 JSON）。
 * - 弹窗门：`n12.f` → `lc1.A1`（参数序列完全一致：int, SafeModeRise, RbiTempBean, boolean）。
 * - 提示卡：`r12.j` → `lc1.D1`（参数序列完全一致）。
 * - 底部推广卡：`r12.s(Activity, RbiTempBean)V` → `PackageInstallerActivity.B0()V`
 *   （新版本把逻辑内联进 Activity；锚点串 `setSafeModeBottomSheetShow() first return` 就在 B0 体内）。
 * - 联网安全检测：`ly1.h` 分发器整体重构为 HnAppCheckJob（`bd1`）→ 两条管线
 *   `ed1`（荣耀云）/ `id1`（通用），入口均为 `b(kd1, boolean)`；两处都挂，运行时只会命中其中一条
 *   （bd1 构造时按 isHn 二选一）。通过结果复用宿主静态工厂 `bd1.a(isHn)`，回调
 *   `c(dd1, vd1)`（= 宿主自己的 "无需检测" 路径），不构造任何伪造类型。
 * - 指纹：`qf2.g(Context, qf2$a)` → `lt1.w0(Context, cv1)`（TAG 锚点 PwdControlUtil 仍在；
 *   回调接口改名 `cv1`，清弹窗方法由 `c()` 变 `b()`，拦截侧已改为按签名找 0 参 void 方法）。
 *
 * 判据：真机 `installed 11/11`（两条检测管线都算挂载点；运行时只有一条会被调用）。
 */
internal val INSTALLER_POINTS_14_0_38_301: List<HookPoint> = listOf(
    HookPoint(
        id = "ad-request:AdCondition.checkIsSupportAdRequest",
        kind = "CONST",
        feature = FeatureCatalog.KEY_INSTALLER_AD,
        className = "p81",
        methodName = "a",
        paramCount = 1,
        returnType = "boolean",
        value = InstallerConfigTable.AD_REQUEST_NOT_SUPPORTED,
        dexStrings = listOf("checkIsSupportAdRequest() personalizedRecommendationSwitchOpen = false"),
        dexParamTypes = listOf("java.lang.String"),
        description = "R4 广告请求条件恒 false（lt1.b → p81.a；V3/V4 两条链路都不发请求）",
    ),
    HookPoint(
        id = "ad-source:AdRespV4.getAdAppList",
        kind = "CONST",
        feature = FeatureCatalog.KEY_INSTALLER_AD,
        className = "com.hihonor.packageinstaller.entity.resp.AdRespV4",
        methodName = "getAdAppList",
        paramCount = 0,
        returnType = "java.util.List",
        value = emptyList<Any>(),
        description = "R1 安装页广告响应数据源恒空表（本版未漂移，保留直连）",
    ),
    HookPoint(
        id = "ab-request:AbExpUtils.updateAbExpStrategy",
        kind = "BLOCK",
        feature = FeatureCatalog.KEY_INSTALLER_AD,
        className = "kc1",
        methodName = "u",
        paramCount = 1,
        returnType = "void",
        dexStrings = listOf("updateAbExpStrategy() network close"),
        dexParamTypes = listOf("boolean"),
        description = "R4 AB 实验请求不发（sx1.Q → kc1.u；唯一调用点 InstallStart/wb2）",
    ),
    HookPoint(
        id = "installer:CommonConfig.isNewTaskStartInstallBySourcePkg",
        kind = "CONST",
        feature = FeatureCatalog.KEY_INSTALLER_NO_INTERCEPT,
        className = "com.hihonor.packageinstaller.hncust.commonconfig.CommonConfigManager",
        methodName = "e",
        paramCount = 1,
        returnType = "boolean",
        value = InstallerConfigTable.THIRD_PARTY_JUMP_NOT_INTERCEPTED,
        dexStrings = listOf("isNewTaskStartInstallBySourcePkg"),
        dexParamTypes = listOf("java.lang.String"),
        description = "R2 三方安装异常跳转拦截名单不命中（j → e，恒 false）",
    ),
    HookPoint(
        id = "installer:CommonConfig.getSafeModeConfigJsonFromRsp",
        kind = "CONST",
        feature = FeatureCatalog.KEY_INSTALLER_NO_INTERCEPT,
        className = "com.hihonor.packageinstaller.hncust.commonconfig.CommonConfigManager",
        methodName = "c",
        paramCount = 1,
        returnType = "java.lang.String",
        value = InstallerConfigTable.SAFE_MODE_ALL_OFF_JSON,
        dexStrings = listOf("initGetSafeModeConfig"),
        dexParamTypes = listOf("com.hihonor.packageinstaller.entity.resp.CommonConfigResponse"),
        description = "R3 安全模式默认全关 JSON（h → c；网络响应即时应用点，兼覆盖已消失的无参读点 g()）",
    ),
    HookPoint(
        id = "installer:SafeModeDialogGate.isShowGuideOpenSafeModeDialog",
        kind = "CONST",
        feature = FeatureCatalog.KEY_INSTALLER_NO_SAFE_PROMPT,
        className = "lc1",
        methodName = "A1",
        paramCount = 4,
        returnType = "boolean",
        value = InstallerConfigTable.SAFE_MODE_GUIDE_DIALOG_NOT_SHOWN,
        dexStrings = listOf("isShowGuideOpenSafeModeDialog input params not available. triggerType = "),
        dexParamTypes = listOf(
            "int",
            "com.hihonor.packageinstaller.entity.resp.SafeModeRise",
            "com.hihonor.packageinstaller.rbi.RbiTempBean",
            "boolean",
        ),
        description = "R5 安全模式引导弹窗不展示（n12.f → lc1.A1，参数序列一致）",
    ),
    HookPoint(
        id = "installer:SafeModeCard.isSupportSafeModeCardShow",
        kind = "CONST",
        feature = FeatureCatalog.KEY_INSTALLER_NO_SAFE_PROMPT,
        className = "lc1",
        methodName = "D1",
        paramCount = 4,
        returnType = "boolean",
        value = InstallerConfigTable.SAFE_MODE_CARD_NOT_SHOWN,
        dexStrings = listOf("isSupportSafeModeCardShow() pageIndex="),
        dexParamTypes = listOf(
            "com.hihonor.packageinstaller.utils.CardTipsRecord",
            "java.lang.String",
            "boolean",
            "java.lang.String",
        ),
        description = "R5 安装页安全模式提示卡不展示（r12.j → lc1.D1，参数序列一致）",
    ),
    HookPoint(
        id = "installer:SafeModeCard.setSafeModeBottomSheetShow",
        kind = "BLOCK",
        feature = FeatureCatalog.KEY_INSTALLER_NO_SAFE_PROMPT,
        className = "com.android.packageinstaller.PackageInstallerActivity",
        methodName = "B0",
        paramCount = 0,
        returnType = "void",
        dexStrings = listOf("setSafeModeBottomSheetShow() first return"),
        description = "R5 安全模式底部推广卡不展示（r12.s → Activity.B0()V 内联；空实现阻断）",
    ),
    HookPoint(
        id = "installer:SecurityCheck.bypass",
        kind = "CHECK_BYPASS_JOB",
        feature = FeatureCatalog.KEY_INSTALLER_SKIP_CHECK,
        className = "ed1",
        methodName = "b",
        paramCount = 2,
        returnType = "void",
        value = true, // isHn 兜底值（正常路径读 bd1.a）
        dexStrings = listOf("startCheck()"),
        dexParamTypes = listOf("kd1", "boolean"),
        description = "R6 联网安全检测短路·荣耀云管线（HnAppCheckJob → ed1.b；回调 bd1.a(true) 通过结果）",
    ),
    HookPoint(
        id = "installer:SecurityCheck.bypass.generic",
        kind = "CHECK_BYPASS_JOB",
        feature = FeatureCatalog.KEY_INSTALLER_SKIP_CHECK,
        className = "id1",
        methodName = "b",
        paramCount = 2,
        returnType = "void",
        value = false,
        dexStrings = listOf("startCheck()"),
        dexParamTypes = listOf("kd1", "boolean"),
        description = "R6 联网安全检测短路·通用管线（HnAppCheckJob → id1.b；回调 bd1.a(false) 通过结果）",
    ),
    HookPoint(
        id = "installer:FingerprintAuth.bypass",
        kind = "FINGERPRINT_BYPASS",
        feature = FeatureCatalog.KEY_INSTALLER_SKIP_CHECK,
        className = "lt1",
        methodName = "w0",
        paramCount = 2,
        returnType = "void",
        dexClassStrings = listOf("PwdControlUtil"),
        dexParamTypes = listOf("android.content.Context", "cv1"),
        description = "R6 安装指纹校验直接通过（qf2.g → lt1.w0；回调 b()+a(true)）",
    ),
)
