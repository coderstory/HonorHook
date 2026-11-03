package top.honorhook.tools.ghostlock

/** 原 GhostLock 工程由 generateBuildInfo 任务生成；本工程只需要构建时间标签。 */
object BuildInfo {
    val BUILD_TIME_LABEL: String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
}
