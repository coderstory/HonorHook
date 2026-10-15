package top.honorhook.xposed.target.xhs

/**
 * 魔数签名表（提案 3，2026-10-15）：逐条抄基准 XHS_Downloader_Android
 * `data/network/MediaFileType.kt:8-28`——认内容，不赌服务端 Content-Type / URL 扩展名。
 * 唯一差异是入参形态：基准传 File 自读前 64 字节；插件传已读入内存的首块（X5 fetchOne 读满
 * 64B、X4 downloadVideoHd 首写路径缓存 64B），签名与判定顺序逐条对齐基准。
 */
object XhsMediaFileType {

    /** 图片类全集（X5 落盘放行集；基准 MediaFileType.kt:14-20）。 */
    val IMAGE_KINDS = setOf("jpg", "png", "gif", "webp", "avif", "heic")

    /** 视频类全集（X4 终检放行集；基准 MediaFileType.kt:21-24）。 */
    val VIDEO_KINDS = setOf("mov", "mp4", "webm")

    /** 按首块判类型；认不出返回 null（基准 ResumableTransfer.kt:171-174：null = NON_MEDIA 删件）。 */
    fun detect(head: ByteArray): String? {
        fun starts(vararg signature: Int) =
            head.size >= signature.size && signature.indices.all { (head[it].toInt() and 255) == signature[it] }
        fun text(start: Int, size: Int): String =
            if (head.size >= start + size) String(head, start, size, Charsets.ISO_8859_1) else ""
        return when {
            starts(255, 216, 255) -> "jpg"                             // FF D8 FF
            starts(137, 80, 78, 71, 13, 10, 26, 10) -> "png"           // 89 50 4E 47 0D 0A 1A 0A
            text(0, 3) == "GIF" -> "gif"                               // 基准只判 3 字节前缀（覆盖 GIF87a/89a）
            text(0, 4) == "RIFF" && text(8, 4) == "WEBP" -> "webp"
            text(4, 4) == "ftyp" -> when {                             // ISO BMFF：offset 4 起 brand
                text(8, head.size.coerceAtMost(64) - 8).contains("avif") || text(8, 4) == "avis" -> "avif"
                text(8, 4) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> "heic"
                text(8, 4) == "qt  " -> "mov"                          // 值含两个尾随空格（基准原文）
                else -> "mp4"                                          // 基准：ftyp 其余 brand（isom/mp42/M4V…）一律 mp4
            }
            starts(26, 69, 223, 163) -> "webm"                         // EBML 1A 45 DF A3
            else -> null
        }
    }

    /** 类型 → MIME（对齐基准 MediaFileType 的 (extension, mimeType) 成对表；X4 只用集合判定，不取 MIME）。 */
    fun mimeTypeOf(kind: String): String? = when (kind) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic" -> "image/heic"
        "mov" -> "video/quicktime"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> null
    }
}
