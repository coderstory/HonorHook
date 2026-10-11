// HonorHook app 模块 —— hook + Miuix(Compose) UI
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.honorhook"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.honorhook"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // CI 构建 run_number（ci.yml 传 -PbuildNum）；对比 GitHub release tag v<version>.r<build> 用
        buildConfigField("int", "BUILD_NUM", providers.gradleProperty("buildNum").orElse("0").get())

        // 只保留真机 arm64：x86/x86_64（模拟器）与 armeabi-v7a（32 位）的 libdexkit.so 全部裁掉，
        // 项目只做真机直连验证（CLAUDE.md），无用架构 so 约 1.07MB。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // 混淆 + 资源收缩 + debug 签名直出（本机无正式 keystore）；
            // proguard-rules.pro 已备 java_init.list 改写 + XposedModule 入口 keep 规则。
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
        }
    }

    packaging {
        resources {
            // 硬保证：显式合并 xposed 元数据 + 排除其余非代码资源，确保混淆后 java_init.list 存活
            merges += "META-INF/xposed/*"
            // 放行 ServiceLoader 配置（coroutines 的 MainDispatcherFactory 等），否则运行期 Dispatchers.Main 可能失效
            merges += "META-INF/services/*"
            excludes += "**"
        }
    }
}

dependencies {
    // libxposed：api 仅编译期（运行时由框架提供）。
    compileOnly(libs.libxposed.api)
    // service：宿主 App 经它与 LSPosed 通信——远程 prefs 下发 + 真实激活状态（照 LSPFRIDA/HyperChanger）。
    implementation(libs.libxposed.service)

    // DexKit：混淆目标方法特征查找（双轨解析的兜底线）
    implementation(libs.dexkit)

    // ---- Miuix UI（KernelSU Manager 同源，UI 真基准）----
    // miuix-ui 提供 Scaffold/TopAppBar/Card/NavigationBar/FloatingBottomBar 依赖的 basic 组件
    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    // 设置页的 SwitchPreference / ArrowPreference / OverlayDropdownPreference
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons.android)
    // 液态玻璃：Backdrop/LayerBackdrop/blur/vibrancy/lens（FloatingBottomBar 的 lens 折射高光）
    implementation(libs.miuix.blur)
    // 动态取色：种子色 → M3 色板（KernelSU 主题页的关键色/配色风格/色规范）
    implementation(libs.material.kolor)

    // ---- 原生 Compose 组件（Miuix 未覆盖的部分：Material3 Icon/Text、状态卡色板）----
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    // Extended 图标：KernelSU 首页/设置页用的 Tag、Security、Smartphone、Fingerprint、
    // DeveloperBoard、Block 等全在 extended 里，core 只有几十个基础图标。
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core.ktx)

    // JVM 单元测试（表驱动决策的回归；这些对象无 Android 依赖，不需要 Robolectric）
    testImplementation(libs.junit)
    // UpdateChecker 网络测试用真实 JSON 解析器（Android 运行时自带 org.json，JVM stub 会抛 Stub!）
    testImplementation("org.json:json:20240303")
}
