package com.kuikagentbus.text

import kotlin.test.Test
import kotlin.test.assertEquals

class BusPlainTextTest {

    @Test
    fun flattensCommonMarkdownIntoOneLine() {
        assertEquals("Title A bold and code.", BusPlainText.preview("# Title\n\nA **bold** and `code`."))
        assertEquals("[代码] then text", BusPlainText.preview("```js\nconsole.log(1)\n```\n\nthen text"))
        assertEquals("[图片] docs", BusPlainText.preview("![](shot.png) [docs](https://example.com)"))
        assertEquals("[表格]", BusPlainText.preview("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertEquals("第一项 第二项", BusPlainText.preview("1. 第一项\n2. 第二项"))
    }

    @Test
    fun truncatesByCharacters() {
        assertEquals("", BusPlainText.preview(""))
        assertEquals("0123…", BusPlainText.preview("0123456789", 4))
        assertEquals("短句", BusPlainText.preview("短句", 10))
    }
}
