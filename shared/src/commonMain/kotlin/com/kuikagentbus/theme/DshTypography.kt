package com.kuikagentbus.theme

/** 单个文字角色的字号与行高，单位与页面 dp 一致 */
data class DshText(
    val size: Float,
    val lineHeight: Float,
)

/**
 * 阅读字号的语义层
 *
 * 只放角色，不放页面名；页面和 Markdown 都从这里取，避免各自写数字。
 * 字号仍按 Kuikly 的默认行为映射到 Android sp，跟随系统字体大小。
 */
data class DshTypography(
    val body: DshText = DshText(15f, 24.75f),
    val label: DshText = DshText(12f, 17f),
    val code: DshText = DshText(13f, 19.5f),
) {
    /** 按页面设计宽度缩放；0 或负数按 1 处理 */
    fun scaled(factor: Float): DshTypography {
        val scale = if (factor > 0f) factor else 1f
        return DshTypography(
            body = body.scaled(scale),
            label = label.scaled(scale),
            code = code.scaled(scale),
        )
    }

    /** h1–h6 只做轻微层级；h5/h6 与正文同号 */
    fun heading(level: Int): DshText {
        val scale = when (level) {
            1 -> 1.25f
            2 -> 1.15f
            3 -> 1.08f
            4 -> 1.02f
            else -> return body
        }
        val size = body.size * scale
        return DshText(size, size * 1.3f)
    }

    companion object {
        val Default: DshTypography = DshTypography()
    }
}

private fun DshText.scaled(factor: Float): DshText =
    DshText(size = size * factor, lineHeight = lineHeight * factor)
