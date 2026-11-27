package top.honorhook.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import top.honorhook.ui.AppSettings
import top.honorhook.ui.component.tech.TechIcons

/**
 * 图标风格切换点（2026-11-27 用户「图标能换一套？」）。
 *
 * 三套风格各自有自己的开关来源，调用点只写 `AppIcons.FolderOpen`：
 * - 插画背景 → 原神图标：**不经过这里**，调用点写的是 `genshinOr(AppIcons.X, GenshinIcons.Y)`，
 *   由 `genshinOr` 在插画主题下替换成原神件。
 * - 科技蓝主题 → 自绘线性集 `TechIcons`（Lucide 路径）：细线、开口轮廓，与深蓝玻璃 + 霓虹描边、
 *   卡片几何底纹是同一套语汇。没有独立开关，跟着主题走（2026-11-27 用户：「不要新搞一个开关」）。
 * - 其余 → 圆角（Material Rounded），即改造前的默认观感。
 *
 * 为什么是 `@Composable` getter 而不是普通 val：它要订阅 `AppSettings.techSkin`。
 * 普通 val 只在首次组合时取值，主题切换后那些没被重组的子树会留着旧图标。
 */
object AppIcons {
    val AcUnit: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("acUnit") else AppIconsRounded.acUnit
    val Add: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("add") else AppIconsRounded.add
    val Archive: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("archive") else AppIconsRounded.archive
    val ArrowDropDown: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("arrowDropDown") else AppIconsRounded.arrowDropDown
    val ArrowUpward: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("arrowUpward") else AppIconsRounded.arrowUpward
    val AutoAwesome: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("autoAwesome") else AppIconsRounded.autoAwesome
    val Backup: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("backup") else AppIconsRounded.backup
    val BlurOn: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("blurOn") else AppIconsRounded.blurOn
    val Bolt: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("bolt") else AppIconsRounded.bolt
    val CallMerge: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("callMerge") else AppIconsRounded.callMerge
    val CallToAction: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("callToAction") else AppIconsRounded.callToAction
    val Campaign: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("campaign") else AppIconsRounded.campaign
    val Check: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("check") else AppIconsRounded.check
    val CheckCircleOutline: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("checkCircleOutline") else AppIconsRounded.checkCircleOutline
    val ChevronRight: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("chevronRight") else AppIconsRounded.chevronRight
    val CleaningServices: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("cleaningServices") else AppIconsRounded.cleaningServices
    val ContentCopy: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("contentCopy") else AppIconsRounded.contentCopy
    val ContentPaste: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("contentPaste") else AppIconsRounded.contentPaste
    val Cottage: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("cottage") else AppIconsRounded.cottage
    val Delete: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("delete") else AppIconsRounded.delete
    val DeleteOutline: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("deleteOutline") else AppIconsRounded.deleteOutline
    val DeveloperBoard: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("developerBoard") else AppIconsRounded.developerBoard
    val Edit: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("edit") else AppIconsRounded.edit
    val ErrorOutline: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("errorOutline") else AppIconsRounded.errorOutline
    val Extension: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("extension") else AppIconsRounded.extension
    val FilterList: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("filterList") else AppIconsRounded.filterList
    val Fingerprint: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("fingerprint") else AppIconsRounded.fingerprint
    val Flare: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("flare") else AppIconsRounded.flare
    val Folder: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("folder") else AppIconsRounded.folder
    val FolderCopy: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("folderCopy") else AppIconsRounded.folderCopy
    val FolderOpen: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("folderOpen") else AppIconsRounded.folderOpen
    val Handyman: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("handyman") else AppIconsRounded.handyman
    val Image: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("image") else AppIconsRounded.image
    val Info: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("info") else AppIconsRounded.info
    val LocalFlorist: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("localFlorist") else AppIconsRounded.localFlorist
    val Memory: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("memory") else AppIconsRounded.memory
    val Palette: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("palette") else AppIconsRounded.palette
    val Password: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("password") else AppIconsRounded.password
    val PhotoLibrary: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("photoLibrary") else AppIconsRounded.photoLibrary
    val QrCodeScanner: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("qrCodeScanner") else AppIconsRounded.qrCodeScanner
    val Refresh: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("refresh") else AppIconsRounded.refresh
    val RestartAlt: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("restartAlt") else AppIconsRounded.restartAlt
    val Security: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("security") else AppIconsRounded.security
    val Settings: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("settings") else AppIconsRounded.settings
    val Shield: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("shield") else AppIconsRounded.shield
    val Smartphone: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("smartphone") else AppIconsRounded.smartphone
    val SystemUpdateAlt: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("systemUpdateAlt") else AppIconsRounded.systemUpdateAlt
    val Tag: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("tag") else AppIconsRounded.tag
    val Terminal: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("terminal") else AppIconsRounded.terminal
    val TravelExplore: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("travelExplore") else AppIconsRounded.travelExplore
    val Wallpaper: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("wallpaper") else AppIconsRounded.wallpaper
    val WaterDrop: ImageVector @Composable get() = if (AppSettings.techSkin) TechIcons.of("waterDrop") else AppIconsRounded.waterDrop
}
