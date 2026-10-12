package top.honorhook.xposed.target.ime

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 搜狗输入法荣耀版 com.sohu.inputmethod.sogou.honor（基线 10.32.44.202608251503，
 * /product_h/region_comm/china/app/SogouInput_for_Honor，第 8 目标，T4 重启票）。
 *
 * R3 最终形态：键盘候选区「短信验证码一键填充」在**不同意搜狗联网服务**的前提下可用。
 *
 * 链路（jadx 实证，反编译产物在 /tmp/autoregsms/，功能本体全程本地）：
 *   c52(ContentObserver content://sms inbox-insert) → SettingManager.X5()
 *   → tr5 查 sms/inbox 最近 60s → nq6.e 本地关键词+正则提码
 *   → OemAppServiceImpl.notifyVerificationCodeReceived → h20 候选区展示。
 *   运行链**无**联网服务再查；唯一耦合在写侧：OemAppServiceImpl.onPrivacyAgreedChanged(false)
 *   → SettingManager.zf(false) 强擦 pref_smscode_to_canidate（X5 的存储值）。
 *
 * 挂载点（窄门）：`zf(Z,Z)V`（X5 唯一写封装，写 pref_smscode_to_canidate）。全 app 调用点
 * 共 5 处（jadx grep 实证）：4 处 true（用户意图流：READ_SMS 授权回调 e7、场景引导
 * SMSVerificationCodeScenarioGuide、sogou/b.java）原样放行；唯一 false 写点 =
 * 联网服务拒绝擦除 → 改写为 true。用户自己的关开关路径不走 zf（设置页 SwitchPreference
 * 直接落盘 false），不受影响。其余云功能（i66/剪贴板/AppStore/智能填写 AutoFillFormManager
 * 等）各自的 W7() 同意门**原样保留**——本 hook 不替用户同意任何条款。
 *
 * 已知显示层瑕疵（不 hook，宁漏杀不误杀）：设置页 KeyboardSettingsFragment.O0 的勾选框
 * 显示态含 W7()（联网同意位）参与计算——联网服务未同意时页面勾选框可能显示为关，但功能
 * 由存储值驱动，chip 照常工作。
 *
 * 判据：重启键盘进程（或切输入法往返）→ `dev.sh modlog` 出 `ImeHook: installed 1/1`；
 * 翻转候选验证码开关/拒绝联网服务事件触发时出 `ime-hook 生效` 行。
 */
object ImeHook : TargetHook {

    override val packageName = "com.sohu.inputmethod.sogou.honor"

    private val engine = MountEngine("ImeHook", packageName)

    /**
     * X5 的唯一写封装。jadx 实证（SettingManager.java:5446）：`public void zf(boolean z,
     * boolean z2)` → `k.putBoolean(getString(f05.U8), z)`，f05.U8 = "pref_smscode_to_canidate"
     * （资源 0x7f101419 解码）。方法名 zf 为 R8 混淆名，随 IME 版本漂移 → 失配时诚实
     * NOT_RESOLVED，按版本适配流程补新名，不猜。
     */
    private const val CLS_SETTING_MANAGER = "com.sogou.bu.basic.settings.SettingManager"

    /** 剪贴板候选读数（ClipboardServiceImpl.java:59 实证 getClipboard2Candidate → u10.d().b()）。 */
    private const val CLS_CLIPBOARD_IMPL = "com.sogou.inputmethod.clipboard.impl.ClipboardServiceImpl"

    /** 剪贴板候选存储直读类（defpackage 混淆名，随 IME 版本漂移，失配诚实 NOT_RESOLVED）。 */
    private const val CLS_U10 = "u10"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_IME_CODE_FREE)) {
            logDisabled(module, FeatureCatalog.KEY_IME_CODE_FREE, "ImeHook")
            return
        }
        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)
        engine.attemptMount(
            listOf(
                MountTask(
                    className = CLS_SETTING_MANAGER,
                    methodName = "zf",
                    paramCount = 2,  // 签名实证：(boolean z, boolean z2) —— SettingManager.java:5446
                    returnType = "void",
                    id = ID_ZF,
                    description = "T4：验证码候选 pref 免联网服务擦除（zf(false)→true，仅此 pref，其余同意门原样）",
                ),
                MountTask(
                    className = CLS_SETTING_MANAGER,
                    methodName = "X5",
                    paramCount = 0,  // 签名实证：()Z —— SettingManager.java:2596
                    returnType = "boolean",
                    id = ID_X5,
                    description = "T4：验证码候选功能读数强制 true（用户 2026-10-12 拍板强制开启；不落盘、不改同意态，开关关即复原）",
                ),
                MountTask(
                    className = CLS_CLIPBOARD_IMPL,
                    methodName = "getClipboard2Candidate",
                    paramCount = 0,  // 签名实证：()Z —— ClipboardServiceImpl.java:59
                    returnType = "boolean",
                    id = ID_CLIP,
                    description = "T4：剪贴板候选读数强制 true（同上，读数层强制，未装判断/同意框全不涉）",
                ),
                MountTask(
                    // h20.k 验证码 chip 门（h20.java:131）：直接调 u10.d().b()，绕过 Service 包装 ——
                    // 必须在存储读数层强制，否则 chip 门仍被设置页关态挡死
                    className = CLS_U10,
                    methodName = "b",
                    paramCount = 0,  // 签名实证：()Z —— u10.java:48（talkback 检查在原方法内，proceed 后覆盖）
                    returnType = "boolean",
                    id = ID_U10B,
                    description = "T4：剪贴板候选存储读数强制 true（h20.k 直读点）",
                ),
                MountTask(
                    // h20.k 第二门（h20.java:131 SettingManager.A4）—— 候选增强开关，默认 true，
                    // 防历史/云端写关；同为读数强制
                    className = CLS_SETTING_MANAGER,
                    methodName = "A4",
                    paramCount = 0,  // 签名实证：()Z —— SettingManager.java:447
                    returnType = "boolean",
                    id = ID_A4,
                    description = "T4：候选增强开关读数强制 true（默认本 true，防写关）",
                ),
            ),
            module,
            loader,
        ) { task -> mountOne(task, module, loader) }
    }

    /** hook 编写时的 IME 版本基线（pm dump 实证 versionName，设备 AAK-AN00 2026-10-12）。 */
    private const val HOST_VERSION_BASELINE = "10.32.44.202608251503"

    private class MountTask(
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val id: String,
        override val description: String,
    ) : MountEngine.Task

    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            when (task.id) {
                ID_ZF -> {
                    val args = chain.args?.toTypedArray()
                        ?: return@mount chain.proceed()  // 取不到参数骨架：原样放行，宁漏杀不误杀
                    val orig = args.getOrNull(0) as? Boolean
                    if (orig == false) {
                        // 唯一 false 写点 = 联网服务拒绝擦除（OemAppServiceImpl.onPrivacyAgreedChanged）。
                        // 改写 true：拒绝同意不再吞掉验证码候选 pref；用户自关走设置页直落盘，不经此。
                        args[0] = true
                        engine.judge(module, task, JUDGE_PREFIX, "consent 擦除已拦截（pref_smscode_to_canidate 保活）")
                    }
                    chain.proceedWith(chain.thisObject, args)
                }
                else -> {
                    // 读数强制（ID_X5 / ID_CLIP）：proceed 跑原方法（副作用保留）后覆盖返回值。
                    // 显示层瑕疵：设置页勾选框显示态含同意位参与计算，可能仍显示关——功能由运行链读数驱动，chip 照常。
                    chain.proceed()
                    engine.judge(module, task, JUDGE_PREFIX, "读数强制 true")
                    true
                }
            }
        }

    /** 判据日志前缀（真机 modlog 按 `ime-hook` 过滤）。 */
    private const val JUDGE_PREFIX = "ime-hook"

    private const val ID_ZF = "ime:SettingManager.zf"
    private const val ID_X5 = "ime:SettingManager.X5"
    private const val ID_CLIP = "ime:ClipboardServiceImpl.getClipboard2Candidate"
    private const val ID_U10B = "ime:u10.b"
    private const val ID_A4 = "ime:SettingManager.A4"
}
