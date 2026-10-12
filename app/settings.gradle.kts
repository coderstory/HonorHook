// HonorHook —— 荣耀系统增强 LSPosed 模块
// 仓库选择：CI 直连官方源，本地走阿里云四仓镜像 + mavenLocal 限定兜底 libxposed
// CI（GitHub Actions）直连官方源——阿里云镜像在 runner 上又慢又易 502（r51 lint 工具链全挂实证）；
// 本地公司网不可靠，才走阿里云镜像。

pluginManagement {
    repositories {
        if (System.getenv("CI") == null) {
            // 公司网络下 services.gradle.org / plugins.gradle.org 不可靠，全部走阿里云镜像
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        } else {
            google()
            gradlePluginPortal()
            mavenCentral()
        }
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
        if (System.getenv("CI") == null) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        } else {
            google()
            mavenCentral()
        }
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