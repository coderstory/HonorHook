package top.honorhook.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.honorhook.BuildConfig
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

private sealed interface Phase {
    data object Confirm : Phase
    data class Downloading(val progress: Int) : Phase
    data class Ready(val apk: File) : Phase
    data class Error(val msg: String) : Phase
}

/** 更新流程对话框：确认 → 下载（进度）→ 安装（跳系统安装器 / 授权页）。 */
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val release = UpdateState.latest ?: return
    var phase by remember { mutableStateOf<Phase>(Phase.Confirm) }
    // 授权提示独立于 Phase：install 跳授权页返回 false 时保持 Ready（覆写成 Error 会让
    // 授权回来后找不到安装按钮，重试=全量重下）；再点安装时 install 内部会重查授权
    var installHint by remember { mutableStateOf(false) }

    fun startDownload() {
        scope.launch {
            phase = Phase.Downloading(0)
            try {
                val dest = File(context.cacheDir, "update/app-release.apk")
                withContext(Dispatchers.IO) {
                    // 探测进行中先等它出结果（TLS 探活 ~2s），拿不到再走逐源兜底链
                    val probed = UpdateState.awaitProbe()
                    if (probed != null) {
                        ApkInstaller.downloadFrom(probed, dest) { p -> phase = Phase.Downloading(p) }
                    } else {
                        ApkInstaller.download(release.apkUrl, dest) { p -> phase = Phase.Downloading(p) }
                    }
                }
                phase = Phase.Ready(dest)
            } catch (e: Exception) {
                phase = Phase.Error(e.message ?: "下载失败")
            }
        }
    }

    WindowDialog(
        show = true,
        title = "检查更新",
        summary = null,
        onDismissRequest = { if (phase !is Phase.Downloading) onDismiss() },
    ) {
        val p = phase
        when (p) {
            is Phase.Confirm -> {
                Text(
                    text = "当前 v${BuildConfig.VERSION_NAME}"
                            + " → 最新 ${release.tag}"
                            + (if (release.apkUrl.isEmpty()) "\n⚠️ 该 release 未附带 APK" else ""),
                )
                Actions(
                    left = "取消" to onDismiss,
                    right = "下载" to { startDownload() },
                    rightEnabled = release.apkUrl.isNotEmpty(),
                )
            }

            is Phase.Downloading -> Column {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    progress = p.progress / 100f,
                )
                Text(
                    text = "下载中… ${p.progress}%",
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            is Phase.Ready -> {
                // install 返回 false = 已跳「安装未知应用」授权页：保持 Ready 只挂提示，
                // 授权回来安装按钮还在，授权后点安装直达系统安装器
                Text(text = "下载完成，点击安装")
                if (installHint) {
                    Text(
                        text = "请先授权安装未知应用，授权后回到这里再点安装",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Actions(
                    left = "关闭" to onDismiss,
                    right = "安装" to {
                        if (ApkInstaller.install(context, p.apk)) {
                            installHint = false
                        } else {
                            installHint = true
                        }
                    },
                )
            }

            is Phase.Error -> {
                Text(text = p.msg)
                Actions(
                    left = "关闭" to onDismiss,
                    right = "重试" to { startDownload() },
                )
                TextButton(
                    text = "用浏览器下载",
                    onClick = { ApkInstaller.openInBrowser(context, release.apkUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Actions(
    left: Pair<String, () -> Unit>,
    right: Pair<String, () -> Unit>,
    rightEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(text = left.first, onClick = left.second, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextButton(
            text = right.first,
            onClick = right.second,
            enabled = rightEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}
