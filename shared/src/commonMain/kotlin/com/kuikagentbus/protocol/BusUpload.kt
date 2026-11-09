package com.kuikagentbus.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 发图的上传规则（FR-9.1）
 *
 * id 的形状、允许的类型和大小上限都放在这里，原生层复用同一套，
 * 免得两端各写一遍再靠人对齐
 */

/** 协议规定附件 id 是 16 随机字节的 base64url，去掉填充正好 22 位 */
const val BUS_UPLOAD_ID_BYTES = 16
const val BUS_UPLOAD_ID_LENGTH = 22

/** 阶段一走本机隧道，请求体上限 2 MiB；宿主的同名常量是 MAX_UPLOAD_BYTES */
const val BUS_UPLOAD_MAX_BYTES = 2L * 1024 * 1024

/** 一条消息最多几张图，跟宿主的 MAX_ATTACHMENT_IMAGES 一致 */
const val BUS_UPLOAD_MAX_IMAGES = 4

/** 宿主 `ALLOWED_UPLOAD_TYPES` 的四项，多一个都不传 */
val BUS_UPLOAD_CONTENT_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** 宿主 `ALLOWED_UPLOAD_FILE_TYPES` 的文档名单；普通文件附件按它提前拦 */
val BUS_UPLOAD_FILE_TYPES = setOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain",
    "text/markdown",
    "text/csv",
    "text/tab-separated-values",
    "application/json",
    "application/zip",
)

/** 图片类型不在那四项里就别发，415 留给实现真的对不上时 */
fun busUploadContentTypeAllowed(contentType: String): Boolean = contentType in BUS_UPLOAD_CONTENT_TYPES

/** 普通文件类型不在文档名单里就提前拦下；文件还必须带原文件名 */
fun busUploadFileTypeAllowed(contentType: String): Boolean = contentType in BUS_UPLOAD_FILE_TYPES

/** 上限含 2 MiB 整，超一个字节就会被电脑回 413 */
fun busUploadSizeAllowed(bytes: Long): Boolean = bytes in 0..BUS_UPLOAD_MAX_BYTES

/** 超过四张在手机上就拦下，别等电脑回「一次最多只能发 4 张图片」 */
fun busUploadCountAllowed(count: Int): Boolean = count in 0..BUS_UPLOAD_MAX_IMAGES

/** 附件描述：随命令带的 attachments 数组就是这一串字段 */
data class BusUploadAttachment(
    val artifactId: String,
    val contentType: String,
    val size: Long,
    val width: Int,
    val height: Int,
)

/** attachments 的写法：五个字段一个不少，顺序照宿主样本 command-follow-up-with-attachments.json */
internal fun busUploadAttachmentsJson(items: List<BusUploadAttachment>): JsonArray = JsonArray(
    items.map { item ->
        buildJsonObject {
            put("artifactId", JsonPrimitive(item.artifactId))
            put("contentType", JsonPrimitive(item.contentType))
            put("size", JsonPrimitive(item.size))
            put("width", JsonPrimitive(item.width))
            put("height", JsonPrimitive(item.height))
        }
    },
)

private const val BASE64_URL_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/**
 * 字节 → base64url，不带填充
 *
 * 只给 id 用：16 字节定长，不需要流式、也不需要解码回来，
 * 所以自己拼六个一组的位，不去依赖各版本状态不一的 base64 API
 */
fun busBase64Url(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var index = 0
    while (index + 2 < bytes.size) {
        val chunk = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or
            (bytes[index + 2].toInt() and 0xff)
        out.append(BASE64_URL_ALPHABET[(chunk shr 18) and 0x3f])
        out.append(BASE64_URL_ALPHABET[(chunk shr 12) and 0x3f])
        out.append(BASE64_URL_ALPHABET[(chunk shr 6) and 0x3f])
        out.append(BASE64_URL_ALPHABET[chunk and 0x3f])
        index += 3
    }
    when (bytes.size - index) {
        1 -> {
            val tail = bytes[index].toInt() and 0xff
            out.append(BASE64_URL_ALPHABET[tail shr 2])
            out.append(BASE64_URL_ALPHABET[(tail shl 4) and 0x3f])
        }

        2 -> {
            val tail = ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)
            out.append(BASE64_URL_ALPHABET[tail shr 10])
            out.append(BASE64_URL_ALPHABET[(tail shr 4) and 0x3f])
            out.append(BASE64_URL_ALPHABET[(tail shl 2) and 0x3f])
        }
    }
    return out.toString()
}
