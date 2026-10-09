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
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // libxposed 离线兜底：限定 group 的 mavenLocal，本地 ~/.m2 有货时优先命中
        mavenLocal {
            content {
                includeGroup("io.github.libxposed")
            }
        }
    }
}

rootProject.name = "HonorHook"

include(":app")