package top.honorhook.xposed.target

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import top.honorhook.data.KEY_F1_VIP_ENABLED
import top.honorhook.data.KEY_F2_FREE_ENABLED
import top.honorhook.data.KEY_F3_TRIAL_ENABLED
import top.honorhook.data.KEY_F4_NO_RESET_ENABLED
import top.honorhook.data.KEY_HOOK_ENABLED
import top.honorhook.xposed.MethodResolver
import top.honorhook.xposed.RemotePrefsHolder

/**
 * 荣耀主题商店目标（F1-F4）——docs/honorhook-theme-requirements.md 功能需求落地。
 *
 * 实现依据：docs/honor-theme-payment-analysis.md 第 6 节 Hook 点清单（逆向实证的类名/方法签名，
 * 目标类名在 keep 名单内可读、不混淆漂移）。本次实现前已逐一对照
 * system-files/theme-store-jadx/sources 复核全部目标类/方法存在性与签名（ItemInfo、
 * OnlinePreviewBaseActivity、VipManager、VipState、tryout.a、TimeLimitIncentiveInfo、
 * ThemeResManager、AgreeRepo、BaseResItemBean 均命中，报告行号与源码一致）。
 *
 * 2026-10 反查修正：docs/theme-ui-real-chain.md（主题商店 UI 真实渲染链反查）为按钮矩阵方向与
 * T1-T10 新点的唯一依据，所有类名/方法/值均已对照 theme-store-jadx/sources 实证：
 * - 按钮矩阵"门"方向修正：isBuyBtnShow/isVipBtnShow 等门类按"门要放行"统一改 true（原 false 会跳过
 *   updateButtons，按钮停留 XML 初始态＝真机"没变"的机制原因，§0.1）；
 * - 新增 T1 IncentiveData.getThemeDetailButtonType→0（免费领分支；勿返 -1 会全隐藏）、
 *   T2/T3 ToolBarGroupLayout/WallpaperDetailView.needShowBuyButton→false（壁纸/挂件平行购买门）、
 *   T5/T6 VipState.getVipStatus/getVipType →2（会员态；原 1=NOT_VIP 是"新用户专享"卡根因）、
 *   T8 MineVipCardView.getVipCardType→SINGLE_WITH_SAVE（我的页会员卡，兜底 T5）；
 * - T10 updateButtons 直改标可选、本轮不挂（先挂判定点，见"不挂的点"）。
 * 实证位置：IncentiveData.java:142、ToolBarGroupLayout.java:489/924、WallpaperDetailView.java:405/722、
 * MineVipCardView.java:1880-1904（VipCardType 内部枚举 L261）、VipState.java:314/319/355、
 * OnlinePreviewBaseActivity.java:3097/4161/5686/2920-2924。
 *
 * 功能 → hook 映射：
 * 2026-10 下载 0% 修复（docs/theme-download-chain.md §4 唯一依据，H1/H2 必须成对）：
 * - 现象：付费主题被 F2 免费化后详情页"下载"永远 0% —— F2 把付费项推入免费分支（o52.java:604），
 *   该分支直接用 item JSON 的 fileUrl 当下载地址，而付费资源服务端不下发可用直链（真实 URL 只在
 *   sendProduct 授权接口返回）→ 空 uri 在 DownloadHandler 里静默短路（无读超时无错误回调），UI 定格 0%；
 * - H1 DownloadInfo.isPayedItem（DownloadInfo.java:939，uri 字段 L145 public String）：uri 为空
 *   （null/空串）→ 返回 true（导入授权下载分支）；uri 非空走原 price 逻辑（F2 A5 已归零 →
 *   真免费资源照常 false → 免费直下分支，不误伤）；已购主题 URL 含 download.do?k= 非空 → 原逻辑；
 * - H2 o52.addDownloadItem(Context, DownloadInfo, int, boolean)（o52.java:882；dex 实证 `Lo52;` 默认包类，
 *   非 ads_mobile_sdk/yads 同名类）：DownloadInfo.uri 为空 → 第 3 参 downloadType 强制 2（TRIAL，
 *   分派点 o52.java:617 → e71 试用策略 → sendProduct(trialFlag=1) 服务端签发试用 URL+密钥）；
 * - H2b 兜底 common.a.getDownloadType()（builder 产品类分派读出，o52.java:615-616；a.java:78 实证）：
 *   主题详情页主链路 common/d.java:233 直调 builder 版（L563）不经过 4 参版，H2 须在此分派点同条件
 *   返 2 才覆盖详情页（4 参版内部也转 builder 版，双点同语义、uri 空判定一致、不冲突）；
 * - H2b 兜底 common.a.getDownloadType()（builder 产品类分派读出，o52.java:615-616；a.java:78 实证）：
 *   主题详情页主链路 common/d.java:233 直调 builder 版（L563）不经过 4 参版，H2 须在此分派点同条件
 *   返 2 才覆盖详情页（4 参版内部也转 builder 版，双点同语义、uri 空判定一致、不冲突）；
 * - 真免费资源（fileUrl 非空）uri 非空 → H1/H2/H2b 全部放行原逻辑，免费直下不受影响；
 *   只做 H1 不做 H2 → type=1 落 c71 普通购买分支弹 IAP 支付页（文档 §4 明示，必须 H1+H2 成对）。
 * - 挽留弹窗拦截（2026-10 docs/theme-ui-real-chain.md §现象四 4.5，F1/F2 双保险，归组 F1）：
 *   F1 OverallRetentionDialogActivity$OverallRetentionDialogManager.showDialog → 空实现（void 方法拦截器返回
 *   null = 不调原方法，公共咽喉覆盖全部 6 型挽留弹窗含真机 MEMBER_EXPIRE=4）；
 *   F2 OverallRetentionDialogActivity.onCreate → 先 proceed 走原 onCreate 再 finish（ARouter 直启绕过 F1 的
 *   兜底；proceed 后 finish 生命周期完整——不 proceed 会让 onDestroy 等在未初始化状态下跑，照
 *   fanqie MainHook.java:689 after 阶段 finish 先例，HC 的 finish+不 proceed 模式不采用，见 mountOne FINISH_ACTIVITY 注释）；
 *   F3/F5 不挂：getData 源头拦截会连带 TRIAL 资源回收副作用（checkPushWithRoom 收 first=false 清积压 push、
 *   TRIAL_ARRIVED 触发 doResRecycle）、handleArrivedPushMsg 源头拦截影响面大——F1+F2 展示咽喉双保险已够。
 * - F2 全部资源免费 = 报告 A 组 11 点全上（报告"建议全上"）：
 *   - A1-A4 ItemInfo 判定 5 方法短路——isNeedPayItem/isNeedOrder → false、getPayed/isPayed → true、
 *     isVipResouce → false（列表/详情全局生效，所有页面按钮逻辑都走 ItemInfo 判定）；
 *   - A5 price/actualPrice（double 字段）libxposed 102 不能 hook 字段，用读点 getter 归零
 *     （getPrice/getActualPrice → 0.0）+ 写点 setter 写 0（setPrice/setActualPrice 首参强制 0.0）组合实现；
 *   - A11 服务端 bean 源头归零——BaseResItemBean.getPrice/getActualPrice → "0"、getVipOnly → 0（列表接口直接用）；
 *   - A6-A10 详情页按钮矩阵门类统一放行（2026-10 方向修正：原 isBuyBtnShow/isVipBtnShow → false 方向反了，
 *     门要放行，全部常返 true；按钮组最终显隐由 T1 getThemeDetailButtonType → updateButtons switch 主控，
 *     详见上方"2026-10 反查修正"段）。
 * - F1 开启 VIP = 报告 B 组：VipManager.isVipUser → true（报告首选读点）、setVipUser 首参强制 true
 *   （网络/本地任何"过期"回写都失效）；VipState 到期/状态 getter → 2088-08-08 永久有效
 *   （getExpireTimeTimestamp/getRemainingTime/getDisplayTimeTimestamp → 2088-08-08 毫秒时间戳、
 *   getDisplayTime → "2088-08-08 00:00:00"、getVipStatus/getVipType → 2（2026-10 值修正：
 *   1=NOT_VIP 是"新用户专享"卡根因，2=会员态/连续包月，见 T5/T6））；另补 VipState.isVipUser → true
 *   （F1 需求"所有 isVip 校验点"——VipState 自带独立 isVipUser 字段读路径，jadx VipState.java:355）。
 *   真机补充（2026-10 "主题 VIP 已到期"提示链路实证）：到期日期展示走 getDisplayTimeTimestamp
 *   （MineVipCardView.java:430/441、HwThemeManagerActivity.java:894/901）而非 expireTimeTimestamp；
 *   到期弹窗触发判定（VipCheckRepo.getShouldDialog，vip/repository/VipCheckRepo.java:134-319）的
 *   本地/云控两个叶子方法 VipDefaultConfigDataSource.fetchShouldDialogByDefault 与
 *   VipCloudConfigDataSource.fetchShouldDialogByCloud（Kotlin suspend Boolean）→ false 不弹。
 *   B5 VipModel.checkVipStateOnline 不挂：报告标注"非必须，B1 已够"（网络回写点不如读点稳）。
 * - F3 试用无倒计时 = 报告 C 组 9 点全上：离线 8 分钟窗（tryout.a.isValidTheme → true、
 *   isWareOutDated → false）、在线任务授权窗（TimeLimitIncentiveInfo.duringIncentive → true、
 *   getIncentiveExpireTime → Long.MAX）、tryout.a.isSupportTrial/checkCurrentTheme →
 *   true、云控 AgreeRepo.isTrialSupportOnline → true。
 * - F4 任何场景不恢复默认 = "到期/失去特权/回退"路径的判定点全部放行为有效态：
 *   试用结束回退（tryout.a.isValidTheme/isWareOutDated/checkCurrentTheme → 有效态，ThemeTimeFenceManager
 *   到期回收不发"试用已结束"）、ROM ThemeProvider 试用态（ThemeResManager.getThemeApplyState → PERMANENT、
 *   isTrialTheme → false）、已失去全部会员特权（F1 B 组 → 永久 VIP，会员到期入口不再触发）。
 *
 * 功能位归组（F1-F4 独立开关，同一方法只属一个功能位；归组不确定的点保守归 F2，总开关为上位语义）：
 * - F2 全部资源免费：[KEY_F2_FREE_ENABLED] —— A1-A4 判定短路 + A5 价格归零 + A11 服务端 bean 归零
 *   + A6-A10 按钮矩阵门类放行（方向修正，2026-10）+ T1 服务端按钮类型→0（updateButtons switch 主控）
 *   + T2/T3 壁纸/挂件购买门关闭 + T4（isNeedPayItem/getPayed 即 A1/A3）
 *   + H1/H2/H2b（下载链 0% 修复：空 URL 付费项 → 试用授权下载通道，theme-download-chain.md §4）；
 * - F1 开启 VIP：[KEY_F1_VIP_ENABLED] —— B1/B2 VipManager 读写点 + B3/B4 VipState 到期/状态
 *   + VipState.isVipUser + T5/T6（vipStatus/vipType → 2 会员态，2026-10 值修正）+ T8 我的页卡型
 *   + F1/F2 挽留弹窗拦截（showDialog 空实现 + onCreate finish 兜底，theme-ui-real-chain.md §现象四）；
 * - F3 试用无倒计时：[KEY_F3_TRIAL_ENABLED] —— C3/C5/C6/C8/C9（支持试用/任务授权窗/云控/试用角标）；
 * - F4 永不恢复默认：[KEY_F4_NO_RESET_ENABLED] —— C4（当前主题试用态核查，防 fence 到期回收）+ C7（应用状态 → PERMANENT）；
 * - 保守归 F2（F3/F4 双用途、归组不确定）：C1/C2（离线试用有效判定，8 分钟窗/SP 超窗）。
 *
 * 开关语义（照 HC 模式，与 HookEntry 演示 hook 同构）：挂载无条件（onPackageLoaded 时机），
 * 每个 intercept 内**现读**远程 prefs（[KEY_HOOK_ENABLED] 总开关 × 功能位 key）决定本次是否生效——
 * 总关 = 原逻辑放行（等效未启用）；总开时按功能位归组：组开关关 = 该组原逻辑放行，开 = 短路改返回值/强制参数。
 * UI 切开关下一次调用即生效，无需重启进程。
 *
 * 挂载健壮性（真机验证前加固，docs/gap-analysis.md G1/G2）：
 * - 懒加载重试：类/方法未解析的挂载项收集，Handler postDelayed 约 500ms 重试一轮、再失败约 2s 最后一轮
 *   （照 HC installCustomTileHooks postDelayed 模式，见 reference/HyperChanger HyperSystemUiModule.kt L408-416），
 *   仍失败 WARN 放弃；
 * - 挂载汇总：收尾输出 `ThemeStoreHook: installed N/M`（成功数 N + 失败点简列，**始终输出**），
 *   逐条挂载细节日志由 [DEBUG_LOGGING] 门控（默认关）。
 *
 * 不挂的点（如实标注）：
 * - D 组（购买/已购流程 5 点）：F2 全免费后不存在购买场景（isNeedPayItem/isNeedOrder 已短路），
 *   且 D4/D5 属服务端单/签名与 IAP SDK 支付页，本地 hook 无效（报告 §6 D 组注）。
 * - B5（非必须，B1 已够，见上）。
 * - T10 OnlinePreviewBaseActivity.updateButtons 直改（theme-ui-real-chain.md §五 标可选、绕服务端类型）：
 *   全读点都走 T1 的 getThemeDetailButtonType（L3099/1733/2676/2714/3782 实证），T1 已覆盖 switch 输入，
 *   先不直改方法体，真机不足再上。
 * - F3/F5 挽留弹窗源头拦截（theme-ui-real-chain.md §4.5 表）：F3 VipPopCfgVM.getData 常返 Pair(false,false)
 *   会让 checkPushWithRoom 收 first=false 清掉该条积压 push（:3621），且 TRIAL_ARRIVED 同时触发
 *   doResRecycle（试用资源回收，不想要的副作用）；F5 HonorMsgUtil.handleArrivedPushMsg 跳过 savePushData
 *   影响服务端推送入库面大、且已入库数据仍需 F1/F2 兜——展示咽喉双保险已覆盖全部 6 型，源头不拦。
 */
