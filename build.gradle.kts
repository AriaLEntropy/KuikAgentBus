plugins {
    //trick: for the same plugin versions in all sub-modules
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    kotlin("android").version("2.1.21").apply(false)
    kotlin("multiplatform").version("2.1.21").apply(false)
    id("com.google.devtools.ksp").version("2.1.21-2.0.1").apply(false)

}

buildscript {
    dependencies {
        classpath(BuildPlugin.kuikly)
    }
}

// ---------------------------------------------------------------------------
// 架构分层校验（fitness function）
//
// 分层（按包）：
//   UI 页面层   <root>.ui.<feature>     home / chat / connection / ...
//   UI 组件层   <root>.ui.rendering
//   数据/领域层 <root>.<domain>         bus / storage / message / ...
//
// 规则：
//   R1 数据/领域层不得依赖 UI 层（<root>.ui.*）。
//   R2 UI 组件层不得依赖页面层，否则通用组件孵化不出来。
//
// 依赖方向应为：数据/领域 ← 组件 ← 页面。跑法：./gradlew checkLayering
//
// 三条防自欺设计（上一版三条都缺）：
//   1. 页面包清单不再手写，直接从源码 package 行推导。旧版手写了 13 个包，
//      其中 10 个在本仓库根本不存在，R2 等于静默不判。
//   2. 通过时打印扫描文件数 / import 条数 / 页面包数，ASCII 一行，管道里也能 grep。
//   3. 每次运行往 build/ 植一个假违规（不在 src 下、不参与编译），确认门真抓得到再删掉。
// ---------------------------------------------------------------------------
val layeringRoot = "com.example.dsh"
val componentPkg = "$layeringRoot.ui.rendering"

fun layeringScan(files: Set<java.io.File>): Triple<List<String>, Int, Set<String>> {
    val violations = mutableListOf<String>()
    val pagePkgs = sortedSetOf<String>()
    val pkgOf = mutableMapOf<java.io.File, String>()
    val importsOf = mutableMapOf<java.io.File, List<String>>()
    var importCount = 0

    files.sortedBy { it.path }.forEach { file ->
        val lines = file.readLines()
        val pkg = lines.firstNotNullOfOrNull { Regex("^package\\s+([\\w.]+)").find(it)?.groupValues?.get(1) }
            ?: return@forEach
        pkgOf[file] = pkg
        val imps = lines.mapNotNull { Regex("^import\\s+([\\w.]+)").find(it)?.groupValues?.get(1) }
        importsOf[file] = imps
        importCount += imps.size
        if (pkg.startsWith("$layeringRoot.ui.") && pkg != componentPkg) pagePkgs.add(pkg)
    }

    val prefix = projectDir.path.replace('\\', '/') + "/"
    files.sortedBy { it.path }.forEach { file ->
        val pkg = pkgOf[file] ?: return@forEach
        val isUi = pkg.startsWith("$layeringRoot.ui.")
        val isComponent = pkg == componentPkg
        val rel = file.path.replace('\\', '/').removePrefix(prefix)
        importsOf[file]?.forEachIndexed { index, imp ->
            if (!imp.startsWith("$layeringRoot.")) return@forEachIndexed
            if (!isUi && imp.startsWith("$layeringRoot.ui.")) {
                violations += "R1  $rel:${index + 1}  数据/领域层 -> UI：$imp"
            }
            if (isComponent && pagePkgs.any { imp == it || imp.startsWith("$it.") }) {
                violations += "R2  $rel:${index + 1}  组件层 -> 页面层：$imp"
            }
        }
    }
    return Triple(violations, importCount, pagePkgs)
}

tasks.register("checkLayering") {
    group = "verification"
    description = "校验包分层：数据/领域层不得依赖 UI，组件层不得依赖页面层；并自证门有效。"

    val sources = fileTree(".") {
        // 模块可以是扁平（shared/）或嵌套（core/data/），只写 */src/** 会静默漏掉嵌套的那批
        include("*/src/**/*.kt", "*/*/src/**/*.kt")
        exclude("**/build/**")
        exclude("**/.gradle/**")
        exclude("**/oh_modules/**")
        exclude("**/*Test*/**")
    }
    inputs.files(sources).withPropertyName("sources")
    outputs.upToDateWhen { false }

    doLast {
        val real = sources.files
        val (violations, importCount, pagePkgs) = layeringScan(real)

        // 盲区检测：凡是带 src 的模块目录，一个 .kt 都没被扫到就说明门瞎了（上一版就是
        // 模块改成 core/data 嵌套后，*/src/** 静默少扫 69 个文件还照打 OK）
        val scannedDirs = real.map { it.path.replace('\\', '/') }.toSet()
        val blind = mutableListOf<String>()
        projectDir.walkTopDown().maxDepth(3).filter { it.isDirectory && it.name == "src" }.forEach { srcDir ->
            val hasKt = srcDir.walkTopDown().any { it.isFile && it.extension == "kt" }
            val covered = scannedDirs.any { it.startsWith(srcDir.path.replace('\\', '/')) }
            if (hasKt && !covered) blind += srcDir.path.replace('\\', '/')
        }
        if (blind.isNotEmpty()) {
            throw GradleException(
                "checkLayering 有盲区：这些源码目录里有 .kt 但一个都没被扫到：\n  " +
                    blind.joinToString("\n  ") + "\n多半是 include 模式没覆盖新的模块层级。"
            )
        }

        logger.lifecycle(
            "checkLayering: scanned=${real.size} imports=$importCount " +
                "pagePackages=${pagePkgs.size} violations=${violations.size}"
        )
        logger.lifecycle("推导出的页面包：" + pagePkgs.joinToString())

        // 门不能先瞎掉：一个页面包都没推出来，说明包根或目录结构变了，规则已失效
        if (pagePkgs.isEmpty()) {
            throw GradleException(
                "checkLayering 失效：源码里推不出任何 $layeringRoot.ui.* 页面包。" +
                    "要么包根改名了（同步改 build.gradle.kts 里的 layeringRoot），要么这条规则已无意义。"
            )
        }

        // 红测：植一个假违规，确认门真的抓得到，否则上面那句 OK 不可信
        val fakeDir = layout.buildDirectory.dir("layering-selftest").get().asFile
        fakeDir.mkdirs()
        val fake = java.io.File(fakeDir, "LayeringSelfTest.kt")
        val caught: Int
        try {
            fake.writeText(
                """
                package $layeringRoot.storage

                import $layeringRoot.ui.home.BusHomeModel

                internal object LayeringSelfTest
                """.trimIndent()
            )
            val (fakeViolations, _, _) = layeringScan(real + fake)
            caught = fakeViolations.size - violations.size
            if (caught == 0) {
                throw GradleException(
                    "checkLayering 红测失败：植入的假违规没被抓到，先修门再谈 OK。"
                )
            }
        } finally {
            fake.delete()
        }
        logger.lifecycle("红测：植入 1 处假违规，被抓到 $caught 处（门有效）")

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("架构分层校验失败（${violations.size} 处）：")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("依赖方向应为：数据/领域 ← 组件 ← 页面。")
                    appendLine("修复方式是把被依赖的声明下沉到正确的层，而不是放宽规则。")
                },
            )
        }
        logger.lifecycle("checkLayering: OK（无分层违规）")
    }
}
