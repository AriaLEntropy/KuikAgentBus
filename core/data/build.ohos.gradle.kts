plugins {
    kotlin("multiplatform")
}

kotlin {
    ohosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core:platform"))
                api(project(":core:log"))
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyOhosVersion()}")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-002")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.1-KBA-003")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
            }
        }
        val ohosArm64Main by getting {
            dependencies {
                implementation("net.shantu.kuiklysqlite:kuiklySqlite:1.1.0")
            }
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