object ThemeStoreHook : TargetHook {

    override val packageName = "com.hihonor.android.thememanager"

    private const val PKG = "com.hihonor.android.thememanager"

    // 目标类（报告 §6；逐一核过 jadx 源码）
    private const val CLS_ITEM_INFO = "$PKG.common.ItemInfo"
    private const val CLS_ITEM_BEAN = "$PKG.hitop.entity.res.BaseResItemBean"
    private const val CLS_PREVIEW = "$PKG.OnlinePreviewBaseActivity"
    private const val CLS_VIP_MANAGER = "$PKG.vip.manager.VipManager"
    private const val CLS_VIP_STATE = "$PKG.vip.data.VipState"
    private const val CLS_VIP_DEFAULT_CONFIG = "$PKG.vip.datasource.VipDefaultConfigDataSource"
    private const val CLS_VIP_CLOUD_CONFIG = "$PKG.vip.datasource.VipCloudConfigDataSource"

    // 2026-10 反查新增（docs/theme-ui-real-chain.md §五 T1/T2/T3/T8，均对照 jadx 源码实证）
    private const val CLS_INCENTIVE_DATA = "$PKG.incentive.IncentiveData"
    private const val CLS_TOOLBAR_GROUP_LAYOUT = "$PKG.entity.ToolBarGroupLayout"
    private const val CLS_WALLPAPER_DETAIL_VIEW = "$PKG.detail.WallpaperDetailView"
    private const val CLS_MINE_VIP_CARD = "$PKG.logic.mine.widget.MineVipCardView"
    /** 我的页 VIP 卡型枚举（MineVipCardView 内部类，MineVipCardView.java:261 实证；反射解析 SINGLE_WITH_SAVE）。 */
    private const val CLS_VIP_CARD_TYPE = "$PKG.logic.mine.widget.MineVipCardView\$VipCardType"

