package com.kuikagentbus.relay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把本机文件交给系统应用打开（FR-9.4 的终点，产物列表也用它）
 *
 * 产物字节都在 cacheDir/bus-artifacts 下，必须经 FileProvider 换成 content:// 才给得出去，
 * 所以 res/xml/file_paths.xml 里要有对应的 cache-path 条目，只授读权限
 */
internal object DshFileOpen {

    /** 回的状态串：opened / missing / no_handler / failed，界面按这几个值出文案 */
    fun open(context: Context, file: File, contentType: String): String {
        if (!file.isFile) return "missing"
        val authority = "${context.packageName}.fileprovider"
        val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }
            .getOrElse { return "failed" }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentType.ifBlank { "application/octet-stream" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            "opened"
        }.getOrElse { if (it is ActivityNotFoundException) "no_handler" else "failed" }
    }
}
