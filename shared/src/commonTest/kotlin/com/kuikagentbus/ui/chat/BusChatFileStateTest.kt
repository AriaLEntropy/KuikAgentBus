package com.kuikagentbus.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 文件卡片那句话
 *
 * 拒绝串的样本逐条照宿主的实现（runtime.ts 的 fetchFile 分支），
 * 电脑改了文案这里会先红，而不是让界面继续显示一句过期解释
 */
class BusChatFileStateTest {

    @Test
    fun missingFromDialogueBecomesTheRefreshHint() {
        // 宿主原文带「这个」前缀，需求只规定了这一条要改写
        assertEquals("下拉刷新记录后再试", busChatFileFailure("这个文件不在对话里"))
        assertEquals("下拉刷新记录后再试", busChatFileFailure("文件不在对话里"))
    }

    @Test
    fun otherComputerReasonsPassThrough() {
        assertEquals("文件路径不合法", busChatFileFailure("文件路径不合法"))
        assertEquals("这个文件已经不在项目里了", busChatFileFailure("这个文件已经不在项目里了"))
        assertEquals("文件太大，无法取回", busChatFileFailure("文件太大，无法取回"))
        assertEquals("本机没有这个任务：codex:thread-1", busChatFileFailure("本机没有这个任务：codex:thread-1"))
        // 手机自己就会挡住缺 id，真收到说明两端对不上，原样透传更利于排查
        assertEquals("taskId is missing", busChatFileFailure("taskId is missing"))
    }

    @Test
    fun blankReasonGetsOneSentence() {
        assertEquals("获取失败", busChatFileFailure(""))
    }

    @Test
    fun keySeparatesSameNameFilesInOneMessage() {
        assertEquals("item-a1|/w/demo/a.md", busChatFileKey("item-a1", "/w/demo/a.md"))
        // 同一条消息里两个同名文件靠 path 区分，不会共用一个状态
        assertEquals(
            false,
            busChatFileKey("item-a1", "/w/a.md") == busChatFileKey("item-a1", "/w/b.md"),
        )
    }

    @Test
    fun openResultsBecomeTheirOwnSentences() {
        assertEquals("这台手机上没有能打开它的应用", busChatOpenFailure("no_handler"))
        assertEquals("本机的文件已经不见了", busChatOpenFailure("missing"))
        assertEquals("打开失败，请稍后再试", busChatOpenFailure("failed"))
    }

    @Test
    fun idleCardHasNoLabelAndKeepsTheSizeLine() {
        // Idle 时 label 为空，卡片第二行回落到「大小 · 文件」
        assertEquals("", BusChatFileState(BusChatFileStatus.Idle).label)
    }
}