    // 2026-10 挽留弹窗拦截（docs/theme-ui-real-chain.md §现象四 4.5 F1/F2，jadx 实证）：
    // - OverallRetentionDialogManager 是 OverallRetentionDialogActivity 的 Kotlin object 嵌套静态类
    //   （OverallRetentionDialogActivity.java:150-163；showDialog L158-161 起 ARouter 带 Parcelable DIALOG_PARAMS）；
    // - F2 目标类即 Activity 自身（onCreate L861 protected void onCreate(@Nullable Bundle)，extends FragmentActivity）。
    private const val CLS_RETENTION_DIALOG_ACTIVITY = "$PKG.overallretentiondialog.ui.OverallRetentionDialogActivity"
    private const val CLS_RETENTION_DIALOG_MANAGER = "$PKG.overallretentiondialog.ui.OverallRetentionDialogActivity\$OverallRetentionDialogManager"

    /** 2088-08-08 00:00:00 东八区毫秒时间戳——会员到期时间的落地值（Long.MAX 会让日期格式化/剩余天数判定出现异常值）。 */
    private const val TS_2088_08_08_MILLIS = 3_742_732_800_000L
    /** 2088-08-08 展示字符串（与服端 displayTime 同格式 "yyyy-MM-dd HH:mm:ss"，即 fn5 默认解析格式）。 */
    private const val TS_2088_08_08_STR = "2088-08-08 00:00:00"
    private const val CLS_TRYOUT = "$PKG.tryout.a"
    private const val CLS_INCENTIVE_INFO = "$PKG.incentive.entity.TimeLimitIncentiveInfo"
    private const val CLS_AGREE_REPO = "$PKG.ams.mgr.AgreeRepo"
    private const val CLS_THEME_RES_MANAGER = "$PKG.theme.apply.ThemeResManager"
    private const val CLS_THEME_APPLY_STATE = "$PKG.theme.apply.ThemeApplyState"

    // 2026-10 下载链修复（docs/theme-download-chain.md §4，jadx 源码 + dex 双重实证）：
    // - common.DownloadInfo：下载条目模型（public String uri 字段，DownloadInfo.java:145；isPayedItem L939 只看 price）；
    // - o52：下载管理器，**默认包类**（dex 类描述符 `Lo52;` 实证于 classes18.dex，非 ads_mobile_sdk/yads 同名类），
    //   addDownloadItem(Context, DownloadInfo, int, boolean)（L882，转调 1 参 builder 版 L563，分派 L615-633）；
    // - common.a：builder 产品类（getDownloadType L78 / getDownloadInfo L74）——详情页主链路 common/d.java:233
    //   直调 builder 版、不经过 4 参版，H2b 挂此分派读出兜底。
    private const val CLS_DOWNLOAD_INFO = "$PKG.common.DownloadInfo"
    private const val CLS_DOWNLOADER_MGR = "o52"
    private const val CLS_DOWNLOAD_MODEL = "$PKG.common.a"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // 注意：libxposed 102 的 PackageLoadedParam 只有 defaultClassLoader（无 classLoader 属性，HookEntry 同款写法）
        val loader = param.defaultClassLoader
        // DexKit 兜底轨（MethodResolver 第二轨，见 mountOne findMethod 兜底）需要目标 APK 磁盘路径；
        // libxposed 102 实码：param.applicationInfo.sourceDir（HC ScreenRecorderModule.kt:85 实证）
        apkPath = param.applicationInfo?.sourceDir
        // 预热远程 prefs（RemotePrefsHolder：拿到后进程级缓存、失败每次调用自动重读——修复「install 期
        // 一次性读取失败后 null 冻结」缺陷）；每个 intercept 仍在调用时现读 obtain()，不持有 install 期引用
        RemotePrefsHolder.obtain(module)

        retryRounds = 0
        okCount = 0
        failedIds.clear()

        // 挂载表 46 项（原 44 + 2026-10 挽留弹窗拦截 F1/F2 共 2 项）：按 F1-F4 功能位归组收集（"同一方法只属一个功能位"）；类/方法未解析的项进入懒加载重试
        val pending = mutableListOf<MountTask>()
        collectFreeJudgmentHooks(pending)   // F2 A1-A4（含 A3 双读点）
        collectPriceZeroHooks(pending)      // F2 A5（getter 归零 + setter 写 0 组合）
        collectBeanSourceZeroHooks(pending) // F2 A11
        collectButtonMatrixHooks(pending)   // F2 A6-A10（门类统一放行，方向修正）
        collectRealChainHooks(pending)      // F2 T1-T3（真实渲染链主控，theme-ui-real-chain.md §五）
        collectDownloadChainHooks(pending)  // F2 H1/H2/H2b（下载 0% 修复，theme-download-chain.md §4）
        collectVipHooks(pending)            // F1 B1-B4 + T5/T6 值修正（→2）+ T8 新增 + 补充 VipState.isVipUser
        collectRetentionDialogHooks(pending) // F1 挽留弹窗拦截 F1/F2（showDialog 空实现 + onCreate finish 兜底）
        collectTrialHooks(pending)          // F3 C3/C5/C6/C8 + F4 C4 + 保守 F2 C1/C2（详见类注释归组表）
        collectApplyStateHooks(pending)     // F4 C7（PERMANENT 延迟解析）+ F3 C9
        totalCount = pending.size

