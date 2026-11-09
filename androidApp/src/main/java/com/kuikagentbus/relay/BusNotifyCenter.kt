package com.kuikagentbus.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kuikagentbus.KuiklyRenderActivity
import com.kuikagentbus.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `notify` 事件的落地：系统本地通知 + 留给前台横幅读取的最近一条。
 *
 * 同任务同类别使用同一个通知 id，新的直接替换旧的；四个类别各一个渠道。
 */
internal object BusNotifyCenter {
    @Volatile
    private var latest: String? = null

    fun accept(context: Context?, payload: JsonObject) {
        latest = payload.toString()
        if (context == null) return
        val category = payload.string("category").orEmpty()
        val taskId = payload.string("taskId").orEmpty()
        if (taskId.isBlank()) return
        val channel = channelFor(category)
        ensureChannel(context, channel)
        val intent = Intent(context, KuiklyRenderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("bus_notify_task_id", taskId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getActivity(context, taskId.hashCode(), intent, flags)
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(payload.string("title").orEmpty().ifBlank { "任务有更新" })
            .setContentText(payload.string("body").orEmpty())
            .setSubText(payload.string("agentName").orEmpty())
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(channel.priority)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify("$taskId|$category".hashCode(), notification)
        }
    }

    fun take(): String? {
        val value = latest
        latest = null
        return value
    }

    fun clear() {
        latest = null
    }

    private data class Channel(
        val id: String,
        val name: String,
        val importance: Int,
        val priority: Int,
    )

    private fun channelFor(category: String): Channel = when (category) {
        "TASK_APPROVAL" -> Channel(
            "bus_task_approval",
            "任务审批",
            NotificationManager.IMPORTANCE_HIGH,
            NotificationCompat.PRIORITY_HIGH,
        )
        "TASK_INPUT" -> Channel(
            "bus_task_input",
            "任务提问",
            NotificationManager.IMPORTANCE_HIGH,
            NotificationCompat.PRIORITY_HIGH,
        )
        "TASK_FAILED" -> Channel(
            "bus_task_failed",
            "任务失败",
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationCompat.PRIORITY_DEFAULT,
        )
        else -> Channel(
            "bus_task_done",
            "任务完成",
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    private fun ensureChannel(context: Context, channel: Channel) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channel.id) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channel.id, channel.name, channel.importance),
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}