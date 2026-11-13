package com.kuikagentbus.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DshTypographyTest {
    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.01f, "expected $expected, was $actual")
    }

    @Test
    fun defaultBodyMatchesChatTokens() {
        val type = DshTypography.Default
        assertEquals(15f, type.body.size)
        assertEquals(24.75f, type.body.lineHeight)
        assertEquals(13f, type.code.size)
        assertEquals(19.5f, type.code.lineHeight)
    }

    @Test
    fun scalingAppliesToEveryRoleAndKeepsRatios() {
        val scaled = DshTypography.Default.scaled(1.2f)
        assertClose(18f, scaled.body.size)
        assertClose(29.7f, scaled.body.lineHeight)
        assertClose(15.6f, scaled.code.size)
    }

    @Test
    fun headingsOnlyGrowABitAndH5FallsBackToBody() {
        val type = DshTypography.Default
        assertClose(18.75f, type.heading(1).size)
        assertClose(24.375f, type.heading(1).lineHeight)
        assertEquals(type.body, type.heading(5))
        assertEquals(type.body, type.heading(6))
    }
}
