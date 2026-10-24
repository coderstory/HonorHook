package top.honorhook.ui.toolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.honorhook.ui.scope.SafeCommands
import java.util.concurrent.atomic.AtomicInteger

/** 28 号 §6：中断续跑不重放 done（JVM 纯逻辑单测，runner 注入）。 */
class ToolQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val switch = ToolAction.Switch(SafeCommands.Table.SECURE, "k1", "0")
    private val freeze = ToolAction.Freeze("com.hihonor.magazine")

    @Test
    fun `单项失败不中断后续执行`() = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder()
        val calls = AtomicInteger(0)
        val core = ToolQueue.Core(java.io.File(dir, "q.txt"), java.io.File(dir, "h")) { action ->
            calls.incrementAndGet()
            if (action == switch) ActionOutcome(action, false, "", "写入失败") 
            else ActionOutcome(action, true, "3", "")
        }
        core.enqueue(listOf(switch, freeze))
        val summary = core.runPending()
        assertEquals(1, summary.done)
        assertEquals(1, summary.failed)
        assertEquals(2, calls.get())
        assertEquals("写入失败", summary.failures.single().error)
    }

    @Test
    fun `中断续跑不重放 done`() = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder()
        val qfile = java.io.File(dir, "q.txt")
        val hdir = java.io.File(dir, "h")
        val calls = AtomicInteger(0)
        val first = ToolQueue.Core(qfile, hdir) { action ->
            calls.incrementAndGet()
            // 第一次执行：Freeze 成功后「进程死亡」——第二项未执行
            if (action == freeze) ActionOutcome(action, true, "3", "") else throw RuntimeException("中断")
        }
        first.enqueue(listOf(freeze, switch))
        runCatching { first.runPending() }

        // 新实例（等价重启后）从同一队列文件恢复
        val second = ToolQueue.Core(qfile, hdir) { action ->
            calls.incrementAndGet()
            ActionOutcome(action, true, "0", "")
        }
        val items = second.items()
        assertEquals(2, items.size)
        assertEquals(ToolQueue.Core.State.DONE, items[0].state)
        assertEquals(ToolQueue.Core.State.PENDING, items[1].state)

        val summary = second.runPending()
        assertEquals(1, summary.done) // 只跑了 PENDING 的那项
        assertEquals(3, calls.get()) // 1(freeze) + 1(中断的 switch) + 1(续跑 switch) —— done 的 freeze 未重放
        assertEquals(0, second.pending().size)
    }

    @Test
    fun `持久化往返保真 Switch 表与值`() {
        val dir = tmp.newFolder()
        val qfile = java.io.File(dir, "q.txt")
        val core = ToolQueue.Core(qfile, java.io.File(dir, "h")) { TODO() }
        val a = ToolAction.Switch(SafeCommands.Table.GLOBAL, "private_dns_mode", "hostname")
        val b = ToolAction.Switch(null, "no_table_key", "v")
        core.enqueue(listOf(a, b))
        val reloaded = ToolQueue.Core(qfile, java.io.File(dir, "h")) { TODO() }.items()
        assertEquals(2, reloaded.size)
        val ra = reloaded[0].action as ToolAction.Switch
        assertEquals(SafeCommands.Table.GLOBAL, ra.table)
        assertEquals("private_dns_mode", ra.key)
        assertEquals("hostname", ra.value)
        val rb = reloaded[1].action as ToolAction.Switch
        assertEquals(null, rb.table)
        assertTrue(reloaded.all { it.state == ToolQueue.Core.State.PENDING })
    }
}
