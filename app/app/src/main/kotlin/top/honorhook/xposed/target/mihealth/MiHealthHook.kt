package top.honorhook.xposed.target.mihealth

import android.util.Base64
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米运动健康 `com.mi.health` 表盘试用永久化（基线 3.59.1，逆向记录 `docs/reverse/mihealth-face.md`）。
 *
 * 数据链（实证）：表盘商店是 RN 应用。付费表盘试用授权 = JS 侧组装 protobuf `WearPacket`
 * （base64 字符串）→ `YRNBluetoothModule.sendRaw(requestData, options, callback)`（@ReactMethod，
 * 未混淆，jadx 复核：内部 base64 decode → `DeviceContact.call(did, 101, bytes)`）→ BT 推手表。
 * 试用包语义：type=4(WATCH_FACE) 且 id=4(PREPARE_INSTALL_WATCH_FACE) 的包带
 * `verification{info=license, sign, trialDuration}`；**抹空 verification（tag 保留 + len=0 的零长
 * 消息 = 全默认值）= 手表按免费安装收货 = 试用永不过期**。
 *
 * 响应层死路（实证，详见 doc §X）：download/list 响应是 Mi WUP 加密二进制，okhttp 链任何位置
 * （头插/尾插/消费式 body.string()）都只拿到 Base64 密文，解密在 JS/桥层——okhttp 拦截器/
 * 缓存/免费化三件套已整体删除，不再走响应层。
 *
 * 选点依据：唯一挂载点 `YRNBluetoothModule.sendRaw`——类与方法未混淆（jadx 复核），BT 通道
 * 收口点（所有 WearPacket 出手表必经），比 JS bundle 内锚点跨周更稳定。
 *
 * 顶层 tag 速查（protobuf wire format，schema 来自 common bundle 的 protobuf.js encoder，
 * 字段号已核对；详见 doc §Y）：
 * ```
 * WearPacket                  { 1: type(int32), 2: id(uint32), 6: watchFace(msg) }
 * WatchFace                   { 6: prepareInfo(msg) }
 * PrepareInfo                 { 1: id(string), 2: size, 3: versionCode, 5: verification(msg) }
 * PrepareInfo.Verification    { 1: info(string), 2: sign(string), 3: trialDuration(varint) }
 * ```
 * 顶层 tag：field1 varint=0x08 / field2 varint=0x10 / field6 len=0x32；
 * WatchFace 内 prepareInfo=0x32；PrepareInfo 内 verification=0x2A。
 *
 * 实验变量：手表端是否校验 sign 覆盖范围 = 黑箱（手表固件不可见），空 verification 是否被
 * 接受以真机为准（doc §Y 判据 3/4）。rewrite 带结构轻校验：字段号漂移/结构不符一律 null
 * 放行原包，绝不改坏其它协议包。
 */
object MiHealthHook : TargetHook {

    override val packageName = "com.mi.health"

    /** 共享挂载基建（重试循环/失败分级/汇总日志）。 */
    private val engine = MountEngine("MiHealthHook", packageName)

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "mih-face"

