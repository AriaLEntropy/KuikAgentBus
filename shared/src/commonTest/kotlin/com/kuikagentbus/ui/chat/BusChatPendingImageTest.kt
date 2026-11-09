package com.kuikagentbus.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class BusChatPendingImageTest {

    @Test
    fun uploadFailureLabels() {
        assertEquals("图片超过 2 MB", busChatUploadFailure("rejected", "too_large"))
        assertEquals("这个附件格式不支持", busChatUploadFailure("rejected", "unsupported_media_type"))
        assertEquals("电脑离线，稍后再试", busChatUploadFailure("offline", ""))
        assertEquals("文件名无效，请重试", busChatUploadFailure("rejected", "invalid_filename"))
        assertEquals("上传失败，请重试", busChatUploadFailure("failed", "network"))
        assertEquals("上传失败，请重试", busChatUploadFailure("rejected", ""))
    }

    @Test
    fun newProjectNameValidation() {
        assertEquals(null, busChatNewProjectError("demo", listOf("other")))
        assertEquals("已经有同名项目", busChatNewProjectError("Demo", listOf("demo")))
        assertEquals("文件夹名不能包含 / \\ :", busChatNewProjectError("a/b", emptyList()))
        assertEquals("文件夹名不能以 . 开头", busChatNewProjectError(".hidden", emptyList()))
        assertEquals("文件夹名最多 80 个字", busChatNewProjectError("x".repeat(81), emptyList()))
        assertEquals("请输入文件夹名", busChatNewProjectError("   ", emptyList()))
    }
}
