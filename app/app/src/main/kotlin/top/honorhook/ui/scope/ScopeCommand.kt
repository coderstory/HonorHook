package top.honorhook.ui.scope

object ScopeCommand {

    // KernelSU 的 sucompat 只认 /system/bin/su（kernel/sucompat.c 的 SU_PATH），其余路径仅为兼容 Magisk/APatch
    val SU_CANDIDATES: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
        "su",
    )

    const val ID_COMMAND = "id"
    const val PS_COMMAND = "ps -A -o PID,NAME"

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** 包名会拼进 root shell 命令串，非法字符 = 注入面。 */
    fun isValidPackage(name: String): Boolean = PACKAGE_NAME.matches(name)

    fun parseProcesses(psOutput: String): Map<String, Set<Int>> {
        val result = mutableMapOf<String, MutableSet<Int>>()
        psOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split(Regex("\\s+"))
                val pid = parts.firstOrNull()?.toIntOrNull() ?: return@forEach // 表头行
                val name = parts.getOrNull(1) ?: return@forEach
                result.getOrPut(name) { mutableSetOf() } += pid
            }
        return result
    }

    fun parseRunning(psOutput: String): Set<String> = parseProcesses(psOutput).keys

    fun isRootShell(idOutput: String): Boolean = idOutput.contains("uid=0")

    /**
     * kill 必须在前：`am force-stop` 对 persistent 进程无效（com.android.systemui 实测 pid 不变），
     * kill -9 才是真正重启的那一下；force-stop 留后只为收尾同包 `:子进程`。
     */
    fun killScript(packages: List<String>, pids: Map<String, Set<Int>>): String {
        val illegal = packages.filterNot { isValidPackage(it) }
        require(illegal.isEmpty()) { "非法包名: $illegal" }
        return packages.mapNotNull { pkg ->
            pids[pkg]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null // kill 无参数会报错，污染退出码
            "kill -9 \$(pidof $pkg) ; am force-stop $pkg"
        }.joinToString(" ; ")
    }
}
