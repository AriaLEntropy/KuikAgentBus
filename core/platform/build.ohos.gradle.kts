plugins {
    kotlin("multiplatform")
}

kotlin {
    ohosArm64()

    sourceSets {
        val commonMain by getting
        val ohosArm64Main by getting
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
