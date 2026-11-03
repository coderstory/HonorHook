package top.honorhook.ui.mfa

import android.content.Context
import android.net.Uri
import org.json.JSONArray

/**
 * MFA 账号存储（2026-11-03）：模块自身 SharedPreferences（"mfa_accounts"）。
 *
 * **安全披露（审计痕迹）**：TOTP 密钥以 otpauth URI 原文落盘本 app prefs——受设备锁屏 +
 * 应用沙箱保护，但**没有**独立密码层（爱码仕/Aegis 有 PBKDF2+AES-GCM vault）。LSPosed 模块
 * 无独立安全屏幕，独立密码层留待后续（用户已知情同意 2026-11-03 会话）。
 *
 * 数据形态：单 key "uris" = JSONArray(otpauth URI 列表)。迁移/扫码/手动录入统一落成 URI，
 * 展示层再解析——单一事实源，迁移后可用备份 URI 原样迁出到任何标准验证器。
 */
data class MfaAccount(
    val uri: String,
    val issuer: String,
    val label: String,
    val secret: ByteArray,
    val algorithm: String,
    val digits: Int,
    val period: Int,
)

object MfaStore {

    private const val PREF = "mfa_accounts"
    private const val KEY = "uris"
    private const val ALIAS = "aliases"

    /** otpauth://totp/Issuer:label?secret=..&issuer=..&algorithm=..&digits=..&period=.. 解析。 */
    fun parse(uri: String): MfaAccount? = runCatching {
        if (!uri.startsWith("otpauth://")) return null
        val u = Uri.parse(uri)
        val path = u.path.orEmpty().trimStart('/')
        val label = if (path.contains(':')) path.substringAfter(':').trim() else path
        val issuer = u.getQueryParameter("issuer")
            ?: path.substringBefore(':').takeIf { path.contains(':') }?.trim()
            ?: label
        val s = u.getQueryParameter("secret") ?: return null
        val secret = Totp.base32Decode(s)
        if (secret.isEmpty()) return null
        MfaAccount(
            uri = uri,
            issuer = issuer.ifEmpty { "未命名" },
            label = label.ifEmpty { "未命名" },
            secret = secret,
            algorithm = (u.getQueryParameter("algorithm") ?: "SHA1").uppercase(),
            digits = (u.getQueryParameter("digits") ?: "6").toIntOrNull()?.coerceIn(6, 8) ?: 6,
            period = (u.getQueryParameter("period") ?: "30").toIntOrNull()?.coerceAtLeast(15) ?: 30,
        )
    }.getOrNull()

    fun list(context: Context): List<MfaAccount> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).mapNotNull { parse(arr.optString(it)) }
    }

    /** 去重加入（同 URI 只存一条）；返回加入条数。 */
    fun addAll(context: Context, uris: List<String>): Int {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        val seen = (0 until arr.length()).mapTo(HashSet()) { arr.optString(it) }
        var added = 0
        for (u in uris) {
            if (u !in seen && parse(u) != null) { arr.put(u); seen.add(u); added++ }
        }
        if (added > 0) prefs.edit().putString(KEY, arr.toString()).apply()
        return added
    }

    // ---- 别名（2026-11-03 用户需求：改别名）：单独 key 存 JSONObject(uri→alias)，显示优先别名 ----
    fun aliasOf(context: Context, uri: String): String? = runCatching {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(ALIAS, null)?.let { org.json.JSONObject(it).optString(uri, "").ifEmpty { null } }
    }.getOrNull()

    fun setAlias(context: Context, uri: String, alias: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val obj = org.json.JSONObject(prefs.getString(ALIAS, "{}") ?: "{}")
            if (alias.isBlank()) obj.remove(uri) else obj.put(uri, alias)
            prefs.edit().putString(ALIAS, obj.toString()).apply()
        }
    }

    /**
     * 备份（2026-11-03 用户需求）：uris + aliases 打包 JSON 写公共下载目录（MediaStore.Downloads，
     * API 29+ 免存储权限）。返回显示文件名；失败 null（UI toast 报错）。
     */
    fun backup(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val uris = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        val aliases = org.json.JSONObject(prefs.getString(ALIAS, "{}") ?: "{}")
        val payload = org.json.JSONObject()
            .put("app", "honorhook-mfa")
            .put("uris", uris)
            .put("aliases", aliases)
        val name = "mfa_backup_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
            java.util.Locale.US).format(java.util.Date())
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$name.json")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toString().toByteArray()) }
        "$name.json"
    }.getOrNull()

    /**
     * 导入（恢复）：吃备份 JSON 文本，uris 去重入库、aliases 并入。
     * 返回 (新增数, 文件内有效账号数)——新增 0 且有效 >0 = 全部已存在（非错误，2026-11-03 真机反馈歧义）。
     */
    fun importJson(context: Context, text: String): Pair<Int, Int> = runCatching {
        val obj = org.json.JSONObject(text)
        val uris = mutableListOf<String>()
        val arr = obj.optJSONArray("uris") ?: return Pair(0, 0)
        for (i in 0 until arr.length()) arr.optString(i).takeIf { parse(it) != null }?.let { uris.add(it) }
        val added = addAll(context, uris)
        val aliases = obj.optJSONObject("aliases")
        if (aliases != null) {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val mine = org.json.JSONObject(prefs.getString(ALIAS, "{}") ?: "{}")
            for (k in aliases.keys()) mine.putOpt(k, aliases.opt(k))
            prefs.edit().putString(ALIAS, mine.toString()).apply()
        }
        Pair(added, uris.size)
    }.getOrDefault(Pair(0, 0))

    fun remove(context: Context, uri: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        val keep = (0 until arr.length()).map { arr.optString(it) }.filter { it != uri }
        prefs.edit().putString(KEY, JSONArray(keep).toString()).apply()
        runCatching {   // 连带清别名
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val obj = org.json.JSONObject(prefs.getString(ALIAS, "{}") ?: "{}")
            obj.remove(uri)
            prefs.edit().putString(ALIAS, obj.toString()).apply()
        }
    }
}
