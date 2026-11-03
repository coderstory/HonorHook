package top.honorhook.ui.mfa

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC-6238 TOTP 引擎（自实现，2026-11-03 用户需求：模块内置 MFA 验证器首页功能）。
 * 不依赖第三方库：Base32 自解码 + javax.crypto HMAC（Android 自带）。
 * 支持算法 SHA1/SHA256/SHA512（爱码仕/Aegis 导出的账号三者都可能出现）、period 30/60、digits 6/8。
 */
object Totp {

    private val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Decode(s: String): ByteArray {
        val sb = StringBuilder()
        for (c in s.uppercase()) if (c != '=' && B32.indexOf(c) >= 0) sb.append(c)
        val out = ByteArray(sb.length * 5 / 8)
        var bits = 0; var value = 0; var o = 0
        for (i in 0 until sb.length) {
            value = (value shl 5) or B32.indexOf(sb[i])
            bits += 5
            if (bits >= 8) {
                out[o] = ((value shr (bits - 8)) and 0xFF).toByte(); o++
                bits -= 8
            }
        }
        return out
    }

    fun codeAt(secret: ByteArray, algo: String, digits: Int, period: Int, counter: Long): String {
        val mac = Mac.getInstance("Hmac$" + algo.uppercase())   // "HmacSHA1/256/512"
        mac.init(SecretKeySpec(secret, "Hmac$" + algo.uppercase()))
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) { msg[i] = (c and 0xFF).toByte(); c = c shr 8 }
        val h = mac.doFinal(msg)
        val off = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[off].toInt() and 0x7F) shl 24) or
            ((h[off + 1].toInt() and 0xFF) shl 16) or
            ((h[off + 2].toInt() and 0xFF) shl 8) or
            (h[off + 3].toInt() and 0xFF)
        val mod = TEN_POW[digits.coerceIn(6, 8)]
        return (bin % mod).toString().padStart(digits, '0')
    }

    private val TEN_POW = longArrayOf(0, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000)

    /** 当前时间片对应验证码。 */
    fun now(acc: MfaAccount, millis: Long = System.currentTimeMillis()): String {
        val counter = millis / 1000L / acc.period
        return codeAt(acc.secret, acc.algorithm, acc.digits, acc.period, counter)
    }

    /** 当前时间片剩余秒数（进度环/倒计时用）。 */
    fun remainingSeconds(acc: MfaAccount, millis: Long = System.currentTimeMillis()): Int =
        acc.period - (millis / 1000L % acc.period).toInt()
}
