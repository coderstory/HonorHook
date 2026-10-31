package top.honorhook.xposed.target.ime

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook

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
 * 判据：重启键盘进程（或切输入法往返）→ `dev.sh modlog` 出 `ImeHook: installed N/M`（全开 = 6 点）；
 * 翻转候选验证码开关/拒绝联网服务事件触发时出 `ime-hook 生效` 行。
 *
 * T5 开屏广告（2026-10-27，逆向档案 docs/reverse/sogou-ime.md §一）：搜狗自有启动页
 * `SogouLauncherActivity.onCreate` 唯一决策点 = `px5.b(Context)Z`（"canShowAds = " 日志串所在，
 * xref 实证全 app 仅此一处调用）；false 分支干净（Handler msg 1 直达主界面，不建 SplashScreen、
 * 不请求荣耀 adsdk 开屏 slotId 1900110810602143744）。读数强制 false = 单点收口，与
 * `SettingManager.W7()` 隐私同意门解耦（不碰同意态）。px5 为 R8 名随版本漂移 → DexHint
 * 类级锚（px5 beacon 面独占字符串组合 press/network/confirm/cancel，不含 paramTypes——
 * 踩坑见 CLS_SPLASH_GATE 处 DexHint 注释），唯一胜出才采纳；漂移时诚实 NOT_RESOLVED。
 */
object ImeHook : TargetHook {

    override val packageName = "com.sohu.inputmethod.sogou.honor"


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

    /**
     * T5 开屏广告总闸（"canShowAds = " 的唯一数据源）。px5 为 R8 混淆名，随 IME 版本漂移 →
     * 直连失败走 DexHint 宽松轨（类级字符串锚，不含 paramTypes——踩坑见 DexHint 处注释），
     * 失配诚实 NOT_RESOLVED，不猜新名。
     */
    private const val CLS_SPLASH_GATE = "px5"

