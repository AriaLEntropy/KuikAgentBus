package com.kuikagentbus.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 产物列表的口径（FR-9.3）
 *
 * 四条样本照宿主的 task-with-artifacts.json：preview + image + file + link，
 * 电脑给的顺序就是新的在前
 */
class BusChatArtifactListTest {

    private val preview = BusChatArtifact(id = "fcyo5arb6h4tj6stp52aspdb4i", kind = "preview", title = "本地预览", size = 0L, contentType = "", url = "")
    private val image = BusChatArtifact(id = "TgJ31imjSW7vhau7ATAuTg", kind = "image", title = "价格表截图", size = 482133L, contentType = "image/png", url = "")
    private val file = BusChatArtifact(id = "syHPyJe8Az_19KvdRbfIZA", kind = "file", title = "pricing-copy.md", size = 2048L, contentType = "text/markdown", url = "")
    private val link = BusChatArtifact(id = "l6QELPk4pC6dJ9lUKprHQg", kind = "link", title = "参考设计", size = 0L, contentType = "", url = "https://design-notes.example.com/pricing-pages")

    @Test
    fun previewIsNotListedAndOrderStays() {
        val listed = busChatListableArtifacts(listOf(preview, image, file, link))

        assertEquals(listOf("image", "file", "link"), listed.map { it.kind })
    }

    @Test
    fun emptyListHasNothingToFilter() {
        assertEquals(emptyList(), busChatListableArtifacts(emptyList()))
    }

    @Test
    fun eachTypeNeedsItsOwnMinimumField() {
        assertTrue(busChatArtifactUsable(image))
        assertTrue(busChatArtifactUsable(file))
        assertTrue(busChatArtifactUsable(link))
        // 链接没有 url 就没有可打开的东西
        assertFalse(busChatArtifactUsable(link.copy(url = "")))
        // 图片/文件没有 id 就取不到字节
        assertFalse(busChatArtifactUsable(image.copy(id = "")))
    }

    @Test
    fun unknownKindIsNotGuessed() {
        // 认不出的类型不猜它该怎么打开，标了不可用也不影响同列其他产物
        assertFalse(busChatArtifactUsable(image.copy(kind = "changes")))
        assertFalse(busChatArtifactUsable(image.copy(kind = "")))
    }

    @Test
    fun unusableCardShowsTheOneLabel() {
        assertEquals("不可用", BUS_CHAT_ARTIFACT_UNAVAILABLE)
    }
}