        attemptMount(pending, module, loader)
    }

    // ---------------- F2：全部资源免费（功能位 key = KEY_F2_FREE_ENABLED） ----------------

    /** A1-A4：ItemInfo 付费/已购判定全短路为免费态（列表/详情全局生效——所有页面按钮逻辑都走它）。 */
    private fun collectFreeJudgmentHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_ITEM_INFO, "isNeedPayItem", 0, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.isNeedPayItem", "F2 A1 需付费判定 → 免费")
        addConst(pending, CLS_ITEM_INFO, "isNeedOrder", 0, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.isNeedOrder", "F2 A2 需下单判定 → 不弹下单")
        addConst(pending, CLS_ITEM_INFO, "getPayed", 0, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.getPayed", "F2 A3 已购读点1 → 已购")
        addConst(pending, CLS_ITEM_INFO, "isPayed", 0, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.isPayed", "F2 A3 已购读点2 → 已购")
        addConst(pending, CLS_ITEM_INFO, "isVipResouce", 0, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.isVipResouce", "F2 A4 VIP 专属标记 → 移除以当普通资源")
    }

    /** A5：price/actualPrice（double 字段）libxposed 102 无法 hook 字段，读点（getter 归零）+ 写点（setter 写 0）组合覆盖。 */
    private fun collectPriceZeroHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_ITEM_INFO, "getPrice", 0, "double", 0.0, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.getPrice", "F2 A5 价格读点（展示）→ 0 元")
        addConst(pending, CLS_ITEM_INFO, "getActualPrice", 0, "double", 0.0, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.getActualPrice", "F2 A5 现价读点（展示）→ 0 元")
        addForceArg(pending, CLS_ITEM_INFO, "setPrice", 1, "void", 0.0, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.setPrice", "F2 A5 价格写点 → 强制写 0")
        addForceArg(pending, CLS_ITEM_INFO, "setActualPrice", 1, "void", 0.0, KEY_F2_FREE_ENABLED,
            "theme:ItemInfo.setActualPrice", "F2 A5 现价写点 → 强制写 0")
    }

    /** A11：列表接口直接用服务端 bean 源头——String 价格归 "0"、vipOnly 归 0。 */
    private fun collectBeanSourceZeroHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_ITEM_BEAN, "getPrice", 0, "java.lang.String", "0", KEY_F2_FREE_ENABLED,
            "theme:ItemBean.getPrice", "F2 A11 服务端 bean 价格源头 → \"0\"")
        addConst(pending, CLS_ITEM_BEAN, "getActualPrice", 0, "java.lang.String", "0", KEY_F2_FREE_ENABLED,
            "theme:ItemBean.getActualPrice", "F2 A11 服务端 bean 现价源头 → \"0\"")
        addConst(pending, CLS_ITEM_BEAN, "getVipOnly", 0, "int", 0, KEY_F2_FREE_ENABLED,
            "theme:ItemBean.getVipOnly", "F2 A11 服务端 bean VIP 专属标记 → 0")
    }

    /**
     * A6-A10：详情页按钮矩阵——门类统一放行（方向修正，2026-10 按 docs/theme-ui-real-chain.md §0.1/§四 反查结论）。
     *
     * [DEAD/半效标注，依据 §四 新旧对照表，本轮不删、保守保留（真机观察日志确认后按需清理）]
     * 反查机制：这些方法不是"渲染开关"而是"入口门"。旧实现 isBuyBtnShow/isVipBtnShow 返 false 方向反了——
     * isBuyBtnShow 返 false 会让 L3097 `if (isBuyBtnShow(item)) { ...getThemeDetailButtonType()...updateButtons(...) }`
     * 整段不执行，按钮停留在 XML 初始 visible 态（真机"没变"的机制原因）；isVipBtnShow/isFreeAccessBtnShow/
     * isTrialBtnShow 只喂 L4161 changeVipBtnsVisibility 的布尔参数（mBuyBtn/mVipBtn/mFreeAccessBtn/mTrialBtn
     * 显隐组装，L2920-3028 实证）、非最终仲裁。
     * 修正 = 门类统一放行（全部常返 true），按钮组最终显隐改由 T1 `IncentiveData.getThemeDetailButtonType()`
     * （常返 0）→ updateButtons switch（L5686，0=免费领小按钮组）主控。mBuyBtn 最终显隐另受
     * `u02.isSupportPayed()`（设备 IAP 能力，u02.java:459）与 isShowIconCustom/isAudit 约束（L2920-2924）。
     */
    private fun collectButtonMatrixHooks(pending: MutableList<MountTask>) {
        // 方向修正（原 false）：门要放行——门开 L3097 updateButtons 才执行（配合 T1 走免费领分支）；
        // 不另挂【购买】隐藏：mBuyBtn 显隐由 changeVipBtnsVisibility 的 `u02.isSupportPayed()` 能力判定兜底
        addConst(pending, CLS_PREVIEW, "isBuyBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isBuyBtnShow", "F2 A6 购买门放行（原 false 跳过 updateButtons 停留初始态）→ true")
        // 保留 true：门类统一放行原则（下载按钮常显，免费资源直接下载）
        addConst(pending, CLS_PREVIEW, "isDownloadBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isDownloadBtnShow", "F2 A7 '下载'按钮常显（门类放行）")
        // 保留 true：门类统一放行原则（对照表：布尔参数非最终仲裁，T1 主控）
        addConst(pending, CLS_PREVIEW, "isFreeAccessBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isFreeAccessBtnShow", "F2 A8 '免费获取'门放行（非最终仲裁）")
        // 保留 true：门类统一放行原则（对照表：布尔参数非最终仲裁，T1 主控）
        addConst(pending, CLS_PREVIEW, "isTrialBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isTrialBtnShow", "F2 A10 '试用'门放行（非最终仲裁）")
        // 方向修正（原 false）：VIP 门放行（对照表：只喂 changeVipBtnsVisibility 布尔参数、
        // 非最终仲裁——mVipBtn 显隐归 T1 updateButtons switch 分支主控）
        addConst(pending, CLS_PREVIEW, "isVipBtnShow", 1, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:Preview.isVipBtnShow", "F2 A9 VIP 门放行（原 false 属门类误关）→ true")
    }

    /**
     * T1-T3（docs/theme-ui-real-chain.md §五 新 hook 点清单）：真实渲染链主控点。
     *
     * 机制：详情页按钮组最终显隐 = 门通过后读服务端下发按钮类型 `IncentiveData.getThemeDetailButtonType()`
     * → `updateButtons()` switch（OnlinePreviewBaseActivity.java:3097-3102、ToolBarGroupLayout.java:924-932、
     * WallpaperDetailView.java:722-730 实证；全部读点走同一个 getter，钩一处全覆盖）。
     * - T1 常返 0：免费领小按钮组（case 0）；服务端激励失败时 IncentiveCommonManager.java:636 强制置 -1
     *   会 hideLayout() 全隐藏（含下载/应用按钮）——绝不返 -1；
     * - T2/T3 常返 false：壁纸/挂件详情的平行购买门（与 isBuyBtnShow 逐行同构但独立的实体/弹窗两份副本，
     *   旧 hook 未覆盖）——needShowBuyButton=false = 门开，updateButtons 走免费/下载分支。
     */
    private fun collectRealChainHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_INCENTIVE_DATA, "getThemeDetailButtonType", 0, "int", 0, KEY_F2_FREE_ENABLED,
            "theme:IncentiveData.getThemeDetailButtonType", "F2 T1 服务端按钮类型 → 0（免费领小按钮组；勿返 -1 全隐藏）")
        addConst(pending, CLS_TOOLBAR_GROUP_LAYOUT, "needShowBuyButton", 1, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:ToolBar.needShowBuyButton", "F2 T2 壁纸/挂件购买门（ToolBarGroupLayout:489，旧未覆盖）→ 关，走免费分支")
        addConst(pending, CLS_WALLPAPER_DETAIL_VIEW, "needShowBuyButton", 1, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:WallpaperDetail.needShowBuyButton", "F2 T3 壁纸详情弹窗购买门（WallpaperDetailView:405，T2 同构副本）→ 关")
    }

    /**
     * H1/H2/H2b（docs/theme-download-chain.md §4 下载 0% 修复，2026-10 新增 3 项）：
     * 空 URL 付费主题 → 试用授权下载通道（e71 = sendProduct trialFlag=1，服务端签发真实 URL + 内容密钥）。
     *
     * 机制：F2 A5 把 price 归零后 isPayedItem() 恒 false → o52 免费分支（L604）直接用 item JSON 的
     * fileUrl 下载，付费资源 fileUrl 服务端不下发直链 → uri 空 → DownloadHandler 静默短路 → 0% 定格。
     * H1 把"uri 空的项"判为付费（进授权分支），H2/H2b 把 downloadType 强制 2（TRIAL）避开普通分派
     * 的 c71 普通购买（会弹 IAP）。三个挂载点共用条件：**DownloadInfo.uri 为空（null/空串）**——
     * uri 非空（真免费资源有 fileUrl / 已购主题有 download.do?k= 直链）一律放行原逻辑，不误伤。
     *
     * 挂载点分工（都是 .uri 判空条件路由，详见 MountTask.uriEmptyRoute/uriOwner）：
     * - H1 common.DownloadInfo.isPayedItem（DownloadInfo.java:939，uri 字段 L145 public String）：
     *   uri 空 → 常返 true；
     * - H2 o52.addDownloadItem(Context, DownloadInfo, int, boolean)（o52.java:882，dex 实证默认包类
     *   `Lo52;`，classes18.dex；非 ads_mobile_sdk/yads 同名 o52）：第 2 参 DownloadInfo.uri 空 → 第 3 参
     *   downloadType 强制 2（覆盖 4 参版直调方：DownloadController/字体/壁纸/云游等 15+ 处）；
     * - H2b common.a.getDownloadType()（builder 产品类，a.java:78；o52.java:616 付费分支分派读出）：
     *   详情页主链路 common/d.java:233 直调 1 参 builder 版（o52.java:563）不经过 4 参版，H2 须在此
     *   分派读点同条件返 2 兜底（4 参版内部 885 行也转 builder 版，H2/H2b 同语义、uri 判定一致、不冲突）。
     */
    private fun collectDownloadChainHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_DOWNLOAD_INFO,
            methodName = "isPayedItem",
            paramCount = 0,
            returnType = "boolean",
            value = true,
            featureKey = KEY_F2_FREE_ENABLED,
            id = "theme:DownloadInfo.isPayedItem",
            description = "F2 H1 空 URL 付费项判定 → 付费（导入授权下载分支；uri 字段 DownloadInfo.java:145 实证）",
            uriEmptyRoute = true,
        )
        pending += MountTask(
            kind = MountKind.FORCE_ARG,
            className = CLS_DOWNLOADER_MGR,
            methodName = "addDownloadItem",
            paramCount = 4,
            returnType = "boolean",
            value = 2,
            featureKey = KEY_F2_FREE_ENABLED,
            id = "theme:o52.addDownloadItem",
            description = "F2 H2 空 URL 项 downloadType → 2（TRIAL；o52.java:882/617 e71 试用策略）",
            uriEmptyRoute = true,
            uriOwner = UriOwner.ARG,
            uriArgIndex = 1,
            forceArgIndex = 2,
        )
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_DOWNLOAD_MODEL,
            methodName = "getDownloadType",
            paramCount = 0,
            returnType = "int",
            value = 2,
            featureKey = KEY_F2_FREE_ENABLED,
            id = "theme:DownloadModel.getDownloadType",
            description = "F2 H2b 空 URL 项 downloadType 分派读出 → 2（TRIAL；o52.java:616 分派点，兜底详情页 builder 版链路）",
            uriEmptyRoute = true,
            uriOwner = UriOwner.GET_DOWNLOAD_INFO,
        )
    }

    // ---------------- F1：开启 VIP（功能位 key = KEY_F1_VIP_ENABLED） ----------------

    /** B 组：VipManager 静态布尔读点 + VipState 到期/状态 getter → 永久 VIP（B5 不挂，见类注释）。 */
    private fun collectVipHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_VIP_MANAGER, "isVipUser", 0, "boolean", true, KEY_F1_VIP_ENABLED,
            "theme:VipManager.isVipUser", "F1 B1 VIP 判定读点（首选）→ 是 VIP")
        // B2：网络/本地任何 setVipUser(false)（含服务端拉到的过期状态）都强制写 true
        addForceArg(pending, CLS_VIP_MANAGER, "setVipUser", 1, "void", true, KEY_F1_VIP_ENABLED,
            "theme:VipManager.setVipUser", "F1 B2 VIP 写点 → 强制置 true")
        // F4："已失去全部会员特权"触发路径的判定源——到期时间置 2088-08-08、状态置生效值（归组 F1 VIP 类）
        addConst(pending, CLS_VIP_STATE, "getExpireTimeTimestamp", 0, "long", TS_2088_08_08_MILLIS, KEY_F1_VIP_ENABLED,
            "theme:VipState.getExpireTimeTimestamp", "F1/F4 B3 会员到期时间戳 → 2088-08-08（VipResSavedActivity:611 续费提示判定用）")
        addConst(pending, CLS_VIP_STATE, "getRemainingTime", 0, "java.lang.Long", TS_2088_08_08_MILLIS, KEY_F1_VIP_ENABLED,
            "theme:VipState.getRemainingTime", "F1/F4 B3 剩余时长 → 2088-08-08（VipCardViewModel 倒计时/剩余天数判定用）")
        // T5 值修正（2026-10，theme-ui-real-chain.md §三/§五）：vipStatus==1 是 NOT_VIP——MineVipCardView
        // getVipCardType switch（L1880-1904）1=NOT_VIP（即"新用户专享"开通引导卡）、2=SINGLE_WITH_SAVE。
        // 原值 1 恰好命中 NOT_VIP 卡（真机"新用户专享"没消掉的根因）；新值 2 = 会员卡（配合 T8 卡型兜底）。
        addConst(pending, CLS_VIP_STATE, "getVipStatus", 0, "int", 2, KEY_F1_VIP_ENABLED,
            "theme:VipState.getVipStatus", "F1 T5 VIP 状态 → 2=SINGLE_WITH_SAVE 会员态（原 1=NOT_VIP 显示'新用户专享'卡）")
        // T6 值修正（2026-10，theme-ui-real-chain.md §二/§五）：getVipType()==2=连续包月——续费入口判定
        // `vipState.isVipUser() && getVipType()==2`（VipMemberCenterActivity.java:274-283）；
        // VipCenterFragment.java:623-630 账户态识别 acountVipType。原值 1 不激活该入口。
        addConst(pending, CLS_VIP_STATE, "getVipType", 0, "java.lang.Integer", 2, KEY_F1_VIP_ENABLED,
            "theme:VipState.getVipType", "F1 T6 VIP 类型 → 2 连续包月（激活续费入口/账户已开通识别）")
        // 补充（F1 需求"所有 isVip 校验点"）：VipState 自带独立 isVipUser 字段读路径（jadx VipState.java:355）
        addConst(pending, CLS_VIP_STATE, "isVipUser", 0, "boolean", true, KEY_F1_VIP_ENABLED,
            "theme:VipState.isVipUser", "F1 补充 VipState 内 VIP 判定 → 是 VIP")
        // 真机补充（2026-10 "主题 VIP 已到期"仍提示的其余链路，jadx 实证）：
        // - UI 到期日期展示走 getDisplayTimeTimestamp 而非 expireTimeTimestamp——MineVipCardView.java:430/441
        //   （"我的"页 VIP 卡"到期时间"）、HwThemeManagerActivity.java:894/901（主界面"即将到期/已用尽"提示文案）、
        //   VipMemberCenterActivity.java:488（续费管理页跳转传参）→ 2088-08-08 毫秒；
        // - getDisplayTime()（"yyyy-MM-dd HH:mm:ss"）是弹窗判定入参（VipCheckRepo.getShouldDialog 传它给
        //   fetchShouldDialogByDefault 做日期解析，VipDefaultConfigDataSource.java:65/69）→ 2088-08-08 字符串；
        // - 到期提示弹窗触发判定（VipCheckRepo.getShouldDialog 两个叶子判定）→ 常返 false 不弹：
        //   本地默认档 VipDefaultConfigDataSource.fetchShouldDialogByDefault（vipType==1 "即将到期"7-14/0-7 天、
        //   vipType==3 "已过期"当天）与云控档 VipCloudConfigDataSource.fetchShouldDialogByCloud
        //   （均为 Kotlin suspend 方法、返回 Object/Boolean：直接返回 Boolean 即非挂起值，语义等价"不弹"）。
        addConst(pending, CLS_VIP_STATE, "getDisplayTimeTimestamp", 0, "long", TS_2088_08_08_MILLIS, KEY_F1_VIP_ENABLED,
            "theme:VipState.getDisplayTimeTimestamp", "F1 补充 到期日期展示时间戳 → 2088-08-08（UI 展示链路）")
        addConst(pending, CLS_VIP_STATE, "getDisplayTime", 0, "java.lang.String", TS_2088_08_08_STR, KEY_F1_VIP_ENABLED,
            "theme:VipState.getDisplayTime", "F1 补充 到期展示字符串 → 2088-08-08（弹窗判定入参）")
        addConst(pending, CLS_VIP_DEFAULT_CONFIG, "fetchShouldDialogByDefault", 3, "java.lang.Object", false, KEY_F1_VIP_ENABLED,
            "theme:VipDefaultConfig.fetchShouldDialogByDefault", "F1 补充 到期弹窗触发判定（本地默认档）→ 不弹")
        addConst(pending, CLS_VIP_CLOUD_CONFIG, "fetchShouldDialogByCloud", 2, "java.lang.Object", false, KEY_F1_VIP_ENABLED,
            "theme:VipCloudConfig.fetchShouldDialogByCloud", "F1 补充 到期弹窗触发判定（云控档）→ 不弹")
        // T8（新增，2026-10，theme-ui-real-chain.md §三/§五）：我的页 VIP 卡型直返 SINGLE_WITH_SAVE。
        // getVipCardType(VipState) switch vipStatus（MineVipCardView.java:1880-1904）：1=NOT_VIP（default 也是）＝
        // "新用户专享"开通引导卡（layout_mine_vip_card_1_not_vip.xml 唯一命中，§三 二进制 XML 扫描实证）、
        // 2=SINGLE_WITH_SAVE 会员卡。常返会员卡是最直接路径（兜底 T5 的 vipStatus 值）。枚举是 MineVipCardView
        // 内部类 `MineVipCardView$VipCardType`（L261 实证），用 resolveValue 延迟到挂载时解析（照 C7 PERMANENT 模式）；
        // 解析失败归入懒加载重试（NOT_RESOLVED）。
        // 注意：enumConstants 会触发目标枚举类 <clinit>（invoke 静态 values()）——已由下方 runCatching 包裹（Throwable
        // 全捕获），且仅运行于主题商店进程挂载/重试线程（非 SystemUI/launcher 启动路径，v2.3 boot-loop 事故模式不适用）；
        // 解析失败（类加载/取值异常）归 NOT_RESOLVED 重试。
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_MINE_VIP_CARD,
            methodName = "getVipCardType",
            paramCount = 1,
            returnType = CLS_VIP_CARD_TYPE,
            value = null,
            featureKey = KEY_F1_VIP_ENABLED,
            id = "theme:MineVipCardView.getVipCardType",
            description = "F1 T8 我的页 VIP 卡型 → SINGLE_WITH_SAVE 会员卡（'新用户专享'卡消失）",
            resolveValue = { loader ->
                runCatching {
                    loader.loadClass(CLS_VIP_CARD_TYPE)
                        .enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "SINGLE_WITH_SAVE" }
                }.getOrNull()
            },
        )
    }

    // ---------------- F1：挽留弹窗拦截（VIP 已过期挽留弹窗，双保险；归组 F1） ----------------

    /**
     * F1/F2（docs/theme-ui-real-chain.md §现象四 4.5，唯一依据）：
     * "VIP已过期，立即行动，重享尊贵体验"整体挽留弹窗走 overallretentiondialog 子系统
     * （pushType=VIP_ARRIVED→OverallRetentionDialogType.MEMBER_EXPIRE=4，ArrivedPushManager/VipPopCfgVM/
     * BuildAndShowDialog 触发链，展示咽喉唯一出口是 showDialog）；旧的 fetchShouldDialogBy* hook 拦不住
     * 因为它走的是休眠消费链（4.4 实证）。拦截点选展示咽喉、F1+F2 双保险：
     * - F1 showDialog 空实现（void 方法拦截器返回 null = 不调原方法，libxposed api 语义，
     *   AI-lsposed-temlate/.cursor/skills/libxposed-api/SKILL.md:102 实证）：单点覆盖全部 6 型
     *   （RESOURCE_TRIAL_END=1/MEMBER_SOON_EXPIRE=2/EXPIRED_RESOURCES=3/MEMBER_EXPIRE=4/
     *   EXPIRED_SPRITE=5/MEMBER_LOGOUT=6），含真机 MEMBER_EXPIRE；
     * - F2 onCreate 先 proceed 后 finish（ARouter 直启绕过 F1 的兜底）：不 proceed 会让 Activity 生命周期
     *   在不初始化状态下走完（onDestroy 等回调可能 NPE），proceed 后再 finish 更稳。
     * 不挂 F3/F5 的原因见类注释"不挂的点"。
     */
    private fun collectRetentionDialogHooks(pending: MutableList<MountTask>) {
        pending += MountTask(
            kind = MountKind.BLOCK,
            className = CLS_RETENTION_DIALOG_MANAGER,
            methodName = "showDialog",
            paramCount = 1,
            returnType = "void",
            value = null,
            featureKey = KEY_F1_VIP_ENABLED,
            id = "theme:RetentionMgr.showDialog",
            description = "F1 挽留弹窗公共咽喉空实现（全部 6 型，OverallRetentionDialogActivity.java:158 实证）",
        )
        pending += MountTask(
            kind = MountKind.FINISH_ACTIVITY,
            className = CLS_RETENTION_DIALOG_ACTIVITY,
            methodName = "onCreate",
            paramCount = 1,
            returnType = "void",
            value = null,
            featureKey = KEY_F1_VIP_ENABLED,
            id = "theme:RetentionActivity.onCreate",
            description = "F2 兜底 ARouter 直启：原 onCreate 走完后 finish（OverallRetentionDialogActivity.java:861 实证）",
        )
    }

    // ---------------- F3：试用无倒计时 + F4：回退路径判定放行 ----------------

    /**
     * C1-C6、C8：离线 8 分钟窗 + 在线任务授权窗 + 云控开关 → 永不过期、永不放行回退。
     * 功能位归组：C1/C2 为 F3/F4 双用途、归组不确定 → 保守归 F2；C3/C5/C6/C8 → F3；C4 → F4。
     */
    private fun collectTrialHooks(pending: MutableList<MountTask>) {
        addConst(pending, CLS_TRYOUT, "isValidTheme", 0, "boolean", true, KEY_F2_FREE_ENABLED,
            "theme:TryOut.isValidTheme", "F3/F4 C1 离线试用有效判定（8 分钟窗）→ 永久有效（归组不确定，保守归 F2）")
        addConst(pending, CLS_TRYOUT, "isWareOutDated", 1, "boolean", false, KEY_F2_FREE_ENABLED,
            "theme:TryOut.isWareOutDated", "F3/F4 C2 SP 超窗判定 → 永不过期（归组不确定，保守归 F2）")
        addConst(pending, CLS_TRYOUT, "isSupportTrial", 0, "boolean", true, KEY_F3_TRIAL_ENABLED,
            "theme:TryOut.isSupportTrial", "F3 C3 支持试用 → 常显可用")
        addConst(pending, CLS_TRYOUT, "checkCurrentTheme", 0, "boolean", true, KEY_F4_NO_RESET_ENABLED,
            "theme:TryOut.checkCurrentTheme", "F4 C4 当前主题试用态核查 → 有效（防 fence 到期回收）")
        addConst(pending, CLS_INCENTIVE_INFO, "duringIncentive", 0, "boolean", true, KEY_F3_TRIAL_ENABLED,
            "theme:Incentive.duringIncentive", "F3 C5 在线任务授权窗判定 → 永久有效")
        addConst(pending, CLS_INCENTIVE_INFO, "getIncentiveExpireTime", 0, "long", Long.MAX_VALUE, KEY_F3_TRIAL_ENABLED,
            "theme:Incentive.getIncentiveExpireTime", "F3 C6 授权到期时间 → 拉远")
        addConst(pending, CLS_AGREE_REPO, "isTrialSupportOnline", 0, "boolean", true, KEY_F3_TRIAL_ENABLED,
            "theme:AgreeRepo.isTrialSupportOnline", "F3 C8 云控试用开关 → 常开")
    }

    /** C7、C9：ROM ThemeProvider 路径——试用态判定（含回退来源）与试用角标都走 ThemeResManager。 */
    private fun collectApplyStateHooks(pending: MutableList<MountTask>) {
        // C7：主题应用状态 → PERMANENT（永非试用态，不回退）。PERMANENT 枚举解析依赖 ThemeApplyState 类加载，
        // 用 resolveValue 延迟到每次挂载尝试时解析（类未加载时归入懒加载重试）。
        // 注意：enumConstants 会触发目标枚举类 <clinit>（invoke 静态 values()）——已由下方 runCatching 包裹（Throwable
        // 全捕获），且仅运行于主题商店进程挂载/重试线程（非 SystemUI/launcher 启动路径，v2.3 boot-loop 事故模式不适用）；
        // 解析失败（类加载/取值异常）归 NOT_RESOLVED 重试。
        pending += MountTask(
            kind = MountKind.CONST,
            className = CLS_THEME_RES_MANAGER,
            methodName = "getThemeApplyState",
            paramCount = 0,
            returnType = CLS_THEME_APPLY_STATE,
            value = null,
            featureKey = KEY_F4_NO_RESET_ENABLED,
            id = "theme:ThemeResManager.getThemeApplyState",
            description = "F4 C7 主题应用状态 → PERMANENT（永非试用态，不回退）",
            resolveValue = { loader ->
                runCatching {
                    loader.loadClass(CLS_THEME_APPLY_STATE)
                        .enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "PERMANENT" }
                }.getOrNull()
            },
        )
        addConst(pending, CLS_THEME_RES_MANAGER, "isTrialTheme", 1, "boolean", false, KEY_F3_TRIAL_ENABLED,
            "theme:ThemeResManager.isTrialTheme", "F3 C9 移除'试用中'判定 → 无试用角标/到期提醒")
    }

    // ---------------- 挂载执行：懒加载重试（HC postDelayed 模式）+ 汇总（G1/G2） ----------------

    /** 挂载项执行结果：[NOT_RESOLVED] 类/方法（或依赖值）未解析到——可随类加载重试；[FAILED] hook 挂载异常——重试无意义。 */
    private enum class MountResult { OK, FAILED, NOT_RESOLVED }

    /**
     * 挂载任务类型：[CONST] 常量短路（直接返回常值）；[FORCE_ARG] 第 forceArgIndex 参强制（放行时替换该参）；
     * [BLOCK] void 方法空实现阻断（不调原方法，返回 null——libxposed 对 void 方法拦截器返回 null 即跳过原文，
     * AI-lsposed-temlate SKILL.md:102 实证）；[FINISH_ACTIVITY] 先 proceed 走原方法再对 Activity 实例 finish
     * （Activity 兜底：拦截不 proceed 会让生命周期在未初始化状态下走完，proceed 后 finish 更稳）。
     */
    private enum class MountKind { CONST, FORCE_ARG, BLOCK, FINISH_ACTIVITY }

    /**
     * DownloadInfo 实例获取路径（uriEmptyRoute 条件路由用，theme-download-chain.md §4 H1/H2/H2b）：
     * [THIS] thisObject 本身即 DownloadInfo（H1 的 isPayedItem）；
     * [ARG] 第 uriArgIndex 个参数即 DownloadInfo（H2 的 4 参 addDownloadItem）；
     * [GET_DOWNLOAD_INFO] thisObject.getDownloadInfo() 反射取（H2b 的 common.a 分派点）。
     */
    private enum class UriOwner { THIS, ARG, GET_DOWNLOAD_INFO }

    /** 一次挂载任务的完整描述（类/方法未解析时保留任务本体供懒加载重试）。 */
    private class MountTask(
        val kind: MountKind,
        val className: String,
        val methodName: String,
        val paramCount: Int,
        val returnType: String,
        val value: Any?,                                    // CONST: 常返值；FORCE_ARG: 第 forceArgIndex 参强制值
        val featureKey: String,                             // 功能位开关 key（F1-F4 独立开关）
        val id: String,                                     // 幂等 ID（框架用它防重复/日志标识）
        val description: String,
        val resolveValue: ((ClassLoader) -> Any?)? = null,  // 非空时每次尝试时解析（优先于 value），如 ThemeApplyState.PERMANENT 枚举
        /** 条件路由（下载链 H1/H2/H2b）：true 时仅当 DownloadInfo.uri 为空（null/空串）才短路/改参；uri 非空放行原逻辑（不误伤正常免费/已购项）。默认 false = 既有无条件语义。 */
        val uriEmptyRoute: Boolean = false,
        /** uriEmptyRoute 时 DownloadInfo 实例获取路径（见 [UriOwner]）。 */
        val uriOwner: UriOwner = UriOwner.THIS,
        /** uriOwner == [UriOwner.ARG] 时 DownloadInfo 所在参数下标（H2 为 1，即 (Context, DownloadInfo, …) 的第 2 参）。 */
        val uriArgIndex: Int = -1,
        /** [MountKind.FORCE_ARG] 强制第几参（0 起；默认 0 = 既有 setter 首参语义不变）。 */
        val forceArgIndex: Int = 0,
    )

    /** 重试轮次（已完成的重试数；第 1 轮 500ms 后、第 2 轮 2s 后；照 HC installCustomTileHooks postDelayed 模式）。 */
    private var retryRounds = 0
    private var okCount = 0
    private var totalCount = 0
    private val failedIds = mutableListOf<String>()
    /** 目标 APK 磁盘路径（install 期写入，MethodResolver 第二轨 DexKit 兜底用；null 时跳过 DexKit 兜底）。 */
    private var apkPath: String? = null

    private const val MAX_RETRY_ROUNDS = 2
    private const val RETRY_DELAY_MS = 500L
    private const val RETRY_FINAL_DELAY_MS = 2000L

    /** 挂载细节日志门控（默认关；汇总日志不受门控、始终输出）。 */
    private const val DEBUG_LOGGING = false

    private fun addConst(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        value: Any?,
        featureKey: String,
        id: String,
        description: String,
    ) {
        pending += MountTask(
            kind = MountKind.CONST, className = className, methodName = methodName, paramCount = paramCount,
            returnType = returnType, value = value, featureKey = featureKey, id = id, description = description,
        )
    }

    private fun addForceArg(
        pending: MutableList<MountTask>,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
        forcedArg: Any?,
        featureKey: String,
        id: String,
        description: String,
    ) {
        pending += MountTask(
            kind = MountKind.FORCE_ARG, className = className, methodName = methodName, paramCount = paramCount,
            returnType = returnType, value = forcedArg, featureKey = featureKey, id = id, description = description,
        )
    }

    /**
     * 挂载一轮 pending 任务（HC postDelayed 懒加载重试模式，installCustomTileHooks L408-416 同构）：
     * 成功/挂载失败的从本轮移除，类/方法（或依赖值）未解析的保留 → 全部挂完即输出汇总；
     * 仍有未解析 → Handler postDelayed 约 500ms 重试一轮、再失败约 2s 最后一轮，仍失败 WARN 放弃并输出汇总
     * （含失败点简列）。调度回调运行在主线程 Looper（照 HC 原样使用 Looper.getMainLooper()）。
     */
    private fun attemptMount(
        pending: MutableList<MountTask>,
        module: XposedModule,
        loader: ClassLoader,
    ) {
        val unresolved = mutableListOf<MountTask>()
        for (task in pending) {
            when (mountOne(task, module, loader)) {
                MountResult.OK -> okCount++
                MountResult.FAILED -> failedIds += task.description
                MountResult.NOT_RESOLVED -> unresolved += task
            }
        }
        if (unresolved.isEmpty()) {
            logSummary(module)
        } else if (retryRounds >= MAX_RETRY_ROUNDS) {
            failedIds += unresolved.map { it.description }
            module.log(
                Log.WARN, TAG,
                "部分 hook 最终未挂载（类/方法不存在，已重试 $MAX_RETRY_ROUNDS 轮，放弃）: " +
                    unresolved.joinToString { it.description },
            )
            logSummary(module)
        } else {
            retryRounds++
            val delay = if (retryRounds == 1) RETRY_DELAY_MS else RETRY_FINAL_DELAY_MS
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { attemptMount(unresolved, module, loader) }
                    .onFailure { t -> module.log(Log.ERROR, TAG, "懒加载重试挂载异常", t) }
            }, delay)
        }
    }

    /** 单个挂载任务执行；失败分级：未解析（可随类加载重试）vs hook 异常（永久，重试无意义）。 */
    private fun mountOne(
        task: MountTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountResult {
        val resolved = task.resolveValue?.invoke(loader)
        if (task.resolveValue != null && resolved == null) {
            module.log(Log.WARN, TAG, "hook 未挂载（依赖项未解析，将重试）: ${task.description}")
            return MountResult.NOT_RESOLVED
        }
        val method = findMethod(loader, task.className, task.methodName, task.paramCount, task.returnType)
            ?: MethodResolver.resolveMethod(loader, task.className, task.methodName, task.paramCount, task.returnType, apkPath)
        if (method == null) {
            module.log(Log.WARN, TAG, "hook 未挂载（类/方法不存在，版本差异，将重试）: ${task.description}")
            return MountResult.NOT_RESOLVED
        }
        val value = resolved ?: task.value
        return runCatching {
            val builder = module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(task.id)
            when (task.kind) {
                // 常量短路：路由激活 → 直接返回 value（不调用原方法，改返回值语义）；未激活 → 原逻辑放行
                MountKind.CONST -> builder.intercept { chain ->
                    // 每次调用现读远程 prefs（RemotePrefsHolder 缓存/自愈）——不捕获 install 期 prefs，防 null 冻结
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) value else chain.proceed()
                }
                // 参数强制（setter 写点 / 下载类型路由点）：路由激活 → 第 forceArgIndex 参强制为 value 后放行原逻辑；未激活 → 原逻辑放行
                MountKind.FORCE_ARG -> builder.intercept { chain ->
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) {
                        val args = chain.args?.toTypedArray() ?: arrayOfNulls<Any?>(task.forceArgIndex + 1)
                        args[task.forceArgIndex] = value
                        chain.proceedWith(chain.thisObject, args)
                    } else {
                        chain.proceed()
                    }
                }
                // void 空实现阻断（挽留弹窗 F1）：路由激活 → 返回 null 不调原方法（弹窗不展示）；
                // 触发判据日志不受 DEBUG_LOGGING 门控；未激活 → 原逻辑放行（弹窗照常）
                MountKind.BLOCK -> builder.intercept { chain ->
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) {
                        module.log(Log.INFO, TAG, "挽留弹窗已拦截: ${task.methodName}")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                // 放行后 finish（挽留弹窗 F2 兜底）：先 proceed 走完原 onCreate（生命周期完整——
                // 拦截不 proceed 会让 Activity 在未初始化状态下走完 onStart/onDestroy 等回调，可能 NPE），
                // 再对 Activity 实例调 finish()（chain.thisObject 即 Activity；照 fanqie MainHook.java:689
                // after 阶段 finish 先例；HC 的 finish+不 proceed 模式不采用——语义见 libxposed 102
                // "拦截不 proceed 可能致 Activity 生命周期异常，proceed 后 finish 更稳"，见类注释 F2）
                MountKind.FINISH_ACTIVITY -> builder.intercept { chain ->
                    val prefs = RemotePrefsHolder.obtain(module)
                    if (routeActive(chain, prefs, module, task)) {
                        val result = chain.proceed()
                        (chain.thisObject as? Activity)?.finish()
                        module.log(Log.INFO, TAG, "挽留弹窗已拦截: ${task.methodName}")
                        result
                    } else {
                        chain.proceed()
                    }
                }
            }
        }.fold(
            onSuccess = {
                if (DEBUG_LOGGING) {
                    val verb = when (task.kind) {
                        MountKind.CONST -> "常返"
                        MountKind.FORCE_ARG -> "参数强制"
                        MountKind.BLOCK -> "空实现阻断"
                        MountKind.FINISH_ACTIVITY -> "放行后 finish"
                    }
                    module.log(Log.INFO, TAG, "hook 已挂载: ${task.description} → $verb ${value ?: "null"} (feature=${task.featureKey})")
                }
                MountResult.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountResult.FAILED
            },
        )
    }

    /** 挂载汇总（始终输出，不受 [DEBUG_LOGGING] 门控；照 Rain "installed N/M resolved hook(s)" + fanqie 汇总行模式）。 */
    private fun logSummary(module: XposedModule) {
        val failurePart = if (failedIds.isEmpty()) "" else " — 失败: ${failedIds.joinToString()}"
        module.log(
            if (failedIds.isEmpty()) Log.INFO else Log.WARN,
            TAG,
            "ThemeStoreHook: installed $okCount/$totalCount$failurePart",
        )
    }

    // ---------------- 通用挂载工具 ----------------

    /** 总开关现读（每次 intercept 调用）：prefs 获取失败时返回 false = 原逻辑放行（安全默认）。 */
    private fun hookEnabled(prefs: SharedPreferences?): Boolean = prefs?.getBoolean(KEY_HOOK_ENABLED, false) ?: false

    /**
     * 功能位开关现读：总开关（上位语义：总关 = 全部放行）&& 对应 F1-F4 独立开关（默认 true）。
     * prefs 获取失败时总开关为 false → 原逻辑放行（安全默认）。
     */
    private fun featureEnabled(prefs: SharedPreferences?, key: String): Boolean =
        hookEnabled(prefs) && (prefs?.getBoolean(key, true) ?: true)

    /**
     * 路由激活判定（含下载链 H1/H2/H2b 的 uri 空条件，theme-download-chain.md §4）：
     * 1) 总开关 × 功能位开关现读（与既有挂载点一致，关 = 原逻辑放行）；
     * 2) uriEmptyRoute=false（既有挂载点）→ 恒激活（与原 featureEnabled 语义等价）；
     * 3) uriEmptyRoute=true → 额外要求 DownloadInfo.uri 为空（null/空串）才激活，uri 非空放行原逻辑
     *    （不坏正常免费项/已购项）；uri 读取失败（目标版本漂移）→ 放行原逻辑（安全默认）。
     * 路由真正触发时输出判据日志（theme-download-chain.md §5，INFO 级不受 DEBUG_LOGGING 门控）——
     * 真机验证从 LSPosed 日志（TAG HonorHook）确认修复链路是否生效。
     */
    private fun routeActive(
        chain: XposedInterface.Chain,
        prefs: SharedPreferences?,
        module: XposedModule,
        task: MountTask,
    ): Boolean {
        if (!featureEnabled(prefs, task.featureKey)) return false
        if (!task.uriEmptyRoute) return true
        val blank = isUriBlank(chain, task)
        if (blank) {
            module.log(
                Log.INFO, TAG,
                "theme-download-chain 路由触发: ${task.id}（DownloadInfo.uri 为空 → ${actionDesc(task)}）",
            )
        }
        return blank
    }

    /** 路由动作描述（判据日志用）：CONST → "常返 x"；FORCE_ARG → "第 N 参强制 x"；BLOCK/FINISH_ACTIVITY → 阻断描述。 */
    private fun actionDesc(task: MountTask): String = when (task.kind) {
        MountKind.CONST -> "常返 ${task.value}"
        MountKind.FORCE_ARG -> "第 ${task.forceArgIndex + 1} 参强制 ${task.value}"
        MountKind.BLOCK -> "空实现阻断（不调原方法）"
        MountKind.FINISH_ACTIVITY -> "放行原方法后 finish Activity"
    }

    /**
     * 条件路由的 uri 判空：按 [UriOwner] 从 chain 取 DownloadInfo 实例，反射读 public String uri
     * （DownloadInfo.java:145 实证）判空。三种情况分开处理：
     * - ① 实例本身为 null → 视为空（H2 场景 DownloadInfo 参数为 null 时原下载入口本就提前 return，路由无害）；
     * - ② uri 字段值为 null / 空串 → 空（服务端不下发直链的典型形态，触发路由）——字段值 null 是
     *   「读到了 null」并非反射异常，必须走判空分支（修复：原实现把字段值 null 误判成反射异常放行）；
     * - ③ 反射异常（getField/get 抛出 NoSuchField 等版本漂移，含 GET_DOWNLOAD_INFO 探测失败）→ 放行原逻辑
     *   （安全默认：探测失败不激活路由，不误伤正常资源）。
     */
    private fun isUriBlank(chain: XposedInterface.Chain, task: MountTask): Boolean {
        val info: Any? = when (task.uriOwner) {
            UriOwner.THIS -> chain.thisObject
            UriOwner.ARG -> chain.args?.getOrNull(task.uriArgIndex)
            UriOwner.GET_DOWNLOAD_INFO -> runCatching {
                chain.thisObject?.javaClass?.getMethod("getDownloadInfo")?.invoke(chain.thisObject)
            }.getOrElse { return false }  // ③ 探测异常（版本漂移）→ 放行原逻辑（安全默认）
        }
        val owner = info ?: return true  // ① 实例为 null：视为空（H2 场景 DownloadInfo 参数 null 时原入口本就提前 return，路由无害）
        val uri = runCatching { owner.javaClass.getField("uri").get(owner) }
            .getOrElse { return false }  // ③ 反射异常（NoSuchField/版本漂移）→ 放行原逻辑（安全默认）
        return (uri as? String).isNullOrEmpty()  // ② 字段值 null / 空串 → 空（触发路由）
    }

    /**
     * 直连反射解析目标方法（双轨第一轨，HookEntry.resolveTargetMethod 同构）。直连未命中时 mountOne 转
     * [MethodResolver.resolveMethod] 第二轨 DexKit 按特征兜底（对抗 OTA 混淆漂移，需 install 期写入 [apkPath]；
     * apkPath 为 null 时跳过兜底）。类/方法不存在返回 null（版本差异容错，只记日志）。
     */
    private fun findMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int,
        returnType: String,
    ): Method? = runCatching {
        loader.loadClass(className).declaredMethods.firstOrNull { m ->
            m.name == methodName && m.parameterCount == paramCount && m.returnType.name == returnType
        }
    }.getOrNull()

    private const val TAG = "HonorHook"
}