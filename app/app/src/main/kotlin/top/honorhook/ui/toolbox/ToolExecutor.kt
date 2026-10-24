package top.honorhook.ui.toolbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.honorhook.ui.scope.RootShell
import top.honorhook.ui.scope.SafeCommands

sealed interface ToolAction {
    /** 冻结 = pm disable-user --user 0（12 号首选机制，/data 持久）。 */
    data class Freeze(val pkg: String) : ToolAction

    /** 解冻 = pm enable。临时 root 环境下解冻同样需 root 在场（12 号红线注）。 */
    data class Unfreeze(val pkg: String) : ToolAction

    /**
     * settings 键写入。[table]=null 时执行器三表定位（28 号 §7 表陷阱：
     * 写错表 exit 0 静默无效；state_of_ads_switch 双表同键会写全部命中表）。
     */
    data class Switch(val table: SafeCommands.Table?, val key: String, val value: String) : ToolAction
}

data class ActionOutcome(
    val action: ToolAction,
    val ok: Boolean,
    /** 回读值（freeze=enabledState，switch=三表读到的值） */
    val readBack: String,
    val error: String = "",
)

/**
 * 执行 + 回读判据（28 号 §2/§6）：
 * freeze → getApplicationEnabledSetting==3（免 root API）；
 * switch → root `settings get` == 目标值，再与 Settings API 双读比对。
 * 任何失败如实返回，不重试不吞错——失败清单留给队列层汇总。
 */
object ToolExecutor {

    suspend fun run(context: Context, action: ToolAction): ActionOutcome = withContext(Dispatchers.IO) {
        runCatching { runInternal(context, action) }.getOrElse {
            ActionOutcome(action, ok = false, readBack = "", error = it.message ?: it.javaClass.simpleName)
        }
    }

    private fun runInternal(context: Context, action: ToolAction): ActionOutcome = when (action) {
        is ToolAction.Freeze -> runFreeze(context, action)
        is ToolAction.Unfreeze -> runUnfreeze(context, action)
        is ToolAction.Switch -> runSwitch(context, action)
    }

    private fun runFreeze(context: Context, action: ToolAction.Freeze): ActionOutcome {
        GovernanceList.requireSelectable(action.pkg)
        val cmd = SafeCommands.pmDisable(action.pkg)
            ?: return ActionOutcome(action, false, "", "非法包名被 SafeCommands 拒绝")
        return when (val result = RootShell.exec(cmd)) {
            is RootShell.Result.Ok -> {
                val state = AppRepository.enabledState(context, action.pkg)
                ActionOutcome(action, state == 3, state.toString(),
                    if (state == 3) "" else "写入 exit 0 但 enabledState=$state")
            }
            else -> ActionOutcome(action, false, "", describe(result))
        }
    }

    private fun runUnfreeze(context: Context, action: ToolAction.Unfreeze): ActionOutcome {
        GovernanceList.requireSelectable(action.pkg)
        val cmd = SafeCommands.pmEnable(action.pkg)
            ?: return ActionOutcome(action, false, "", "非法包名被 SafeCommands 拒绝")
        return when (val result = RootShell.exec(cmd)) {
            is RootShell.Result.Ok -> {
                val state = AppRepository.enabledState(context, action.pkg)
                ActionOutcome(action, state == 0 || state == 1, state.toString(), "")
            }
            else -> ActionOutcome(action, false, "", describe(result))
        }
    }

    private fun runSwitch(context: Context, action: ToolAction.Switch): ActionOutcome {
        val tables = action.table?.let { listOf(it) } ?: locateTables(action.key)
        if (tables.isEmpty()) {
            // 三表都查不到该键：落到 Entry 声明的缺省表（首开类键本来就不在场）
            val fallback = ToolSwitchTable.ENTRIES.firstOrNull { it.key == action.key }?.table
                ?: SafeCommands.Table.SECURE
            return writeAndVerify(context, action, listOf(fallback))
        }
        return writeAndVerify(context, action, tables)
    }

    /** 三表定位：命中的表全写（双表同键场景），一个都没命中由调用方落缺省。 */
    private fun locateTables(key: String): List<SafeCommands.Table> =
        SafeCommands.Table.entries.filter { t ->
            val cmd = SafeCommands.settingsGet(t, key) ?: return@filter false
            when (val r = RootShell.exec(cmd)) {
                is RootShell.Result.Ok -> r.stdout.trim().isNotEmpty() && r.stdout.trim() != "null"
                else -> false
            }
        }

    private fun writeAndVerify(
        context: Context,
        action: ToolAction.Switch,
        tables: List<SafeCommands.Table>,
    ): ActionOutcome {
        for (t in tables) {
            val put = SafeCommands.settingsPut(t, action.key, action.value)
                ?: return ActionOutcome(action, false, "", "非法键/值被 SafeCommands 拒绝")
            when (val r = RootShell.exec(put)) {
                is RootShell.Result.Ok -> Unit
                else -> return ActionOutcome(action, false, "", describe(r))
            }
        }
        // 回读：root settings get（写过的每张表）+ Settings API 免 root 双读
        for (t in tables) {
            val get = SafeCommands.settingsGet(t, action.key)
                ?: return ActionOutcome(action, false, "", "非法键被 SafeCommands 拒绝")
            val viaRoot = when (val r = RootShell.exec(get)) {
                is RootShell.Result.Ok -> r.stdout.trim()
                else -> return ActionOutcome(action, false, "", describe(r))
            }
            if (viaRoot != action.value) {
                return ActionOutcome(action, false, viaRoot, "root 回读≠目标值（表=${t.token}）")
            }
            val viaApi = SettingsReader.read(context, t, action.key)
            if (viaApi != null && viaApi != action.value) {
                return ActionOutcome(action, false, viaApi, "Settings API 回读≠目标值（表=${t.token}）")
            }
        }
        return ActionOutcome(action, true, action.value, "")
    }

    fun describe(result: RootShell.Result): String = when (result) {
        is RootShell.Result.Failed -> "exit ${result.exit} stderr=${result.stderr.take(120)}"
        RootShell.Result.Unavailable -> "su 不可用"
        RootShell.Result.Timeout -> "命令超时"
        is RootShell.Result.Ok -> ""
    }
}
