package top.honorhook.xposed.target.xhs

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * X5 批量保存（引擎 XhsHook·X5）：保存按钮 hook → 分享链接 → vendored 下载器管线（2026-10-19 定案）。
 *
 * 架构（2026-10-19 重构）：不碰 app 内部结构、不再旁路采集图集/视频流——两个保存点击点把
 * hook 现场的 NoteFeed（含 noteId）交给 [XhsSavePivot]；Pivot 单链取分享链接（noteShares 长链 →
 * 宿主 getShortUrl 短链 → vendored resolve），vendored `com.neoruaa.xhsdn.*`（XHS_Downloader_Android 原样复制）
 * 解析分享页出 ResolvedNote：图片 → 本文件现有外挂直下管线（downloadAll/downloadOne）；
 * 视频 → X4 HD 询问（offerHdDownloadFromNote 映射候选）。解析/选档逻辑全部 vendored，本文件只做接线。
 *
 * 前几版死因存档（勿复活）：
 * 1. 画廊三击——400ms 窗口苛刻 + 双击缩放冲突（用户否决）
 * 2. 拦截-重放本张——dispatch token 一次性，事后重放被下游身份过滤静默丢弃
 * 3. app 内存找图集（controller 图搜 / adapter.setItems 快照）——sheet 控制器图里图集列表
 *    实证为空（三轮 census），MultiTypeAdapter.setItems 真机不存在（串池类名≠运行时类）
 * 4. okhttp 旁路采集图集/视频流（imagefeed images_list / video_info_v2 逐流）——2026-10-14 定稿、
 *    2026-10-19 删除：save-hostile 笔记服务端剥直链、归属 lookback 脆、采集正则与宿主载荷形态
 *    耦合；分享页 __INITIAL_STATE__ 是唯一全量无剥离数据源（vendored XhsNoteParser 消费）
 *
 * 挂载点 `ji9.b`（图片长按面板「保存」点击 lambda；9.49.1 为 mh9.b，9.50.0 R8 迁名后旧类留
 * class_data_off=0 空壳——loadClass 成功但 declaredMethods 空 ⇒ 直连 0 候选 + 串锚随迁 ⇒ NOT_RESOLVED，
 * findings-x5 2026-10-19）：**模块全接管（不 proceed ⇒ 宿主原保存逻辑不执行）**，分享链接解析出
 * 全部原图——1 张静默直下、≥2 张弹确认卡；链接不可得才 `proceed()` 回落宿主原生保存
 * （2026-10-15 定案保留：先 proceed 本张原生保存再追加会重复一张、模块零介入，见 git 历史）。
 * 视频点 a59.q（9.49.1 为 d49.q）：9.50.0 真机「存活 1 点」实为 **DexKit 无锚点兜底误绑假阳性**
 * （d49 包已换语义为分享引导族，误绑其 invoke lambda，arg 非 String ⇒ isSave=false 静默放行，视频
 * 反馈卡实际失效）。误绑根因 = MethodResolver「pkg 非空即绕过无锚点闸门 + firstOrNull 即采纳」，
 * 本轮不改码（记录见 docs/reverse/xhs.md §9.50 适配，留拍板）。
 */
object XhsBatchSave {

    private const val JUDGE_PREFIX = "XhsHook·X5"

    private val engine = MountEngine("XhsHook·X5", "com.xingin.xhs")

    /** 图片长按面板「保存」点击 lambda（逻辑体 invoke(Object)，jadx ji9/b.java:42 实证；9.49.1 为 mh9/b.java:41，
     *  9.50.0 迁名后旧 mh9/b 成 class_data_off=0 空壳类，findings-x5 2026-10-19）。 */
    private const val CLS_SAVE_CLICK_LAMBDA = "ji9.b"
    /** 视频保存发起 lambda（a59/q.java:16-53 jadx 实证；9.49.1 为 d49.q，逐行同构迁名）。 */
    private const val CLS_VIDEO_SAVE_LAMBDA = "a59.q"
    private const val METHOD_INVOKE = "invoke"

    /** 直下参数：UA（xhscdn 对无 UA 请求可能 403）、单图体积上限 50MB、超时。 */
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 30_000

