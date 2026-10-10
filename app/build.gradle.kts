// 顶层插件声明：AGP 9.x 内置 Kotlin 支持；Compose 编译器插件与 Kotlin 2.3.21 对齐（照 HyperChanger）。
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
