// HonorHook app 模块 —— 纯 hook 模块（无 Activity / 无图标 / 无资源 / 无 UI 依赖）
plugins {
    alias(libs.plugins.android.application)
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
        }
    }

    packaging {
        resources {
            // 硬保证：显式合并 xposed 元数据 + 排除其余非代码资源，确保混淆后 java_init.list 存活
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    // libxposed：api 仅编译期（运行时由框架提供）。service 仅供宿主 App 与框架通信，纯 hook 模块不需要。
    compileOnly(libs.libxposed.api)

    // DexKit：混淆目标方法特征查找（双轨解析的兜底线）
    implementation(libs.dexkit)
}
