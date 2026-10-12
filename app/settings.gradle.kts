// HonorHook —— 荣耀系统增强 LSPosed 模块
// 仓库镜像：阿里云四仓（照 AI-temlate settings.gradle.kts 写法）+ mavenLocal 限定兜底 libxposed

pluginManagement {
    repositories {
        // 公司网络下 services.gradle.org / plugins.gradle.org 不可靠，全部走阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
    plugins {
        kotlin("jvm") version "2.4.21"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // JetBrains Compose Multiplatform 的 material3/material-icons 只在 Maven Central 有
        // （阿里云镜像未同步），必须保留直连；国内网络不通时靠前面的阿里云仓兜底。
        maven { url = uri("https://repo1.maven.org/maven2") }
        // libxposed 离线兜底：限定 group 的 mavenLocal，本地 ~/.m2 有货时优先命中
        mavenLocal {
            content {
                includeGroup("io.github.libxposed")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "HonorHook"

include(":app")