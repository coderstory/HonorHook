package top.honorhook.ui.toolbox

import java.io.File
import java.security.MessageDigest

/**
 * 文件查重（2026-11-04）：大小聚类 → 同大小再算 MD5 → 分组输出。
 * 删除复用 FileOrganizer.execute（直接删除 + WAL journal；删除不可恢复）。
 * 排除区与 FileOrganizer.R0 同一份（含 Android/、.Trash/、project/ 等）。
 */
object FileDedup {

    data class FileEntry(val path: String, val size: Long, val mtime: Long) {
        val file: File get() = File(path)
    }

    /** 一组内容完全相同的文件（entries.size >= 2） */
    data class Group(val entries: List<FileEntry>)

    /** keepIndex 由 UI 逐组决定；submit 时按组删除非 keep 项（→ 回收站，可撤销） */
    data class Selection(val group: Group, val keepIndex: Int)

    private val ROOT = File("/storage/emulated/0")
    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

    /** 扫描全盘重复文件。onProgress(fileStage, hashed) 供 UI 显示进度 */
    fun scan(onProgress: (Int, Int) -> Unit): List<Group> {
        val t0 = System.currentTimeMillis()
        // ① 全盘收集（>1MB 才参与：缩略图/空文件无查重价值，大文件才是用户痛点）
        val bySize = HashMap<Long, MutableList<File>>()
        var visited = 0
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) {
                    val rel = c.relativeTo(ROOT).path.replace(File.separatorChar, '/')
                    if (FileOrganizer.EXCLUDES.any { rel.startsWith(it) || rel == it.trimEnd('/') }) continue
                    walk(c)
                } else {
                    visited++
                    if (visited % 200 == 0) onProgress(visited, 0)
                    if (c.length() > 1L shl 20) bySize.getOrPut(c.length()) { mutableListOf() }.add(c)
                }
            }
        }
        walk(ROOT)
        val walkMs = System.currentTimeMillis() - t0
        println("[FileDedup] 遍历 ${walkMs}ms / 大小重复候选 ${bySize.values.count { it.size > 1 }} 组")
        val sizeDupes = bySize.values.filter { it.size > 1 }

        // ② 同大小组 MD5：4 线程并行哈希（串行读 GB 级视频是查重慢的主因）
        val hashedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val groups = mutableListOf<Group>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val byHashAll = java.util.Collections.synchronizedMap(HashMap<String, MutableList<File>>())
        val flat = sizeDupes.sortedByDescending { it.first().length() }.flatten()
        val latch = java.util.concurrent.CountDownLatch(flat.size.coerceAtLeast(1))
        for (f in flat) {
            pool.execute {
                try {
                    val h = runCatching { headTailHash(f) }.getOrNull() ?: return@execute
                    synchronized(byHashAll) { byHashAll.getOrPut(h) { mutableListOf() }.add(f) }
                    val n = hashedCount.incrementAndGet()
                    if (n % 5 == 0) onProgress(visited, n)
                } finally { latch.countDown() }
            }
        }
        latch.await()
        pool.shutdown()
        println("[FileDedup] 哈希 ${System.currentTimeMillis() - t0 - walkMs}ms / 校验 ${hashedCount.get()} 个")
        for ((_, same) in byHashAll) {
            if (same.size > 1) groups.add(Group(same.map { FileEntry(it.absolutePath, it.length(), it.lastModified()) }))
        }
        onProgress(visited, hashedCount.get())
        // 展示排序：疑似照片组优先，再按组大小（腾出空间收益）降序
        return groups.sortedWith(
            compareByDescending<Group> { g -> g.entries.first().file.extension.lowercase() in IMG_EXT }
                .thenByDescending { g -> g.entries.first().size })
    }

    /**
     * 首尾各 100 字节摘要（用户拍板：不做全文件 MD5）。
     * size 参与哈希；≤200B 的小文件只整读一次，避免首尾区间重叠重复读。
     * 注意：首尾采样弱于全文件 MD5，理论上存在不同内容首尾相同的碰撞可能。
     */
    private fun headTailHash(f: File): String {
        val size = f.length()
        val d = MessageDigest.getInstance("MD5")
        val headLen = minOf(100L, size).toInt()
        val head = ByteArray(headLen)
        f.inputStream().use { i ->
            val n = i.read(head)
            if (n > 0) d.update(head, 0, n)
        }
        d.update(":$size".toByteArray())  // 大小参与，进一步降碰撞
        if (size > 200) {
            val tail = ByteArray(100)
            java.io.RandomAccessFile(f, "r").use { r ->
                r.seek(size - 100)
                r.readFully(tail)
            }
            d.update(tail)
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** 建议保留项：1=路径最短 2=最新 3=最旧（UI 快速选择用） */
    fun suggest(g: Group, kind: Int): Int {
        val es = g.entries
        return when (kind) {
            1 -> es.indices.minByOrNull { es[it].path.length } ?: 0
            2 -> es.indices.maxByOrNull { es[it].mtime } ?: 0
            else -> es.indices.minByOrNull { es[it].mtime } ?: 0
        }
    }

    /** 按选择删除：每组保留 keepIndex，其余 → .Trash（经 FileOrganizer.execute 全链路） */
    fun delete(context: android.content.Context, selections: List<Selection>): FileOrganizer.RunResult? {
        var seq = 0
        val ops = mutableListOf<FileOrganizer.Op>()
        for (sel in selections) {
            val now = System.currentTimeMillis()
            sel.group.entries.forEachIndexed { i, e ->
                if (i == sel.keepIndex) return@forEachIndexed
                if (now - e.mtime < 60_000) return@forEachIndexed // 60s 内写入的文件不删（审计 M4 竞态门槛）
                ops.add(FileOrganizer.Op(
                    "dup${seq++}", "dedup", FileOrganizer.OpType.DELETE,
                    e.path, "", FileOrganizer.OpStatus.PENDING,
                    e.size, e.mtime))
            }
        }
        if (ops.isEmpty()) return null
        return FileOrganizer.execute(context, FileOrganizer.Plan(ops, emptyList()), emptySet(), dryRun = false)
    }
}
