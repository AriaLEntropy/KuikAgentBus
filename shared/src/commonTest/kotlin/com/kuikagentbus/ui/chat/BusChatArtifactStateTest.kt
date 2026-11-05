package com.kuikagentbus.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 产物取回状态的翻译与提示句
 *
 * FR-9.2 的离线文案是需求写死的串，翻了就看得见；
 * 原生层回的状态串在这里一次翻译成界面值，UI 分支不再认字面量
 */
class BusChatArtifactStateTest {

    @Test
    fun readyKeepsTheLocalPathAndShowsNothing() {
        val state = busChatArtifactState("ready", "/data/user/0/com.kuikagentbus/cache/bus-artifacts/x.jpg", "")

        assertEquals(BusChatArtifactStatus.Ready, state.status)
        assertEquals("/data/user/0/com.kuikagentbus/cache/bus-artifacts/x.jpg", state.path)
        assertEquals("", state.label)
    }

    @Test
    fun readyWithoutPathIsNotReady() {
        val state = busChatArtifactState("ready", "", "")

        assertEquals(BusChatArtifactStatus.Failed, state.status)
    }

    @Test
    fun offlineUsesTheRequiredCopy() {
        val state = busChatArtifactState("offline", "", "no_tunnel")

        assertEquals(BusChatArtifactStatus.Offline, state.status)
        // 需求 FR-9.2 验收第 2 条逐字：电脑离线，暂时无法加载
        assertEquals("电脑离线，暂时无法加载", state.label)
        assertEquals("no_tunnel", state.reason)
    }

    @Test
    fun notFoundSaysTheComputerNoLongerHasIt() {
        val state = busChatArtifactState("not_found", "", "http_404")

        assertEquals(BusChatArtifactStatus.NotFound, state.status)
        assertEquals("电脑上已经没有这张图", state.label)
    }

    @Test
    fun unknownStateFallsBackToFailed() {
        val state = busChatArtifactState("http_500", "", "network")

        assertEquals(BusChatArtifactStatus.Failed, state.status)
        assertEquals("加载失败，请稍后再试", state.label)
        assertEquals("network", state.reason)
    }

    @Test
    fun noticeStaysQuietWhileEverythingIsReadyOrLoading() {
        val states = listOf(
            BusChatArtifactState(BusChatArtifactStatus.Ready, path = "/tmp/a.jpg"),
            BusChatArtifactState(BusChatArtifactStatus.Loading),
        )

        assertEquals("", busChatArtifactNotice(states))
    }

    @Test
    fun noticeReportsOnlyTheFirstTerminalState() {
        val states = listOf(
            BusChatArtifactState(BusChatArtifactStatus.Loading),
            BusChatArtifactState(BusChatArtifactStatus.NotFound, label = "电脑上已经没有这张图"),
            BusChatArtifactState(BusChatArtifactStatus.Offline, label = "电脑离线，暂时无法加载"),
        )

        // 四张图同时坏也只挂一句，不会堆成四行
        assertEquals("电脑上已经没有这张图", busChatArtifactNotice(states))
    }

    @Test
    fun emptyAttachmentListHasNoNotice() {
        assertEquals("", busChatArtifactNotice(emptyList()))
    }
}