    /** 提案 1 重试阶梯（基准 ResumableTransfer.kt:42-58 语义）：同一 URL 最多 3 次尝试，仅瞬时失败重试。 */
    private const val FETCH_MAX_ATTEMPTS = 3
    private const val FETCH_BACKOFF_BASE_MS = 500L
    private const val FETCH_BACKOFF_CAP_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近 resumed 的宿主 Activity（弹窗载体；[ensureActivityTracker] 追踪）。 */
    @Volatile private var lastActivity: Activity? = null
    private val activityTracked = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * install 期注册追踪：`mainHandler.post` 轮询 `ActivityThread.currentApplication` 拿 Application，
     * 注册 `registerActivityLifecycleCallbacks`（onResume 记 / onPause 清）。
     *
     * **必须轮询、不能只试一次**（同 XhsVideoSave.ensureActivityTracker，2026-10-14 真机实证）：install
     * 时 Application 往往还没建好，一次性注册拿不到 ⇒ 当前页永远追踪不到 ⇒ 弹窗取不到 Activity。
     */
    private fun ensureActivityTracker() {
        if (!activityTracked.compareAndSet(false, true)) return
        mainHandler.post(object : Runnable {
            private var attempt = 0
            override fun run() {
                val app = runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null) as? android.app.Application
                }.getOrNull()
                if (app != null) {
                    runCatching {
                        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                            override fun onActivityResumed(a: Activity) { lastActivity = a }
                            override fun onActivityPaused(a: Activity) { if (lastActivity === a) lastActivity = null }
                            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                            override fun onActivityStarted(a: Activity) {}
                            override fun onActivityStopped(a: Activity) {}
                            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                            override fun onActivityDestroyed(a: Activity) {}
                        })
                    }
                    return
                }
                if (attempt++ < 60) mainHandler.postDelayed(this, 500)
            }
        })
    }

    /** 宿主 ClassLoader（弹窗 Activity 图搜用；直下链路已零宿主反射）。 */
    @Volatile private var hostLoader: ClassLoader? = null

    /**
     * 宿主长按面板 Dialog 类（`ji9.c.i` 那个 Lazy 的值类型，jadx ji9/c.java:28,200 实证；9.49.1 为 mh9.c.i，
     * 字段名 i 未漂），挂载期用宿主 loader 缓存一次：
     * [dismissHostPanel] 按**类型**认面板用（不赌混淆字段名）。
     */
    private const val CLS_PANEL_DIALOG = "com.xingin.foundation.core.v2.dialog.LCBBottomSheetDialog"
    @Volatile private var panelDialogClass: Class<*>? = null

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        hostLoader = param.defaultClassLoader
        // 面板 Dialog 类挂载期缓存一次（关面板用；取不到只是面板关不掉，不阻断下载，见 dismissHostPanel）
        panelDialogClass = runCatching { Class.forName(CLS_PANEL_DIALOG, false, hostLoader) }.getOrNull()
        ensureActivityTracker()
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf("com.xingin.xhs"))

        // 版本坐标走 XhsPoints（9.49.x 旧坐标；9.50 专属点旧套缺席 = 跳过，走 videoClick 轨兜底）
        // 版本源不用 HostVersion.resolve：那条路走 ActivityThread.currentPackageManager，现代 Android/AOSP
        // 无此成员（真机 + AOSP 14 源码全文 0 命中）⇒ 恒 null ⇒ 9.49.1 覆盖套从未生效过，一直走基线。
        val hostVersion = HostVersion.resolveHostVersion(param.applicationInfo?.sourceDir, "com.xingin.xhs")
        val pending = mutableListOf<MountEngine.Task>()
        XhsPoints.cls(hostVersion, "saveClick")?.let { pending += MountEngine.SimpleTask(
                className = it,
                methodName = METHOD_INVOKE,
                paramCount = 1,
                returnType = "java.lang.Object",
                id = "xhs:batchSave.saveClick.invoke",
                description = "X5 批量保存·图片长按面板「保存」点击 → 模块全接管（不 proceed）分享链接解析直下全部原图（链接三轨皆 miss 才回落宿主原生保存）",
                dex = DexHint(
                    strings = listOf(
                        "download blocked, disableSaveMedia=true",
                        "onDownloadClick, noteId=",
                    ),
                ),
        ) }
        XhsPoints.cls(hostVersion, "videoClick")?.let { pending += MountEngine.SimpleTask(
                className = it,
                methodName = METHOD_INVOKE,
                paramCount = 1,
                returnType = "java.lang.Object",
                id = "xhs:batchSave.videoSaveClick.invoke",
                description = "X5·视频保存发起（a59.q invoke default=saveVideo(noteFeed.u0())，a59/v.java:471 实证）→ 链接可得拦截默认保存（不 proceed）弹 HD 询问；不可得放行宿主保存弹反馈卡",
                // a59.q = Function1 双参 lambda（构造 (NoteFeed,int)，default 分支=saveVideo 组装；
                // a59/q.java:16-53 jadx 实证，实例化点 a59/v.java:471 `new q(noteFeed, 1)`）。
                // 方法体无字符串常量（只有 PatchProxy 数字 id）⇒ 串锚不可用，DexHint 改字段类型锚
                // （int + NoteFeed）收紧兜底轨——9.50.0 曾因无锚点兜底把 d49.q 误绑到换语义包的
                // 分享引导 lambda（installed 假阳性，findings-x5 2026-10-19）。
                dex = DexHint(classFieldTypes = listOf("int", "com.xingin.entities.notedetail.NoteFeed")),
        ) }
        XhsPoints.cls(hostVersion, "downloaderEntry")?.let { pending += MountEngine.SimpleTask(
                className = it,
                methodName = "e",
                // 反射全参计数（MethodResolver.matchesParameters 用 parameterCount）：e(h,String,String,
                // boolean,String,long,String,long,boolean,boolean,Function3,int,Object) = 13（h.java:227）
                paramCount = 13,
                returnType = "void",
                id = "xhs:videoSave.downloader.entry",
                description = "X5 保存下载总入口·b75.h#e（CommercialHorizonBridge_VideoDownloader 添加下载任务，h.java:227-330 反编译实证；save→e→XYDownloader 下载→n/fs2.b 拷贝 全链 head）→ 接管中不 proceed：宿主零下载零落盘零进度回调，模块弹卡直下原片",
                dex = DexHint(strings = listOf("addDownloadTask 强制下载")),
        ) }
        pending += MountEngine.SimpleTask(
                className = "xec.w",
                methodName = "h0",
                paramCount = 5,
                returnType = "boolean",
                id = "xhs:videoSave.albumSink.h0",
                description = "X5 视频落盘拷贝断根·公共工具 w.h0(File,relDir,name,delSrc,mime)（未混淆 public final，w.java:730 签名实证）→ DCIM+Camera/+video/mp4 三判据命中不 proceed 返 true 假成功，图片等其余落盘照常放行",
            )
        pending += MountEngine.SimpleTask(
                className = "xec.w",
                methodName = "e0",
                paramCount = 4,
                returnType = "boolean",
                id = "xhs:videoSave.albumSink.e0",
                description = "X5 视频落盘拷贝断根·公共工具 w.e0(File,relDir,name,mime)（未混淆，w.java:619 签名实证，内部委托 h0(...,true,...) w.java:621）→ 同 h0 三判据拦截，两分支共用 mountAlbumSink",
            )
        XhsPoints.cls(hostVersion, "aigcEditLaunch")?.let { pending += MountEngine.SimpleTask(
                className = it,
                methodName = "invoke",
                paramCount = 1,
                returnType = "java.lang.Object",
                id = "xhs:videoSave.aigcEditLaunch",
                description = "X5 AIGC 编辑保存发起断·mg4.b#invoke case10（VideoEditDownloadWidget lambda：弹 CapaProgressDialogFragment「正在保存到相册」→ startDownload=LightVideoEdit 转码+下载，mg4/b.java:33-70）→ 接管中不 proceed + 反射 dismiss 进度框：转码零启动、提示不弹",
                dex = DexHint(strings = listOf("tag_video_edit_download_loading")),
        ) }
        XhsPoints.cls(hostVersion, "noteSaveEntry")?.let { pending += MountEngine.SimpleTask(
                className = it,
                methodName = "b",
                paramCount = 9,
                returnType = "void",
                id = "xhs:videoSave.noteSaveEntry",
                description = "X5 笔记视频保存总入口·ab9.r1#b(boolean,Activity,z0,NoteFeed,int,boolean,ViewGroup,Function1,Function0)（r1.java:189 起：网络检查→建 SaveProgressView「正在保存到相册」→postDelayed(1s)→r1.d→function1(noteFeed.u0())=save 请求+下载流，2026-10-20 00:03 实证笔记保存正主链）→ 链接可得：不 proceed（view 不弹+请求不发+下载不跑）+ 模块管线弹卡直下原片；链接 miss：proceed 宿主原生保底",
                dex = DexHint(strings = listOf("save video failed:")),
        ) }
        // mountOne 按 SimpleTask 分派；全列表本就只含 SimpleTask（mutableListOf<Task> 只为 let-追加方便）
        @Suppress("UNCHECKED_CAST")
        engine.attemptMount(pending as List<MountEngine.SimpleTask>, module, hostLoader!!) { task -> mountOne(task, module) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        when (task.id) {
            "xhs:videoSave.noteSaveEntry" -> mountNoteSaveEntry(task, module)
            "xhs:videoSave.aigcEditLaunch" -> mountAigcEditLaunch(task, module)
            "xhs:videoSave.downloader.entry" -> mountDownloadEntry(task, module)
            "xhs:batchSave.videoSaveClick.invoke" -> engine.mount(task, module, hostLoader!!) { chain ->
                // a59.q 双分支：case1=分享引导（arg=Triple，rv9.a.CLOUD）default=saveVideo（arg=String）——只对
                // String 轨做拦截/反馈，分享路径静默放行
                val arg0 = runCatching { chain.getArg(0) }.getOrNull()
                val isSave = arg0 is String
                // 本轮起点（proceed 前）：X4 若在本轮保存里弹了询问卡，其 lastAskAtMs >= t0（见 askShownAfter）
                val t0 = System.currentTimeMillis()
                if (!isSave) return@mount chain.proceed()
                // noteId 三轨判定提前到 proceed 前（2026-10-19「拦截默认」拍板：链接可得性判定必须先于
                // 放行决策；纯反射 + 内存表，无网络，回调线程安全——同图片分支 availableShareLink 用法）。
                // 三轨：
                // ① arg0 String 仅 24hex 命中才采信——a59/q.java:52 cast 实证为 String，但值在 lambda
                //   体内未消费（q.java:53 只透传 PatchProxy），语义静态不可证；
                // ② miss 时反射取捕获的 NoteFeed 实例（字段序 (int, NoteFeed)，DexHint classFieldTypes
                //   已证）invoke "u0"——a59/q.java:53 实证 saveVideo(noteFeed.u0())，返回值即 save API
                //   入参 = note_id。u0 是 9.50 混淆名、与宿主版本绑定（漂移时反射失败走 ③）；
                // ③ 再 miss → WARN 跳过（宁不弹、不错弹）。
                // 返回值须 is String 且过同一 24hex 门——**24hex 门是防错弹硬校验**：曾用
                // lastVideoNoteId 回落，历史/缓存进入的笔记不发 entry videofeed ⇒ 取到上一篇 id
                // （2026-10-19 实证），错弹卡 = 下错片 + deleteLowQuality 删错文件，已弃用
                // （XhsVideoSave.lastVideoNoteId 字段留作日志观测，本分支不再读）。
                val hex24 = Regex("^[0-9a-f]{24}$")
                val argNoteId = (arg0 as? String)?.takeIf { it.matches(hex24) }
                var feed: Any? = null
                val noteId = argNoteId ?: runCatching {
                    feed = chain.thisObject.javaClass.declaredFields
                        .firstOrNull { it.type.name == "com.xingin.entities.notedetail.NoteFeed" }
                        ?.apply { isAccessible = true }
                        ?.get(chain.thisObject)
                    (feed?.javaClass?.getMethod("u0")?.apply { isAccessible = true }?.invoke(feed) as? String)
                        ?.takeIf { it.matches(hex24) }
                }.getOrNull()
                if (noteId == null) {
                    // ③ WARN 路径（现状不变）：拦截判定无从做起，放行宿主原生保存
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX X5 视频保存点击：noteId 不可得，HD 询问跳过")
                } else {
                    // feed 现场补取 + stash（落表保留调用点；arg 命中时 feed 未取，对象图
                    // 下钻补一次——XhsSavePivot.findFeedAndNoteId，ji9/b 同款 u0 语义；根 = thisObject
                    // + 入参 arg0）
                    runCatching {
                        if (feed == null) feed = XhsSavePivot.findFeedAndNoteId(module, chain.thisObject, arg0)?.first
                        XhsSavePivot.stashFeed(feed)
                    }.onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX X5 feed 现场捕获失败: ${it.message}") }
                }
                // 拦截判定（2026-10-19「拦截默认」拍板，proceed 前同步内存判定）：链接可得 = 宿主默认保存
                // 全断——网络 / 播放缓存直拷 / 已存在短路三路都从本 default 分支 saveVideo 发起（findings
                // 已证），不 proceed = 宿主 720p 不落盘，只剩模块一次下载，替代旧「宿主先存 720p + 模块下
                // 原片 + deleteLowQuality 删 720p」的两次下载一次删除。链接三轨 miss 才放行宿主 720p 保底。
                noteId?.let { n ->
                    val link = runCatching { XhsSavePivot.availableShareLink(n, module) }.getOrNull()
                    if (link != null) {
                        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX X5 拦截宿主默认保存（链接可得，不 proceed）noteId=$n")
                        engine.judge(module, task, JUDGE_PREFIX, "拦截宿主默认保存（链接可得，不 proceed）")
                        startHdAsk(n, t0, module, failCard = true)
                        // 不 proceed ⇒ 返回值自给：宿主 invoke 返 kotlin.Unit（图片分支同款写法，
                        // **宿主 loader** 取 INSTANCE——模块 loader 的同名类不保证同一 class 身份）
                        return@mount runCatching {
                            Class.forName("kotlin.Unit", true, hostLoader).getDeclaredField("INSTANCE").get(null)
                        }.getOrNull()
                    }
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX X5 链接不可得，放行宿主原生保存")
                }
                // ——回落路径（noteId 不可得 / 链接三轨 miss）：宿主原生 720p 照存，HD 询问与反馈卡照旧。
                // 反馈卡段只在回落执行（拦截已提前 return）——拦截模式下宿主没存，「已保存本视频」是谎言。
                val result = chain.proceed()
                noteId?.let { n -> startHdAsk(n, t0, module, failCard = false) }
                mainHandler.post {
                    // 判重（2026-10-15，缺陷④）：X4 本轮已弹「下载高清原版视频」就不再弹「已保存本视频」
                    // （真机实证两卡并存）。重复保存落在 X4 的 5 分钟去重窗口内时 X4 不弹卡 ⇒ lastAskAtMs
                    // 仍是旧值 < t0 ⇒ 反馈卡照常可见。
                    // ★ 这里只是**快路径**：X4 写 lastAskAtMs 是在宿主 save API 的**网络响应**到达时
                    //   （retrofit 异步，100~500ms 后），远晚于本判定点 ⇒ 通常不命中。最终保障是 X4 弹卡前
                    //   主动调 [dismissVideoFeedbackCard]（后弹的关先弹的，确定性握手，不依赖时序）。
                    if (XhsVideoSave.askShownAfter(t0)) {
                        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX X4 本轮已弹询问卡，跳过视频反馈卡")
                        return@post
                    }
                    val activity = lastActivity ?: return@post
                    engine.judge(module, task, JUDGE_PREFIX, "视频保存完成，弹反馈卡")
                    runCatching { showVideoSavedDialog(activity, module) }
                        .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 视频反馈卡失败: ${it.message}") }
                }
                result
            }
            "xhs:videoSave.albumSink.h0" -> mountAlbumSink(task, module)
            "xhs:videoSave.albumSink.e0" -> mountAlbumSink(task, module)
            else -> mountClick(task, module)
        }

    /**
     * 笔记视频保存总入口断（v3.1 主断点，2026-10-20 00:15）：`ab9.r1#b` 是保存链最上游
     * （网络检查 → 建 SaveProgressView「正在保存到相册」→ postDelayed(1s) → r1.d → save 请求 +
     * 下载流 → moveToPublic）。**在 save 请求之前**，pending 登记不可用——改用 NoteFeed 参数反射
     * u0() 取 noteId（ji9/b.java:55 锚串旁证 u0()=noteId；24hex 门防误绑）判 [XhsSavePivot.availableShareLink]：
     * - 有链 → 不 proceed（view 不弹、save 请求不发、下载/转码不跑、零提示）+ [startHdAsk]
     *   后台解析弹卡（failCard=true：拦截 = 模块是唯一出口）
     * - 链接 miss/拉黑 → proceed 宿主原生保底
     * 下游 b75.h.e / mg4.b / albumSink 全降级纯兜底（r1.b 拦住时它们无输入）。
     */
    private fun mountNoteSaveEntry(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        engine.mount(task, module, hostLoader!!) { chain ->
            val noteId = runCatching {
                val feed = chain.getArg(3) ?: return@runCatching null
                val id = feed.javaClass.getMethod("u0").invoke(feed) as? String
                // 24hex 门（findFeedAndNoteId 同款）：NoteFeed u0() 产物形如 6ad5d4d6000000002a00fa1b
                id?.takeIf { Regex("^[0-9a-f]{24}$").matches(it) }
            }.getOrNull()
            if (noteId == null || XhsSavePivot.availableShareLink(noteId, module) == null) {
                return@mount chain.proceed()
            }
            module.log(Log.INFO, MountEngine.TAG,
                "$JUDGE_PREFIX 笔记保存总入口已断（ab9.r1.b，宿主零提示零请求零下载）noteId=$noteId")
            engine.judge(module, task, JUDGE_PREFIX, "笔记保存总入口已断（ab9.r1.b，链接可得不 proceed）")
            startHdAsk(noteId, System.currentTimeMillis(), module, failCard = true)
            null // r1.b 返回 void
        }

    /**
     * AIGC 编辑保存发起断（2026-10-20 00:10）：`mg4.b#invoke` = VideoEditDownloadWidget 的下载
     * lambda 分派（case10：弹 CapaProgressDialogFragment「正在保存到相册」→ startDownload（DebugMetadata
     * `VideoEditDownloadWidget$startDownload$1`，mg4/l.java 注解实证）→ LightVideoEdit 转码+下载 →
     * moveToPublic 拷贝）。**AIGC 笔记走此链，不走 b75.h.e**（2026-10-19 23:55 实证：e 断点零触发而
     * 落盘判据为 fs2/e0）。
     * 拦截（savePending 命中 + this.d==10）= 不 proceed（转码零启动、z4 下载零启动）+ 反射
     * `widget.v`（CapaProgressDialogFragment，mg4/b.java:65 赋值，可能尚未 add——dismiss 幂等安全）
     * 关掉「正在保存」框；返回 kotlin.Unit.INSTANCE（Function1 契约，跨 loader 反射取）。
     * 模块弹卡直下原片（interceptVideoSave 主卡位已弹）。
     */
    private fun mountAigcEditLaunch(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        engine.mount(task, module, hostLoader!!) { chain ->
            val case = runCatching {
                chain.thisObject.javaClass.getDeclaredField("d").get(chain.thisObject) as? Int
            }.getOrNull()
            if (case != 10 || !XhsVideoSave.savePendingActive()) return@mount chain.proceed()
            runCatching {
                // dismiss「正在保存到相册」进度框（mg4/b.java:60-70：Dialog 先 add 后 z4，拦截点 v 已赋值）
                val widget = chain.thisObject.javaClass.getDeclaredField("e").get(chain.thisObject)
                val frag = widget?.javaClass?.getDeclaredField("v")?.get(widget)
                frag?.javaClass?.getMethod("dismissAllowingStateLoss")?.invoke(frag)
                    ?: frag?.javaClass?.getMethod("dismiss")?.invoke(frag)
            }.onFailure {
                module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX AIGC 进度框 dismiss 失败（忽略）: ${it.message}")
            }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX AIGC 编辑保存发起已断（转码零启动+提示已关）")
            engine.judge(module, task, JUDGE_PREFIX, "AIGC 编辑保存发起已断（mg4.b case10 不 proceed）")
            // Function1.invoke 返 Unit：跨 loader 反射取 INSTANCE，失败退 null（返回值无人消费）
            runCatching {
                Class.forName("kotlin.Unit", true, hostLoader).getDeclaredField("INSTANCE").get(null)
            }.getOrNull()
        }

    /**
     * 保存下载总入口断（v3「视频图片化」主挂点，2026-10-19 23:50 定稿）：`b75.h#e` = 宿主保存链
     * 的下载 head（save 请求登记 → **e 添加下载任务** → m/XYDownloader 下载 → l → n/fs2.b 拷贝）。
     * 接管中不 proceed = 无下载、无落盘、无进度/完成回调（Function3 不触发，宿主 UI 干净）+
     * [XhsVideoSave.rescueAskFromCache] 弹 HD 卡（resolveCache 60s 窗，save→e 间隔毫秒级）。
     * 未接管（解析失败/拉黑）→ proceed 宿主原生保底。
     * 四轮管道内手术全废弃（反例档案见 [mountAlbumSink]），入口断 = 图片 ji9.b 同款模式。
     * e 为 void ⇒ 不 proceed 返 null 即可（U6 惯例）。
     */
    private fun mountDownloadEntry(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        engine.mount(task, module, hostLoader!!) { chain ->
            // url 精确匹配 pending 登记（审计 H1 修复：A 笔记解析失败没登记 → e(A.url) 放行宿主保底；
            // B 笔记早前登记不影响 A）。域判据（xhscdn.com）保留作第二道：e 的 Java 调用点为 0
            // （跨平台桥 JS 分发，classes.dex 未 dump），非 xhscdn 域必有别的用途，登记表污染也放行。
            val url = runCatching { chain.getArg(1)?.toString() }.getOrNull().orEmpty()
            val noteId = XhsVideoSave.pendingDownloadHit(url)
            if (noteId == null || !url.contains("xhscdn.com")) return@mount chain.proceed()
            module.log(Log.INFO, MountEngine.TAG,
                "$JUDGE_PREFIX 保存下载入口已断（b75.h.e，宿主零下载零回调）noteId=$noteId url=${url.take(120)}")
            engine.judge(module, task, JUDGE_PREFIX, "保存下载入口已断（b75.h.e，接管中不 proceed）")
            XhsVideoSave.rescuePending(noteId, module)
            null
        }

    /**
     * 视频落盘拷贝断根·公共工具挂（2026-10-19 改方案）：逐类挂 `b75.n#execute`（X5 3/3 挂上，21:35
     * 真机）断不了根——DCIM 仍落 `Camera_xhs_<ts>.mp4` 且无断根判据行 = 该批走了**另一拷贝路**
     * `fs2/b.java:51 w.e0(file, DCIM, "Camera/"+name, "video/mp4")`，逐类挂 = 打地鼠。改挂**公共
     * 工具 `xec.w`**（public final 未混淆，所有落盘必经，跨版本稳）：`h0`（w.java:730，b75/n.java:35
     * 调方）与 `e0`（w.java:619，fs2/b.java:51 调方，内部委托 `h0(..., true, ...)` w.java:621 实证）
     * 同挂，两分支共用本 intercept。
     *
     * 过滤语义（[isVideoAlbumCopy] 四判据全满足才拦）：args[1] 含 "DCIM" && args[2] 含 "Camera/"
     * && 末参 == "video/mp4" && **无换链放行窗** → 不 proceed 返 true（假成功——b75.n 拿到成功后回调
     * invoke(true)，宿主无感；e0 调方 fs2/b 只消费返回值不另作分支）。
     * args[2] "Camera/" 是**防误伤边界**（其他视频写盘相对路径不同，天然放行）；图片被 mime 判据放行。
     *
     * **v3 纯兜底（2026-10-19 23:59 审计修订）**：主断点已上移 [mountDownloadEntry]（b75.h#e 下载
     * head），本闸只兜漏网拷贝（e 挂载失败/非 e 拷贝路）：**savePending 非空（5min 窗，M3：慢下载
     * 的迟到拷贝）→ 拦**（不弹卡——interceptVideoSave 正常路每单必弹主卡，此处再弹会在 10s 幂等
     * 窗外重复叠卡，审计 M2）；pending 空（解析失败/拉黑）→ 放行宿主原生保底（失败卡
     * 「再次点击保存将使用系统保存」的兑现路径，闸不得断保底）。
     * 反例档案（23:11-23:30 四轮，全部废弃）：①纯三判据曾断掉换链原片；②8MB 豁免误杀小原片；
     * ③60s 窗放行过 1.2MB 缓存 720p；④size>8MB 分流误放 AIGC 长缓存。教训 = 在拷贝层按文件
     * 特征猜「宿主在下什么」必错；猜的活全交给入口断。
     */
    private fun mountAlbumSink(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        engine.mount(task, module, hostLoader!!) { chain ->
            val hit = runCatching { isVideoAlbumCopy(chain.args) }.getOrDefault(false)
            if (!hit) return@mount chain.proceed()
            if (!XhsVideoSave.savePendingActive()) {
                module.log(Log.INFO, MountEngine.TAG,
                    "$JUDGE_PREFIX 未接管（解析失败/拉黑）→ 放行宿主原生保底")
                return@mount chain.proceed()
            }
            val size = runCatching { (chain.args?.getOrNull(0) as? java.io.File)?.length() ?: -1L }.getOrDefault(-1L)
            module.log(Log.INFO, MountEngine.TAG,
                "$JUDGE_PREFIX save_to_album 落盘已断（纯兜底，size=$size）")
            engine.judge(module, task, JUDGE_PREFIX, "save_to_album 落盘已断（w.h0/e0 video 过滤）")
            true // 假成功：h0/e0 返 boolean（java.lang.Boolean 自动装箱），宿主按「拷贝成功」消费
        }

    /** 三判据全满足才拦（见 [mountAlbumSink] KDoc）：DCIM && Camera/ && video/mp4。 */
    private fun isVideoAlbumCopy(args: List<Any?>?): Boolean {
        if (args == null || args.size < 4) return false
        val dir = args.getOrNull(1) as? String ?: return false
        val name = args.getOrNull(2) as? String ?: return false
        return dir.contains("DCIM") && name.contains("Camera/") && args.last() == "video/mp4"
    }

    /**
     * 分享链接后台解析 → X4 HD 询问发起（a59.q 点击轨两路共用：拦截主路 + 回落路，2026-10-19）。
     * 解析是网络操作，必须在后台线程。[failCard] 只在拦截主路置 true——拦截 = 宿主保存已断，解析
     * 失败即用户零所得，弹失败卡「下载原片失败，再次点击保存将使用系统保存」（[XhsSavePivot]
     * parsedEmptyNotes 已记 → 下次点击 availableShareLink 返 null 放行宿主）；回落路宿主已存 720p
     * 且反馈卡照旧，不弹（防误导）。解析成功但无候选仍由 offerHdDownload 仪表 WARN（既有）。
     */
    private fun startHdAsk(noteId: String, t0: Long, module: XposedModule, failCard: Boolean) {
        Thread({
            runCatching {
                val note = kotlinx.coroutines.runBlocking {
                    XhsSavePivot.resolveByShareLink(noteId, module)
                }
                if (note == null && failCard) {
                    showOnTrackedActivity(module, "原片解析失败卡") {
                        showParseFailCard(it, "下载原片失败", module)
                    }
                }
                XhsVideoSave.offerHdDownloadFromNote(noteId, t0, note, module)
            }.onFailure {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX X5 HD 询问发起失败: ${it.message}")
            }
        }, "HonorHook-xhs-video-hd-ask").start()
    }

    /**
     * 视频解析失败卡·公共入口（XhsVideoSave 请求级短路轨调用，2026-10-19）：宿主 save 请求已被短路
     * 吞掉，分享链接解析失败 = 用户零所得 → 失败卡。走 [showOnTrackedActivity]（本类追踪 Activity +
     * 重试链），文案/样式同 [startHdAsk] failCard 路。
     * 诚实性注：短路轨「再次点击保存将使用系统保存」（[showParseFailCard] 固定文案）不成立——save
     * 请求仍会被请求级短路吞掉；parsedEmptyNotes 拉黑只影响 a59.q 点击轨（本机零触发）。
     */
    fun showVideoParseFailCard(module: XposedModule) {
        showOnTrackedActivity(module, "原片解析失败卡") {
            showParseFailCard(it, "下载原片失败", module)
        }
    }

    /**
     * 图片保存点击：**模块全接管**——接管判定只看分享链接可得性（全内存，见 [XhsSavePivot.availableShareLink]），
     * 链接可得 = 不 proceed（宿主原保存逻辑整段不执行），后台线程解析分享页（vendored 下载器管线）
     * 拿全部原图直链：1 张静默直下、≥2 张弹确认卡；链接不可得才 `proceed()` 回落宿主原生保存。
     *
     * 2026-10-19 实况图集根因修（真机 19:46 日志「解析成功 图0 视频0 live6 候选0」+ vendored parser
     * 读码）：分享页实况图集解析出的媒体全为 ResolvedMedia.LivePhoto（每项 image: Image + video:
     * Video 双轨），旧取数只消费 note.images/note.videos ⇒ livePhotos 两头不沾 ⇒ urls 空 ⇒「无可用
     * 图片」。取 URL 改两类合并（LinkedHashSet 去重保序）：images.originalUrl + livePhotos 每项
     * image.originalUrl——LivePhoto 语义 = 只下静态帧原图（下载器实测 livePhoto 关时只下静帧，
     * 对齐不双下 mp4+帧）；videos 维持不消费。「图 N 张」N 即合并后 URL 数（urls.size 透传，
     * 进度卡/取消/落盘管线零改动）。
     *
     * 与任务口径的偏差（2026-10-19，工程约束）：「解析也失败才 proceed()」不可行——libxposed 的
     * `chain.proceed()` 必须在 hook 回调线程调用，而解析是网络操作（app 主线程上等 15s = ANR）；
     * 故接管判定提前到「链接可得」（单链内存判定），链接可得但网页解析失败 = WARN + 失败卡
     * （宿主未保存，parsedEmptyNotes 已记 → 下次点击放行宿主）——宁缺勿错（绝不替用户下错图）。
     */
    private fun mountClick(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        engine.mount(task, module, hostLoader!!) { chain ->
            // noteId/feed 下钻根 = lambda 捕获（thisObject）+ invoke 入参（ji9/b.java:55 `cVar.Ta().u0()`
            // 实证——cVar 是入参 controller、feed 挂其上；u0 是 9.50 混淆名，漂移即 miss → 回落宿主）
            val found = XhsSavePivot.findFeedAndNoteId(
                module, chain.thisObject, runCatching { chain.getArg(0) }.getOrNull())
            val noteId = found?.second
            if (noteId == null) {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX noteId 不可得（u0 反射 miss），回落宿主原生保存")
                return@mount chain.proceed()
            }
            XhsSavePivot.stashFeed(found.first)
            val link = XhsSavePivot.availableShareLink(noteId, module)
            if (link == null) {
                // 三轨皆 miss（noteShares 无、NoteFeed 反射无 share_info）= 分享链不可得，回落宿主
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 分享链接三轨皆 miss noteId=$noteId，回落宿主原生保存")
                return@mount chain.proceed()
            }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 接管图片保存（分享链接管线）noteId=$noteId（不 proceed）")
            // 宿主原实现末尾的 `cVar.i.getValue().dismiss()` 随 proceed 一起被跳过 ⇒ 面板得模块自己关
            dismissHostPanel(chain.thisObject, module)
            engine.judge(module, task, JUDGE_PREFIX, "已接管图片保存（分享链接管线）noteId=$noteId")
            // 解析（网络）在后台线程；交互与旧采集管线一致（1 张静默 / ≥2 张确认卡）
            Thread({
                val urls = runCatching {
                    val note = kotlinx.coroutines.runBlocking { XhsSavePivot.resolveByShareLink(noteId, module) }
                    // 两类合并去重保序（2026-10-19 实况图集根因修：真机 19:46 日志「解析成功 图0 视频0
                    // live6 候选0」+ vendored parser 读码——该笔记 5 项媒体全为 ResolvedMedia.LivePhoto
                    // （parser 正确产出，每项 image: Image + video: Video 双轨），旧适配层只消费
                    // note.images/note.videos，livePhotos 两头不沾 ⇒ urls 空 ⇒「无可用图片」死寂）：
                    // ① images.originalUrl（vendored NoteOutput.eligible 语义映射：封面/评论图排除，
                    //   originalUrl = 下载器确认的原图直链，imageList urlDefault 同源）
                    // ② livePhotos 每项只展开 image.originalUrl（下载器实测 livePhoto 关时只下静态帧
                    //   原图，模块对齐此行为——不双下 mp4+帧）
                    // ③ videos 维持不消费（视频流归 X4 HD 询问分支）
                    val imageUrls = note?.images
                        ?.filterNot { it.cover || it.commentId != null }
                        ?.map { it.originalUrl }
                        .orEmpty()
                    val liveFrameUrls = note?.livePhotos.orEmpty().map { it.image.originalUrl }
                    LinkedHashSet(imageUrls + liveFrameUrls).toList()
                }.getOrNull().orEmpty()
                if (urls.isEmpty()) {
                    module.log(Log.WARN, MountEngine.TAG,
                        "$JUDGE_PREFIX 分享链接解析无可用图片 noteId=$noteId（接管已定，宿主未保存——诚实失败）")
                    // 单链定案（2026-10-19）：接管后解析失败 = 宿主未保存、原为死寂——失败卡告知下次
                    // 点击走系统保存（parsedEmptyNotes 已记，availableShareLink 返 null 放行宿主）
                    showOnTrackedActivity(module, "解析失败卡") {
                        showParseFailCard(it, "下载失败", module)
                    }
                    return@Thread
                }
                if (urls.size == 1) {
                    showOnTrackedActivity(module, "单图静默直下") { downloadAll(it, urls, module) }
                } else {
                    showOnTrackedActivity(module, "追加询问") { maybeShowBatchDialog(it, urls, module) }
                }
            }, "HonorHook-xhs-batch").start()
            // 不 proceed ⇒ 返回值得自己给：宿主 invoke 返的是 kotlin.Unit（点击 lambda）。用**宿主 loader**
            // 取 Unit.INSTANCE（模块 loader 的同名类不保证同一 class 身份），取不到才回 null。
            runCatching {
                Class.forName("kotlin.Unit", true, hostLoader).getDeclaredField("INSTANCE").get(null)
            }.getOrNull()
        }

    /**
     * 弹窗载体封装（BadToken 修复，2026-10-15）：真机实证用抓到的历史 Activity 建 Dialog 会抛
     * `Unable to add window -- token … is not valid; is your activity running?`。统一改走
     * [lastActivity] 追踪值 + 取不到时 300ms × 16 重试（同 XhsVideoSave.showDownloadedDialog），
     * 绝不再用 controller 图搜出来的 Activity 建窗。主线程执行；异常只 WARN，不影响下载。
     */
    private fun showOnTrackedActivity(module: XposedModule, what: String, attempt: Int = 0, block: (Activity) -> Unit) {
        mainHandler.post {
            val activity = lastActivity
            if (activity == null) {
                if (attempt < 16) {
                    mainHandler.postDelayed({ showOnTrackedActivity(module, what, attempt + 1, block) }, 300)
                } else {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX $what 未出：无 resumed Activity")
                }
                return@post
            }
            runCatching { block(activity) }
                .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX $what 失败: ${it.message}") }
        }
    }

    /**
     * 关宿主长按面板（2026-10-15）：宿主 `ji9.b#invoke`（9.49.1 为 mh9.b）原实现末尾会 `cVar.i.getValue().dismiss()`
     * 收面板——图片分支不 proceed 后这段被一起跳过，面板会留在屏上。
     * 从 lambda（[chain.thisObject]）**按类型**找，不按字段名（混淆名会漂）：字段值是 `kotlin.Lazy`
     * 就先 `getValue()`，结果落在 Dialog 子类（或挂载期缓存的 [panelDialogClass]）就 dismiss；
     * 值不是 Lazy 就下钻一层（lambda 捕获的 controller → 它的 Lazy 面板字段）。
     * 只在主线程调；任何失败仅 WARN，绝不影响下载。
     */
    private fun dismissHostPanel(lambda: Any?, module: XposedModule) {
        mainHandler.post {
            runCatching {
                val loader = hostLoader ?: return@post
                val lazyCls = runCatching { Class.forName("kotlin.Lazy", false, loader) }.getOrNull() ?: return@post
                var node: Any? = lambda
                var depth = 0
                while (node != null && depth < 3) {
                    val fields = runCatching { node.javaClass.declaredFields }.getOrNull()
                    var next: Any? = null
                    var scanned = 0
                    if (fields != null) for (f in fields) {
                        if (++scanned > 64) break
                        val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                        val unwrapped = if (lazyCls.isInstance(v)) {
                            // Lazy 未初始化时 getValue() 会就地建实例（不显示）——dismiss 对未显示的
                            // Dialog 是 no-op，最坏只是白建一个实例；建不出来由外层 runCatching 兜住
                            runCatching { v.javaClass.methods.firstOrNull { it.name == "getValue" }?.invoke(v) }.getOrNull()
                        } else v
                        if (unwrapped != null && dismissIfPanel(unwrapped)) {
                            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 已关宿主长按面板（${unwrapped.javaClass.name}）")
                            return@post
                        }
                        if (next == null && unwrapped != null && !unwrapped.javaClass.name.startsWith("java.")) next = unwrapped
                    }
                    node = next
                    depth++
                }
            }.onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 面板关闭失败: ${it.message}") }
        }
    }

    /** Dialog 子类（或缓存的 [panelDialogClass]）→ dismiss；直接调不到就反射找无参 `dismiss`。返回是否已关。 */
    private fun dismissIfPanel(v: Any): Boolean {
        if (v is android.app.Dialog) return runCatching { v.dismiss() }.isSuccess
        if (panelDialogClass?.isInstance(v) != true) return false
        val m = runCatching { v.javaClass.getMethod("dismiss") }.getOrNull() ?: return false
        return runCatching { m.isAccessible = true; m.invoke(v) }.isSuccess
    }

    /** 当前「已保存本视频」反馈卡（X4 弹询问卡前由 [dismissVideoFeedbackCard] 主动关掉）。 */
    @Volatile private var videoFeedbackDialog: android.app.Dialog? = null

    /**
     * 关掉 X5 的「已保存本视频」反馈卡——供 X4 在弹「下载高清原版视频」询问卡前调用（2026-10-15，缺陷④）。
     *
     * 确定性握手（后弹的关先弹的）：视频保存点击 → X5 反馈卡先弹 → 宿主 save API 响应到达 → X4 询问卡
     * 后弹 ⇒ 由 X4 主动关掉先弹的 X5 卡，不依赖两者时序（X4 写 `lastAskAtMs` 在响应到达时，retrofit
     * 异步 ⇒ X5 侧 [XhsVideoSave.askShownAfter] 快路径基本命中不了）。
     * 任何失败静默：绝不能波及 X4 链路。
     */
    fun dismissVideoFeedbackCard() {
        mainHandler.post {
            runCatching { videoFeedbackDialog?.dismiss() }
            videoFeedbackDialog = null
        }
    }

    /** 视频保存反馈卡（复用图片卡样式，单按钮）。 */
    private fun showVideoSavedDialog(activity: Activity, module: XposedModule) {
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
        val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
        val accent = 0xFFFF2E4D.toInt()
        val dp = activity.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(24), d(22), d(24), d(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(22).toFloat(); setColor(bg)
            }
        }
        val title = android.widget.TextView(activity).apply {
            text = "已保存本视频"
            textSize = 17f
            setTextColor(titleColor)
            paint.isFakeBoldText = true
        }
        card.addView(title)
        val ok = android.widget.TextView(activity).apply {
            text = "好"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(d(18), d(11), d(18), d(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(18).toFloat(); setColor(accent)
            }
            setTextColor(0xFFFFFFFF.toInt())
        }
        val dialog = android.app.Dialog(activity)
        ok.setOnClickListener { dialog.dismiss() }
        card.addView(ok.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = d(18) }
        })
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.setCancelable(true)
        // 单例引用（X4 弹询问卡前据此关掉本卡）；用户自己点「好」/返回键关掉时自动清引用
        dialog.setOnDismissListener { if (videoFeedbackDialog === dialog) videoFeedbackDialog = null }
        videoFeedbackDialog = dialog
        dialog.show()
    }

    /**
     * 解析失败卡（2026-10-19 单链定案，消死寂）：接管后解析失败 = 宿主未保存，单按钮中性卡告知
     * 「再次点击保存将使用系统保存」（[XhsSavePivot.parsedEmptyNotes] 已记，下次点击放行宿主）。
     * 自绘风格照抄 [showStyledDialog]（圆角卡/深色自适应/XHS 红）；必须主线程调用
     * （showOnTrackedActivity 已保证）。任何失败只 WARN，绝不波及下载/保存链路。
     */
    private fun showParseFailCard(activity: Activity, title: String, module: XposedModule) {
        runCatching {
            val night = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
            val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
            val subColor = if (night) 0xFF98989D.toInt() else 0xFF8A8A8E.toInt()
            val accent = 0xFFFF2E4D.toInt()
            val dp = activity.resources.displayMetrics.density
            fun d(v: Int) = (v * dp).toInt()
            val card = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(d(24), d(22), d(24), d(18))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = d(22).toFloat(); setColor(bg)
                }
            }
            card.addView(android.widget.TextView(activity).apply {
                text = title
                textSize = 17f
                setTextColor(titleColor)
                paint.isFakeBoldText = true
            })
            card.addView(android.widget.TextView(activity).apply {
                text = "再次点击保存将使用系统保存"
                textSize = 14f
                setTextColor(subColor)
                setPadding(0, d(8), 0, 0)
            })
            val dialog = android.app.Dialog(activity)
            card.addView(android.widget.TextView(activity).apply {
                text = "好"
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setPadding(d(18), d(11), d(18), d(11))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = d(18).toFloat(); setColor(accent)
                }
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { dialog.dismiss() }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = d(18) }
            })
            dialog.setContentView(card)
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            dialog.setCancelable(true)
            dialog.show()
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 解析失败卡失败: ${it.message}")
        }
    }

    /** 追加询问（≥2 张）：图集来自分享链接解析（XhsSavePivot → vendored ResolvedNote）；确认后外挂直下。分流见 [mountClick]。 */
    private fun maybeShowBatchDialog(activity: Activity, urls: List<String>, module: XposedModule) {
        // 全量 URL 不截断（2026-10-15 原图链路诊断）：PC 端 curl 比对需完整 token/query
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 追加询问：已采图集 ${urls.size} 张（首张 " + urls.first() + "）")
        runCatching {
            showStyledDialog(activity, urls.size) { downloadAll(activity, urls, module) }
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 追加弹窗失败: ${it.message}")
        }
    }

    /**
     * 程序化自绘弹窗（宿主进程内无模块资源，全代码绘制）：圆角卡片 + 小红书红实心主按钮 +
     * 灰描边次按钮 + 深色模式自适应。
     */
    private fun showStyledDialog(activity: Activity, count: Int, onBatch: () -> Unit) {
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
        val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
        val subColor = if (night) 0xFF98989D.toInt() else 0xFF8A8A8E.toInt()
        val accent = 0xFFFF2E4D.toInt()
        val dp = activity.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(24), d(22), d(24), d(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(22).toFloat()
                setColor(bg)
            }
        }
        fun tv(text: String, size: Int, color: Int, bold: Boolean = false) = android.widget.TextView(activity).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) paint.isFakeBoldText = true
        }
        card.addView(tv("下载本篇全部原图", 17, titleColor, bold = true))
        card.addView(tv("本篇 $count 张，一起下载原图吗？", 14, subColor).apply {
            setPadding(0, d(8), 0, 0)
        })

        fun pillBtn(label: String, filled: Boolean, onClick: () -> Unit) =
            android.widget.TextView(activity).apply {
                text = label
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                setPadding(d(18), d(11), d(18), d(11))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = d(18).toFloat()
                    if (filled) setColor(accent) else {
                        setColor(0x00000000); setStroke(d(1), subColor)
                    }
                }
                setTextColor(if (filled) 0xFFFFFFFF.toInt() else subColor)
                setOnClickListener { onClick() }
            }
        val dialog = android.app.Dialog(activity)
        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, d(18), 0, 0)
        }
        row.addView(pillBtn("不用了", filled = false) { dialog.dismiss() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(10) }
        })
        row.addView(pillBtn("全部下载", filled = true) { dialog.dismiss(); onBatch() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(row)
        dialog.setContentView(card)
        // 真机实测默认 wrap 宽度挤「全部下载」换行（2026-10-15）：定宽 90% 屏 + 按钮单行
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.setCancelable(true)
        dialog.show()
    }

    // ---- 图片批量进度卡（2026-10-19 用户需求：进度 + 取消 + 半成品删除）----

    /** 取消旗标：downloadAll 每张开始前查，取消按钮置位；每轮 downloadAll 开头重置（上轮旗标不泄漏）。 */
    @Volatile private var imageCancelRequested = false

    /** 当前在写的 MediaStore uri（fetchOne insert 后持有、写完清空，每张开始前 downloadAll 再清一次）——
     *  取消时删本张半成品，绝不误删上张成品。竞态注：取消落在本张写入期间 = 本张作废，刚写完被删
     *  也符合「取消」语义（用户拍板）。 */
    @Volatile private var currentImageUri: android.net.Uri? = null

    /** 进度卡引用（对齐 XhsVideoSave.progressDialog 模式：持到字段，downloadAll 结束必关、dismiss 幂等）。 */
    @Volatile private var imageProgressDialog: android.app.Dialog? = null
    @Volatile private var imageProgressText: android.widget.TextView? = null

    /**
     * 图片批量进度卡（2026-10-19 用户需求）：自绘风格照抄 [showStyledDialog]（圆角卡/深色自适应/XHS 红），
     * 内容 = 标题「正在下载原图」+ 大字「N/M」（N = 已处理张数，成功+失败；每张完成/失败后主线程更新）
     * + 取消按钮（红 filled「取消」，pillBtn 同款）。取消 = 置 [imageCancelRequested] 并即时删本张半成品
     * （[currentImageUri]；流占用删不掉时由 downloadAll 收尾补删）。必须主线程调用（downloadAll 两处
     * 调用点都在主线程：单图静默直下 / 追加询问卡按钮 onClick）。
     */
    private fun showImageProgressDialog(activity: Activity, total: Int, module: XposedModule) {
        runCatching {
            imageProgressDialog?.dismiss()
            val night = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
            val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
            val accent = 0xFFFF2E4D.toInt()
            val dp = activity.resources.displayMetrics.density
            fun d(v: Int) = (v * dp).toInt()
            val card = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(d(24), d(22), d(24), d(18))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = d(22).toFloat(); setColor(bg)
                }
            }
            card.addView(android.widget.TextView(activity).apply {
                text = "正在下载原图"
                textSize = 17f
                setTextColor(titleColor)
                paint.isFakeBoldText = true
            })
            imageProgressText = android.widget.TextView(activity).apply {
                text = "0/$total"
                textSize = 22f
                setTextColor(titleColor)
                paint.isFakeBoldText = true
                setPadding(0, d(8), 0, 0)
            }
            card.addView(imageProgressText)
            val dialog = android.app.Dialog(activity)
            card.addView(android.widget.TextView(activity).apply {
                text = "取消"
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                setPadding(d(18), d(11), d(18), d(11))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = d(18).toFloat(); setColor(accent)
                }
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    imageCancelRequested = true
                    // 即时删本张半成品（流占用删不掉时由 downloadAll 收尾补删）；卡文本置「取消中…」
                    currentImageUri?.let { uri ->
                        runCatching { activity.contentResolver.delete(uri, null, null) }
                    }
                    imageProgressText?.text = "取消中…"
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = d(18) }
            })
            dialog.setContentView(card)
            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            // 不可点外/返回关（同 XhsVideoSave 进度卡）：误关会失去取消入口；收卡只走 dismissImageProgress
            dialog.setCancelable(false)
            imageProgressDialog = dialog
            dialog.show()
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 进度卡失败（不影响下载）: ${it.message}")
        }
    }

    /** 进度大字更新（主线程 post；卡已收则空引用 no-op）。N = 已处理张数（成功+失败）。 */
    private fun updateImageProgress(done: Int, total: Int) {
        mainHandler.post {
            runCatching { imageProgressText?.text = "$done/$total" }
        }
    }

    /** 收进度卡（幂等；主线程 post，对齐 XhsVideoSave.dismissProgress）。downloadAll 完成/取消/异常都必调。 */
    private fun dismissImageProgress() {
        mainHandler.post {
            runCatching { imageProgressDialog?.dismiss() }
            imageProgressDialog = null
            imageProgressText = null
        }
    }

    /**
     * 外挂直下：后台线程逐张 HttpURLConnection + MediaStore 落「Pictures/小红书」。
     * 2026-10-19 进度+取消（用户需求）：开头重置取消旗标并弹进度卡（[showImageProgressDialog]，主线程）；
     * 每张开始前查 [imageCancelRequested]（true 则停，剩余张不动、已下载完成的不处理），取消落在本张
     * 写入期间 = 本张作废（onClick 即删 + 收尾补删半成品）；结束（完成/取消/异常）finally 必收卡
     * （幂等）后弹既有完成卡（取消时文案带「已取消」）。
     */
    private fun downloadAll(activity: Activity, urls: List<String>, module: XposedModule) {
        imageCancelRequested = false
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 外挂直下开始 ${urls.size} 张")
        mainHandler.post {
            runCatching {
                showImageProgressDialog(activity, urls.size, module)
                android.widget.Toast.makeText(activity, "开始下载 ${urls.size} 张…", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        Thread({
            var ok = 0
            var bytes = 0L
            try {
                for ((i, raw) in urls.withIndex()) {
                    if (imageCancelRequested) break   // 取消：剩余张不动，已下载完成的不处理
                    // 每张开始前清引用——取消删的是本张半成品，上张成品（fetchOne 写完已自清）不受波及
                    currentImageUri = null
                    // 主链 = 裸链（token，无 query）；2026-10-15 基准切换逐语义移植 XHS_Downloader_Android
                    // domain/download/NoteOutput.kt:34-45（AUTO：preferred = sns-img-bd 裸链 → 回落 raw 原样，
                    // fetch() 按列表顺序逐个试）。toBare 不适用（null）时退回 raw 本身；裸链失败（非 200 /
                    // 错误页 Content-Type）由 downloadOne 用 raw 原样回落一次（见 downloadOne）。
                    val url = XhsImageUrl.toBare(raw) ?: raw
                    // 文件名扩展名仅占位：落盘 MIME/扩展名由 fetchOne 首块魔数定（提案 3，认内容不认 URL）
                    val saved = runCatching { downloadOne(activity, module, url, "xhs_${System.currentTimeMillis()}_$i.${extOf(url)}", raw) }
                        .getOrDefault(0L)
                    if (imageCancelRequested) {
                        // 取消落在本张写入期间：本张作废（计入失败侧，不 ok）。onClick 即删可能因流占用
                        // 未成功，此处流已关补删一次；竞态下刚写完被删也符合「取消」语义（用户拍板）
                        currentImageUri?.let { uri ->
                            runCatching { activity.contentResolver.delete(uri, null, null) }
                        }
                        break
                    }
                    if (saved > 0) { ok++; bytes += saved }
                    else module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 第 ${i + 1} 张下载失败: ${url.take(80)}")
                    updateImageProgress(i + 1, urls.size)
                }
            } finally {
                // 收尾必关（完成/取消/异常都到这）：清当前张引用 + 幂等 dismiss
                currentImageUri = null
                dismissImageProgress()
            }
            val canceled = imageCancelRequested
            if (canceled) {
                module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 外挂直下被用户取消 ok=$ok/${urls.size}（半成品已删）")
            }
            // 完成态提示（替换旧 Toast「已保存 N/N」，用户定案 2026-10-14）；走追踪 Activity（修 BadToken）
            showOnTrackedActivity(module, "完成卡") { showBatchSavedDialog(it, ok, urls.size, bytes, canceled) }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 外挂直下完成 $ok/${urls.size}")
        }, "HonorHook-xhs-batch").start()
    }

    /** 体积显示（单点实现）：1024 进制一位小数 MB（截断）。 */
    private fun fmtSize(bytes: Long): String {
        val mi = bytes / 1048576L
        val frac = (bytes % 1048576L) * 10L / 1048576L
        return "$mi.$frac MB"
    }

    /**
     * 批量下载完成卡（用户定案 2026-10-14）：询问卡用动作态、完成卡才说「已下载」。
     * 标题 = 成功/总数，正文 = 合计落盘体积（有失败张数时追加「，N 张失败」），单按钮关闭。
     * 2026-10-19 进度+取消：被取消轮次（[canceled]）正文改「，已取消」——剩余张未尝试不算失败；
     * 被取消的当前张按取消语义计入失败侧（不 ok，不进「N 张失败」文案）。
     * 复用本文件自绘风格（同 [showVideoSavedDialog]）；必须主线程调用。
     */
    private fun showBatchSavedDialog(activity: Activity, ok: Int, total: Int, bytes: Long, canceled: Boolean = false) {
        val failed = total - ok
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
        val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
        val subColor = if (night) 0xFF98989D.toInt() else 0xFF8A8A8E.toInt()
        val accent = 0xFFFF2E4D.toInt()
        val dp = activity.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(24), d(22), d(24), d(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(22).toFloat(); setColor(bg)
            }
        }
        card.addView(android.widget.TextView(activity).apply {
            text = "已下载 $ok/$total"
            textSize = 17f
            setTextColor(titleColor)
            paint.isFakeBoldText = true
        })
        card.addView(android.widget.TextView(activity).apply {
            text = when {
                canceled -> "${fmtSize(bytes)}，已取消"
                failed > 0 -> "${fmtSize(bytes)}，$failed 张失败"
                else -> fmtSize(bytes)
            }
            textSize = 14f
            setTextColor(subColor)
            setPadding(0, d(8), 0, 0)
        })
        val dialog = android.app.Dialog(activity)
        card.addView(android.widget.TextView(activity).apply {
            text = "好"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(d(18), d(11), d(18), d(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(18).toFloat(); setColor(accent)
            }
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { dialog.dismiss() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = d(18) }
        })
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.setCancelable(true)
        dialog.show()
    }

    private fun extOf(url: String): String =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.')
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "jpg"

    /** 提案 1 退避：500ms << (attempt-1) 封顶 15s（基准 ResumableTransfer.kt:54 `500ms shl attempt` 封顶 15s，本插件 1 基换算）。 */
    private fun backoffMs(attempt: Int): Long =
        (FETCH_BACKOFF_BASE_MS shl (attempt - 1).coerceAtMost(5)).coerceAtMost(FETCH_BACKOFF_CAP_MS)

    /**
     * 直下单张：先抓 [url]；若命中「HTTP 非 200」或「错误页 Content-Type」两类失败，则改用
     * [rawFallback]（采集原串，含 query）重下一次——**仅一次，不递归**。其余失败（超限/写盘异常）不重试。
     * （2026-10-15 基准切换：回落目标由裸链改 raw 原样，NoteOutput.kt:34-45 列表序语义；旧的
     * markQualityFailed 粘性降级随 q/100 主链一并删除。单 URL 内的瞬时失败重试阶梯在 [fetchOne]
     * 内部——基准 ResumableTransfer.kt:42-58 = 逐候选 × maxRetries 双层，候选间各走一轮阶梯。）
     * 返回值 = 落盘字节数（>0 = 成功；0 = 失败），供完成卡合计体积。
     */
    private fun downloadOne(activity: Activity, module: XposedModule, url: String, fileName: String, rawFallback: String? = null): Long {
        val bytes = LongArray(1)
        when (fetchOne(activity, module, url, fileName, bytes)) {
            Fetch.OK -> return bytes[0]
            Fetch.RETRY_FALLBACK -> if (rawFallback != null && rawFallback != url) {
                val retry = LongArray(1)
                if (fetchOne(activity, module, rawFallback, fileName, retry) == Fetch.OK) return retry[0]
            }
            Fetch.FAIL -> Unit
        }
        return 0L
    }

    /** 单地址抓取结果：OK = 已落盘；RETRY_FALLBACK = 非 200（瞬时码耗尽）/ 错误页 / 魔数非图片，可回落 raw 原样重试一次；FAIL = 终局失败。 */
    private enum class Fetch { OK, RETRY_FALLBACK, FAIL }

    /**
     * 单地址抓取（302 跟随）+ MediaStore 落盘到 Pictures/小红书。
     * 错误页识别走 Content-Type **黑名单**：只拒 JSON / XML / text 系说明页；
     * `application/octet-stream` 是合法原图（XHS 原始档实测），**绝不拒收**——其内容真实性
     * 由提案 3 魔数探测兜底。
     *
     * 提案 1 重试阶梯（基准 ResumableTransfer.kt:42-58 语义）：同一 URL 最多 [FETCH_MAX_ATTEMPTS]
     * 次尝试，仅「瞬时失败」（IOException / HTTP 5xx / 408 / 429）重试，退避见 [backoffMs]；重试只
     * 发生在「连接 + 收响应头」阶段——Content-Type 校验通过即进入写盘阶段，此后任何失败维持原
     * 语义（上抛 / 返回值，交外层 bare→raw 候选链），**不在此层重试**（MediaStore insert 不可重复）。
     * 401/403/404/410 等 4xx = 基准 Access 失败码同类（ResumableTransfer.kt:111-113），非瞬时，不重试。
     * 提案 3 魔数探测（基准 ResumableTransfer.kt:171-174 + MediaFileType.kt:8-28）：200 后先读首块
     * （64B）判内容，仅图片类继续；null / 视频类 = 可能错误页/串流 → RETRY_FALLBACK（尚未 insert，
     * 不留半成品）。落盘 MIME/扩展名以 magic 为准。
     * 提案 4 空间预检（基准 ResumableTransfer.kt:137-139 NO_SPACE）：Content-Length 已知且可用
     * 空间 < 需求 + 1MB ⇒ 判 FAIL；长度未知跳过检查（不发明阈值）。
     * 成功时经 [bytesOut] 回传落盘字节数（供完成卡合计体积）。
     */
    private fun fetchOne(activity: Activity, module: XposedModule, url: String, fileName: String, bytesOut: LongArray): Fetch {
        var u = url
        var redirects = 0
        var attempt = 1   // 提案 1：同一 URL 第几次尝试（1 基，上限 FETCH_MAX_ATTEMPTS）
        while (true) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://www.xiaohongshu.com/")
            // 响应码阶段（连接 + 收响应头）单独 try：只有这里的 IOException 算「瞬时失败」可重试
            val code = try {
                conn.responseCode
            } catch (io: IOException) {
                conn.disconnect()
                if (attempt >= FETCH_MAX_ATTEMPTS) throw io   // 全耗尽 = 原失败语义（上抛，downloadAll 记失败）
                val delay = backoffMs(attempt)
                module.log(Log.WARN, MountEngine.TAG,
                    "$JUDGE_PREFIX 直下 瞬时失败（${io.javaClass.simpleName}: ${io.message}），${delay}ms 后第 $attempt 次重试: ${u.take(80)}")
                Thread.sleep(delay)
                attempt++
                continue
            }
            try {
                when (code) {
                    in 300..399 -> {
                        if (++redirects > 5) return Fetch.FAIL
                        u = conn.getHeaderField("Location") ?: return Fetch.FAIL
                        continue
                    }
                    200 -> {
                        // 错误页类型（CDN 直链失效时常回说明页，如「图片不存在」JSON/HTML）；
                        // 非瞬时，不重试，交给回落。octet-stream 是合法原图，不在黑名单内。
                        val ctype = conn.getHeaderField("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                        if (ctype == "application/json" || ctype == "application/xml" ||
                            ctype?.startsWith("text/") == true
                        ) {
                            return Fetch.RETRY_FALLBACK
                        }
                        // 提案 4 空间预检（基准 ResumableTransfer.kt:137-139 NO_SPACE）：长度已知才查
                        val len = conn.contentLengthLong
                        if (len > 0) {
                            val dir = java.io.File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "小红书")
                            val usable = dir.usableSpace
                            if (usable < len + 1024L * 1024) {
                                module.log(Log.WARN, MountEngine.TAG,
                                    "$JUDGE_PREFIX 直下 空间不足（需 ${len / 1048576}MB，可用 ${usable / 1048576}MB），放弃")
                                return Fetch.FAIL
                            }
                        }
                        // 提案 3：insert 之前先读首块（64B，读满或流尽为止）跑魔数——null / 视频类 =
                        // 可能错误页/串流，交 raw 回落；此时尚未 insert，MediaStore 不留半成品
                        val head = ByteArray(64)
                        var headFill = 0
                        val input = conn.inputStream
                        while (headFill < head.size) {
                            val n = input.read(head, headFill, head.size - headFill)
                            if (n < 0) break
                            headFill += n
                        }
                        val kind = XhsMediaFileType.detect(if (headFill == head.size) head else head.copyOf(headFill))
                        if (kind == null || kind !in XhsMediaFileType.IMAGE_KINDS) {
                            return Fetch.RETRY_FALLBACK
                        }
                        // 落盘 MIME/扩展名以 magic 为准（基准认内容不认 URL/Content-Type；extOf 初值仅占位）
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName.substringBeforeLast('.') + "." + kind)
                            put(MediaStore.Images.Media.MIME_TYPE, XhsMediaFileType.mimeTypeOf(kind))
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/小红书")
                        }
                        val outUri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: return Fetch.FAIL
                        // 2026-10-19 进度+取消：insert 后持有（写完即清）——进度卡取消按钮据此删本张半成品
                        currentImageUri = outUri
                        activity.contentResolver.openOutputStream(outUri)?.use { out ->
                            // 首块先写入（不能丢），再续读剩余
                            out.write(head, 0, headFill)
                            input.use { rest ->
                                val buf = ByteArray(64 * 1024)
                                var total = headFill.toLong()
                                while (true) {
                                    val n = rest.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_IMAGE_BYTES) return Fetch.FAIL
                                    out.write(buf, 0, n)
                                }
                                bytesOut[0] = total
                            }
                        } ?: return Fetch.FAIL
                        // 写完即清：此后取消不得误删本张成品（已下载完成的不处理）
                        currentImageUri = null
                        return Fetch.OK
                    }
                    // 其余状态码（提案 1）：仅 5xx / 408 / 429 算瞬时重试；其余 4xx（含基准 Access
                    // 失败码 401/403/404/410，ResumableTransfer.kt:111-113）非瞬时直接回落。
                    // 全耗尽返回原失败语义（RETRY_FALLBACK，仍可走 raw 回落）
                    else -> {
                        if ((code >= 500 || code == 408 || code == 429) && attempt < FETCH_MAX_ATTEMPTS) {
                            val delay = backoffMs(attempt)
                            module.log(Log.WARN, MountEngine.TAG,
                                "$JUDGE_PREFIX 直下 瞬时失败（HTTP $code），${delay}ms 后第 $attempt 次重试: ${u.take(80)}")
                            Thread.sleep(delay)
                            attempt++
                            continue
                        }
                        return Fetch.RETRY_FALLBACK
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
