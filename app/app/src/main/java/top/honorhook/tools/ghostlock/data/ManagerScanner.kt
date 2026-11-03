package top.honorhook.tools.ghostlock.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipFile

/** Finds root-manager candidates and orders them for ksud resolution. */
object ManagerScanner {
    const val PREF_KEY = "manager_package"

    /**
     * Known KernelSU-family manager package ids. Priority order: plain
     * KernelSU first (proven to ship libksud.so), then named forks. Suffixes
     * cover `.pr`/`.debug`/`.next` repacks that still call themselves SukiSU.
     *
     * Unknown variants (FinaSU, spoof scripts) are covered by the generic
     * wildcard search in [resolveKsud].
     */
    val BUILTIN = listOf(
        "me.weishu.kernelsu",
        "me.weishu.kernelsu.pr",
        "com.sukisu.ultra",
        "com.sukisu.ultra.pr",
        "com.sukisu.next",
        "com.sukisu.new",
        "com.sukisu.app",
        "com.sukisu.sukisu",
        "com.resukisu.resukisu",
        "org.bakasu.bakasu",
        "com.rifsxd.ksunext",
        "com.kowx712.supermanager",
    )

    /** Substrings that mark a fork even when its exact package is unknown. */
    private val FORK_HINTS = listOf("suki", "resuki", "baka", "finasu", "kernelsu", "ksunext", "supermanager")

    /**
     * SukiSU Ultra >= 4.3.0 drops the stable `libksud.so` name. Its CI packs
     * ksud as `lib<10 lowercase letters>.so` (see build-manager.yml
     * `KSUD_LIB="lib$(rand_alpha 10).so"`), so the only stable traits left are
     * the package shape and the size: ksud embeds the KMI modules and is
     * multi-megabyte, while the JNI bridges are a few KB.
     */
    private val RANDOM_KSUD_LIB = Regex("^lib[a-z]{10}\\.so$")
    private const val KSUD_MIN_BYTES = 1_000_000L
    private val LEGACY_KSUD_NAMES = listOf("libksud.so", "libksud.ultra.so", "libkernelsu.so", "libksu.so")

    data class Candidate(
        val packageName: String,
        val label: String,
        val hasKsud: Boolean,
        val builtin: Boolean,
    )

    /** Manager package plus the ksud binary it ships. */
    data class ResolvedKsud(val packageName: String, val file: File)

    /** Installed KernelSU-family apps (builtin + wildcard forks). */
    @Suppress("DEPRECATION")
    fun scan(pm: PackageManager, selfPackage: String): List<Candidate> =
        pm.getInstalledPackages(0)
            .asSequence()
            .mapNotNull { pi -> pi.applicationInfo?.let { pi to it } }
            .filter { (pi, _) -> pi.packageName != selfPackage }
            .filter { (_, ai) -> (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { (pi, ai) ->
                val nativeKsud = runCatching {
                    ksudInDir(File(ai.nativeLibraryDir)) != null
                }.getOrDefault(false)
                val builtin = pi.packageName in BUILTIN
                val fuzzyFork = FORK_HINTS.any { pi.packageName.contains(it, ignoreCase = true) }
                Candidate(
                    packageName = pi.packageName,
                    label = ai.loadLabel(pm).toString(),
                    hasKsud = nativeKsud || builtin || fuzzyFork,
                    builtin = builtin,
                )
            }
            .filter { it.hasKsud }
            .sortedWith(sortComparator())
            .toList()

    /**
     * Resolve ksud for the chosen manager. Walks: (1) chosen, (2) the rest of
     * the built-ins in priority order, (3) any other installed app whose
     * package name hints at a fork. For each pkg we try the nativeLibraryDir
     * probe first; if that fails (extractNativeLibs=false) we stage from the
     * APK by pattern matching any libksud*.so entry under lib/<abi>/.
     */
    fun resolveKsud(
        pm: PackageManager,
        chosen: String?,
        stagingDir: File? = null,
    ): ResolvedKsud? {
        val tried = mutableSetOf<String>()
        for (pkg in resolvePackages(chosen)) {
            tried += pkg
            val r = tryPkg(pm, pkg, stagingDir)
            if (r != null) return r
        }
        for (candidate in scan(pm, "")) {
            val pkg = candidate.packageName
            if (pkg in tried) continue
            tried += pkg
            val r = tryPkg(pm, pkg, stagingDir)
            if (r != null) return r
        }
        // Spoofed SukiSU builds rename the package (`aaaaaa.bbbbbb.cccccc`),
        // so name hints miss. Fall back to any installed app whose native
        // library dir carries a ksud-sized daemon.
        for (app in allApps(pm)) {
            val pkg = app.packageName
            if (pkg in tried) continue
            if (runCatching { ksudInDir(File(app.nativeLibraryDir)) }.getOrNull() == null) continue
            tried += pkg
            val r = tryPkg(pm, pkg, stagingDir)
            if (r != null) return r
        }
        return null
    }

    private fun allApps(pm: PackageManager): List<ApplicationInfo> =
        runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0).mapNotNull { it.applicationInfo }
        }.getOrDefault(emptyList())

