// 顶层插件声明：AGP 9.x 内置 Kotlin 支持；Compose 编译器插件与 Kotlin 同版本（照 HyperChanger）。
// 版本统一由 gradle/libs.versions.toml 提供，此处只引用别名（含 kotlin-jvm）——
// 此前 kotlin("jvm") 无版本号落回 settings.gradle.kts 嵌的 2.4.21，与 toml 的 2.3.21 双源漂移（已去重）。
plugins {
    alias(libs.plugins.android.application) apply false
    // :xhs-dn-core（上游下载器 AGPL 模块）用同一 AGP 产物 ⇒ 必须也在根声明 apply false，
    // 否则子模块再带版本请求会被判「插件已在 classpath 且版本未知」（Gradle 9 报错）。
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm)
}
dependencies {
    testImplementation(kotlin("test"))
}
kotlin {
    jvmToolchain(8)
}