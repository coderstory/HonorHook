package top.honorhook.xposed.target.mihealth

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONArray
import org.json.JSONObject
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米运动健康 `com.mi.health` 表盘试用永久化（基线 3.59.1，逆向记录 `docs/reverse/mihealth-face.md`）。
 *
 * 数据链（实证）：表盘商店是 RN 应用。付费表盘安装链 =
 * `GET /ops/v1/paidwatchface/download` → 响应 JSON 含 `downloadInfo.configFileV2`（CDN 签名 URL，
 * Expires=Long.MAX 永不过期）+ `license`（JSON 串，含 trial_duration）+ `sign`。
 * JS 侧 license 非空 ⇒ BT 推送 verification{info,sign,trialDuration} ⇒ 试用；
 * license 空 ⇒ 免费安装（免费表盘同链路同结构，实证）。UI 按钮状态机由 list/tab 接口的
 * face 记录驱动（price/purchaseStatus/onTrial 字段）。
 *
 * 功能语义 = **试用过的表盘永久免费重装**，三件事：
 * A. download 响应 2xx → 原文缓存到磁盘 + license/sign 置空后返回（走免费安装路径）；
 * B. download 非 2xx（试用过期服务端拒绝）且有缓存 → 回放缓存（同样置空后返回 200）；
 * C. list/tab 响应中属于已缓存 faceId 的记录改免费（price=0 / purchaseStatus=0 / onTrial=false），
 *    让 UI 出「安装」按钮。
 *
 * 选点依据：唯一挂载点 `okhttp3.OkHttpClient$Builder.build()`——okhttp3 未混淆、公开 API，
 * 跨版本最稳锚点（RN bundle 周更动态化，业务层类名/接口路径随时漂移，网络栈公开类不动）。
 * hook 语义 = **前置挂载、尾部注入**：build 前往 builder.interceptors() 末尾追加单例 Interceptor
 * 再 proceed 放行（宿主响应解密在应用层拦截器链内完成，尾插才能拿到解密后明文，见 ensureInterceptor）。Interceptor 经 Proxy 实现于目标 ClassLoader
 * （模块不引 okhttp 依赖，避免重复类与版本错配）。全部响应改写 runCatching 包裹：
 * 任何异常放行原始响应，绝不 break 网络。
 *
 * 边界：手表端是否接受空 verification 是实验问题（sign 覆盖范围黑箱），以真机为准；
 * 依赖试用期内成功拉取过一次授权（缓存生成的前提）。
 */
object MiHealthHook : TargetHook {

    override val packageName = "com.mi.health"

    /** 共享挂载基建（重试循环/失败分级/汇总日志）。 */
    private val engine = MountEngine("MiHealthHook", packageName)

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "mih-face"

    /** download 接口路径（缓存/回放的分流锚点；list/tab 也以它为排除项）。 */
    private const val DOWNLOAD_PATH = "/ops/v1/paidwatchface/download"

    /**
     * 免费化生效的 list/tab 接口路径前缀（download 已在分流时排除；三选一命中即处理，
     * 多路径并陈是因为接口随 RN bundle 版本迭代会换前缀，宁可多试探不漏）。
     */
    private val LIST_PATHS = listOf(
        "/ops/v1/paidwatchface/",
        "/ops/v1/mall/v4/watchface/",
        "/ops/v1/mall/watchface/",
    )

    /** 缓存目录（appInfo.dataDir 下；install 时传入，null = 无法缓存，hook 退化为纯放行）。 */
    private const val CACHE_DIR_REL = "files/honorhook_face_cache"

    /** peekBody 上限（响应 JSON 远小于此；超限时截断 → JSON 解析失败 → 放行原始响应，失败安全）。 */
    private const val PEEK_MAX_BYTES = 4L * 1024 * 1024

    /** install 时传入的目标数据目录（缓存落盘根）。 */
    @Volatile
    private var dataDir: String? = null

    /** 单例 Interceptor（Proxy 实例，目标 ClassLoader 生成；同进程只建一次）。 */
    @Volatile
    private var interceptorProxy: Any? = null

