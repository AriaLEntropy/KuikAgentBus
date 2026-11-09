package com.kuikagentbus.module

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.kuikagentbus.protocol.BUS_UPLOAD_ID_BYTES
import com.kuikagentbus.protocol.busBase64Url
import com.kuikagentbus.relay.ArtifactFetch
import com.kuikagentbus.relay.ArtifactUpload
import com.kuikagentbus.relay.AgentHostBus
import com.kuikagentbus.relay.DshFileOpen
import com.kuikagentbus.relay.DshQrScanActivity
import com.kuikagentbus.relay.DshRelayManager
import com.kuikagentbus.relay.DshRelayNativeState
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread

internal class KRDshRelayModule : KuiklyRenderBaseModule() {
    private var stateListener: ((DshRelayNativeState) -> Unit)? = null
    private var pairCallback: KuiklyRenderCallback? = null

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? = when (method) {
        "scanAndPair" -> {
            pairCallback = callback
            val intent = Intent(activity, DshQrScanActivity::class.java)
            activity?.startActivityForResult(intent, REQUEST_QR)
            null
        }
        "connect" -> {
            requestNotificationPermission()
            DshRelayManager.connect(requireNotNull(context))
            listen(callback)
            null
        }
        "disconnect" -> {
            DshRelayManager.disconnect()
            null
        }
        "forget" -> {
            DshRelayManager.forget()
            callback?.invoke(mapOf("ok" to true))
            null
        }
        "status" -> DshRelayManager.current().toMap()
        "busHome" -> mapOf("json" to (AgentHostBus.busHomeJson() ?: ""))
        "switchTo" -> {
            val hostId = runCatching { JSONObject(params ?: "{}").optString("hostId") }.getOrDefault("")
            mapOf("ok" to (hostId.isNotBlank() && DshRelayManager.switchTo(requireNotNull(context), hostId)))
        }
        "busTakeNotify" -> mapOf("json" to (AgentHostBus.takeBusNotify() ?: ""))
        "busHostKind" -> mapOf("kind" to AgentHostBus.busHostKind())
        "busTaskMessages" -> {
            val taskId = runCatching { JSONObject(params ?: "{}").optString("taskId") }.getOrDefault("")
            mapOf("json" to (AgentHostBus.taskMessagesJson(taskId) ?: ""))
        }
        "busSkillList" -> {
            val key = runCatching { JSONObject(params ?: "{}").optString("key") }.getOrDefault("")
            mapOf("json" to (AgentHostBus.skillListJson(key) ?: ""))
        }
        "busSkillContent" -> {
            val skillId = runCatching { JSONObject(params ?: "{}").optString("skillId") }.getOrDefault("")
            mapOf("json" to (AgentHostBus.skillContentJson(skillId) ?: ""))
        }
        "busCommand" -> {
            val json = runCatching { JSONObject(params ?: "{}").optString("json") }.getOrDefault("")
            if (json.isBlank()) {
                callback?.invoke(mapOf("status" to 0, "body" to ""))
            } else {
                thread(name = "agent-host-command") {
                    val ack = AgentHostBus.command(json)
                    activity?.runOnUiThread {
                        callback?.invoke(
                            mapOf(
                                "status" to (ack?.status ?: 0),
                                "body" to (ack?.body.orEmpty()),
                            ),
                        )
                    }
                }
            }
            null
        }
        "busFetchArtifact" -> {
            val args = runCatching { JSONObject(params ?: "{}") }.getOrNull()
            val artifactId = args?.optString("artifactId").orEmpty()
            val contentType = args?.optString("contentType").orEmpty()
            val cacheRoot = activity?.cacheDir
            if (cacheRoot == null) {
                callback?.invoke(mapOf("state" to "failed", "reason" to "no_context"))
            } else {
                thread(name = "agent-host-artifact") {
                    val result = AgentHostBus.fetchArtifact(cacheRoot, artifactId, contentType)
                    val payload = when (result) {
                        is ArtifactFetch.Ready -> mapOf("state" to "ready", "path" to result.path)
                        ArtifactFetch.Offline -> mapOf("state" to "offline")
                        ArtifactFetch.NotFound -> mapOf("state" to "not_found")
                        is ArtifactFetch.Failed -> mapOf("state" to "failed", "reason" to result.reason)
                    }
                    activity?.runOnUiThread { callback?.invoke(payload) }
                }
            }
            null
        }
        "busUploadArtifact" -> {
            val args = runCatching { JSONObject(params ?: "{}") }.getOrNull()
            val raw = args?.optString("dataBase64").orEmpty().substringAfterLast(',')
            val contentType = args?.optString("contentType").orEmpty()
            val name = args?.optString("name").orEmpty()
            val bytes = runCatching {
                android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                callback?.invoke(mapOf("state" to "rejected", "reason" to "empty_bytes"))
            } else {
                val random = ByteArray(BUS_UPLOAD_ID_BYTES).also { SecureRandom().nextBytes(it) }
                val artifactId = busBase64Url(random)
                thread(name = "agent-host-upload") {
                    val payload = when (val result = AgentHostBus.uploadArtifact(artifactId, contentType, bytes, name)) {
                        is ArtifactUpload.Uploaded -> mapOf(
                            "state" to "uploaded",
                            "artifactId" to result.artifactId,
                            "size" to result.size.toString(),
                        )

                        ArtifactUpload.Offline -> mapOf("state" to "offline")
                        is ArtifactUpload.Rejected -> mapOf("state" to "rejected", "reason" to result.reason)
                        is ArtifactUpload.Failed -> mapOf("state" to "failed", "reason" to result.reason)
                    }
                    activity?.runOnUiThread { callback?.invoke(payload) }
                }
            }
            null
        }
        "busOpenFile" -> {
            val args = runCatching { JSONObject(params ?: "{}") }.getOrNull()
            val path = args?.optString("path").orEmpty()
            val contentType = args?.optString("contentType").orEmpty()
            val ctx = context
            if (ctx == null) {
                callback?.invoke(mapOf("state" to "failed", "reason" to "no_context"))
            } else {
                callback?.invoke(mapOf("state" to DshFileOpen.open(ctx, File(path), contentType)))
            }
            null
        }
        "subscribe" -> {
            listen(callback)
            null
        }
        else -> null
    }

