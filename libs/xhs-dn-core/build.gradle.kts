// :xhs-dn-core —— 上游 XHS_Downloader_Android（AGPL-3.0）下载器管线的**外部依赖模块**。
//
// 本仓不存上游任何源码：源码在 submodule（reference/xhs/XHS_Downloader_Android，pin 见 .gitmodules），
// 构建时按 **7 个文件白名单**同步到 build/generated 后参与编译。
//
// 为什么不整目录当源集：上游 com/neoruaa/xhsdn 共 112 个 .kt（15 个含 Compose/Activity），
// 全量编译会把 UI 依赖树拖进来必炸；白名单同时是「我们到底用了上游哪些文件」的显式审计清单。
// 白名单来源 = 2026-10-20 逐字比对通过的复制件清单（7/7 一致，零改动）。
// Ruling：白名单用逐文件路径而非目录通配——data/storage(4 取 2)、domain/download(10 取 1) 目录级不精确。
//
// 许可：本模块编译进 APK 的是 AGPL-3.0 代码 ⇒ HonorHook 整体按 AGPLv3 分发（见根 LICENSE / NOTICE）。

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

/** 上游白名单：相对 reference/xhs/XHS_Downloader_Android/app/src/main/java */
val upstreamWhitelist = listOf(
    "com/neoruaa/xhsdn/core/model/ResolvedNote.kt",
    "com/neoruaa/xhsdn/data/storage/StorageDestination.kt",
    "com/neoruaa/xhsdn/data/storage/StoredMediaRef.kt",
    "com/neoruaa/xhsdn/data/xhs/XhsContentRepository.kt",
    "com/neoruaa/xhsdn/data/xhs/XhsNoteParser.kt",
    "com/neoruaa/xhsdn/data/xhs/XhsUrlParser.kt",
    "com/neoruaa/xhsdn/domain/download/DownloadContracts.kt",
)

/** 上游源码根（submodule 只读，不改） */
val upstreamRoot = rootProject.file("../reference/xhs/XHS_Downloader_Android/app/src/main/java")

/** 同步白名单文件到 build/generated（构建产物，不入库） */
val generatedDir = layout.buildDirectory.dir("generated/xhsdn/kotlin")
val syncUpstream = tasks.register<Sync>("syncXhsDnUpstream") {
    description = "从上游 submodule 同步白名单文件（:xhs-dn-core 的唯一源码供给）"
    from(upstreamRoot) { include(upstreamWhitelist) }
    into(generatedDir)
    // Sync 对缺失文件是静默跳过 ⇒ 显式校验：submodule 未初始化 / 上游结构漂移时立刻炸，
    // 不静默少编译（少编译 = 挂载点静默失效，只表现为 M 变小）。
    doLast {
        val missing = upstreamWhitelist.filterNot { upstreamRoot.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "上游白名单文件缺失（submodule 未初始化，或上游结构已变）：$missing\n" +
                    "修复：git submodule update --init --recursive"
            )
        }
    }
}

android {
    namespace = "com.neoruaa.xhsdn"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("17"))
        }
    }
}

// AGP 9 源集接线 —— 两代旧 API 都被判死，只能走 Variant API：
//   ① 旧 `android.sourceSets`（com.android.build.gradle.api.*）：内部实现 DefaultAndroidLibrarySourceSet
//      不再实现旧接口 ⇒ 取 source set 直接 ClassCastException；
//   ② 新旧 SourceSet API 都不接受 Provider（"You cannot add Provider instances to the Android
//      SourceSet API"），AGP 明确指引改用 Variant API 的 Sources（addStaticSourceDirectory /
//      addGeneratedSourceDirectory）。
// 这里用 Sources.kotlin.addStaticSourceDirectory；任务依赖由下面 preBuild 显式带（静态目录语义，
// 不会自动带依赖）。目录在配置期先建出来，避免 AGP 校验时目录不存在。
generatedDir.get().asFile.mkdirs()
androidComponents {
    onVariants { variant ->
        // kotlin 源集为可空 ⇒ 显式判空：缺了就当场炸，不静默少编译（少编译 = 挂载点静默失效）
        val kotlinDirs = variant.sources.kotlin
            ?: error("变体 ${variant.name} 没有 Kotlin 源集 —— 上游白名单文件无法编译")
        kotlinDirs.addStaticSourceDirectory(generatedDir.get().asFile.absolutePath)
    }
}

tasks.named("preBuild") { dependsOn(syncUpstream) }

dependencies {
    // 三条全部 compileOnly：
    // - okhttp 5.5.0：XhsContentRepository.kt 的 OkHttpXhsPageSource 引用；版本必须对齐上游
    //   （上游代码假定 okhttp5 的 Response.body 非空，4.12 编译不过；上游文件禁改）。
    //   运行期不加载该类（R8 判其不可达整类删除 ⇒ APK 内无 okhttp 引用，proguard 无需 -dontwarn）。
    // - kotlinx-serialization-core：ResolvedNote/StoredMediaRef 的 @Serializable 注解（仅注解）。
    // - kotlinx-coroutines：挂起函数与 Dispatchers。
    // compileOnly 不传递给消费方 ⇒ app 模块必须自己声明同样三条（见 app/app/build.gradle.kts）。
    compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
    compileOnly(libs.kotlinx.coroutines.core)
}
