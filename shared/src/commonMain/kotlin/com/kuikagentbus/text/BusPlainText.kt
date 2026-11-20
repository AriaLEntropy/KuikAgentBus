package com.kuikagentbus.text

/**
 * 把 Markdown 压成一行纯文本，给通知、横幅、任务行摘要这类“只有一两行”的位置用。
 *
 * 这些位置不是阅读面：渲染列表/代码块/表格反而更乱，系统通知也不支持完整 Markdown。
 * 这里只保留可读文字，不追求保真；完整内容仍在会话页里。
 */
object BusPlainText {

    fun preview(markdown: String, maxChars: Int = 0): String {
        var text = markdown.replace("\r\n", "\n").replace('\r', '\n')
        // 代码块整体替换，避免把围栏和代码原样塞进一行预览
        text = text.replace(Regex("```[\\s\\S]*?```"), " [代码] ")
        // 图片留 alt；链接只留文字
        text = text.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")) { match ->
            match.groupValues[1].trim().ifBlank { "[图片]" }
        }
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        // 行内代码去掉反引号，内容保留
        text = text.replace(Regex("`([^`]+)`"), "$1")
        text = text.replace(Regex("<[^>]+>"), " ")

        val lines = text.split('\n').map { raw ->
            var line = raw.trim()
            if (line.matches(Regex("\\|.*\\|"))) return@map "[表格]"
            line = line.replace(Regex("^#{1,6}\\s+"), "")
            line = line.replace(Regex("^[-*+]\\s+"), "")
            line = line.replace(Regex("^\\d+\\.\\s+"), "")
            line = line.replace(Regex("^>\\s?"), "")
            line = line.replace(Regex("^([-*_])\\1{2,}$"), "")
            line
        }
        text = lines.joinToString("\n")
        text = text.replace(Regex("(\\[表格\\]\\s*)+"), "[表格] ")
        // 只去成对的强调符号；单个 _ / * 可能是标识符或命令，不碰
        text = text.replace(Regex("(\\*\\*|__|~~)(.+?)\\1"), "$2")
        text = text.replace(Regex("\\\\([\\\\`*{}\\[\\]()#+\\-.!_>])"), "$1")
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return ""
        if (maxChars > 0 && text.length > maxChars) {
            return text.take(maxChars).trimEnd() + "…"
        }
        return text
    }
}
