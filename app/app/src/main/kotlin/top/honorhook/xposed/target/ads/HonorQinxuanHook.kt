package top.honorhook.xposed.target.ads

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/** 判据日志前缀 + 常量。 */
private const val JUDGE_PREFIX = "qinxuan-hook"
private const val CLS_RECOMMEND_MODEL = "cn.honor.qinxuan.honorchoice.home.recommend.RecommendModel"
private const val CLS_CARD_CONVERTER = "com.hihonor.module.home.convert.FeatureCardConverterKt"
private const val RX_OBSERVABLE = "io.reactivex.rxjava3.core.Observable"
private const val CLS_SPLASH_AD_ACTIVITY = "com.hihonor.library.advertisement.view.SplashAdvertisementActivity"
private const val TASK_SPLASH_FINISH = "qinxuan:health.splashAd.finish"
private const val CLS_OPERATIONS_MANAGER = "com.hihonor.module.home.logic.operations.OperationsManager"
private const val CLS_ADS_MANAGER = "com.hihonor.library.advertisement.manager.AdsManager"
private const val TASK_IS_SHOW_ADS = "qinxuan:AdsManager.isShowAds"
private const val CLS_BANNER_REPO = "com.hihonor.module.home.repository.BannerDataRepository"
private const val TASK_BANNER_EMPTY = "qinxuan:BannerDataRepository.getBannerCards"
private const val TASK_OPS_ITEM_EMPTY = "qinxuan:OperationsManager.updateOperationsItem"
private const val CLS_CARD_ADAPTER = "com.hihonor.module.home.display.adapter.HomeShowCardAdapter"
private const val TASK_CARD_ADAPTER_PROBE = "qinxuan:homeCard.adapter.probe"

/**
 * 荣耀亲选电商模块（cn.honor.qinxuan）通杀净化。
 *
 * 实证（health 17.24.0.302 MT MCP 静态分析，docs/reverse/health.md）：运动健康内嵌「荣耀亲选」
 * 电商模块 496 类，首页营销信息（推荐商品流/banner/秒杀/运营位）唯一订阅收口 =
 * `RecommendModel.getHomeModulesData()`（HomeRegion + SecKill + Recommend 三路合流管道的头部，
 * 方法名未混淆直连可挂）。挂它一个点 = 亲选营销区整体空。
 *
 * 与 adsdk 通杀（[HonorAdsHook]）的关系：亲选自建 View（HomeBannerView extends RelativeLayout）
 * **不经过** adsdk BaseAdView，两钩子互补；osoperationsdk（推荐卡）在 health 不存在，无需挂。
 *
 * 拦截语义：返回 `Observable.empty()`（反射构造——模块代码零 rx 类型，同 XhsSavePivot hostShortUrl
 * 教训：宿主 rx 类不可由模块 classloader 解析）。UI 侧空态由宿主自处理（RecyclerView 空模块不渲染）。
 *
 * 类名/方法名未混淆 → 直连反射命中；无 SDK 的包解析失败 → NOT_RESOLVED 随类加载重试后放弃。
 * 真机判据：`HonorQinxuanHook[包名]: installed N/M`（N/M 随探针任务增减）。
 */
