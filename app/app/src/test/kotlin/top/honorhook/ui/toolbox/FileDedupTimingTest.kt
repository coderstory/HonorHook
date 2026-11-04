package top.honorhook.ui.toolbox

import org.junit.Test

/** 计时诊断：直接对真机 /sdcard 跑查重扫描，输出分阶段耗时（只读，不动文件） */
class FileDedupTimingTest {
    @Test
    fun dedupScanTiming() {
        val t0 = System.currentTimeMillis()
        val groups = FileDedup.scan { v, h -> if (h % 50 == 0) println("[test] hashed $h") }
        println("[test] 总耗时 ${System.currentTimeMillis() - t0}ms，查得 ${groups.size} 组")
    }
}
