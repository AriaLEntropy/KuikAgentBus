package com.kuikagentbus.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * fetchFile 的序列化（FR-9.4）
 *
 * 期望值逐键照上游契约样本 command-fetch-file.json，两端各测各的同一份形状
 */
class BusFetchFileCommandTest {

    @Test
    fun matchesTheUpstreamFixture() {
        val built = BusCommands.fetchFile(
            commandId = "5f2f3a1c-7f2e-4f3a-9a11-2b6d0c1e7a90",
            createdAt = "2026-09-25T08:03:00Z",
            agentId = "hV3nQ7pLxK2mR8sTfW4bZQ",
            taskId = "codex:thread-1",
            messageId = "item-a1",
            path = "/Users/me/proj/out/demo.mp4",
        )
        val expected = """
            {"id":"5f2f3a1c-7f2e-4f3a-9a11-2b6d0c1e7a90","createdAt":"2026-09-25T08:03:00Z",
             "agentId":"hV3nQ7pLxK2mR8sTfW4bZQ","kind":"fetchFile",
             "fetchFile":{"taskId":"codex:thread-1","messageId":"item-a1","path":"/Users/me/proj/out/demo.mp4"}}
        """.trimIndent()

        assertEquals(busParse(expected).jsonObject, busParse(built).jsonObject)
    }

    @Test
    fun keepsAWindowsPathVerbatim() {
        val built = BusCommands.fetchFile(
            commandId = "cmd-2",
            createdAt = "2026-11-05T00:00:00Z",
            agentId = "host-1",
            taskId = "claude:sess-9",
            messageId = "item-a2",
            path = """C:\work\demo\报告.pdf""",
        )
        val payload = busParse(built).jsonObject.payload("fetchFile")

        assertEquals("""C:\work\demo\报告.pdf""", payload.string("path"))
        assertEquals("claude:sess-9", payload.string("taskId"))
        assertEquals("item-a2", payload.string("messageId"))
    }

    @Test
    fun neverOmitsTheThreeFields() {
        // 空值由 source 层挡住不发，命令本身仍然三个键都在，电脑才不会对不上形状
        val built = BusCommands.fetchFile("cmd-3", "2026-11-05T00:00:00Z", "host-1", "", "", "")
        val payload = busParse(built).jsonObject.payload("fetchFile")

        assertTrue("taskId" in payload)
        assertTrue("messageId" in payload)
        assertTrue("path" in payload)
    }
}

private fun JsonObject.payload(key: String): JsonObject = this[key] as JsonObject

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
