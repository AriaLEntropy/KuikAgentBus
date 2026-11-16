import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
}

kotlin {
    ohosArm64 {
        binaries.sharedLib("shared") {
            freeCompilerArgs += "-Xadd-light-debug=enable"
            linkerOpts += "--build-id=sha1"
            if (buildType == NativeBuildType.RELEASE) {
                val clangOptFlags = "-O3 -ffunction-sections -fdata-sections"
                freeCompilerArgs +=
                    "-Xoverride-konan-properties=clangOptFlags.ohos_arm64=$clangOptFlags;clangDebugFlags.ohos_arm64=$clangOptFlags"
                linkerOpts += "--pack-dyn-relocs=relr"
                linkerOpts += "--gc-sections"
                linkerOpts += "--hash-style=gnu"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core:platform"))
                api(project(":core:log"))
                api(project(":core:data"))
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuiklybase:KuiklyMarkdown:1.0.6-2.0.21-ohos")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-002")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.1-KBA-003")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
            }
        }
    }
}

dependencies {
    add("kspOhosArm64", "com.tencent.kuikly-open:core-ksp:${Version.getKuiklyOhosVersion()}")
}

ksp {
    arg("catchException", "false")
}

// 编译完把产物同步进 DevEco 工程，避免手工拷贝漏文件。
tasks.register("syncOhosShell") {
    group = "build"
    description = "把 libshared.so、头文件和 assets 同步到 ohosApp"
    dependsOn("linkSharedDebugSharedOhosArm64")
    doLast {
        val bin = layout.buildDirectory.dir("bin/ohosArm64/sharedDebugShared").get().asFile
        copy {
            from(bin.resolve("libshared.so"))
            into(rootProject.file("ohosApp/entry/libs/arm64-v8a"))
        }
        copy {
            from(bin.resolve("libshared_api.h"))
            into(rootProject.file("ohosApp/entry/src/main/cpp/include"))
        }
        copy {
            from(project.file("src/commonMain/assets"))
            into(rootProject.file("ohosApp/entry/src/main/resources/resfile/common"))
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
            "-opt-in=kotlin.experimental.ExperimentalNativeApi",
        )
    }
}
