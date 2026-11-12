package com.kuikagentbus.e2e

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.kuikagentbus.protocol.BUS_UPLOAD_ID_BYTES
import com.kuikagentbus.protocol.busBase64Url
import com.kuikagentbus.relay.AgentHostBus
import com.kuikagentbus.relay.ArtifactFetch
import com.kuikagentbus.relay.ArtifactUpload
import com.kuikagentbus.relay.DshRelayManager
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Debug-only E2E driver. It is intentionally not visible in the product UI:
 * CI launches this activity with a pair link, then it drives the same relay /
 * transport APIs the UI uses and logs E2E_RESULT.
 */
class E2eActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (running) {
            finish()
            return
        }
        running = true
        val pairLink = intent?.getStringExtra("e2e_pair_b64")
            ?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP)) }.getOrNull() }
            ?: intent?.getStringExtra("e2e_pair").orEmpty()
        if (pairLink.isBlank()) {
            finishWith("E2E_RESULT FAIL missing e2e_pair")
            return
        }
        thread(name = "agentbus-e2e") { runScript(pairLink) }
    }

    private fun runScript(pairLink: String) {
        try {
            log("pairing")
            val paired = DshRelayManager.pairFromQr(applicationContext, pairLink)
            if (paired["ok"] != true) {
                finishWith("E2E_RESULT FAIL pair: ${paired["message"]}")
                return
            }
            log("connecting")
            DshRelayManager.connect(applicationContext)
            if (!waitFor(60_000) {
                    DshRelayManager.current().phase == "READY" &&
                        !AgentHostBus.busHomeJson().isNullOrBlank()
                }) {
                finishWith("E2E_RESULT FAIL host snapshot not received")
                return
            }

            val before = taskIds(snapshot())
            log("startTask")
            command("""{"id":"e2e-start-${UUID.randomUUID()}","kind":"startTask","startTask":{"source":"acp","connectorId":"fake","projectPath":"/tmp","prompt":"ECHO:ok"}}""")
            val started = waitForTask(60_000, before) ?: run {
                finishWith("E2E_RESULT FAIL startTask did not create a fake task")
                return
            }
            val taskId = started.optString("id")
            if (!waitForTaskStatus(60_000, taskId, "completed")) {
                finishWith("E2E_RESULT FAIL startTask did not complete ($taskId)")
                return
            }
            log("startTask completed")

            log("followUp")
            command("""{"id":"e2e-follow-${UUID.randomUUID()}","kind":"followUp","followUp":{"taskId":"$taskId","prompt":"ECHO:follow"}}""")
            if (!waitFor(60_000) { taskById(snapshot(), taskId)?.optString("status") == "completed" }) {
                finishWith("E2E_RESULT FAIL followUp did not complete")
                return
            }
            log("followUp completed")

            log("approval")
            val beforeApproval = taskIds(snapshot())
            command("""{"id":"e2e-permission-${UUID.randomUUID()}","kind":"startTask","startTask":{"source":"acp","connectorId":"fake","projectPath":"/tmp","prompt":"NEED_PERMISSION"}}""")
            val approvalTask = waitForTask(60_000, beforeApproval) ?: run {
                finishWith("E2E_RESULT FAIL approval task was not created")
                return
            }
            val approvalTaskId = approvalTask.optString("id")
            if (!waitForTaskStatus(60_000, approvalTaskId, "waitingApproval")) {
                finishWith("E2E_RESULT FAIL approval task did not wait ($approvalTaskId)")
                return
            }
            val requestId = taskById(snapshot(), approvalTaskId)
                ?.optJSONObject("pendingRequest")
                ?.optString("id")
                .orEmpty()
            if (requestId.isBlank()) {
                finishWith("E2E_RESULT FAIL pendingRequest missing id")
                return
            }
            command("""{"id":"e2e-approve-${UUID.randomUUID()}","kind":"approve","approve":{"taskId":"$approvalTaskId","requestId":"$requestId","decision":"allow"}}""")
            if (!waitFor(60_000) {
                    taskById(snapshot(), approvalTaskId)?.optString("status") in setOf("running", "completed")
                }) {
                finishWith("E2E_RESULT FAIL approval did not resume the task")
                return
            }
            log("approval resumed")

            log("artifact")
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val artifactId = busBase64Url(ByteArray(BUS_UPLOAD_ID_BYTES).also { SecureRandom().nextBytes(it) })
            val uploaded = AgentHostBus.uploadArtifact(
                artifactId,
                "image/png",
                bytes,
                "e2e.png",
            )
            if (uploaded !is ArtifactUpload.Uploaded) {
                finishWith("E2E_RESULT FAIL artifact upload: $uploaded")
                return
            }
            val fetched = AgentHostBus.fetchArtifact(cacheDir, uploaded.artifactId, "image/png")
            if (fetched !is ArtifactFetch.Ready) {
                finishWith("E2E_RESULT FAIL artifact fetch: $fetched")
                return
            }
            log("artifact round-trip")

            finishWith("E2E_RESULT PASS")
        } catch (error: Exception) {
            Log.e(TAG, "e2e failed", error)
            finishWith("E2E_RESULT FAIL ${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun command(json: String) {
        var ack: com.kuikagentbus.relay.BusCommandAck? = null
        for (attempt in 0 until 3) {
            ack = AgentHostBus.command(json)
            if (ack != null) break
            Thread.sleep(500)
        }
        val result = ack
        if (result == null || result.status !in 200..299) {
            throw IllegalStateException("command failed status=${result?.status} body=${result?.body}")
        }
    }

    private fun snapshot(): JSONObject =
        runCatching { JSONObject(AgentHostBus.busHomeJson().orEmpty()) }.getOrDefault(JSONObject())

    private fun tasks(snapshot: JSONObject): List<JSONObject> {
        val array = snapshot.optJSONArray("tasks") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun taskIds(snapshot: JSONObject): Set<String> =
        tasks(snapshot).map { it.optString("id") }.toSet()

    private fun taskById(snapshot: JSONObject, id: String): JSONObject? =
        tasks(snapshot).firstOrNull { it.optString("id") == id }

    private fun waitForTask(timeoutMs: Long, before: Set<String>): JSONObject? {
        var found: JSONObject? = null
        waitFor(timeoutMs) {
            found = tasks(snapshot()).firstOrNull {
                it.optString("id").startsWith("acp:fake:") && it.optString("id") !in before
            }
            found != null
        }
        return found
    }

    private fun waitForTaskStatus(timeoutMs: Long, taskId: String, status: String): Boolean =
        waitFor(timeoutMs) { taskById(snapshot(), taskId)?.optString("status") == status }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun finishWith(result: String) {
        Log.i(TAG, result)
        runOnUiThread { finish() }
    }

    companion object {
        private const val TAG = "E2eDriver"
        @Volatile private var running = false
    }
}
