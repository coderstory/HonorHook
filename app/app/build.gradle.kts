// HonorHook app 模块（照 HyperChanger app/build.gradle.kts + AI-temlate packaging 硬手法）
plugins {
    alias(libs.plugins.android.application)
    // AGP 9.x 内置 Kotlin 支持，Kotlin 编译只需 compose 编译器插件
    alias(libs.plugins.kotlin.compose)
}

// java.time 显式 import：KTS 脚本不解析裸 java.* 引用（Unresolved reference 'time'）
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 构建时刻（首页“构建时间”详情行数据源；格式对齐 demo）
val buildTime = LocalDateTime.now()
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

android {
    namespace = "top.honorhook"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.honorhook"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // buildConfigField 向 Home"/关于"页暴露构建时刻（buildConfig 开启见下方 buildFeatures）
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildTypes {
        release {
            // 混淆 + 资源收缩 + debug 签名直出（照 AI-lsposed-temlate 范本：本机无正式 keystore，
            // debug 签名包可安装覆盖升级）；proguard-rules.pro 已备 java_init.list 改写
            // （-adaptresourcefilecontents）+ XposedModule 入口 keep 规则。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // JVM target 17（android.kotlin DSL；照 LSPFRIDA 写法）
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true // 关于页 VERSION_NAME 与 BUILD_TIME 注入前提
    }

    packaging {
        resources {
            // AI-temlate 硬手法：显式合并 xposed 元数据 + 排除其余非代码资源——
            // 混淆后 java_init.list 存活的硬保证（比 proguard keep 更硬，书写顺序照 HC）
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    // Compose（AndroidX BOM）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3) // M3 dynamicLightColorScheme（Monet 取色）
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // miuix UI（Maven Central）
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    // miuix-blur（玻璃拟态悬浮底栏）：AAR minSdk33 由官方 tools:overrideLibrary 放行；
    // API<33 运行时自动降级纯色（照 LSPFRIDA build.gradle.kts:122-124）
    implementation(libs.miuix.blur)

    // libxposed：api 仅编译期（运行时由 LSPosed 框架提供）；service 为宿主 App 与框架通信
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // DexKit：混淆目标方法特征查找（双轨解析的兜底线；版本照 HC dexkit:2.2.0）
    implementation(libs.dexkit)
}