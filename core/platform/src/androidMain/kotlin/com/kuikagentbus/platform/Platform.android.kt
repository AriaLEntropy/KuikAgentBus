package com.kuikagentbus.platform

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun localTimeZoneId(): String =
    java.util.TimeZone.getDefault().id.takeIf { it.isNotEmpty() } ?: "UTC"
