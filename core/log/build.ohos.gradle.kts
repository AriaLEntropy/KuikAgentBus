plugins {
    kotlin("multiplatform")
}

kotlin {
    ohosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core:platform"))
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyOhosVersion()}")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-002")
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