    /**
     * Collect every .so entry inside the chosen manager's APK (for diagnostics).
     * Returns a short printable summary like "lib/arm64-v8a/libksud.so,...".
     * Returns null if the APK cannot be read.
     */
    fun listApkLibraries(pm: PackageManager, pkg: String): List<String>? {
        val apk = runCatching { File(pm.getApplicationInfo(pkg, 0).sourceDir) }
            .getOrNull()?.takeIf { it.isFile } ?: return null
        return runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".so") && it.name.startsWith("lib/") }
                    .map { it.name }
                    .toList()
            }
        }.getOrNull()
    }

    /**
     * A ksud daemon inside a manager's nativeLibraryDir: the historical fixed
     * names first, then the randomized `lib<10 letters>.so` that SukiSU Ultra
     * 4.3.0 started shipping. Both must clear [KSUD_MIN_BYTES].
     */
    private fun ksudInDir(dir: File?): File? {
        if (dir == null) return null
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val candidates = files.asSequence().filter { it.isFile && it.length() >= KSUD_MIN_BYTES }
        return candidates.firstOrNull { LEGACY_KSUD_NAMES.contains(it.name) }
            ?: candidates.sortedByDescending { it.length() }.firstOrNull { RANDOM_KSUD_LIB.matches(it.name) }
    }

    private fun tryPkg(pm: PackageManager, pkg: String, stagingDir: File?): ResolvedKsud? {
        // 1. Fast native-library-dir probe.
        ksudOf(pm, pkg)?.let { return ResolvedKsud(pkg, it) }
        // 2. Stage from APK.
        if (stagingDir != null) {
            extractKsud(pm, pkg, stagingDir)?.let { return ResolvedKsud(pkg, it) }
        }
        return null
    }

    private fun ksudOf(pm: PackageManager, pkg: String): File? =
        runCatching {
            ksudInDir(File(pm.getApplicationInfo(pkg, 0).nativeLibraryDir))
        }.getOrNull()

    /**
     * Extract the real ksud binary out of the manager APK. Handles renamed
     * libs (SukiSU has shipped `libksud.ultra.so` on some builds) by matching
     * any `lib/arm64-v8a/libksud*.so` / `lib/arm64-v8a/libkernelsu*.so` entry,
     * then renames the staged copy to plain `libksud.so` so the native payload
     * can exec it without knowing the fork's rename.
     */
    @Suppress("DEPRECATION")
    fun extractKsud(pm: PackageManager, pkg: String, stagingDir: File): File? {
        val apk = runCatching { File(pm.getApplicationInfo(pkg, 0).sourceDir) }
            .getOrNull()?.takeIf { it.isFile } ?: return null
        val dirEntryPattern = Regex("^lib/arm64-v8a/(libksud[^/]*\\.so|libkernelsu[^/]*\\.so|lib[a-z]{10}\\.so)$")
        return runCatching {
            ZipFile(apk).use { zip ->
                val libs = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                    .toList()
                // Named ksud first; otherwise the multi-MB daemon is still the
                // largest .so in the archive (SukiSU 4.3.0 randomized its name).
                val target = libs.filter { dirEntryPattern.matches(it.name) }.maxByOrNull { it.size }
                    ?: libs.filter { it.size >= KSUD_MIN_BYTES }.maxByOrNull { it.size }
                    ?: return null
                val dest = File(File(stagingDir, pkg), "libksud.so")
                dest.parentFile?.mkdirs()
                zip.getInputStream(target).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setReadable(true, false)
                dest.setExecutable(true, false)
                dest
            }
        }.getOrNull()
    }

    /** Pure sort, extracted so it is testable without a PackageManager. */
    fun sortComparator() = compareByDescending<Candidate> { it.builtin && it.hasKsud }
        .thenByDescending { it.hasKsud }
        .thenBy { it.label.lowercase() }

    /** Chosen package first, then the rest of the built-ins in priority order. */
    fun resolvePackages(chosen: String?): List<String> =
        if (chosen.isNullOrBlank()) BUILTIN
        else listOf(chosen) + BUILTIN.filter { it != chosen }
}