    /**
     * T6 商店去推广：postSuccess(pv, String) 是**所有** API JSON 响应的唯一入口
     * （base 类 onResponse 内调用，子类无法绕过；与 (pv,JSONObject) 重载同签名，
     * 挂载层命中其一即生效——实测 String 重载命中）。
     */
    private const val CLS_OK_CALLBACK = "com.sogou.http.okhttp.a"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        // 闸门 = 功能开关（引擎按 task.feature 过滤，M = 开关过滤后的点数；与 LauncherHook 同款）。
        val session = MountEngine.open("ImeHook", packageName, param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName), RemoteConfig)
        session.attemptMount(
            listOf(
                MountEngine.SimpleTask(
                    className = CLS_SETTING_MANAGER,
                    methodName = "zf",
                    paramCount = 2,  // 签名实证：(boolean z, boolean z2) —— SettingManager.java:5446
                    returnType = "void",
                    id = ID_ZF,
                    description = "T4：验证码候选 pref 免联网服务擦除（zf(false)→true，仅此 pref，其余同意门原样）",
                    feature = FeatureCatalog.KEY_IME_CODE_FREE,
                ),
                MountEngine.SimpleTask(
                    className = CLS_SETTING_MANAGER,
                    methodName = "X5",
                    paramCount = 0,  // 签名实证：()Z —— SettingManager.java:2596
                    returnType = "boolean",
                    id = ID_X5,
                    description = "T4：验证码候选功能读数强制 true（用户 2026-10-12 拍板强制开启；不落盘、不改同意态，开关关即复原）",
                    feature = FeatureCatalog.KEY_IME_CODE_FREE,
                ),
                MountEngine.SimpleTask(
                    className = CLS_CLIPBOARD_IMPL,
                    methodName = "getClipboard2Candidate",
                    paramCount = 0,  // 签名实证：()Z —— ClipboardServiceImpl.java:59
                    returnType = "boolean",
                    id = ID_CLIP,
                    description = "T4：剪贴板候选读数强制 true（同上，读数层强制，未装判断/同意框全不涉）",
                    feature = FeatureCatalog.KEY_IME_CODE_FREE,
                ),
                MountEngine.SimpleTask(
                    // h20.k 验证码 chip 门（h20.java:131）：直接调 u10.d().b()，绕过 Service 包装 ——
                    // 必须在存储读数层强制，否则 chip 门仍被设置页关态挡死
                    className = CLS_U10,
                    methodName = "b",
                    paramCount = 0,  // 签名实证：()Z —— u10.java:48（talkback 检查在原方法内，proceed 后覆盖）
                    returnType = "boolean",
                    id = ID_U10B,
                    description = "T4：剪贴板候选存储读数强制 true（h20.k 直读点）",
                    feature = FeatureCatalog.KEY_IME_CODE_FREE,
                ),
                MountEngine.SimpleTask(
                    // h20.k 第二门（h20.java:131 SettingManager.A4）—— 候选增强开关，默认 true，
                    // 防历史/云端写关；同为读数强制
                    className = CLS_SETTING_MANAGER,
                    methodName = "A4",
                    paramCount = 0,  // 签名实证：()Z —— SettingManager.java:447
                    returnType = "boolean",
                    id = ID_A4,
                    description = "T4：候选增强开关读数强制 true（默认本 true，防写关）",
                    feature = FeatureCatalog.KEY_IME_CODE_FREE,
                ),
                MountEngine.SimpleTask(
                    className = CLS_SPLASH_GATE,
                    methodName = "b",
                    paramCount = 1,  // 签名实证：(Landroid/content/Context;)Z —— px5.smali（MT MCP 工作区 3npsn0lh）
                    returnType = "boolean",
                    id = ID_SPLASH_GATE,
                    description = "T5：开屏广告总闸 canShowAds 读数强制 false（onCreate 走无广告分支，荣耀 adsdk 请求体全 app 仅此一处消费）",
                    dex = MountEngine.DexHint(
                        // b() 方法体零字符串（纯读判定），锚在类级：px5 的 beacon 面（e/g/h）独占
                        // 这组 URL 参数串，四串同行出现 = 强特征。**不传 paramTypes**：直连轨的
                        // 参数比对走 canonicalTypeName（只归一包装类型，不剥 L…; 描述符），描述符
                        // 形式会把 b 静默排除（2026-10-27 真机踩坑：installed 5/6 即此因）；宽松轨
                        // loose() 不消费 paramTypes，传了也白传。当前版本直连「名 b + 单参 + 返 Z」
                        // 在 px5 内唯一，无需参数锚。已知局限：类名漂移后宽松轨 L3（类级查询）会把
                        // <init>/<clinit> 一并带回，getMethodInstance 对构造器必炸 → 候选集永不完整
                        // ⇒ 未来版本适配需先修 DexKitHelper 的构造器确定性排除（基建项，单独清单）。
                        classStrings = listOf("press", "network", "confirm", "cancel"),
                    ),
                    feature = FeatureCatalog.KEY_IME_SPLASH_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    // T6a：套装列表条目 setAdData 禁用——AMS 信息流广告附到条目上渲染，
                    // 切断数据即无广告位（真机实证：抖音极速版推广混在套装列表）
                    className = CLS_COSTUME_ITEM,
                    methodName = "setAdData",
                    paramCount = 1,
                    returnType = "void",
                    id = ID_AD_SETADDATA,
                    description = "T6a：套装列表条目 AMS 广告数据禁用",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    // T6b：商店首页 AMS 信息流回调清空——AdBean 列表置空后页面无广告混排
                    className = CLS_MAINPAGE_AD_CB,
                    methodName = "a",
                    paramCount = 1,
                    returnType = "void",
                    id = ID_AD_FEED,
                    description = "T6b：商店首页 AMS 信息流广告列表清空",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    // 套装安装兼容：phoneTheme.ini 字段 G()/H() 白名单矫正——
                    // COS 商店文件 G()=2 超 {0,1} 白名单 → cg4 f(20)「暂不支持当前设备」；
                    // H() 空/超限 → f(21)。真机判据实证（G()=2 矫正为 1）。
                    className = "zf4",
                    methodName = "G",
                    paramCount = 0,
                    returnType = "java.lang.String",
                    id = ID_INI_G,
                    description = "套装安装：phoneTheme.ini G() 白名单矫正",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    className = "zf4",
                    methodName = "H",
                    paramCount = 0,
                    returnType = "java.lang.String",
                    id = ID_INI_H,
                    description = "PoC：phoneTheme.ini H() 字段白名单矫正",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    className = CLS_THEME_PREVIEW,
                    methodName = "r1",
                    paramCount = 4,  // 签名实证：(D I String Z)I —— ThemePreviewActivity.java:2602（jadx）
                    returnType = "int",
                    id = ID_SKIN_R1,
                    description = "PoC：付费状态机 r1 恒返 1(已购)，验证付费门仅客户端 UI 层",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    className = CLS_JSON,
                    methodName = "optInt",
                    paramCount = 1,
                    returnType = "int",
                    id = ID_SKIN_PAYMENT,
                    description = "PoC备：optInt(payment) 0→1（全局，观测用）",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    // N1 内含 l3 副作用（payment==2 赠礼控制器），CONST 短路跳过——对启用路径无影响。
                    className = CLS_SMART_DETAIL,
                    methodName = "N1",
                    paramCount = 1,  // 签名实证：(SmartThemeDetailModel)I —— SmartThemeSkinDetailActivity.java:1185
                    returnType = "int",
                    id = ID_SMART_N1,
                    description = "PoC：智能皮肤详情付费状态机 N1 恒返 3(已购/启用态)",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    className = CLS_THEME_CTRL,
                    methodName = "u0",
                    paramCount = 0,
                    returnType = "void",
                    id = ID_DBG_U0,
                    description = "PoC 诊断：下载发起 URL",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    className = CLS_THEME_CTRL,
                    methodName = "T",
                    paramCount = 2,
                    returnType = "void",
                    id = ID_DBG_TFAIL,
                    description = "PoC 诊断：下载失败回调",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    className = CLS_THEME_CTRL,
                    methodName = "X",
                    paramCount = 2,  // 签名实证：(Intent,boolean)V —— b.java X/doReceiver
                    returnType = "void",
                    id = ID_DBG_RESULT,
                    description = "PoC 调试：安装结果广播 result_code 打印",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                ),
                MountEngine.SimpleTask(
                    // T6 商店去推广：postSuccess 响应层过滤。**必须用 paramTypes 锚钉 (pv,String) 重载**——
                    // 类内另有 (pv,JSONObject) 同 paramCount 同返回类型重载，无锚时挂载在两者间抽签，
                    // 挂错重载 = 永不触发（onResponse 只调 String 版）。实证：2026-10-28 02:02 进程
                    // installed 11/11 但零 judge 行即此因。
                    className = CLS_OK_CALLBACK,
                    methodName = "postSuccess",
                    paramCount = 2,
                    returnType = "void",
                    id = ID_STORE_AD_FILTER,
                    description = "T6：皮肤商店推广条目过滤（list/skinlist/more_recommend 去广告项，bannerList 清空）",
                    feature = FeatureCatalog.KEY_IME_STORE_AD_OFF,
                    dex = MountEngine.DexHint(
                        // 默认包类的运行时名无前缀（jadx 显示 defpackage. 仅展示用）
                        paramTypes = listOf("pv", "java.lang.String"),
                    ),
                ),
            ),
            module,
            loader,
        ) { task -> mountOne(session, task, module, loader) }
    }

    private fun mountOne(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            when (task.id) {
                ID_ZF -> {
                    val args = chain.args?.toTypedArray()
                        ?: return@mount chain.proceed()  // 取不到参数骨架：原样放行，宁漏杀不误杀
                    val orig = args.getOrNull(0) as? Boolean
                    if (orig == false) {
                        // 唯一 false 写点 = 联网服务拒绝擦除（OemAppServiceImpl.onPrivacyAgreedChanged）。
                        // 改写 true：拒绝同意不再吞掉验证码候选 pref；用户自关走设置页直落盘，不经此。
                        args[0] = true
                        session.judge(module, task, JUDGE_PREFIX, "consent 擦除已拦截（pref_smscode_to_canidate 保活）")
                    }
                    chain.proceedWith(chain.thisObject, args)
                }
                ID_AD_SETADDATA -> {
                    // AMS 广告数据不附到条目：吞掉 setAdData（void，不 proceed）
                    session.judge(module, task, JUDGE_PREFIX, "setAdData 已禁用")
                }
                ID_AD_FEED -> {
                    // AMS 信息流回调：List<AdBean> 清空后走原逻辑
                    try {
                        val arr = chain.args?.toTypedArray()
                        if (arr != null && arr.isNotEmpty()) {
                            arr[0] = java.util.ArrayList<Any>()
                            session.judge(module, task, JUDGE_PREFIX, "信息流广告列表已清空")
                            return@mount chain.proceedWith(chain.thisObject, arr)
                        }
                    } catch (_: Throwable) { }
                    chain.proceed()
                }
                ID_INI_H -> {
                    val ret = chain.proceed() as? String
                    val n = ret?.toLongOrNull()
                    if (ret != null && ret.isNotBlank() && (n == null || n <= 20000)) ret else {
                        session.judge(module, task, JUDGE_PREFIX, "H()=$ret 矫正为 20000")
                        "20000"
                    }
                }
                ID_INI_G -> {
                    val ret = chain.proceed() as? String
                    if (ret == "0" || ret == "1") ret else {
                        session.judge(module, task, JUDGE_PREFIX, "G()=$ret 矫正为 1")
                        "1"
                    }
                }
                ID_SKIN_R1 -> {
                    session.judge(module, task, JUDGE_PREFIX, "r1 恒返 1（已购态）")
                    1
                }
                ID_SKIN_PAYMENT -> {
                    val ret = chain.proceed()
                    val key = chain.args?.firstOrNull() as? String
                    if (key == "payment" && ret == 0) {
                        session.judge(module, task, JUDGE_PREFIX, "optInt(payment) 0→1")
                        1
                    } else ret
                }
                ID_SMART_N1 -> {
                    session.judge(module, task, JUDGE_PREFIX, "N1 恒返 3（智能详情已购/启用态）")
                    3
                }
                ID_DBG_U0 -> {
                    // proceed 只能调一次：先取返回值，诊断读取全包在 runCatching（原 catch 里再 proceed
                    // 会抛 IllegalStateException，被 PROTECTIVE 吞掉 ⇒ 本点静默空转）。
                    val ret = chain.proceed()
                    runCatching {
                        val h = chain.thisObject.javaClass.getDeclaredField("h")
                        h.isAccessible = true
                        val u = h.get(chain.thisObject) as? String
                        module.log(Log.INFO, "SkinPoC", "DOWNLOAD_URL $u")
                    }
                    ret
                }
                ID_DBG_TFAIL -> {
                    try {
                        val code = chain.args?.getOrNull(0)
                        val path = chain.args?.getOrNull(1)
                        module.log(Log.INFO, "SkinPoC", "DL_FAIL code=$code path=$path")
                    } catch (_: Throwable) { }
                    chain.proceed()
                }
                ID_DBG_RESULT -> {
                    val intent = chain.args?.firstOrNull() as? android.content.Intent
                    val ret = chain.proceed()
                    runCatching {
                        val code = intent?.getIntExtra("result_code", -999)
                        val msg = intent?.getStringExtra("result_msg")
                        module.log(Log.INFO, "SkinPoC", "INSTALL_RESULT code=$code msg=$msg")
                    }
                    ret
                }
                ID_STORE_AD_FILTER -> {
                    // T6：响应层过滤（规则定稿 2026-10-28，匿名 curl 全量 + 真机抓包互证）：
                    //   ① 清空组：banner_list/bannerList（轮播横幅）、content_list（首页运营推荐）、
                    //      more_recommend/recommend（详情页推荐条）
                    //   ② 通用剔除：任意对象数组内 ad_type≠0 或带 ad_url/ad_title 的条目
                    //      （覆盖 home_store_recommend_item_list 等全部混排位）
                    //   ③ 附带：收集 skin_id→COS 直链；suit/items 12003 匿名重取；fileAdd 死链换 COS
                    val args = chain.args
                    var body = args?.getOrNull(1) as? String
                        ?: return@mount chain.proceed()
                    // 收集 skin_id→COS 直链（Z()/DAI 兜底数据源）
                    try {
                        if (body.contains("ssf_download_url") && body.contains("skin_id")) {
                            collectCosUrls(org.json.JSONObject(body))
                        }
                    } catch (_: Throwable) { }
                    // suit/items 的 fileAdd 死链（srv-ios 需 SCOOKIE）→ 换 COS 直链
                    if (body.contains("srv-ios") && body.contains("fileAdd")) {
                        val patched = runCatching {
                            val sc0 = forgeScookie("j=10.32&c=100&e=1080_2400&h=440&i=1&o=honor&u=zh-CN&it=0")
                            rewriteFileAdds(body, sc0)
                        }.getOrNull()
                        if (patched != null && patched != body) {
                            body = patched
                            val arr0 = args.toTypedArray()
                            arr0[1] = patched
                            session.judge(module, task, JUDGE_PREFIX, "fileAdd 已换 COS 直链")
                            return@mount chain.proceedWith(chain.thisObject, arr0)
                        }
                    }
                    // suit/items 12003（真机参数触发）→ 伪造 S-COOKIE 匿名重取替换
                    if (body.contains("数据库操作失败") && lastSuitId != null) {
                        val fixed = refetchSuitItems(lastSuitId!!)
                        if (fixed != null) {
                            body = fixed
                            val arr1 = args.toTypedArray()
                            arr1[1] = fixed
                            session.judge(module, task, JUDGE_PREFIX, "suit/items 12003 已匿名重取替换")
                            return@mount chain.proceedWith(chain.thisObject, arr1)
                        }
                    } else {
                        runCatching {
                            org.json.JSONObject(body).optJSONObject("data")
                                ?.takeIf { it.optString("content_title") == "套装内容" }
                                ?.optString("id")?.takeIf { it.isNotBlank() }
                                ?.let { id ->
                                    lastSuitId = id
                                    // 预取：套装内全部主题的 COS 直链入表（本处在 okhttp 线程，可联网）
                                    val sc = forgeScookie("j=10.32&c=100&e=1080_2400&h=440&i=1&o=honor&u=zh-CN&it=0")
                                    val items = refetchSuitItems(id)
                                    if (items != null) {
                                        val arr = org.json.JSONObject(items).getJSONObject("data").getJSONArray("list")
                                        for (i in 0 until arr.length()) {
                                            val it2 = arr.optJSONObject(i) ?: continue
                                            if (it2.optInt("type") != 2000) continue
                                            val sid = it2.optString("id")
                                            if (sid.isBlank() || cosUrlMap.containsKey(sid)) continue
                                            val sc2 = sc ?: continue
                                            val det = fetchUrl("https://srv.android.shouji.sogou.com/v1/skin/detail?skin_id=$sid&from=detail&req_id=poc&device_type=1&supportvideo=1&supportgyroscope=0&is_full_screen=1", sc2)
                                            val u = det?.let { o -> org.json.JSONObject(o).optJSONObject("data")?.optString("ssf_download_url") }
                                                ?.replace("android.store.ime.local", "skininfo-1258344701.file.myqcloud.com")
                                            if (u != null && u.endsWith(".ssf")) cosUrlMap[sid] = u
                                        }
                                        module.log(Log.INFO, "SkinPoC", "预取套装 $id 直链完成，映射表 ${cosUrlMap.size} 条")
                                    }
                                }
                        }
                    }
                    // 快路径：无广告/列表标记的响应零成本放行
                    if (!body.contains("banner") && !body.contains("ad_") &&
                        !body.contains("skinlist") && !body.contains("content_list")) {
                        return@mount chain.proceed()
                    }
                    try {
                        val jo = org.json.JSONObject(body)
                        val changed = booleanArrayOf(false)
                        fun filtr(container: org.json.JSONObject, depth: Int) {
                            if (depth > 8) return
                            val names = container.keys()
                            while (names.hasNext()) {
                                val k = names.next() as String
                                if (k == "banner_list" || k == "bannerList" ||
                                    k == "content_list" || k == "more_recommend" || k == "recommend") {
                                    // 清空组：横幅/运营推荐/详情推荐条（真机实证均为付费皮肤推广）
                                    val arr = container.optJSONArray(k)
                                    if (arr != null && arr.length() > 0) {
                                        container.put(k, org.json.JSONArray())
                                        changed[0] = true
                                    }
                                } else {
                                    // 通用剔除：任意对象数组内带广告标记的条目
                                    val arr = container.optJSONArray(k) ?: continue
                                    var hadObj = false; var removed = false
                                    val kept = org.json.JSONArray()
                                    for (i in 0 until arr.length()) {
                                        val it2 = arr.optJSONObject(i)
                                        if (it2 == null) { kept.put(arr.opt(i)); continue }
                                        hadObj = true
                                        val ad = it2.optInt("ad_type", 0) != 0 ||
                                            !it2.optString("ad_url").isNullOrBlank() ||
                                            !it2.optString("ad_title").isNullOrBlank()
                                        if (!ad) kept.put(it2) else removed = true
                                    }
                                    if (hadObj && removed) {
                                        container.put(k, kept)
                                        changed[0] = true
                                    }
                                }
                                when (val v = container.opt(k)) {
                                    is org.json.JSONObject -> filtr(v, depth + 1)
                                    is org.json.JSONArray -> for (i in 0 until v.length()) {
                                        (v.opt(i) as? org.json.JSONObject)?.let { filtr(it, depth + 1) }
                                    }
                                }
                            }
                        }
                        filtr(jo, 0)
                        if (changed[0]) {
                            val arr = args.toTypedArray()
                            arr[1] = jo.toString()
                            session.judge(module, task, JUDGE_PREFIX, "推广条目已过滤")
                            chain.proceedWith(chain.thisObject, arr)
                        } else chain.proceed()
                    } catch (_: Throwable) {
                        chain.proceed()  // 解析失败：原样放行，宁漏杀不误杀
                    }
                }
                else -> {
                    if (task.id == ID_SPLASH_GATE) {
                        // T5：纯读判定无副作用（8 道闸全是 read-only），CONST 短路即可，不 proceed
                        // （LauncherHook CONST 同款）。false = onCreate 走无广告分支直达主界面。
                        session.judge(module, task, JUDGE_PREFIX, "canShowAds 强制 false（开屏不加载）")
                        false
                    } else {
                        // 读数强制（ID_X5 / ID_CLIP / ID_U10B / ID_A4）：proceed 跑原方法（副作用保留）后覆盖返回值。
                        // 显示层瑕疵：设置页勾选框显示态含同意位参与计算，可能仍显示关——功能由运行链读数驱动，chip 照常。
                        chain.proceed()
                        session.judge(module, task, JUDGE_PREFIX, "读数强制 true")
                        true
                    }
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
    private const val ID_SPLASH_GATE = "ime:px5.b"
    private const val ID_INI_G = "ime:zf4.G(ini_flag)"
    private const val ID_INI_H = "ime:zf4.H(ini_flag)"
    private const val ID_STORE_AD_FILTER = "ime:okhttp.a.postSuccess(store_ad_off)"
    private const val CLS_THEME_PREVIEW = "com.sogou.theme.ThemePreviewActivity"
    private const val CLS_JSON = "org.json.JSONObject"
    private const val CLS_SMART_DETAIL = "com.sogou.theme.SmartThemeSkinDetailActivity"
    private const val CLS_THEME_CTRL = "com.sogou.theme.b"
    private const val ID_SKIN_R1 = "ime:ThemePreviewActivity.r1"
    private const val ID_SKIN_PAYMENT = "ime:JSONObject.optInt(payment)"
    private const val ID_SMART_N1 = "ime:SmartThemeSkinDetailActivity.N1"
    private const val ID_DBG_U0 = "ime:theme.b.u0(dbg)"
    private const val ID_DBG_TFAIL = "ime:theme.b.T(fail_dbg)"
    private const val ID_DBG_RESULT = "ime:theme.b.X(result)"
    private const val ID_AD_SETADDATA = "ime:CostumeItem.setAdData(off)"
    private const val ID_AD_FEED = "ime:MainPageFeedCb(off)"

    private val CLS_COSTUME_ITEM = "com.sogou.home.costume.bean.CostumeSuitDataBean\$CostumeSuitContentItemBean"
    private val CLS_MAINPAGE_AD_CB = "com.sohu.inputmethod.sogou.home.main.viewmodel.MainPageViewModel\$c"



    /** 最近一次套装详情 id（suit/items 12003 替换用）。 */
    @Volatile private var lastSuitId: String? = null

    /** skin_id→COS ssf 直链（Z() 死链替换用；容量 80 截断防膨胀）。 */
    private val cosUrlMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 递归收集 JSON 里成对的 skin_id+ssf_download_url。 */
    private fun collectCosUrls(c: org.json.JSONObject) {
        try {
            val sid = c.optString("skin_id")
            val url = c.optString("ssf_download_url")
            if (sid.isNotBlank() && url.endsWith(".ssf")) {
                cosUrlMap[sid] = url.replace("android.store.ime.local", "skininfo-1258344701.file.myqcloud.com")
            }
            val it2 = c.keys()
            while (it2.hasNext()) {
                when (val v = c.opt(it2.next() as String)) {
                    is org.json.JSONObject -> collectCosUrls(v)
                    is org.json.JSONArray -> for (i in 0 until v.length()) {
                        (v.opt(i) as? org.json.JSONObject)?.let { collectCosUrls(it) }
                    }
                }
            }
            if (cosUrlMap.size > 80) cosUrlMap.keys.take(cosUrlMap.size - 80).forEach { cosUrlMap.remove(it) }
        } catch (_: Throwable) { }
    }

    /**
     * 伪造 S-COOKIE 复刻（by3.b 算法，jadx 实证 + curl 验证通过）：
     * A=当前时间各字段拼接（月份 0 基）；C=随机 5 位；sub=MD5(A+C+params)[0:10]；
     * key1=MD5(A+C+"6E09C97EB8798EEB") 后 16 字符；内层=Base64(AES/ECB/PKCS5(zlib(params|sub)))；
     * S-COOKIE=Base64(AES/ECB/PKCS5("A|C|内层"))。静态密钥硬编码于 APK。
     */
    private fun forgeScookie(params: String): String? = runCatching {
        val cal = java.util.Calendar.getInstance()
        val a = "${cal.get(java.util.Calendar.YEAR)}${cal.get(java.util.Calendar.MONTH)}" +
            "${cal.get(java.util.Calendar.DAY_OF_MONTH)}${cal.get(java.util.Calendar.HOUR_OF_DAY)}" +
            "${cal.get(java.util.Calendar.MINUTE)}${cal.get(java.util.Calendar.SECOND)}"
        val c = ((kotlin.math.abs(java.util.Random().nextInt()) % 99999) + 10000).toString()
        fun md5(x: String): String {
            val d = java.security.MessageDigest.getInstance("MD5").digest(x.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }
        }
        fun aes(data: ByteArray, key: String): ByteArray {
            val ci = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
            ci.init(javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
            return ci.doFinal(data)
        }
        fun zlib(data: ByteArray): ByteArray {
            val def = java.util.zip.Deflater(); def.setInput(data); def.finish()
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!def.finished()) { val n = def.deflate(buf); out.write(buf, 0, n) }
            return out.toByteArray()
        }
        val sub = md5(a + c + params).substring(0, 10)
        val k = md5(a + c + "6E09C97EB8798EEB")
        val inner = android.util.Base64.encodeToString(
            aes(zlib((params + "|" + sub).toByteArray(Charsets.UTF_8)), k.substring(k.length - 16)),
            android.util.Base64.NO_WRAP)
        android.util.Base64.encodeToString(
            aes((a + "|" + c + "|" + inner).toByteArray(Charsets.UTF_8), "6E09C97EB8798EEB"),
            android.util.Base64.NO_WRAP)
    }.getOrNull()

    /** 匿名重取 suit/items（伪造 S-COOKIE + 假设备参数串，curl 实证 code 0）。 */
    private fun refetchSuitItems(suitId: String): String? = runCatching {
        val sc = forgeScookie("j=10.32&c=100&e=1080_2400&h=440&i=1&o=honor&u=zh-CN&it=0") ?: return null
        val url = java.net.URL(
            "https://srv.android.shouji.sogou.com/v1/store/suit/items?id=$suitId&supportvideo=1&supportgyroscope=0&is_full_screen=1")
        val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("S-COOKIE", sc)
        conn.setRequestProperty("SOGOU_PLATFORM", "Android")
        conn.setRequestProperty("User-Agent", "sgandroid_ime/10.32.44")
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        if (!body.contains("\"code\":0")) return null
        // fileAdd 指向 srv-ios ssf_download.php（需 SCOOKIE，App 拿到 JSON 错误体 → 皮肤包错误）。
        // 逐条目换 COS 直链：v1/skin/detail 匿名可得 ssf_download_url（占位主机换 COS 域）。
        runCatching {
            val jo = org.json.JSONObject(body)
            val list = jo.getJSONObject("data").getJSONArray("list")
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                if (item.optInt("type") != 2000) continue
                val skinId = item.optString("id")
                val info = item.optJSONObject("info") ?: continue
                val det = fetchUrl(
                    "https://srv.android.shouji.sogou.com/v1/skin/detail?skin_id=$skinId&from=detail&req_id=poc&device_type=1&supportvideo=1&supportgyroscope=0&is_full_screen=1",
                    sc) ?: continue
                val url = org.json.JSONObject(det).optJSONObject("data")?.optString("ssf_download_url")
                if (!url.isNullOrBlank() && url.endsWith(".ssf")) {
                    val cos = url.replace("android.store.ime.local", "skininfo-1258344701.file.myqcloud.com")
                    info.put("fileAdd", cos)
                    // 关键：补齐 ThemePreviewItem 反序列化字段——套装数据缺 ssf_download_url（只有
                    // 死链 fileAdd）→ ssfUrl=null → R() 判「非网络主题」→ 不下载直接皮肤包错误。
                    // ssf_download_url=COS 直链；skin_id/md5 置空 → R0 走下载分支、跳过本地校验。
                    info.put("ssf_download_url", cos)
                    if (!info.has("skin_id")) info.put("skin_id", "")
                    if (!info.has("md5")) info.put("md5", "")
                }
            }
            jo.toString()
        }.getOrDefault(body)
    }.getOrNull()

    /** 把响应里所有 srv-ios fileAdd 换成 COS 直链（逐条目查 v1/skin/detail）。 */
    private fun rewriteFileAdds(body: String, sc: String?): String? {
        if (sc == null) return null
        val jo = org.json.JSONObject(body)
        var n = 0
        fun walk(c: org.json.JSONObject) {
            val it2 = c.keys()
            while (it2.hasNext()) {
                val k = it2.next() as String
                val v = c.opt(k)
                if (v is org.json.JSONObject) {
                    if (k == "info") {
                        val fa = v.optString("fileAdd")
                        if (fa.contains("skin_id=")) {
                            val m = Regex("skin_id=(\\d+)").find(fa)
                            val sid = m?.groupValues?.get(1)
                            if (sid != null) {
                                val det = fetchUrl(
                                    "https://srv.android.shouji.sogou.com/v1/skin/detail?skin_id=$sid&from=detail&req_id=poc&device_type=1&supportvideo=1&supportgyroscope=0&is_full_screen=1", sc)
                                val url = det?.let { org.json.JSONObject(it).optJSONObject("data")?.optString("ssf_download_url") }
                                if (!url.isNullOrBlank() && url.endsWith(".ssf")) {
                                    v.put("fileAdd", url.replace("android.store.ime.local", "skininfo-1258344701.file.myqcloud.com"))
                                    n++
                                }
                            }
                        }
                    }
                    walk(v)
                } else if (v is org.json.JSONArray) {
                    for (i in 0 until v.length()) (v.opt(i) as? org.json.JSONObject)?.let { walk(it) }
                }
            }
        }
        walk(jo)
        return if (n > 0) jo.toString() else null
    }

    private fun fetchUrl(u: String, sc: String): String? = runCatching {
        val conn = java.net.URL(u).openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("S-COOKIE", sc)
        conn.setRequestProperty("SOGOU_PLATFORM", "Android")
        val b = conn.inputStream.bufferedReader().readText(); conn.disconnect(); b
    }.getOrNull()
}
