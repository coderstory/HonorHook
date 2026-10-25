package top.honorhook.xposed.target.mms

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MethodResolver
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 荣耀短信 com.hihonor.mms（基线 19.0.9.304）。
 *
 * 架构实证全量见 docs/reverse/sms-ads.md §一（单进程插件式注入：intelligentmessage base.apk 经
 * `LinkerDexClassLoader` 映射进 mms 进程）。结论：**插件类（com.ted.sms.*）不在宿主 dex**，
 * `defaultClassLoader` 拿不到——挂载策略见 [mountMenuFactory] 注释。
 *
 * 功能与开关：
 * - [FeatureCatalog.KEY_MMS_AD_OFF] 广告净化（T2 底部自定义菜单 + T3 卡片按钮/应用推广卡 + T4 AIM
 *   富媒体推广按钮，全部经同一载体事件驱动挂载，见 [mountMenuFactory]）。验证码功能归 ime 目标（ImeHook），mms 侧无挂载点。
 *
 * 判据：真机冷启短信 → `MmsHook: installed N/M`；广告净化另有两条主轨生效行
 * （`mms-hook 生效: mms:TedMenuProcessor.findMenus` / `mms:SimpleActionLayout.b`——两轨同为
 * 插件侧目标，宿主 CL 不可达，不进 pending 表，installed 的 M 只含宿主侧探针+载体）。
 */
object MmsHook : TargetHook {

    override val packageName = "com.hihonor.mms"

    /**
     * 插件环境探针类：mms 侧智能信息控制器，**未混淆**（跨 APK 反射留名，sms-ads.md §一；
     * `getIntelligentIpMessageEnable` 返 1/0 见 SPEC.md M3——返回类型 "int" 为逆向结论，
     * jadx 产物未落盘，T4 实装前须以设备 dex 复核，错则 NOT_RESOLVED 兜底不误伤）。
     */
    private const val CLS_SMART_SMS_UTIL = "com.smartsms.hwcontroller.SmartSmsUtilControl"

    /**
     * T2 载体：智能信息菜单处理器统一出口，**未混淆**（跨 APK 反射留名）；mms 自身 dex，
     * 静态 0 参返 Optional，每次会话页 MenuControll 构造必经（MenuControll.java:40）。返回值
     * 两种分支形态（Honor：插件 TedMenuProcessor 本尊；HW：HwMenuProcessorProxy 持 processor
     * 字段桥接）——形态细节与 HW 分支在本机为死代码的实测，见 docs/reverse/sms-ads.md §四；
     * 两种形态都由 [pluginClassLoaderOf] 从返回值对象捕获插件 CL（分支无关）。
     */
    private const val CLS_MENU_FACTORY = "com.smartsms.hwcontroller.SmartSmsProcessorFactory"

    /** T2 主轨目标：插件侧菜单数据源（类名/方法名可读，跨 APK 反射留名；jadx 实证 private static）。 */
    private const val CLS_TED_MENU_PROCESSOR = "com.ted.sms.plugin.TedMenuProcessor"

    /**
     * T3 主轨目标：卡片动作区渲染收口（插件 dex，全类唯一 4 参 b，jadx 实证 public final）。
     * 数据链全貌（TedMessage.action → doBindCard/doBindTextAction → N5 卡片视图族 → b()
     * 统一收口，含 AimButtonLayout 无 left_button id 的布局排除论证）见
     * docs/reverse/sms-ads.md §五「卡片数据链全貌」。
     */
    private const val CLS_SIMPLE_ACTION_LAYOUT = "com.ted.sms.feature.ui.action.SimpleActionLayout"

    /**
     * 验证码卡片 code（引擎门禁常量，SimpleActionLayout.b 开头与 doBindTextAction 同款判断）。
     * 第四版定稿后**不再作 hook 例外**，仅留作数据链注释锚点：该卡左 `left_button`=「复制验证码」
     * 由 `c()` 从 `tedCard.subTitle` 本地合成（T4/R3 面）；json 置空后 c() 仍会跑（见
     * [simpleActionTask] intercept 注释），复制按钮存活；云端 menus（「打开国家政务」「应用推荐」
     * 等跳市场按钮）不再被解析，全部消灭。
     */
    private const val VERIFY_CARD_CODE = "0EFF76FF"

    /** TedMenuProcessor 桥接持有字段名（jadx 实证；仅 HW 分支形态用到，取不到按字段扫描兜底）。 */
    private const val FIELD_PROCESSOR = "processor"