class HonorQinxuanHook(override val packageName: String) : TargetHook {

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_ADS_BLOCK)) {
            logDisabled(module, FeatureCatalog.KEY_ADS_BLOCK, "HonorQinxuanHook")
            return
        }
        val session = MountEngine.open("HonorQinxuanHook", packageName, param.applicationInfo?.sourceDir)

        // ⚠️ 撤销记录（2026-10-24 真机）：曾挂 getHomeModulesData → Observable.empty() 当「营销总入口」，
        // 实测**过度拦截**——功能卡（运动记录/步数/消耗等）也走该管道，empty = 首页内容区白屏。
        // 营销拦截改走 OperationsManager update* 写入口断根（下方 5 点），功能卡链不受影响。
        val pending = mutableListOf(
            // 运营位写入口断根（2026-10-24 真机实证「天天赚现金」卡为服务端下发后定位）：
            // OperationsManager 的 5 个 update* 是服务端运营数据写进 UI Flow 的全部通道
            // （banner 卡「天天赚现金」/商业信息/首页弹窗「现金奖励待领取」/运营条目/特殊卡）。
            // 不 proceed = 写入不发生 → UI 拿 Flow 初始空值，营销位不渲染；suspend 方法返回 null
            // 按 Unit 语义被调用方忽略（均为 private 写入型方法，无返回值消费），风险可控。
            // 运营条目列表（天天赚现金等）：参数替换型断根——放行原逻辑但 p0 列表置空，
            // 原方法照常写入「空」→ 零 null 返回风险（suspend 白屏教训的修正挂法）
            MountEngine.SimpleTask(
                className = CLS_OPERATIONS_MANAGER,
                methodName = "updateOperationsItem",
                paramCount = 3,
                returnType = "java.lang.Object",
                id = TASK_OPS_ITEM_EMPTY,
                description = "运营条目列表写入口：p0 置空列表后 proceed（原逻辑写空，无 null 风险）",
            ),

            // banner 卡 DB 读出口置空（2026-10-25）：拦网络写入（updateBannerData）不够——
            // BannerDataRepository 有本地 DB 缓存旁路（insertBannerCards/getViewedDataList），
            // 历史卡照常读出（真机 17:59 截图实证）。挂 getBannerCards 返回空 ArrayList
            // （合法非 null 对象，调用方拿到「无卡」）→ 网络/缓存两条读链同时断。
            MountEngine.SimpleTask(
                className = CLS_BANNER_REPO,
                methodName = "getBannerCards",
                paramCount = 1,
                returnType = "java.lang.Object",
                id = TASK_BANNER_EMPTY,
                description = "banner 卡 DB 读出口置空：返回空列表（非 null），网络+缓存读链同断",
            ),
            // 首页卡片适配器探针（2026-10-25）：UI 实际渲染的每张卡都经 getItemData，
            // 全量打印 name/cardId/type——「天天赚现金」banner 若经此适配器必现形
            MountEngine.SimpleTask(
                className = CLS_CARD_ADAPTER,
                methodName = "getItemData",
                paramCount = 1,
                returnType = "com.hihonor.library.base.model.FeatureCardModel",
                id = TASK_CARD_ADAPTER_PROBE,
                description = "首页卡片适配器探针（只读）：proceed 后打印返回卡的 name/cardId/type",
            ),
            // 特殊卡（2026-10-25 适配器探针实证：banner「天天赚现金」= 专题卡片 cardId=1061
            // dataModel=SpecialCardModel）→ 参数替换型断根（p0 置空，无 null 风险）
            MountEngine.SimpleTask(
                className = CLS_OPERATIONS_MANAGER,
                methodName = "updateSpecialCard",
                paramCount = 2,
                returnType = "java.lang.Object",
                id = "qinxuan:OperationsManager.updateSpecialCard.empty",
                description = "特殊卡写入口：p0 置空列表后 proceed（原逻辑写空，无 null 风险）",
            ),
            // 广告总判定门（2026-10-25）：「开会员免广告」的权益实现 = isShowAds 查会员态，
            // 恒返 false = 全 App 广告判定假（开屏/banner/运营位上游同断）。比逐点清视图更上游，
            // 且不伪造会员态（服务端权益，伪造有害——hook-craft §二·3）。未混淆直连。
            // 疑似「天天赚现金」大 banner 真身 = 全局投放 DeliveryCampaign（Room 库 DeliveryCampaignEntity），
            // 若 isShowAds 断后仍残留，下一步挂 GlobalDeliveryDataHelper.getGlobalDeliveryData。
            MountEngine.SimpleTask(
                className = CLS_ADS_MANAGER,
                methodName = "isShowAds",
                paramCount = 1,
                returnType = "boolean",
                id = TASK_IS_SHOW_ADS,
                description = "广告总判定门 isShowAds → false（会员免广告的等效实现，全广告位上游同断）",
            ),
            MountEngine.SimpleTask(
                className = CLS_OPERATIONS_MANAGER,
                methodName = "updateBannerData",
                paramCount = 2,
                returnType = "void",
                id = "qinxuan:OperationsManager.updateBannerData",
                description = "运营 banner 卡写入口断根（天天赚现金等服务端运营卡）",
            ),
            MountEngine.SimpleTask(
                className = CLS_OPERATIONS_MANAGER,
                methodName = "updateHealthHomepagePopupData",
                paramCount = 1,
                returnType = "void",
                id = "qinxuan:OperationsManager.updateHealthHomepagePopupData",
                description = "首页营销弹窗写入口断根（现金奖励待领取等）",
            ),
        )
        // 开屏广告 Activity：BaseAdView 清内容后它仍空壳停留（计时结束才走）= 白屏假死，
        // onCreate 走完即 finish（UpgradeGuideHook「闪一下就退」先例）；类名未混淆。
        pending += MountEngine.SimpleTask(
            className = CLS_SPLASH_AD_ACTIVITY,
            methodName = "onCreate",
            paramCount = 1,
            returnType = "void",
            id = "qinxuan:health.splashAd.finish",
            description = "开屏广告 Activity 内容已清 → onCreate 走完即 finish，白屏假死消除",
        )
        session.attemptMount(pending, module, loader) { task ->
            when (task.id) {
                TASK_IS_SHOW_ADS -> mountIsShowAdsFalse(session, task, module, loader)
                TASK_SPLASH_FINISH -> mountSplashFinish(session, task, module, loader)
                TASK_OPS_ITEM_EMPTY -> mountOpsItemEmpty(session, task, module, loader)
                "qinxuan:OperationsManager.updateSpecialCard.empty" -> mountOpsItemEmpty(session, task, module, loader)
                TASK_CARD_ADAPTER_PROBE -> mountAdapterProbe(session, task, module, loader)
                TASK_BANNER_EMPTY -> mountBannerEmpty(session, task, module, loader)
                else -> mountUpdateBlock(session, task, module, loader)
            }
        }
    }

    /**
     * 开屏广告 Activity 秒退：proceed 走完 onCreate（生命周期完整，防 NPE）→ finish。
     * ThemeStoreHook 挽留弹窗 FINISH_ACTIVITY 同款模式。
     */
    private fun mountSplashFinish(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? android.app.Activity)?.finish()
            session.judge(module, task, JUDGE_PREFIX)
            result
        }

    /**
     * 广告总判定门恒假：isShowAds after → false。boolean 返回值替换，无 null 风险。
     */
    private fun mountIsShowAdsFalse(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            session.judge(module, task, JUDGE_PREFIX)
            false
        }

    /**
     * 运营条目列表置空（suspend 白屏教训的修正挂法）：不返回 null，而是 proceedWith 把
     * p0（运营条目 List）替换为空列表——原方法照常执行、写入「空」，调用方拿到合法结果。
     * 取参失败 → 原样放行保底。
     */
    private fun mountOpsItemEmpty(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val args = chain.args?.toTypedArray() ?: return@mount chain.proceed()
            if (args.isNotEmpty()) args[0] = emptyList<Any>()
            session.judge(module, task, JUDGE_PREFIX, "运营条目列表已置空")
            chain.proceedWith(chain.thisObject, args)
        }

    /**
     * banner 卡读出口置空：不 proceed，返回空 ArrayList（合法非 null 对象）。
     * 调用方按「服务端无卡」消费 → 网络/DB 缓存两条读链同断，无 null/NPE 风险。
     */
    private fun mountBannerEmpty(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            session.judge(module, task, JUDGE_PREFIX)
            java.util.ArrayList<Any>()
        }

    /**
     * 首页卡片适配器探针（after，只读）：打印返回的 FeatureCardModel 字段。零行为改动。
     */
    private fun mountAdapterProbe(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val result = chain.proceed()
            runCatching {
                result?.let { card ->
                    val cls = card.javaClass
                    fun g(n: String): Any? = runCatching {
                        cls.getDeclaredField(n).apply { isAccessible = true }.get(card)
                    }.getOrNull()
                    module.log(Log.INFO, MountEngine.TAG,
                        "$JUDGE_PREFIX 卡片渲染: name=${g("name")} cardId=${g("cardId")} type=${g("type")} dataModel=${g("dataModel")?.javaClass?.name}")
                }
            }
            result
        }

    /**
     * 运营写入断根（统一拦截）：不 proceed = 服务端运营数据不进 Flow/UI。
     * 返回 null：void 任务无返回值语义；suspend 任务按 Unit 语义（private 写入型，无消费）。
     */
    private fun mountUpdateBlock(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            session.judge(module, task, JUDGE_PREFIX)
            null
        }

    /**
     * 亲选营销总入口拦截：不 proceed，返回反射构造的 `Observable.empty()`。
     * 反射失败（rx 不在默认 loader）放行原逻辑保底，不炸宿主。
     */
    private fun mountOne(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val empty = runCatching {
                loader.loadClass(RX_OBSERVABLE).getMethod("empty").invoke(null)
            }.getOrNull()
            if (empty == null) {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX Observable.empty 反射失败，放行原生（不 crash）")
                return@mount chain.proceed()
            }
            session.judge(module, task, JUDGE_PREFIX)
            empty
        }

    /**
     * 运营卡取证探针（after 语义，只读）：proceed 拿转换结果 List<FeatureCardModel>，
     * 逐项打 name/cardId/type。零行为改动。
     */
    private fun mountCardProbe(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val result = chain.proceed()
            runCatching {
                (result as? List<*>)?.takeIf { it.isNotEmpty() }?.let { list ->
                    val dump = list.mapNotNull { item ->
                        item?.javaClass?.let { cls ->
                            fun g(n: String): Any? = runCatching {
                                cls.getDeclaredField(n).apply { isAccessible = true }.get(item)
                            }.getOrNull()
                            "${g("name")}/cardId=${g("cardId")}/type=${g("type")}"
                        }
                    }
                    module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 运营卡清单: $dump")
                }
            }
            session.judge(module, task, JUDGE_PREFIX)
            result
        }
}
