package com.kuikagentbus.platform

import kotlin.system.getTimeMillis
import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun currentTimeMillis(): Long = getTimeMillis()

internal actual fun localTimeZoneId(): String =
    getenv("TZ")?.toKString()?.takeIf { it.isNotEmpty() } ?: "UTC"
