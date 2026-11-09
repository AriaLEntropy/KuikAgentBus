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
}
