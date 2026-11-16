// 鸿蒙构建的根脚本，只由 settings.ohos.gradle.kts 选用。
// 不加载 Android Gradle Plugin：鸿蒙侧只需要 Kotlin/Native 的 ohosArm64 目标。
plugins {
    kotlin("multiplatform").version("2.0.21-KBA-010").apply(false)
    id("com.google.devtools.ksp").version("2.0.21-1.0.28").apply(false)
}
