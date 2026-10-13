package top.honorhook.xposed.target.mihealth

import android.util.Base64
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米运动健康 `com.mi.health` 表盘试用永久化（基线 3.59.1，逆向记录 `docs/reverse/mihealth-face.md`）。
 *
 * 控制通道手术 v1/v2 已死透（2026-10-13 真机双证）：v1 抹空 verification（tag+len=0）、v2 保
 * info/sign 原字节改 trialDuration=36500，手表均拒收 ⇒ 手表校验 sign 覆盖全字段，**prepareInfo
 * 一个字节都不能动**（v3 起不碰 prepareInfo；历史实证免费流手表不校验文件↔表盘绑定）。
 *
 * 现行 v3「试用截获 → 免费流装载」（文件层，零协议字节改动）：
 * - 截获：试用付费表盘时 `YRNBluetoothModule.sendFileToDevice(did, type, detailType, path,
 *   segmentLength, options, callback)`（@ReactMethod 未混淆，jadx 复核 path=待传文件本地路径）
 *   被调用，临时文件安装后即删——此刻把文件字节抄进持久目录。
 * - 装载：同一挂载点，持久目录有截获文件且本次传输文件 md5 ≠ 截获记录 md5（= 免费表盘安装）
 *   时，用截获文件覆写 path 处临时文件再原样 proceed——参数零改动，文件在盘上被换，传输层照常读。
 * - 判别：付费/免费流从参数不可分、临时文件路径含表盘 hash 不可靠，唯一可靠锚 = 文件内容
 *   md5（重装同一付费表盘不自装载；装载不一次性消耗，每次非同 md5 安装都换）。
 *
 * v1/v2 的 wire 层级与 tag 速查（历史记录，工具函数仍在文末）：详见 [rewritePrepareInstall]
 * 注释与 doc §Y。选点依据不变：YRNBluetoothModule 类与方法未混淆（jadx 复核）。
 */
/**
 * ⚠️ 已关线（2026-10-14，docs/reverse/mihealth-face.md §关线）：未注册（TargetRegistry 无此目标、
 * scope.list 无 com.mi.health）——代码留档供后续变体实验复用，任何复活须先读 §关线三轮实证。
 * 关线原因：手表端三重校验（v1 verification 签名 / v2 时长字段 / v3 文件内容）全部拒收。
 * 残留活路 = 试用循环（10 分钟还原后重点试用，服务端重发授权已实证）。
 */
object MiHealthHook : TargetHook {

    override val packageName = "com.mi.health"

    /** 共享挂载基建（重试循环/失败分级/汇总日志）。 */
    private val engine = MountEngine("MiHealthHook", packageName)

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "mih-face"

    /** v1/v2 实验遗留：试用时长扩展值（天）。控制通道手术已死透（见类注释），仅供
     *  [rewritePrepareInstall] 变体实验复用，v3 挂载链不再引用。 */
    private const val TRIAL_DAYS_EXT = 36500L

    /** 判据日志节流（每 key 1 次）。 */
    private val loggedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 宿主 dataDir（install 期写入，回调期只读成员，同旧模式）。 */
    private var hostDataDir: String? = null

