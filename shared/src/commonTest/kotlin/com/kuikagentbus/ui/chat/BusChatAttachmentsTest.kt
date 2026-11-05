package com.kuikagentbus.ui.chat

import com.kuikagentbus.data.message.DshMessage
import com.kuikagentbus.data.message.DshMessageRole
import com.kuikagentbus.protocol.busParse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 随文图片与文件引用的解析
 *
 * 样本形状照宿主的契约 fixture `event-task-messages-with-attachments.json`，
 * 两端各测各的同一份形状，避免手机按自己想象的字段名解析
 */
class BusChatAttachmentsTest {

    private val fixture = """
    {
      "messages": [
        { "id": "item-u1", "role": "user", "text": "看看这张截图",
          "attachments": [ { "artifactId": "AAAAAAAAAAAAAAAAAAAAAA", "contentType": "image/jpeg", "size": 123456, "width": 1170, "height": 2532 } ] },
        { "id": "item-u2", "role": "user", "text": "",
          "attachments": [ { "artifactId": "EEEEEEEEEEEEEEEEEEEEEE", "contentType": "image/png", "size": 54321 } ] },
        { "id": "item-a1", "role": "agent", "text": "已保存到 out/demo.mp4",
          "files": [
            { "path": "/Users/me/proj/out/demo.mp4", "name": "demo.mp4", "contentType": "video/mp4", "size": 4200000 },
            { "path": "/Users/me/proj/out/shot.png", "name": "shot.png", "contentType": "image/png", "size": 80000, "artifactId": "BBBBBBBBBBBBBBBBBBBBBB" }
          ] }
      ]
    }
    """.trimIndent()

    private fun messageAt(index: Int): JsonObject =
        (busParse(fixture).jsonObject["messages"] as JsonArray)[index] as JsonObject

    @Test
    fun parsesImageAttachmentWithDimensions() {
        val attachments = busChatAttachments(messageAt(0)["attachments"])
        assertEquals(1, attachments.size)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", attachments[0].artifactId)
        assertEquals("image/jpeg", attachments[0].contentType)
        assertEquals(123456L, attachments[0].size)
        assertEquals(1170, attachments[0].width)
        assertEquals(2532, attachments[0].height)
    }

    @Test
    fun missingDimensionsStayZero() {
        val attachments = busChatAttachments(messageAt(1)["attachments"])
        assertEquals(1, attachments.size)
        assertEquals(0, attachments[0].width)
        assertEquals(0, attachments[0].height)
    }

    @Test
    fun dropsOnlyTheBrokenAttachmentNotTheMessage() {
        val broken = busParse(
            """[{"contentType":"image/png","size":10},{"artifactId":"AAAAAAAAAAAAAAAAAAAAAA","contentType":"image/jpeg","size":20}]"""
        ).jsonArray
        val attachments = busChatAttachments(broken)
        // 没有 artifactId 的那件取不到字节，丢掉；同条消息剩下的照常
        assertEquals(1, attachments.size)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", attachments[0].artifactId)
    }

    @Test
    fun fileRefFallsBackToPathNameAndKeepsMissingArtifactIdEmpty() {
        val files = busChatFileRefs(messageAt(2)["files"])
        assertEquals(2, files.size)
        assertEquals("demo.mp4", files[0].name)
        // FR-9.4：没有 id 的文件卡片点了要先发 fetchFile，不能直接下载
        assertEquals("", files[0].artifactId)
        assertEquals("BBBBBBBBBBBBBBBBBBBBBB", files[1].artifactId)
    }

    @Test
    fun imageFilterFollowsContentType() {
        val withImage = DshMessage(
            id = "u1",
            role = DshMessageRole.USER,
            content = "看图",
            attachments = busChatAttachments(messageAt(0)["attachments"]),
        )
        assertEquals(1, busChatImageAttachments(withImage).size)

        val onlyFile = withImage.copy(
            attachments = withImage.attachments.map { it.copy(contentType = "application/pdf") },
        )
        assertEquals(0, busChatImageAttachments(onlyFile).size)
    }

    @Test
    fun imagePlaceholderShowsOnlyUntilTheAttachmentArrives() {
        // FR-6.3：先收到空正文无附件的占位消息，画「图片」
        val placeholder = DshMessage(id = "u2", role = DshMessageRole.USER, content = "")
        assertTrue(busChatNeedsImagePlaceholder(placeholder))

        // 同 id 的新消息带上了 attachments，原位换成缩略图，不再走占位
        val arrived = placeholder.copy(attachments = busChatAttachments(messageAt(1)["attachments"]))
        assertFalse(busChatNeedsImagePlaceholder(arrived))

        // 有正文的消息永远不占位
        assertFalse(busChatNeedsImagePlaceholder(placeholder.copy(content = "文字")))
    }
}