    /**
     * T4 目标：AIM 富媒体卡的应用推广按钮（「打开应用」）。插件 10.0.42.308 起富媒体模板不经
     * [CLS_SIMPLE_ACTION_LAYOUT]（T3 的 json 置空管不到它），按钮本体是广告 SDK 下载按钮
     * （类名未混淆、跨版本留名）。模板链实证与复发根因见 docs/reverse/sms-ads.md 追记 T4；
     * 处置 = 构造后 setVisibility(GONE)（视图级补杀：云端数据源无干净裁剪口；GONE 不留空块，
     * 卡片正文不动）。
     */
    private const val CLS_AIM_APP_BUTTON = "com.ted.sms.feature.ui.aim.app.AppDownloadButton"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MMS_AD_OFF)) {
            logDisabled(module, FeatureCatalog.KEY_MMS_AD_OFF, "MmsHook")
            return
        }

        val loader = param.defaultClassLoader
        /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
        val session = MountEngine.open("MmsHook", packageName, param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))

        // 挂载表（T1 探针 + T2 载体；**以真机 `installed N/M` 的 M 为准**）。
        val pending = mutableListOf<MountEngine.SimpleTask>()
        pending += MountEngine.SimpleTask(
            className = CLS_SMART_SMS_UTIL,
            methodName = "getIntelligentIpMessageEnable",
            paramCount = 1,  // 签名实证：(Context)——system-files/honor-mms-19.0.9.304-jadx SmartSmsUtilControl.java:515
            returnType = "int",
            id = "mms:SmartSmsUtilControl.getIntelligentIpMessageEnable",
            description = "T1 骨架探针：智能信息插件环境存活检查（proceed 后原值返回，零行为改动）",
        )
        pending += MountEngine.SimpleTask(
            className = CLS_MENU_FACTORY,
            methodName = "getMenuProcessor",
            paramCount = 0,  // 签名实证：static Optional getMenuProcessor()——SmartSmsProcessorFactory.java
            returnType = "java.util.Optional",
            id = ID_MENU_FACTORY,
            description = "T2 菜单全杀载体：proceed 后从返回的菜单处理器对象捕获插件 ClassLoader 并挂主轨 findMenus，本身放行不改行为",
        )
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
    }

    private fun mountOne(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        when (task.id) {
            ID_MENU_FACTORY -> mountMenuFactory(session, task, module, loader)
            else -> mountProbe(session, task, module, loader)
        }

    /**
     * T1 探针挂载：proceed 跑原方法 → judge 记录返回值（每任务至多 [MountEngine.JUDGE_LOG_PER_TASK]
     * 次，自带节流）→ **原值返回**，零行为改动。around 语义照 Exam8Hook 放行路径：chain.proceed()
     * 裸调不吞原方法异常，挂载级 PROTECTIVE 模式兜底防炸宿主。
     */
    private fun mountProbe(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val orig = chain.proceed()
            session.judge(module, task, JUDGE_PREFIX, "当前值=$orig")
            orig
        }

    /**
     * T2 菜单全杀——载体挂载 + 主轨实装。
     *
     * 选型（详证见 docs/reverse/sms-ads.md §三/§四）：主轨 hook 插件侧 `TedMenuProcessor.findMenus`
     * 返**空 JSONArray** → init 里 length()==0 走引擎自带 -201 干净退出（menuGroup 保持 GONE、
     * 无 UI 残留）——「改数据源，不改逻辑」；备轨 `HwMenuProcessorProxy.init` 直返 -201 在本机
     * 是死代码（SmartSmsSwitcher 见插件包已装走 Honor 分支，第一版实测零触发后弃）。
     *
     * 载体时序（约束）：本 hook 在 getMenuProcessor proceed 后捕获插件 CL 并挂主轨——
     * TedMenuProcessor 的 init（含 findMenus 调用）经 MenuControll 异步 post 到后台线程，
     * 晚于本拦截内的主轨挂载，零竞态；Optional 为空（插件未就绪）不置一次幂标志，随下次
     * 会话页重试。
     *
     * 插件 CL 捕获（核心约束）：插件类由 `LinkerDexClassLoader` 加载且 `LitePluginManager` 会
     * 为广告拦截引擎另造第二个同 apk loader——必须从**运行中对象**取定义 CL 才保证拿到渲染链
     * 正在用的那份 Class。DexKit 轨只扫 mms 自身 apk，扫不到插件 dex；插件若混淆改名属版本
     * 适配问题（adapt_new_version.py 流程），本点诚实 NOT_RESOLVED 不误伤。
     */
    private fun mountMenuFactory(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val result = chain.proceed()
            // 载体放行：菜单处理器照常返回，只在返回值上做插件 CL 捕获
            val processor = (result as? java.util.Optional<*>)?.takeIf { it.isPresent }?.orElse(null)
            if (processor == null) {
                // 插件未就绪（Optional.empty）——菜单本来就不渲染；不置一次幂标志，下次会话页再试
                session.judge(module, task, JUDGE_PREFIX, "返回空 Optional（插件未就绪）")
                return@mount result
            }
            session.judge(module, task, JUDGE_PREFIX, "处理器=${processor.javaClass.name}")
            installMenuKill(session, module, processor, loader)
            installCardKill(session, module, processor, loader)
            installAimButtonKill(session, module, processor, loader)
            result
        }

    /** 主轨一次幂标志（载体每次会话页都触发，避免重复 hook）；**仅成功时保持置位**——失败即复位，随下次会话页重试（P4：此前失败即永久放弃）。 */
    private val menuKillTried = java.util.concurrent.atomic.AtomicBoolean(false)

    /** T3 主轨一次幂标志（与 T2 分开：各自独立重试/失败留痕，互不拖累）；同上仅成功置位。 */
    private val cardKillTried = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 主轨任务描述（判据日志用；不进 pending 表，挂载由载体回调事件驱动）。 */
    private val findMenusTask = MountEngine.SimpleTask(
        className = CLS_TED_MENU_PROCESSOR,
        methodName = "findMenus",
        paramCount = 1,  // 签名实证：private static JSONArray findMenus(String)——TedMenuProcessor.java
        returnType = "org.json.JSONArray",
        id = ID_FIND_MENUS,
        description = "T2 主轨：菜单数据源清空（返空数组 → init 走引擎自带 -201 干净退出）",
    )

    private fun installMenuKill(session: MountSession, module: XposedModule, processor: Any, hostLoader: ClassLoader) {
        if (!menuKillTried.compareAndSet(false, true)) return
        runCatching {
            val pluginCl = pluginClassLoaderOf(processor, hostLoader)
                ?: run {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 未捕获插件 ClassLoader（处理器=${processor.javaClass.name}），菜单广告原样显示")
                    menuKillTried.set(false)   // 失败不置位：下次会话页重试
                    return
                }
            val method = MethodResolver.resolveMethod(
                pluginCl, CLS_TED_MENU_PROCESSOR, "findMenus",
                paramCount = findMenusTask.paramCount, returnType = findMenusTask.returnType,
            ) ?: run {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 主轨未解析（$CLS_TED_MENU_PROCESSOR#findMenus，插件版本差异？），菜单广告原样显示")
                menuKillTried.set(false)   // 失败不置位：下次会话页重试
                return
            }
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(ID_FIND_MENUS)
                .intercept { _ ->
                    // org.json. 在插件加载白名单（Interceptor.HOST_CLASS_ARRAY）——宿主/插件同一份
                    // Class，返空数组类型对齐；length()==0 → init -201 → menuGroup 保持 GONE。
                    session.judge(module, findMenusTask, JUDGE_PREFIX)
                    org.json.JSONArray()
                }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 主轨已挂载: $CLS_TED_MENU_PROCESSOR.findMenus → 空数组")
        }.onFailure { t ->
            menuKillTried.set(false)   // 失败不置位：下次会话页重试
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 主轨挂载异常（菜单广告原样显示，不 crash）: ${t.message}", t)
        }
    }

    /** T3 任务描述（判据日志用；不进 pending 表，挂载由载体回调事件驱动，同 [findMenusTask]）。 */
    private val simpleActionTask = MountEngine.SimpleTask(
        className = CLS_SIMPLE_ACTION_LAYOUT,
        methodName = "b",
        paramCount = 4,  // 签名实证：public final void b(String msgId, String json, String smsNumber, TedCard)——SimpleActionLayout.java（全类唯一 4 参 b，无重载歧义）
        returnType = "void",
        id = ID_SIMPLE_ACTION,
        description = "T3 主轨：卡片动作区数据源清空（json 参数置空 → 引擎自带 setVisibility(GONE) 路径）",
    )

    /**
     * T3 卡片按钮/应用推广卡补杀——数据源选型（jadx 实证，见 [CLS_SIMPLE_ACTION_LAYOUT] 注释）：
     * - 改参数 > 改返回值 > View 层 GONE：把 `b` 的第 2 参 json 置空串，引擎自己的空数据路径接管
     *   （`str2.length()==0 && (tedCard==null || code!=验证码)` → `setVisibility(8)` 整块动作区隐藏，
     *   按钮/标语/banner 全清，无白块）——不碰 View 层、不碰 card 正文（`n5.b(body,card)` 在另一方法，
     *   流量提醒正文 R4 保留）。
     * - 不 hook `TedMessage.getAction()`：TedMessageDao_Impl 落库直接读 getter（`statement.k(12,..)`），
     *   全局返 null 会把 action 列洗成 NULL = 持久数据突变，开关关不掉回来（违反 T2 边界"落库照旧"）。
     * - 验证码卡无例外（用户 2026-10-12 第四版定稿）：json 置空后 c() 本地合成的复制按钮仍存活
     *   （默认 JSON 的 menus:[] 数组值使 optString 返回 "[]"，空 menus 提前 return 不触发），
     *   云端下发的跳市场按钮（打开国家政务/应用推荐）不再被解析，全灭。
     *
     * 覆盖面：left_button/right_button（1元30G/抢100G/打开国家政务/应用推荐）与「查看应用」推广卡
     * 若同为 SimpleActionLayout 渲染（UI dump 同 id 实证）则一并消失；若推广卡实为 AIM 模板
     * （bindAimCard 链）则本点管不到，真机如实报告。
     */
    private fun installCardKill(session: MountSession, module: XposedModule, processor: Any, hostLoader: ClassLoader) {
        if (!cardKillTried.compareAndSet(false, true)) return
        runCatching {
            val pluginCl = pluginClassLoaderOf(processor, hostLoader)
                ?: run {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 未捕获插件 ClassLoader（处理器=${processor.javaClass.name}），卡片按钮原样显示")
                    cardKillTried.set(false)   // 失败不置位：下次会话页重试
                    return
                }
            val method = MethodResolver.resolveMethod(
                pluginCl, CLS_SIMPLE_ACTION_LAYOUT, "b",
                paramCount = simpleActionTask.paramCount, returnType = simpleActionTask.returnType,
            ) ?: run {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 主轨未解析（$CLS_SIMPLE_ACTION_LAYOUT#b(4参)，插件版本差异？），卡片按钮原样显示")
                cardKillTried.set(false)   // 失败不置位：下次会话页重试
                return
            }
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(ID_SIMPLE_ACTION)
                .intercept { chain ->
                    val args = chain.args?.toTypedArray()
                        ?: return@intercept chain.proceed()  // 取不到参数骨架：原样放行，宁漏杀不误杀
                    // 全卡统一（用户 2026-10-12 第四版定稿）：json 一律置空，无任何例外。
                    // 「打开 xxx」「应用推荐」类按钮不论是否带包名全部消灭。
                    // 验证码卡（0EFF76FF）的「复制验证码」不受影响：json 置空后 b() 走引擎默认 JSON
                    // （menus:[] 数组值，optString → "[]" 非空 → 不触发空 menus 提前 return），
                    // c() 从 tedCard.subTitle 本地合成复制按钮——引擎原生路径，实测见验证记录。
                    args[1] = ""
                    session.judge(module, simpleActionTask, JUDGE_PREFIX, "json 已置空（card=${args.getOrNull(3)?.javaClass?.simpleName ?: "null"}）")
                    chain.proceedWith(chain.thisObject, args)
                }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 主轨已挂载: $CLS_SIMPLE_ACTION_LAYOUT.b → 全卡 json 置空")
        }.onFailure { t ->
            cardKillTried.set(false)   // 失败不置位：下次会话页重试
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 主轨挂载异常（卡片按钮原样显示，不 crash）: ${t.message}", t)
        }
    }

    /** T4 任务描述（判据日志用；不进 pending 表，挂载由载体回调事件驱动，同 [findMenusTask]）。 */
    private val aimButtonTask = MountEngine.SimpleTask(
        className = CLS_AIM_APP_BUTTON,
        methodName = "<init>",
        paramCount = 2,  // 签名实证（MT MCP smali，插件 10.0.42.308）：<init>(Landroid/content/Context;Landroid/util/AttributeSet;)V——唯一实例化构造
        returnType = "void",
        id = ID_AIM_APP_BUTTON,
        description = "T4：AIM 应用推广按钮（打开应用）构造后置 GONE（视图级补杀，卡片正文不动）",
    )

    /** T4 一次幂标志（同 T2/T3：仅成功置位，失败复位随载体重试）。 */
    private val aimButtonTried = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * T4 AIM 应用推广按钮补杀（2026-10-23）：插件 10.0.42.308 的富媒体模板渲染不经
     * [CLS_SIMPLE_ACTION_LAYOUT]（T3 管不到）；「打开应用」按钮 = `AppDownloadButton`
     * （继承广告 SDK HnProgressButton，类名未混淆留名）。锚定**唯一 2 参构造**，
     * after 语义 proceed 后 setVisibility(GONE)——不碰卡片正文与其它 AIM 模板
     * （取件提醒等保留），GONE 不留空块。异常全吞（PROTECTIVE 兜底 + onFailure 复位重试）。
     */
    private fun installAimButtonKill(session: MountSession, module: XposedModule, processor: Any, hostLoader: ClassLoader) {
        if (!aimButtonTried.compareAndSet(false, true)) return
        runCatching {
            val pluginCl = pluginClassLoaderOf(processor, hostLoader)
                ?: run {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX T4 未捕获插件 ClassLoader（处理器=${processor.javaClass.name}），AIM 推广按钮原样显示")
                    aimButtonTried.set(false)
                    return
                }
            val ctor = runCatching {
                val cls = pluginCl.loadClass(CLS_AIM_APP_BUTTON)
                cls.getDeclaredConstructor(android.content.Context::class.java, android.util.AttributeSet::class.java)
            }.getOrNull() ?: run {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX T4 未解析（$CLS_AIM_APP_BUTTON.<init>(2参)，插件版本差异？），AIM 推广按钮原样显示")
                aimButtonTried.set(false)
                return
            }
            module.hook(ctor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(ID_AIM_APP_BUTTON)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? android.view.View)?.visibility = android.view.View.GONE
                    session.judge(module, aimButtonTask, JUDGE_PREFIX)
                    result
                }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX T4 已挂载: $CLS_AIM_APP_BUTTON.<init> → 构造后 GONE")
        }.onFailure { t ->
            aimButtonTried.set(false)
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX T4 挂载异常（AIM 推广按钮原样显示，不 crash）: ${t.message}", t)
        }
    }

    /**
     * 从菜单处理器对象取插件 ClassLoader：Honor 分支返回值即 TedMenuProcessor（定义 CL 即插件
     * CL，≠ 宿主 CL）直取；HW 分支返回 HwMenuProcessorProxy——优先按 jadx 实证字段名
     * `processor` 取桥接的插件对象，字段名失配（R8 改名）时兜底扫描首个「值由非宿主 CL
     * 加载」的字段（不猜名，防漂移）。
     */
    private fun pluginClassLoaderOf(processor: Any, hostLoader: ClassLoader): ClassLoader? {
        // ① Honor 分支：处理器本身就是插件对象
        processor.javaClass.classLoader?.takeIf { it !== hostLoader }?.let { return it }
        // ② HW 分支：按实证字段名直取桥接对象
        runCatching {
            val f = processor.javaClass.getDeclaredField(FIELD_PROCESSOR)
            f.isAccessible = true
            f.get(processor)?.javaClass?.classLoader?.let { return it }
        }
        // ③ 兜底：扫全部字段，取第一个「定义 CL ≠ 宿主 CL」的非空对象（即插件侧对象）
        for (f in processor.javaClass.declaredFields) {
            runCatching {
                f.isAccessible = true
                val v = f.get(processor) ?: return@runCatching
                val cl = v.javaClass.classLoader ?: return@runCatching
                if (cl !== hostLoader) return cl
            }
        }
        return null
    }

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "mms-hook"

    /** 载体任务 id（mountOne 分派键）。 */
    private const val ID_MENU_FACTORY = "mms:SmartSmsProcessorFactory.getMenuProcessor"

    /** 主轨任务 id（插件侧 findMenus）。 */
    private const val ID_FIND_MENUS = "mms:TedMenuProcessor.findMenus"

    /** T3 主轨任务 id（插件侧 SimpleActionLayout.b）。 */
    private const val ID_SIMPLE_ACTION = "mms:SimpleActionLayout.b"

    /** T4 主轨任务 id（插件侧 AppDownloadButton 构造）。 */
    private const val ID_AIM_APP_BUTTON = "mms:AppDownloadButton.<init>"
}