    /** v3 截获文件持久目录（相对 dataDir）与其下 payload/元数据文件名。 */
    private const val PAYLOAD_DIR = "files/honorhook_face_payload"
    private const val PAYLOAD_BIN = "payload.bin"
    private const val PAYLOAD_JSON = "payload.json"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MIH_FACE_FREE)) {
            logDisabled(module, FeatureCatalog.KEY_MIH_FACE_FREE, "MiHealthHook")
            return
        }
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))
        hostDataDir = param.applicationInfo?.dataDir
        val loader = param.defaultClassLoader
        val task = object : MountEngine.Task {
            override val className = "com.xiaomi.wearable.yrn.modules.bluetooth.YRNBluetoothModule"
            override val methodName = "sendFileToDevice"
            override val paramCount = 7
            override val returnType = "void"
            override val id = "mih:bt.sendFileToDevice"
            override val description = "sendFileToDevice 文件层手术：试用文件截获 → 免费流装载（v3）"
        }
        engine.attemptMount(listOf(task), module, loader) { t -> mountSendFileToDevice(t, module, loader) }
    }

    private fun mountSendFileToDevice(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // 就绪判定（每进程一次）：首个 sendFileToDevice 调用即 hook 已挂载且通路被踩过
            logThrottled(module, "ready", "$JUDGE_PREFIX: sendFileToDevice hook 就绪")
            val args = chain.args
            val path = args?.getOrNull(3) as? String
            val dataDir = hostDataDir
            if (path == null || dataDir == null) return@mount chain.proceed()
            // 文件手术整体 runCatching：任何异常（读/写/md5/JSON）= 不动文件、放行原文件，
            // 绝不 throw（ReactMethod 异步线程，回调抛非预期异常会打爆宿主桥线程）。
            runCatching {
                val src = File(path)
                if (src.isFile && src.canRead()) {
                    val dir = File(dataDir, PAYLOAD_DIR)
                    val md5 = md5Hex(src)
                    val meta = readPayloadMeta(dir)
                    when {
                        // 无截获记录 → 截获模式（临时文件安装后即删，此刻必须抄）
                        meta == null -> capturePayload(src, dir, md5, module)
                        // 同一份截获文件在重传（重装同一付费表盘）→ 不自装载，放行
                        md5 == meta.srcMd5 -> Unit
                        // 非同一份（= 免费表盘安装）→ 装载模式：截获文件覆写临时文件
                        else -> injectPayload(dir, src, meta, module, path)
                    }
                }
            }.onFailure { e ->
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX: 文件手术异常（放行原文件）: ${e.message}")
            }
            // proceed 唯一出口（防二次调用；proceed 自身异常原样传播 = 宿主语义）
            chain.proceed()
        }

    // ---------------------------------------------------------------- 截获 / 装载（文件层）

    /** 截获：复制文件字节到持久目录 + 写元数据。失败仅 WARN，不影响本次安装。 */
    private fun capturePayload(src: File, dir: File, md5: String, module: XposedModule) {
        runCatching {
            dir.mkdirs()
            src.copyTo(File(dir, PAYLOAD_BIN), overwrite = true)
            File(dir, PAYLOAD_JSON).writeText(
                JSONObject()
                    .put("srcMd5", md5)
                    .put("srcLen", src.length())
                    .put("savedAt", System.currentTimeMillis())
                    .toString(),
            )
            logThrottled(module, "capture", "$JUDGE_PREFIX: 付费表盘文件已截获 len=${src.length()} md5=${md5.take(8)}")
        }.getOrElse { e ->
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX: 截获落盘失败（本次照常安装）: ${e.message}")
        }
    }

    /**
     * 装载：用截获文件覆写 path 处的临时文件（原样 proceed，参数零改动——传输层照常读盘上字节）。
     * 覆写失败 → WARN + 放行（传输原文件）；装载不消耗元数据，可反复命中。
     */
    private fun injectPayload(dir: File, target: File, meta: PayloadMeta, module: XposedModule, path: String) {
        val written = overwriteInPlace(File(dir, PAYLOAD_BIN), target)
        if (written != null) {
            logThrottled(module, "inject", "$JUDGE_PREFIX: 已装载截获文件 len=$written（原 len=${meta.srcLen}）")
        } else {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX: 截获文件覆写失败（放行原文件传输）path=$path")
        }
    }

    /**
     * 原地覆写：payload 字节先整体读入内存，再 RandomAccessFile 清空重写（setLength(0) + write）。
     * 不用 delete+create/rename——临时文件可能正被宿主持有写句柄，inode 变化会让句柄失效；
     * 同 inode 清空重写则传输层照常读到新字节。任一步失败返回 null（调用方放行原文件）。
     */
    private fun overwriteInPlace(payload: File, target: File): Long? = runCatching {
        val bytes = payload.readBytes()
        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(0)
            raf.write(bytes)
        }
        bytes.size.toLong()
    }.getOrNull()

    /** payload 元数据（payload.json）；缺失/损坏 = 无截获记录（重新截获，良性自愈）。 */
    private class PayloadMeta(val srcMd5: String, val srcLen: Long)

    private fun readPayloadMeta(dir: File): PayloadMeta? = runCatching {
        val json = JSONObject(File(dir, PAYLOAD_JSON).readText())
        PayloadMeta(json.getString("srcMd5"), json.getLong("srcLen"))
    }.getOrNull()

    /** 文件 MD5（流式，表盘文件可能 >1MB）；读失败抛 IOException 由调用方统一兜底。 */
    private fun md5Hex(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- wire 解析（v1/v2 历史工具，纯 Kotlin，无反射；v3 挂载链不再调用）

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
     * **v3 起不再挂载（保留原因：判据日志历史与后续可能的变体实验）。**
     * v1/v2 控制通道手术双证死透（2026-10-13 真机：v1 整段抹空、v2 保 info/sign 改
     * trialDuration 均被手表拒收 ⇒ 手表校验 sign 覆盖全字段）；v3 不碰 prepareInfo，
     * 一个字节都不动（历史实证免费流手表不校验文件↔表盘绑定）。
     *
     * 原逻辑（历史记录）：识别 PREPARE_INSTALL_WATCH_FACE 试用包，保 info/sign 原字节，
     * 仅把 verification.field3(trialDuration) 改为 [TRIAL_DAYS_EXT]。
     * 重建方式 = verification 内逐字段拷贝原字节，唯 field3 替换（无则追加）；外层 tag+len 重排，
     * 包其余字段零扰动。任一步解析失败 / 字段不唯一 → null（放行原包，绝不改坏其它协议包）。
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
        // 逐字段拷贝原字节，唯 field3(trialDuration) 换成扩展值（varint 重编）；field3 缺失则追加
        val newVer = ArrayList<Byte>(verification.size + 8)
        var durationPatched = false
        for (f in verification) {
            if (f.no == 3 && f.wire == 0) {
                newVer.add(0x18.toByte())  // field3, wire0（tag 与原字段一致）
                newVer.addAll(writeVarint(TRIAL_DAYS_EXT).toList())
                durationPatched = true
            } else {
                for (i in f.tagStart until f.valEnd) newVer.add(packet[i])
            }
        }
        if (!durationPatched) {
            newVer.add(0x18.toByte())
            newVer.addAll(writeVarint(TRIAL_DAYS_EXT).toList())
        }
        val verBytes = newVer.toByteArray()
        // 外层重排：prefix + tag(0x2A) + len(varint) + 新 verification + suffix
        val out = ByteArray(verField.tagStart + 1 + writeVarint(verBytes.size.toLong()).size + verBytes.size + (packet.size - verField.valEnd))
        System.arraycopy(packet, 0, out, 0, verField.tagStart)
        var p = verField.tagStart
        out[p++] = 0x2A
        for (b in writeVarint(verBytes.size.toLong())) out[p++] = b
        System.arraycopy(verBytes, 0, out, p, verBytes.size)
        p += verBytes.size
        System.arraycopy(packet, verField.valEnd, out, p, packet.size - verField.valEnd)
        return out
    }

    /** 写 varint（protobuf，无符号；值 ≤ 0 视为 0）。 */
    private fun writeVarint(value: Long): ByteArray {
        var v = if (value > 0) value else 0L
        val out = ArrayList<Byte>(5)
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(b.toByte())
                return out.toByteArray()
            }
            out.add((b or 0x80).toByte())
        }
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
