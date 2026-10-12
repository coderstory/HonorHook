// 顶层插件声明：AGP 9.x 内置 Kotlin 支持；Compose 编译器插件与 Kotlin 同版本（照 HyperChanger）。
// 版本统一由 gradle/libs.versions.toml 提供，此处只引用别名，避免版本号写两处后漂移。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    kotlin("jvm")
}
dependencies {
    testImplementation(kotlin("test"))
}
kotlin {
    jvmToolchain(8)
}