    /** 判据日志节流（每 key 1 次）。 */
    private val loggedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MIH_FACE_FREE)) {
            logDisabled(module, FeatureCatalog.KEY_MIH_FACE_FREE, "MiHealthHook")
            return
        }
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))
        val loader = param.defaultClassLoader
        val task = object : MountEngine.Task {
            override val className = "com.xiaomi.wearable.yrn.modules.bluetooth.YRNBluetoothModule"
            override val methodName = "sendRaw"
            override val paramCount = 3
            override val returnType = "void"
            override val id = "mih:bt.sendRaw"
            override val description = "sendRaw BT 出包手术：PREPARE_INSTALL 包抹空 verification（免费安装收货）"
        }
        engine.attemptMount(listOf(task), module, loader) { t -> mountSendRaw(t, module, loader) }
    }

    private fun mountSendRaw(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // 就绪判定（每进程一次）：首个 sendRaw 调用即 hook 已挂载且通路被踩过
            logThrottled(module, "ready", "$JUDGE_PREFIX: sendRaw hook 就绪")
            val args = chain.args
            val b64 = args?.getOrNull(0) as? String
            if (args == null || b64 == null) {
                // 参数骨架不完整：无从判断包类型，原样放行（宁漏改不误改）
                return@mount chain.proceed()
            }
            // 整体 runCatching：任何异常（含 base64 解码失败）= 原样 proceed，绝不 throw
            // （okhttp 教训：回调抛非预期异常会打爆宿主线程；ReactMethod 异步线程同理）
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val rewritten = rewritePrepareInstall(bytes)
                if (rewritten != null) {
                    val faceId = prepareFaceId(bytes)?.take(64) ?: ""
                    logThrottled(module, "wipe:$faceId", "$JUDGE_PREFIX: PREPARE_INSTALL verification 已抹空 faceId=$faceId")
                    chain.proceedWith(chain.thisObject, arrayOf(Base64.encodeToString(rewritten, Base64.NO_WRAP), args[1], args[2]))
                } else {
                    chain.proceed()
                }
            }.getOrElse { chain.proceed() }
        }

    // ---------------------------------------------------------------- wire 解析（纯 Kotlin，无反射）

    /** 读 varint（protobuf，最多 10 字节），返回 (值, 下一位置)；越界/超长返回 null。 */
    private fun readVarint(b: ByteArray, pos: Int, end: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var p = pos
        while (true) {
            if (p >= end) return null
            if (shift >= 64) return null
            val byte = b[p].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            p++
            if (byte and 0x80 == 0) return result to p
            shift += 7
        }
    }

    /**
     * 单个 protobuf 字段：字段号 + wire type + 原始包内的定位（tag 起始 / 值起止）。
     * 定位字段是为原位手术（改 len 而非整体重组包）——多余字节零扰动。
     */
    private data class WireField(val no: Int, val wire: Int, val tagStart: Int, val valStart: Int, val valEnd: Int)

    /**
     * 逐 tag 解析 [start, end) 区间内的 protobuf 字段。只认 wireType 0（varint）与 2（len-delimited），
     * 未知 wireType 返回 null；解析必须恰好消耗到 [end]，否则 null（截断/畸形包 = 不改）。
     */
    private fun parseFields(b: ByteArray, start: Int, end: Int): List<WireField>? {
        val fields = ArrayList<WireField>()
        var pos = start
        while (pos < end) {
            val tagStart = pos
            val (tag, afterTag) = readVarint(b, pos, end) ?: return null
            val no = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (no == 0) return null
            val (valStart, valEnd) = when (wire) {
                0 -> {
                    val (_, next) = readVarint(b, afterTag, end) ?: return null
                    afterTag to next
                }
                2 -> {
                    val (len, next) = readVarint(b, afterTag, end) ?: return null
                    if (next + len > end) return null
                    next to (next + len).toInt()
                }
                else -> return null
            }
            fields.add(WireField(no, wire, tagStart, valStart, valEnd))
            pos = valEnd
        }
        return fields
    }

    /**
     * BT wire 手术：识别 PREPARE_INSTALL_WATCH_FACE 试用包，抹空 verification。
     * 重建方式 = 原包字节原位替换 `[verification.tagStart, valEnd)` 段为 `0x2A, 0x00`
     * （tag 保留 + 零长消息 = verification 全默认值），其余字段零扰动。
     * 任一步解析失败 / 字段不唯一 → null（放行原包，绝不改坏其它协议包）。
     */
    private fun rewritePrepareInstall(packet: ByteArray): ByteArray? {
        val packetFields = parseFields(packet, 0, packet.size) ?: return null
        // 语义门：type=4(WATCH_FACE) 且 id=4(PREPARE_INSTALL_WATCH_FACE) 才动
        val typeField = packetFields.singleOrNull { it.no == 1 && it.wire == 0 } ?: return null
        val idField = packetFields.singleOrNull { it.no == 2 && it.wire == 0 } ?: return null
        val typeVal = readVarint(packet, typeField.valStart, typeField.valEnd)?.first ?: return null
        val idVal = readVarint(packet, idField.valStart, idField.valEnd)?.first ?: return null
        if (typeVal != 4L || idVal != 4L) return null
        // 层级下钻：WearPacket.6 → WatchFace.6(prepareInfo) → PrepareInfo.5(verification)
        val wfField = packetFields.singleOrNull { it.no == 6 && it.wire == 2 } ?: return null
        val watchFace = parseFields(packet, wfField.valStart, wfField.valEnd) ?: return null
        val piField = watchFace.singleOrNull { it.no == 6 && it.wire == 2 } ?: return null
        val prepareInfo = parseFields(packet, piField.valStart, piField.valEnd) ?: return null
        val verField = prepareInfo.singleOrNull { it.no == 5 && it.wire == 2 } ?: return null
        val verification = parseFields(packet, verField.valStart, verField.valEnd) ?: return null
        // 结构轻校验：verification 必须含 field1(info) 或 field2(sign) 字符串字段——防误伤
        // 其它层级的 field5 消息（宿主 proto 字段号随版本可能漂移，不符即放行）
        if (verification.none { (it.no == 1 || it.no == 2) && it.wire == 2 }) return null
        val out = ByteArray(packet.size - (verField.valEnd - verField.tagStart) + 2)
        System.arraycopy(packet, 0, out, 0, verField.tagStart)
        out[verField.tagStart] = 0x2A // field5, wire2（tag 与原字段一致）
        out[verField.tagStart + 1] = 0x00 // len=0：零长消息 = verification 全默认值 = 免费安装
        System.arraycopy(packet, verField.valEnd, out, verField.tagStart + 2, packet.size - verField.valEnd)
        return out
    }

    /** 判据日志用 faceId：PrepareInfo.Verification.1(info) 的 UTF-8 字符串；结构不符返回 null。 */
    private fun prepareFaceId(packet: ByteArray): String? {
        val packetFields = parseFields(packet, 0, packet.size) ?: return null
        val wfField = packetFields.singleOrNull { it.no == 6 && it.wire == 2 } ?: return null
        val watchFace = parseFields(packet, wfField.valStart, wfField.valEnd) ?: return null
        val piField = watchFace.singleOrNull { it.no == 6 && it.wire == 2 } ?: return null
        val prepareInfo = parseFields(packet, piField.valStart, piField.valEnd) ?: return null
        val infoField = prepareInfo.singleOrNull { it.no == 1 && it.wire == 2 } ?: return null
        return String(packet, infoField.valStart, infoField.valEnd - infoField.valStart, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- 工具

    /** 判据日志节流（每 key 1 次，防高频刷屏）。 */
    private fun logThrottled(module: XposedModule, key: String, msg: String) {
        if (loggedKeys.add(key)) module.log(Log.INFO, MountEngine.TAG, msg)
    }
}
