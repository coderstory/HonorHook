// 顶层插件声明（照 HyperChanger 根 build.gradle.kts）
// AGP 9.x 内置 Kotlin 支持：无需再应用 org.jetbrains.kotlin.android
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}