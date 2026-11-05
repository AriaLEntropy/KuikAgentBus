package com.kuikagentbus.protocol

/**
 * 产物字节的本机缓存策略（FR-9.2）
 *
 * 这里只做纯计算：id 校验、文件名映射、超量时该淘汰谁。
 * 真正的读写在 androidApp 的 relay 层，规则放在 common 侧才能不连电脑就跑单测。
 * 这几个函数是公开的：原生层直接调同一套规则，避免两端各写一份 id 校验和命名。
 */

/** 协议规定的产物 id：22 位 base64url。拼任何路径之前先过这条，不合规直接丢，不给路径穿越留口子 */
private val BUS_ARTIFACT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{22}$")

/**
 * 缓存总量上限
 *
 * 需求只写「按 id 缓存，限制总量」，没给具体数，200 MiB 是我们自己定的；
 * 电脑侧产物本身有 10 MiB 单件上限与 7 天保留，这里只管本机占多少
 */
const val BUS_ARTIFACT_CACHE_LIMIT_BYTES = 200L * 1024 * 1024

/** 一条缓存记录：文件名、字节数、最后一次被用到的时间 */
data class BusArtifactEntry(
    val fileName: String,
    val bytes: Long,
    val lastUsedAt: Long,
)

/** id 是否可以直接用来拼路径 */
fun busArtifactIdUsable(artifactId: String): Boolean =
    BUS_ARTIFACT_ID_PATTERN.matches(artifactId)

/**
 * 缓存文件名：`<artifactId>.<后缀>`
 *
 * 后缀只为人在文件管理器里看得顺眼，渲染只看字节；认不出的类型统一 .bin。
 * 同一个 id 永远映射到同一个名字，这样重复拉取会覆盖而不是攒一堆副本
 */
fun busArtifactFileName(artifactId: String, contentType: String): String {
    val extension = when (contentType.substringBefore(';').trim().lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/ogg" -> "ogg"
        "audio/wav" -> "wav"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        "application/json" -> "json"
        else -> "bin"
    }
    return "$artifactId.$extension"
}

/**
 * 超量时要删掉哪些
 *
 * 按「最后一次用到」从旧到新淘汰，删到限内为止；`keep` 那条是刚写入的，不参与淘汰。
 * 未超量返回空列表，调用方据此决定不删任何东西
 */
fun busArtifactEviction(
    entries: List<BusArtifactEntry>,
    keep: String,
    limitBytes: Long = BUS_ARTIFACT_CACHE_LIMIT_BYTES,
): List<BusArtifactEntry> {
    var total = entries.sumOf { it.bytes }
    if (total <= limitBytes) return emptyList()
    val victims = mutableListOf<BusArtifactEntry>()
    // 只从"不是刚写入的那条"里挑，最久未用的先走
    for (entry in entries.filter { it.fileName != keep }.sortedBy { it.lastUsedAt }) {
        if (total <= limitBytes) break
        victims += entry
        total -= entry.bytes
    }
    return victims
}