    /** 判据日志节流（每 key 1 次）。 */
    private val loggedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [diag] 已采样请求路径（定位用，cap 60；定位后随探针一并删除）。 */
    private val diagPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MIH_FACE_FREE)) {
            logDisabled(module, FeatureCatalog.KEY_MIH_FACE_FREE, "MiHealthHook")
            return
        }
        dataDir = param.applicationInfo?.dataDir
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))
        val loader = param.defaultClassLoader
        val task = object : MountEngine.Task {
            override val className = "okhttp3.OkHttpClient\$Builder"
            override val methodName = "build"
            override val paramCount = 0
            override val returnType = "okhttp3.OkHttpClient"
            override val id = "mih:okhttp.builderBuild"
            override val description = "okhttp Builder.build 前置注入表盘授权缓存/回放/免费化拦截器"
        }
        engine.attemptMount(listOf(task), module, loader) { t -> mountBuilderBuild(t, module, loader) }
    }

    private fun mountBuilderBuild(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            engine.judge(module, task, JUDGE_PREFIX, "okhttp Builder.build 已注入授权拦截器")
            runCatching { ensureInterceptor(chain.thisObject, module, loader) }
                .onFailure { t ->
                    module.log(Log.WARN, MountEngine.TAG, "mih-face 拦截器注入失败（放行原调用）: ${t.message}")
                }
            chain.proceed()
        }

    // ---------------------------------------------------------------- Interceptor 注入

    /**
     * 往 builder.interceptors() 插入本模块单例 Interceptor（identity 判重防重复插入）。
     * **尾插**（列表末 = 最内层 application interceptor）：宿主响应**解密**在应用层拦截器链内完成
     * （com.xiaomi.fitness.app.CloudInterceptor，2026-10-13 真机 [diag] download 实证 body=Base64
     * 密文），头插看到的是密文、JSON 解析必败；尾插拿到的才是解密后明文。宿主 build() 本体逻辑
     * 零改动（proceed 放行）。
     */
    private fun ensureInterceptor(builder: Any?, module: XposedModule, loader: ClassLoader) {
        builder ?: return
        synchronized(this) {
            val proxy = interceptorProxy ?: createInterceptor(module, loader).also { interceptorProxy = it }
            val list = builder.javaClass.getMethod("interceptors").invoke(builder) as? MutableList<Any?> ?: return
            if (list.any { it === proxy }) return
            list.add(proxy)
            module.log(Log.INFO, MountEngine.TAG, "mih-face: okhttp 拦截器已注入（尾部）")
        }
    }

    /**
     * 经 Proxy 在目标 ClassLoader 实现 okhttp3.Interceptor——模块不引 okhttp 编译依赖，
     * 避免模块自带 okhttp 与宿主内置 okhttp 双类冲突/版本错配。
     */
    private fun createInterceptor(module: XposedModule, loader: ClassLoader): Any {
        val itf = loader.loadClass("okhttp3.Interceptor")
        val holder = arrayOfNulls<Any>(1)
        val proxy = Proxy.newProxyInstance(loader, arrayOf(itf)) { _, m, args ->
            when (m.name) {
                "intercept" -> handleChain(module, loader, args?.getOrNull(0))
                "hashCode" -> System.identityHashCode(holder[0])
                "equals" -> args?.getOrNull(0) === holder[0]
                "toString" -> "HonorHook-MiHealth-FaceInterceptor"
                else -> null
            }
        }
        holder[0] = proxy
        return proxy
    }

    // ---------------------------------------------------------------- Interceptor 逻辑

    /**
     * 分流：download → 缓存/回放；list/tab → 免费化；其余一律直接 proceed。
     * 前置解析（request/url/path）失败 = 无从分流 ⇒ 放行，绝不吞请求。
     */
    private fun handleChain(module: XposedModule, loader: ClassLoader, chain: Any?): Any? {
        if (chain == null) return null
        val req = runCatching { chain.javaClass.getMethod("request").invoke(chain) }.getOrNull()
        val url = req?.let { runCatching { it.javaClass.getMethod("url").invoke(it) }.getOrNull() }
        val path = url?.let {
            runCatching { it.javaClass.getMethod("encodedPath").invoke(it) as? String }.getOrNull()
        }
        // [diag] 请求路径采样（每唯一 path 前 1 次，cap 60）——定位「试用装完但 download 分支零命中」的
        // 真机问题：请求到底走没走 Java okhttp / 实际路径长啥样。定位后删除。
        if (path != null && diagPaths.size < 60 && diagPaths.add(path)) {
            module.log(Log.INFO, MountEngine.TAG, "mih-face [diag] okhttp 请求: $path")
        }
        val isDownload = path?.contains(DOWNLOAD_PATH) == true
        val isList = !isDownload && path != null && LIST_PATHS.any { path.contains(it) }
        // proceed 反射：okhttp4 RealInterceptorChain.proceed(Request) 是 **1 参**（0 参查不到 →
        // NoSuchMethodException 经 Proxy 变 UndeclaredThrowableException 直接打爆 OkHttp 线程，
        // 2026-10-13 真机闪退实证）。按参个数匹配：1 参优先，0 参兜底（旧 okhttp）。
        val proceed = runCatching {
            chain.javaClass.methods.firstOrNull { it.name == "proceed" && it.parameterCount == 1 }
                ?.also { it.isAccessible = true }
        }.getOrNull()
        val resp = if (proceed != null && req != null) {
            invokeUnchecked(proceed, chain, arrayOf(req))
        } else {
            val zero = runCatching { chain.javaClass.getMethod("proceed") }.getOrNull()
                ?: return null
            invokeUnchecked(zero, chain, null)
        } ?: return null
        return try {
            if (isDownload) handleDownload(module, loader, url, req, path, resp)
            else handleList(module, loader, resp)
        } catch (t: Throwable) {
            module.log(Log.WARN, MountEngine.TAG, "mih-face 响应处理异常（放行原始响应）: ${t.message}")
            resp
        }
    }

    /**
     * 反射 invoke 统一出口：
     * - 解包 InvocationTargetException，还原宿主真实异常（Proxy 对声明外受检异常会包成
     *   UndeclaredThrowableException 掩盖 cause）；
     * - **okhttp4 异步链只兜 IOException**（AsyncCall.run 对非 IOException 的 Throwable 直接
     *   rethrow 打爆 Dispatcher 线程 = 进程级闪退，2026-10-13 真机实证 Canceled→RuntimeException
     *   连环炸）：cause 是 IOException 原样重抛，其余一律包成 IOException（拦截器链契约内的失败）。
     */
    private fun invokeUnchecked(method: java.lang.reflect.Method, target: Any?, args: Array<Any?>?): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            when (cause) {
                is java.io.IOException -> throw cause
                null -> throw e
                else -> throw java.io.IOException(cause)
            }
        }

    /** 事 A：download 2xx → 缓存原文 + license/sign 置空返回；事 B：非 2xx 且有缓存 → 回放。 */
    private fun handleDownload(
        module: XposedModule,
        loader: ClassLoader,
        url: Any?,
        req: Any?,
        path: String,
        resp: Any,
    ): Any? {
        val code = respCode(resp)
        val faceId = runCatching { extractFaceId(loader, url, req) }.getOrNull()
        val urlText = runCatching { url?.toString() }.getOrNull() ?: ""
        if (code in 200..299) {
            // 消费式读取（走宿主惰性解密 ResponseBody 包装，peekBody 只能拿到密文——2026-10-13
            // 真机实证尾插+peek 仍 Base64）。body 消费后必须 rebuild，读不到 = 放行（native 失败语义）
            val text = consumeText(loader, resp) ?: return resp
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return rebuildBody(loader, resp, text) ?: resp
            val key = faceId ?: md5Key(urlText.ifEmpty { path })
            saveCache(key, faceId, urlText, text)
            val stripped = stripLicenseSign(json) ?: text
            logThrottled(module, "cache:$key", "mih-face: 授权已缓存并置空 license faceId=$key")
            return rebuildBody(loader, resp, stripped) ?: resp
        }
        // 非 2xx：试用过期服务端拒绝 → 有缓存则回放（同样置空后返回 200 走免费安装路径）
        val files = cacheDir()?.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return resp
        val file = findCacheFile(files, faceId) ?: return resp
        val (cachedFaceId, bodyText) = readCache(file) ?: return resp
        val bodyJson = runCatching { JSONObject(bodyText) }.getOrNull() ?: return resp
        val stripped = stripLicenseSign(bodyJson) ?: bodyText
        val shownId = cachedFaceId ?: file.nameWithoutExtension
        logThrottled(module, "replay:$shownId", "mih-face: 服务端拒绝，已回放缓存 faceId=$shownId")
        return rebuildBody(loader, resp, stripped, forceCode = 200) ?: resp
    }

    /** 事 C：list/tab 响应中属于已缓存 faceId 的记录改免费，让 UI 出「安装」按钮。 */
    private fun handleList(module: XposedModule, loader: ClassLoader, resp: Any): Any? {
        val files = cacheDir()?.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return resp
        val ids = files.mapNotNull { readCache(it)?.first }.toSet()
        if (ids.isEmpty()) return resp
        if (respCode(resp) !in 200..299) return resp
        // 消费式读取（同 handleDownload：宿主 body 是惰性解密包装，消费后必须 rebuild）
        val text = consumeText(loader, resp) ?: return resp
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return rebuildBody(loader, resp, text) ?: resp
        val changed = mutableSetOf<String>()
        runCatching { freeize(json, ids, changed) }
        if (changed.isNotEmpty()) {
            changed.forEach { id -> logThrottled(module, "free:$id", "mih-face: 记录免费化 id=$id") }
        }
        return rebuildBody(loader, resp, if (changed.isEmpty()) text else json.toString()) ?: resp
    }

    // ---------------------------------------------------------------- JSON 处理

    /**
     * 置空 license/sign：优先 data 层，顶层字段存在也一并置空（两种都尝试——字段层级随版本漂移，
     * 置空多余层级无副作用）。无任何字段命中返回 null = 无改动（免费表盘同链路，正常路径）。
     */
    private fun stripLicenseSign(json: JSONObject): String? {
        var changed = false
        for (field in listOf("license", "sign")) {
            val data = json.optJSONObject("data")
            if (data != null && data.has(field)) {
                data.put(field, "")
                changed = true
            }
            if (json.has(field)) {
                json.put(field, "")
                changed = true
            }
        }
        return if (changed) json.toString() else null
    }

    /** 递归遍历：含 "id"+"price" 的对象且 id 已缓存 ⇒ price=0 / purchaseStatus=0 / onTrial=false。 */
    private fun freeize(node: Any?, ids: Set<String>, changed: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                if (node.has("id") && node.has("price")) {
                    val id = node.opt("id")?.toString()
                    if (id != null && id in ids) {
                        node.put("price", 0)
                        node.put("purchaseStatus", 0)
                        node.put("onTrial", false)
                        changed += id
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) freeize(node.opt(keys.next()), ids, changed)
            }
            is JSONArray -> for (i in 0 until node.length()) freeize(node.opt(i), ids, changed)
        }
    }

    /**
     * faceId 提取：先 URL query（faceId/watchFaceId/id），取不到再读请求体 JSON
     * （faceId/watchFaceId 全树找；"id" 只认顶层，防抓到无关嵌套 id）。
     * 请求体经 okio Buffer 读（writeTo 不消费原 body）。全链 runCatching，取不到返回 null。
     */
    private fun extractFaceId(loader: ClassLoader, url: Any?, req: Any?): String? {
        url?.let { u ->
            for (name in listOf("faceId", "watchFaceId", "id")) {
                val v = runCatching {
                    u.javaClass.getMethod("queryParameter", String::class.java).invoke(u, name) as? String
                }.getOrNull()
                if (!v.isNullOrEmpty()) return v
            }
        }
        val body = req?.let { runCatching { it.javaClass.getMethod("body").invoke(it) }.getOrNull() }
            ?: return null
        val text = runCatching {
            val buf = loader.loadClass("okio.Buffer").getDeclaredConstructor().newInstance()
            body.javaClass.getMethod("writeTo", buf.javaClass).invoke(body, buf)
            buf.javaClass.getMethod("readUtf8").invoke(buf) as? String
        }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        findFirstStringDeep(json, listOf("faceId", "watchFaceId"))?.let { return it }
        for (k in listOf("faceId", "watchFaceId", "id")) {
            if (!json.has(k)) continue
            json.optString(k).takeIf { it.isNotEmpty() && it != "null" }?.let { return it }
        }
        return null
    }

    /** 全树找第一个命中 [keys] 的标量值（对象/数组值跳过）。 */
    private fun findFirstStringDeep(node: Any?, keys: List<String>): String? {
        return when (node) {
            is JSONObject -> {
                for (k in keys) {
                    val v = node.opt(k)
                    if (v is String && v.isNotEmpty()) return v
                    if (v != null && v !is JSONObject && v !is JSONArray) return v.toString()
                }
                val it = node.keys()
                while (it.hasNext()) findFirstStringDeep(node.opt(it.next()), keys)?.let { return it }
                null
            }
            is JSONArray -> {
                for (i in 0 until node.length()) findFirstStringDeep(node.opt(i), keys)?.let { return it }
                null
            }
            else -> null
        }
    }

    // ---------------------------------------------------------------- 缓存

    private fun cacheDir(): File? = dataDir?.let { File(it, CACHE_DIR_REL) }

    /** 缓存文件格式：`{"faceId":"...","url":"...","body":"<原始响应文本>","savedAt":...}`。 */
    private fun saveCache(key: String, faceId: String?, urlText: String, bodyText: String) {
        val dir = cacheDir() ?: return
        runCatching {
            dir.mkdirs()
            val wrapper = JSONObject()
                .put("faceId", faceId ?: key)
                .put("url", urlText)
                .put("body", bodyText)
                .put("savedAt", System.currentTimeMillis())
            File(dir, "$key.json").writeText(wrapper.toString())
        }
    }

    /**
     * 读缓存 → (faceId, 原始响应文本)。兼容两种文件形态：本模块 wrapper（含 body 字段）
     * 与直接是响应体的情况（无 body 字段 = 整个文件就是响应文本）。
     */
    private fun readCache(file: File): Pair<String?, String>? = runCatching {
        val text = file.readText()
        val json = JSONObject(text)
        if (json.has("body")) {
            json.optString("faceId").takeIf { it.isNotEmpty() } to json.optString("body")
        } else {
            null to text
        }
    }.getOrNull()?.takeIf { it.second.isNotEmpty() }

    /** 缓存命中：先文件名匹配（saveCache 的 key 口径），再 wrapper 内 faceId 匹配；都未命中用最新文件。 */
    private fun findCacheFile(files: List<File>, faceId: String?): File? {
        if (faceId != null) {
            files.firstOrNull { it.nameWithoutExtension == faceId }?.let { return it }
            files.firstOrNull { readCache(it)?.first == faceId }?.let { return it }
        }
        return files.maxByOrNull { it.lastModified() }
    }

    // ---------------------------------------------------------------- 反射工具（响应改写）

    private fun respCode(resp: Any): Int =
        runCatching { resp.javaClass.getMethod("code").invoke(resp) as? Int }.getOrNull() ?: 0

    /**
     * 消费式读取响应文本（`body.string()`）：宿主 body 是**惰性解密 ResponseBody 包装**
     * （CloudInterceptor 系），peekBody 只能偷到原始密文——只有真正消费 body 才走解密
     * （2026-10-13 真机 [diag] 实证：头插/尾插 + peek 均 Base64）。消费后原 body 失效，
     * 调用方必须 [rebuildBody] 重建响应；读取失败返回 null = 放行（native 失败语义）。
     */
    private fun consumeText(loader: ClassLoader, resp: Any): String? = runCatching {
        val body = resp.javaClass.getMethod("body").invoke(resp) ?: return null
        body.javaClass.getMethod("string").invoke(body) as? String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** peekBody 读响应原文（不消费原 body）；截断/异常返回 null。仅 [diag] 用。 */
    private fun peekText(resp: Any): String? = runCatching {
        val peeked = resp.javaClass.getMethod("peekBody", Long::class.javaPrimitiveType).invoke(resp, PEEK_MAX_BYTES)
        peeked?.javaClass?.getMethod("string")?.invoke(peeked) as? String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 用改写文本重建 response（读过 body 必须重建：`newBuilder().body(ResponseBody.create(...))`）。
     * [forceCode] 非 null 时同时改 code（回放场景把服务端拒绝改 200）。失败返回 null = 放行原始响应。
     */
    private fun rebuildBody(loader: ClassLoader, resp: Any, newText: String, forceCode: Int? = null): Any? =
        runCatching {
            val bodyCls = loader.loadClass("okhttp3.ResponseBody")
            val mediaType = runCatching {
                val body = resp.javaClass.getMethod("body").invoke(resp)
                body?.javaClass?.getMethod("contentType")?.invoke(body)
            }.getOrNull()
            val newBody = bodyCls.getMethod("create", loader.loadClass("okhttp3.MediaType"), String::class.java)
                .invoke(null, mediaType, newText)
            val b = resp.javaClass.getMethod("newBuilder").invoke(resp)
            if (forceCode != null) b.javaClass.getMethod("code", Int::class.javaPrimitiveType).invoke(b, forceCode)
            b.javaClass.getMethod("body", bodyCls).invoke(b, newBody)
            b.javaClass.getMethod("build").invoke(b)
        }.onFailure { t ->
            Log.w(MountEngine.TAG, "mih-face 响应重建失败: ${t.message}")
        }.getOrNull()

    // ---------------------------------------------------------------- 工具

    /** 无 faceId 时的缓存 key：path+query（完整 URL）的 MD5。 */
    private fun md5Key(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 判据日志节流（每 key 1 次，防高频刷屏）。 */
    private fun logThrottled(module: XposedModule, key: String, msg: String) {
        if (loggedKeys.add(key)) module.log(Log.INFO, MountEngine.TAG, msg)
    }
}
