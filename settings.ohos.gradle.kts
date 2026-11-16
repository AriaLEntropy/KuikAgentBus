// HarmonyOS 专用构建入口：与主构建完全隔离。
//
// 鸿蒙版 Kotlin/Native 只能用 KuiklyBase 定制的 Kotlin 工具链（KBA），
// 官方 Kotlin 2.1.21 编不出 ohosArm64；所以 Android/iOS/JS 继续走
// settings.gradle.kts，鸿蒙单独走这一份，避免把整仓 Kotlin 降级。
//
// 用法：./gradlew -c settings.ohos.gradle.kts :shared:linkDebugSharedOhosArm64

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
        maven {
            url = uri("https://hd-l.github.io/KuiklyUISqlite")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
        maven {
            url = uri("https://hd-l.github.io/KuiklyUISqlite")
        }
    }
}

rootProject.name = "KuikAgentBus"

// 每个模块换成 build.ohos.gradle.kts，脚本里只声明 ohosArm64 目标。
val ohosBuild = "build.ohos.gradle.kts"
rootProject.buildFileName = ohosBuild

include(":core:platform")
project(":core:platform").buildFileName = ohosBuild

include(":core:log")
project(":core:log").buildFileName = ohosBuild

include(":core:data")
project(":core:data").buildFileName = ohosBuild

include(":shared")
project(":shared").buildFileName = ohosBuild
