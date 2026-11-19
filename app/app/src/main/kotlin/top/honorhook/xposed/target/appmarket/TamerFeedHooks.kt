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
import top.honorhook.xposed.target.appmarket.TamerUtil.readField

/**
 * 推荐信息流拦截（tamer 1.4.11 RecommendFeedBlocker 移植，2026-11-19）。
 *
 * mine_feed（默认开）/search_feed（默认开）/update_feed（默认开）/detail_rec（默认开）。
 * 手法：改数据源（请求层 setResult null / 应答层 args[0] 换空 BaseResp / adapter 入参置 null），
 * detail 走 ViewModel#h→null 后反射 postValue(BaseResult.Success(空 BaseResp)) 收口。
 * 日志判据只走 session.judge（MountEngine 按 task.id 节流），不再自建计数器。
 */
object TamerFeedHooks : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"
    private const val MINE_VM = "$PKG.mine.viewmodel.MarketManageViewModel"
    private const val MINE_FRAG = "$PKG.mine.MarketManageFragment"
    private const val SEARCH_VM = "$PKG.search.model.SearchActivationViewModel"
    private const val SEARCH_FRAG = "$PKG.search.fragment.SearchActivationFragment"
    private const val SEARCH_ADAPTER = "$PKG.search.adapter.AssSearchActivationAdapter"
    private const val UPDATE_ACT = "$PKG.appupdate.UpdateManagerActivity"
    private const val UPDATE_DISCOVER = "$PKG.appupdate.adapter.DiscoverMoreAdapter"
    private const val DETAIL_VM = "$PKG.base.details.recommend.AppDetailRecommendViewModel"
    private const val DETAIL_BRIDGE = "$PKG.app.details.activity.AppDetailsActivity\$requestMiddle\$1\$1"
    private const val BASE_RESP = "$PKG.network.base.BaseResp"

    private fun task(id: String, cls: String, method: String, argc: Int, ret: String, desc: String) =
        MountEngine.SimpleTask(
            id = id, className = cls, methodName = method, paramCount = argc, returnType = ret,
            description = desc,
        )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val pending = mutableListOf<MountEngine.SimpleTask>()

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_MINE_FEED)) {
            pending += listOf(
                // 16.1.11.302（adapt-report.md §11）：请求源 = I(MutableLiveData,I,String,Z)（锚串
                // realRequestRecommend/R232）；应答汇 = m2(BaseResp,Z,Z,Z)（锚串 setRecommendResponseData）。
                // 旧 o/n/Z1/W1/V1 形状已消失。
                task("market-tamer:feed.mine.I", MINE_VM, "I", 4, "java.lang.Object", "我的推荐流请求源→null"),
                task("market-tamer:feed.mine.m2", MINE_FRAG, "m2", 4, "void", "我的推荐流应答汇（args[0]=空 BaseResp）"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_MINE_FEED, "TamerFeedHooks")
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_SEARCH_FEED)) {
            pending += listOf(
                task("market-tamer:feed.src.o", SEARCH_VM, "o", 6, "java.lang.Object", "搜索推荐流请求源→BaseResp(errorCode=-1)"),
                task("market-tamer:feed.src.C1", SEARCH_FRAG, "C1", 5, "java.lang.Object", "搜索推荐流应答 args[0]=null"),
                task("market-tamer:feed.src.G1", SEARCH_ADAPTER, "G1", 2, "void", "搜索推荐 adapter 入参→null"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_SEARCH_FEED, "TamerFeedHooks")
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_UPDATE_FEED)) {
            pending += listOf(
                // setRecommendResponseData(3) 旧形已并入 access$ 点（suspend 化），不重复挂。
                task("market-tamer:feed.upd.bridge", UPDATE_ACT, "access\$setRecommendResponseData", 4, "void", "更新页推荐 bridge args[1]=null"),
                task("market-tamer:feed.upd.discover", UPDATE_DISCOVER, "getItemCount", 0, "int", "更新页「发现更多」卡 after→0"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_UPDATE_FEED, "TamerFeedHooks")
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_DETAIL_REC)) {
            pending += listOf(
                // 16.1.11.302 仅剩 h(11) 重载（h(12) 已消失）。
                task("market-tamer:feed.det.h", DETAIL_VM, "h", 11, "void", "详情页推荐请求（11 参形态）→null+空 Success"),
                task("market-tamer:feed.det.bridge", DETAIL_BRIDGE, "invokeSuspend", 1, "java.lang.Object", "详情页推荐协程桥→null+空 Success"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_DETAIL_REC, "TamerFeedHooks")
        }
        if (pending.isEmpty()) return

        val loader = param.defaultClassLoader
        val session = MountEngine.open(
            "TamerFeedHooks", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, loader) { t ->
            session.mount(t, module, loader) { chain ->
                runCatching {
                    when {
                        // 我的流：请求层 →null
                        t.id == "market-tamer:feed.mine.I" -> {
                            session.judge(module, t, "market-tamer", "推荐请求已拦")
                            null
                        }
                        // 我的流应答汇：args[0] 换空 BaseResp（构造失败退 null）
                        t.id == "market-tamer:feed.mine.m2" -> {
                            if (chain.args.getOrNull(0) != null) {
                                val args = chain.args.toTypedArray()
                                args[0] = emptySuccessResp(loader)
                                session.judge(module, t, "market-tamer", "应答已置空")
                                chain.proceedWith(chain.thisObject, args)
                            } else {
                                chain.proceed()
                            }
                        }
                        // 搜索请求源：返 BaseResp(errorCode=-1)
                        t.id == "market-tamer:feed.src.o" -> {
                            val resp = runCatching {
                                loader.loadClass(BASE_RESP).getDeclaredConstructor().apply { isAccessible = true }
                                    .newInstance().also { callMethod(it, "setErrorCode", -1) }
                            }.getOrNull()
                            session.judge(module, t, "market-tamer", "搜索推荐源已拦")
                            resp
                        }
                        // discover 卡片：after→0
                        t.id == "market-tamer:feed.upd.discover" -> {
                            val r = chain.proceed()
                            if ((r as? Int) != 0) session.judge(module, t, "market-tamer", "发现更多卡已清")
                            0
                        }
                        // 详情页推荐：→null 后 postValue(BaseResult.Success(空 BaseResp))
                        t.id.startsWith("market-tamer:feed.det.h") -> {
                            postEmptySuccess(loader, chain.thisObject)
                            session.judge(module, t, "market-tamer", "详情推荐已拦+空收口")
                            null
                        }
                        // 详情协程桥：→null + 经 this$0 拿 ViewModel 收口
                        t.id == "market-tamer:feed.det.bridge" -> {
                            val vm = readField(chain.thisObject, "this\$0")?.let { callMethod(it, "getMAppDetailRecommendViewModel") }
                            if (vm != null) postEmptySuccess(loader, vm)
                            session.judge(module, t, "market-tamer", "详情桥已拦+空收口")
                            null
                        }
                        // 其余（C1/G1/更新页 bridge）：目标入参置 null
                        else -> {
                            val idx = if (t.id == "market-tamer:feed.upd.bridge") 1 else 0
                            if (chain.args.getOrNull(idx) != null) {
                                val args = chain.args.toTypedArray()
                                args[idx] = null
                                session.judge(module, t, "market-tamer", "入参已置空")
                                chain.proceedWith(chain.thisObject, args)
                            } else {
                                chain.proceed()
                            }
                        }
                    }
                }.getOrElse {
                    module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer feed 拦截异常（已吞，放行原方法）: ${it.message}")
                    chain.proceed()
                }
            }
        }
    }

    /** tamer emptySuccessResp：BaseResp{errorCode=0, data=MultiAssemblyDataResp{空两表, offset=0}}。 */
    private fun emptySuccessResp(loader: ClassLoader): Any? = runCatching {
        val data = loader.loadClass("$PKG.network.response.MultiAssemblyDataResp")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val empty = ArrayList<Any>()
        callMethod(data, "setAssemblyList", empty)
        callMethod(data, "setAssemblyNewList", empty)
        callMethod(data, "setAssemblyOffset", 0)
        loader.loadClass(BASE_RESP).getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            .also { callMethod(it, "setErrorCode", 0); callMethod(it, "setData", data) }
    }.getOrNull()

    /** tamer postEmptySuccess：vm.d()（LiveData）postValue(BaseResult.Success(空 BaseResp))。 */
    private fun postEmptySuccess(loader: ClassLoader, vm: Any?) {
        runCatching {
            val liveData = callMethod(vm, "d") ?: return
            val resp = loader.loadClass(BASE_RESP).getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            callMethod(resp, "setErrorCode", 0)
            val success = loader.loadClass("$PKG.network.base.BaseResult\$Success")
                .getConstructor(Any::class.java).newInstance(resp)
            callMethod(liveData, "postValue", success)
        }
    }
}
