package com.kuikagentbus.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 发图那套上传规则（FR-9.1）
 *
 * 类型清单、大小上限和 id 形状都跟宿主逐字对齐：常量对不上时这里先红，
 * 而不是等到真机上传被回一个 415
 */
class BusUploadTest {

    @Test
    fun base64UrlMatchesTheStandardAlphabetWithoutPadding() {
        // RFC 4648 的样例：去掉填充后尾组不带 '='
        assertEquals("Zm9vYmFy", busBase64Url("foobar".encodeToByteArray()))
        assertEquals("Zg", busBase64Url("f".encodeToByteArray()))
        assertEquals("Zm8", busBase64Url("fo".encodeToByteArray()))
        assertEquals("Zm9v", busBase64Url("foo".encodeToByteArray()))
    }

    @Test
    fun base64UrlUsesDashAndUnderscoreForTheLastTwoBits() {
        // 0xFB 0xFF 的位组是 111110|111111|111100 → 62、63、60 → -、_、8；
        // 与 python urlsafe_b64encode(b'\xfb\xff') 去掉填充后的 "-_8" 对齐
        assertEquals("-_8", busBase64Url(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    @Test
    fun sixteenBytesEncodeToTheTwentyTwoCharId() {
        val id = busBase64Url(ByteArray(BUS_UPLOAD_ID_BYTES) { (it * 7).toByte() })

        assertEquals(BUS_UPLOAD_ID_LENGTH, id.length)
        // 生成的 id 必须过取字节那道同样的门，否则上传完也下不回来
        assertTrue(busArtifactIdUsable(id))
    }

    @Test
    fun emptyBytesEncodeToEmptyString() {
        assertEquals("", busBase64Url(ByteArray(0)))
    }

    @Test
    fun allowedTypesAreExactlyTheHostFour() {
        assertEquals(setOf("image/png", "image/jpeg", "image/gif", "image/webp"), BUS_UPLOAD_CONTENT_TYPES)
        assertFalse(busUploadContentTypeAllowed("image/heic"))
        assertFalse(busUploadContentTypeAllowed("application/octet-stream"))
        assertFalse(busUploadContentTypeAllowed(""))
    }

    @Test
    fun fileGateAllowsDocumentsButNotImagesOrExecutables() {
        assertTrue(busUploadFileTypeAllowed("application/pdf"))
        assertTrue(
            busUploadFileTypeAllowed(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
        assertTrue(busUploadFileTypeAllowed("text/markdown"))
        assertFalse(busUploadFileTypeAllowed("image/png"))
        assertFalse(busUploadFileTypeAllowed("application/x-msdownload"))
        assertFalse(busUploadFileTypeAllowed(""))
    }

    @Test
    fun sizeGateKeepsTheTwoMiBTopInclusive() {
        assertTrue(busUploadSizeAllowed(BUS_UPLOAD_MAX_BYTES))
        assertFalse(busUploadSizeAllowed(BUS_UPLOAD_MAX_BYTES + 1))
        assertTrue(busUploadSizeAllowed(0L))
    }

    @Test
    fun countGateStopsAtFourImages() {
        assertTrue(busUploadCountAllowed(4))
        assertFalse(busUploadCountAllowed(5))
    }

    @Test
    fun followUpWithAttachmentsMatchesTheUpstreamFixture() {
        val built = BusCommands.followUp(
            commandId = "6b1c2d3e-4f50-4a61-8b72-93a4b5c6d7e8",
            createdAt = "2026-09-17T08:06:00Z",
            agentId = "hV3nQ7pLxK2mR8sTfW4bZQ",
            taskId = "codex:01a0ab79-8c36-7011-bda3-10587db84466",
            prompt = "",
            attachments = listOf(
                BusUploadAttachment(
                    artifactId = "CCCCCCCCCCCCCCCCCCCCCC",
                    contentType = "image/jpeg",
                    size = 200000L,
                    width = 2048,
                    height = 1536,
                ),
            ),
        )
        val expected = """
            {"id":"6b1c2d3e-4f50-4a61-8b72-93a4b5c6d7e8","createdAt":"2026-09-17T08:06:00Z",
             "agentId":"hV3nQ7pLxK2mR8sTfW4bZQ","kind":"followUp",
             "followUp":{"taskId":"codex:01a0ab79-8c36-7011-bda3-10587db84466","prompt":"",
             "attachments":[{"artifactId":"CCCCCCCCCCCCCCCCCCCCCC","contentType":"image/jpeg",
             "size":200000,"width":2048,"height":1536}]}}
        """.trimIndent()

        assertEquals(busParse(expected).jsonObject, busParse(built).jsonObject)
    }

    @Test
    fun noAttachmentsMeansTheKeyIsAbsent() {
        val payload = busParse(
            BusCommands.followUp("cmd-1", "2026-11-05T00:00:00Z", "host-1", "t-1", "继续"),
        ).jsonObject.payload("followUp")

        assertTrue("attachments" !in payload)
        // 只发图不带文字是协议允许的，prompt 写空串而不是省略
        val withImage = busParse(
            BusCommands.followUp(
                commandId = "cmd-2",
                createdAt = "2026-11-05T00:00:00Z",
                agentId = "host-1",
                taskId = "t-1",
                prompt = "",
                attachments = listOf(BusUploadAttachment("CCCCCCCCCCCCCCCCCCCCCC", "image/png", 1L, 0, 0)),
            ),
        ).jsonObject.payload("followUp")
        assertEquals("", (withImage["prompt"] as JsonPrimitive).content)
        assertEquals(1, (withImage["attachments"] as JsonArray).size)
    }

    @Test
    fun startTaskCarriesTheSameAttachmentShape() {
        val payload = busParse(
            BusCommands.startTask(
                commandId = "cmd-3",
                createdAt = "2026-11-05T00:00:00Z",
                agentId = "host-1",
                source = "claude",
                connectorId = null,
                projectPath = "/w/demo",
                prompt = "看下这张图",
                attachments = listOf(BusUploadAttachment("AAAAAAAAAAAAAAAAAAAAAA", "image/jpeg", 123456L, 1170, 2532)),
            ),
        ).jsonObject.payload("startTask")
        val first = (payload["attachments"] as JsonArray).first().jsonObject

        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", first.string("artifactId"))
        assertEquals("image/jpeg", first.string("contentType"))
        assertEquals("123456", first.string("size"))
        assertEquals("1170", first.string("width"))
        assertEquals("2532", first.string("height"))
    }
}

private fun JsonObject.payload(key: String): JsonObject = this[key] as JsonObject

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
