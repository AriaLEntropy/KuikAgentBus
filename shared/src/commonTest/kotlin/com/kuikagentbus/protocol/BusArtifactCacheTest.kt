package com.kuikagentbus.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 产物缓存的纯规则：id 校验、文件名映射、超量淘汰
 *
 * 淘汰按「最后一次用到」，不是按写入时间——用户反复翻同一批图时不该把刚看过的挤掉
 */
class BusArtifactCacheTest {

    private val id = "AAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun onlyProtocolShapedIdsReachThePath() {
        assertTrue(busArtifactIdUsable(id))
        // 含 - 与 _ 的 base64url 也算合法；长度由断言钉住，不靠人数字符
        val urlSafe = "aZ09-_abcdefghijklmnop"
        assertEquals(22, urlSafe.length)
        assertTrue(busArtifactIdUsable(urlSafe))
        // 路径穿越与长度不符都不许拼路径
        assertFalse(busArtifactIdUsable("../../etc/passwd"))
        assertFalse(busArtifactIdUsable("AAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(busArtifactIdUsable("AAAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(busArtifactIdUsable("AAAAAAAAAAAAAAAAAAAAA."))
        assertFalse(busArtifactIdUsable(""))
    }

    @Test
    fun fileNameIsStableAndCarriesTheExtension() {
        assertEquals("$id.jpg", busArtifactFileName(id, "image/jpeg"))
        assertEquals("$id.jpg", busArtifactFileName(id, "IMAGE/JPEG; charset=binary"))
        assertEquals("$id.png", busArtifactFileName(id, "image/png"))
        assertEquals("$id.mp4", busArtifactFileName(id, "video/mp4"))
        // 认不出的类型统一 .bin，但同一个 id 不能每次换一个名字，否则缓存会攒副本
        assertEquals("$id.bin", busArtifactFileName(id, ""))
        assertEquals("$id.bin", busArtifactFileName(id, "application/x-thing"))
        assertEquals(busArtifactFileName(id, "image/png"), busArtifactFileName(id, "image/png"))
    }

    @Test
    fun nothingIsEvictedWhileUnderTheLimit() {
        val entries = listOf(
            BusArtifactEntry("a.bin", bytes = 100, lastUsedAt = 1),
            BusArtifactEntry("b.bin", bytes = 100, lastUsedAt = 2),
        )
        assertEquals(emptyList(), busArtifactEviction(entries, keep = "b.bin", limitBytes = 1000))
    }

    @Test
    fun evictsOldestUsedUntilBackUnderTheLimit() {
        val entries = listOf(
            BusArtifactEntry("old.bin", bytes = 60, lastUsedAt = 1),
            BusArtifactEntry("mid.bin", bytes = 60, lastUsedAt = 5),
            BusArtifactEntry("new.bin", bytes = 60, lastUsedAt = 9),
        )
        // 180 超 150，删最久没用的那一条就够
        val victims = busArtifactEviction(entries, keep = "new.bin", limitBytes = 150)
        assertEquals(listOf("old.bin"), victims.map { it.fileName })
    }

    @Test
    fun keepsTheFreshlyWrittenEntryEvenWhenStillOverTheLimit() {
        val entries = listOf(
            BusArtifactEntry("old.bin", bytes = 90, lastUsedAt = 1),
            BusArtifactEntry("mid.bin", bytes = 90, lastUsedAt = 2),
            BusArtifactEntry("keep.bin", bytes = 90, lastUsedAt = 3),
        )
        // 全删光也压不进 50，也要保住刚写入的这条：否则用户正在看的图会被自己挤掉
        val victims = busArtifactEviction(entries, keep = "keep.bin", limitBytes = 50)
        assertEquals(listOf("old.bin", "mid.bin"), victims.map { it.fileName })
    }
}