    private fun listen(callback: KuiklyRenderCallback?) {
        stateListener?.let(DshRelayManager::removeListener)
        val listener: (DshRelayNativeState) -> Unit = { state ->
            activity?.runOnUiThread { callback?.invoke(state.toMap()) }
        }
        stateListener = listener
        DshRelayManager.addListener(listener)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_QR) return
        val qr = data?.getStringExtra(DshQrScanActivity.EXTRA_QR)
            ?: data?.getStringExtra("qr").orEmpty()
        if (resultCode != Activity.RESULT_OK || qr.isBlank()) {
            pairCallback?.invoke(mapOf("ok" to false, "message" to "已取消扫码"))
            pairCallback = null
            return
        }
        val callback = pairCallback
        pairCallback = null
        val appContext = requireNotNull(context)
        thread(name = "dsh-relay-pair") {
            val result = DshRelayManager.pairFromQr(appContext, qr)
            activity?.runOnUiThread { callback?.invoke(result) }
        }
    }

    private fun DshRelayNativeState.toMap(): Map<String, Any> = mapOf(
        "phase" to phase,
        "message" to message,
        "localPort" to localPort,
        "localToken" to localToken,
        "hostId" to hostId,
        "hostName" to hostName,
        "relayOrigin" to relayOrigin,
        "paired" to paired,
        "generation" to generation,
    )

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val current = activity ?: return
        if (current.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        current.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    companion object {
        const val MODULE_NAME = "DshRelayModule"
        const val REQUEST_QR = 4102
        private const val REQUEST_NOTIFICATIONS = 4103
        private var active: KRDshRelayModule? = null

        fun dispatchActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            active?.onActivityResult(requestCode, resultCode, data)
        }
    }

    init {
        active = this
    }
}
