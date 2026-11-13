package com.kuikagentbus.relay

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal data class DshRelayNativeState(
    val phase: String,
    val message: String = "",
    val localPort: Int = 0,
    val localToken: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val relayOrigin: String = "",
    val clientId: String = "",
    val paired: Boolean = false,
    val generation: Long = 0,
)

internal object DshRelayManager {
    private const val TAG = "DshRelay"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val listeners = CopyOnWriteArrayList<(DshRelayNativeState) -> Unit>()
    private val generation = AtomicLong(0)
    private val connecting = AtomicBoolean(false)
    private val reconnectScheduled = AtomicBoolean(false)
    private val outbound = Any()
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    // The tunnel must not inherit callTimeout: OkHttp treats a WebSocket as one Call
    // and would cancel a healthy sealed session after 20s. iOS sets these to infinity.
    private val tunnelClient = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var secrets: DshRelaySecrets? = null
    private var appContext: Context? = null
    private var loopback: DshRelayLoopbackServer? = null
    private var socket: WebSocket? = null
    private var cipher: SecureCipher? = null
    private var accessSessionId = ""
    private var localToken = ""
    private var hostName = ""
    private var relayOrigin = ""
    private var hostId = ""
    @Volatile private var stopped = true
    @Volatile private var removed = false
    @Volatile private var droppingSocket = false
    @Volatile private var state = DshRelayNativeState("IDLE")
    @Volatile private var pairStartedAt = 0L
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun addListener(listener: (DshRelayNativeState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (DshRelayNativeState) -> Unit) {
        listeners -= listener
    }

    fun current(): DshRelayNativeState = state

    fun attach(context: Context) {
        appContext = context.applicationContext
        if (secrets == null) secrets = DshRelaySecrets(context.applicationContext)
        val stored = secrets ?: return
        if (stored.hasPairing()) {
            hostId = stored.hostId().orEmpty()
            hostName = stored.hostName().orEmpty()
            relayOrigin = stored.relayOrigin().orEmpty()
            publish(state.copy(paired = true, hostId = hostId, hostName = hostName, relayOrigin = relayOrigin, clientId = stored.clientId().orEmpty(), phase = if (state.phase == "READY") "READY" else "IDLE"))
        }
    }

    fun pairFromQr(context: Context, qr: String): Map<String, Any> {
        val scanStartedAt = System.currentTimeMillis()
        pairStartedAt = scanStartedAt
        Log.i(TAG, "PAIR_TIMING scan_started_at=$scanStartedAt")
        attach(context)
        publish(DshRelayNativeState("PAIRING", "正在配对"))
        return try {
            val link = parsePairQr(qr)
            val claimToken = SealedTunnelCrypto.deriveClaimToken(link.masterKey)
            val deviceName = Build.MODEL?.trim().takeUnless { it.isNullOrBlank() } ?: "Android"
            val body = JSONObject()
                .put("pairId", link.pairId)
                .put("claimToken", claimToken)
                .put("deviceLabel", deviceName)
                .put("platform", "android")
                .put(
                    "sealedName",
                    SealedNameCrypto.seal(link.masterKey, deviceName) ?: "",
                )
            val response = http.newCall(
                Request.Builder()
                    .url("${link.origin}/pair/claim-device")
                    .header("User-Agent", androidUserAgent())
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build(),
            ).execute()
            val json = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
            if (!response.isSuccessful) {
                val code = json.optString("error")
                val reason = when (code) {
                    "expired" -> "二维码已过期，请让电脑重新生成"
                    "already_claimed" -> "二维码已被其他手机认领"
                    "device_limit" -> "这台电脑已达 10 台手机上限，请先在电脑上移除一台"
                    "invalid_claim" -> "配对参数不匹配，请重新扫码"
                    "not_found" -> "配对会话不存在，请重新扫码"
                    else -> code.ifBlank { "claim failed (${response.code})" }
                }
                publish(DshRelayNativeState("ERROR", reason, paired = secrets?.hasPairing() == true))
                return mapOf("ok" to false, "message" to reason)
            }
            val newHostId = json.optString("hostId")
            val token = json.optString("clientToken")
            val newClientId = json.optString("clientId")
            val name = json.optString("hostName").ifBlank { "电脑" }
            require(newHostId.isNotBlank() && token.isNotBlank()) { "pairing response missing credentials" }
            removed = false
            secrets?.save(link.masterKey, token, newHostId, name, link.origin, newClientId)
            hostId = newHostId
            hostName = name
            relayOrigin = link.origin
            publish(DshRelayNativeState("IDLE", "已配对", hostId = newHostId, hostName = name, relayOrigin = link.origin, clientId = newClientId, paired = true))
            Log.i(TAG, "PAIR_TIMING scan_to_claim_ms=${System.currentTimeMillis() - scanStartedAt}")
            mapOf("ok" to true, "hostId" to newHostId, "hostName" to name, "relayOrigin" to link.origin, "pairedAt" to System.currentTimeMillis())
        } catch (error: Exception) {
            Log.e(TAG, "pair failed", error)
            val message = error.message ?: "配对失败"
            publish(DshRelayNativeState("ERROR", message, paired = secrets?.hasPairing() == true))
            mapOf("ok" to false, "message" to message)
        }
    }

    fun connect(context: Context) {
        attach(context)
        if (removed) {
            publish(DshRelayNativeState("REMOVED", "这台电脑已移除本机，请重新扫码", paired = false))
            return
        }
        if (loopbackListening()) {
            stopped = false
            startForeground(context)
            registerNetwork()
            Log.i(TAG, "reusing loopback port=${loopback?.port}")
            return
        }
        if (connecting.getAndSet(true)) return
        stopped = false
        startForeground(context)
        registerNetwork()
        dropLoopback("new-connect")
        publish(
            DshRelayNativeState(
                "CONNECTING",
                "正在申请访问票",
                hostId = hostId,
                hostName = hostName,
                relayOrigin = relayOrigin,
                paired = secrets?.hasPairing() == true,
                generation = generation.get(),
            ),
        )
        thread(name = "dsh-relay-connect") { connectInternal() }
    }

    fun disconnect() {
        stopped = true
        generation.incrementAndGet()
        connecting.set(false)
        reconnectScheduled.set(false)
        unregisterNetwork()
        dropSocket()
        cipher = null
        dropLoopback("disconnect")
        AgentHostBus.clear()
        stopForeground()
        publish(DshRelayNativeState("STOPPED", "扫码连接已断开", hostId = hostId, hostName = hostName, relayOrigin = relayOrigin, paired = secrets?.hasPairing() == true))
    }

    fun forget() {
        val current = hostId
        removed = false
        disconnect()
        // FR-9.2 验收：解除配对时清掉这台电脑的产物缓存
        appContext?.cacheDir?.let { AgentHostBus.clearArtifactCache(it) }
        if (current.isBlank()) secrets?.clear() else secrets?.clear(current)
        hostId = ""
        hostName = ""
        relayOrigin = ""
        publish(DshRelayNativeState("IDLE", "已移除配对"))
    }

    /** FR-3.5：切到另一台已保存的电脑；找不到密钥时返回 false，当前连接不动。 */
    fun switchTo(context: Context, targetHostId: String): Boolean {
        attach(context)
        val stored = secrets ?: return false
        if (targetHostId == hostId && state.phase == "READY") return true
        if (!stored.select(targetHostId)) return false
        removed = false
        disconnect()
        hostId = stored.hostId().orEmpty()
        hostName = stored.hostName().orEmpty()
        relayOrigin = stored.relayOrigin().orEmpty()
        publish(
            DshRelayNativeState(
                "IDLE",
                "已切换电脑",
                hostId = hostId,
                hostName = hostName,
                relayOrigin = relayOrigin,
                clientId = stored.clientId().orEmpty(),
                paired = true,
            ),
        )
        connect(context)
        return true
    }

    /** 移除一台非当前电脑的配对；当前电脑仍走 forget()。 */
    fun forgetHost(targetHostId: String) {
        if (targetHostId == hostId) forget() else secrets?.clear(targetHostId)
    }

    /** FR-3.6：本组手机列表；sealedName 在这里解开成 name */
    fun pairClients(): Pair<Int, String> {
        val (status, json) = relayCall("GET", "/pair/clients")
        if (status != 200 || json.isBlank()) return status to json
        return runCatching {
            val master = secrets?.masterKey() ?: return status to json
            val root = JSONObject(json)
            val clients = root.optJSONArray("clients") ?: return status to json
            val decoded = org.json.JSONArray()
            for (index in 0 until clients.length()) {
                val item = clients.optJSONObject(index) ?: continue
                val sealed = item.optString("sealedName")
                val name = SealedNameCrypto.open(master, sealed)
                    ?.takeIf { it.isNotBlank() }
                    ?: "手机"
                item.put("name", name)
                decoded.put(item)
            }
            root.put("clients", decoded)
            status to root.toString()
        }.getOrDefault(status to json)
    }

    /** 把本机名字以密文同步到 Relay；旧配对第一次连上时补一次 */
    fun pairSetName(name: String): Boolean {
        val stored = secrets ?: return false
        val master = stored.masterKey() ?: return false
        val sealed = SealedNameCrypto.seal(master, name) ?: return false
        val body = JSONObject().put("sealedName", sealed).toString()
        return relayCall("POST", "/pair/clients/name", body).first in 200..299
    }

    /** FR-3.6：移除另一台手机 */
    fun pairRemoveClient(clientId: String): Int =
        relayCall("DELETE", "/pair/clients/$clientId").first

    /** FR-3.6：解散整组 */
    fun pairRevoke(): Int = relayCall("POST", "/pair/revoke").first

    /** FR-3.6：移除电脑 */
    fun pairRemoveAgent(agentId: String): Int =
        relayCall("DELETE", "/pair/agents/$agentId").first

    private fun relayCall(method: String, path: String, body: String? = null): Pair<Int, String> {
        val stored = secrets ?: return 0 to ""
        val clientToken = stored.clientToken() ?: return 0 to ""
        val origin = stored.relayOrigin().orEmpty().ifBlank { relayOrigin.ifBlank { "http://127.0.0.1:8787" } }
        return runCatching {
            val builder = Request.Builder()
                .url("$origin$path")
                .header("Authorization", "Bearer $clientToken")
                .header("User-Agent", androidUserAgent())
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                else -> builder.post((body ?: "{}").toRequestBody(jsonMedia))
            }
            http.newCall(builder.build()).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        }.getOrDefault(0 to "")
    }

    private fun connectInternal() {
        val myGeneration = generation.incrementAndGet()
        dropSocket()
        try {
            val stored = secrets ?: throw IllegalStateException("missing secrets")
            val master = stored.masterKey() ?: throw IllegalStateException("not paired")
            val clientToken = stored.clientToken() ?: throw IllegalStateException("not paired")
            val origin = stored.relayOrigin().orEmpty().ifBlank { relayOrigin.ifBlank { "http://127.0.0.1:8787" } }
            hostId = stored.hostId().orEmpty()
            hostName = stored.hostName().orEmpty().ifBlank { hostName }
            relayOrigin = origin
            val clientId = stored.clientId().orEmpty()
            publish(DshRelayNativeState("CONNECTING", "正在申请访问票", hostId = hostId, hostName = hostName, relayOrigin = origin, clientId = clientId, paired = true, generation = myGeneration))
            val ticketResponse = http.newCall(
                Request.Builder()
                    .url("$origin/access-ticket")
                    .header("Authorization", "Bearer $clientToken")
                    .header("User-Agent", androidUserAgent())
                    .post("{}".toRequestBody(jsonMedia))
                    .build(),
            ).execute()
            val ticketJson = JSONObject(ticketResponse.body?.string().orEmpty().ifBlank { "{}" })
            if (!ticketResponse.isSuccessful) {
                if (
                    ticketResponse.code == 401 &&
                    ticketJson.optString("error") == "invalid_client_token"
                ) {
                    handleRemoved("access ticket rejected")
                    return
                }
                throw IllegalStateException(ticketJson.optString("error").ifBlank { "ticket failed" })
            }
            val sessionId = ticketJson.optString("accessSessionId")
            val ticket = ticketJson.optString("ticket")
            val tunnelUrl = ticketJson.optString("tunnelUrl").ifBlank { toWs(origin) + "/client-tunnel" }
            hostId = ticketJson.optString("hostId").ifBlank { hostId }
            val ticketClientId = ticketJson.optString("clientId").ifBlank { clientId }
            if (ticketClientId.isNotBlank()) stored.saveClientId(ticketClientId)
            require(ticket.isNotBlank() && sessionId.isNotBlank()) { "ticket response incomplete" }
            accessSessionId = sessionId
            thread(name = "agentbus-pair-name") {
                pairSetName(Build.MODEL?.trim().takeUnless { it.isNullOrBlank() } ?: "Android")
            }
            publish(state.copy(phase = "HANDSHAKING", message = "正在建立加密隧道", generation = myGeneration, hostId = hostId, hostName = hostName, relayOrigin = origin, clientId = ticketClientId, paired = true))
            val clientRandomB64 = SealedTunnelCrypto.encodeBase64Url(SealedTunnelCrypto.randomBytes())
            val hello = envelope(
                "client_hello",
                JSONObject()
                    .put("accessSessionId", sessionId)
                    .put("clientRandomB64", clientRandomB64)
                    .put("clientProofB64", SealedTunnelCrypto.clientProof(master, sessionId, clientRandomB64)),
            )
            tunnelClient.newWebSocket(
                Request.Builder()
                    .url(tunnelUrl)
                    .header("Authorization", "Bearer $ticket")
                    .header("User-Agent", androidUserAgent())
                    .header("Sec-WebSocket-Protocol", "dsh-e2ee-v1")
                    .build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (stopped || myGeneration != generation.get()) {
                            webSocket.cancel()
                            return
                        }
                        droppingSocket = false
                        socket = webSocket
                        webSocket.send(hello.toString())
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (stopped || myGeneration != generation.get()) return
                        handleOuter(text, master, sessionId, clientRandomB64, myGeneration, origin)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (socket === webSocket) socket = null
                        if ((code == 4001 || code == 4003) && myGeneration == generation.get()) {
                            handleRemoved("tunnel closed $code")
                            return
                        }
                        if (!stopped && myGeneration == generation.get()) {
                            connecting.set(false)
                            scheduleReconnect()
                        }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val expected = droppingSocket || socket !== webSocket
                        if (socket === webSocket) socket = null
                        if (!stopped && myGeneration == generation.get()) {
                            connecting.set(false)
                            if (!expected) {
                                Log.e(TAG, "relay socket failed", t)
                                tunnelCloseMessage(t)?.let { message ->
                                    publish(state.copy(phase = "ERROR", message = message))
                                }
                            } else {
                                Log.i(TAG, "relay socket closed: ${t.message}")
                            }
                            scheduleReconnect()
                        }
                    }
                },
            )
        } catch (error: Exception) {
            Log.e(TAG, "connect failed", error)
            connecting.set(false)
            if (!stopped && myGeneration == generation.get()) {
                publish(DshRelayNativeState("ERROR", error.message ?: "连接失败", hostId = hostId, hostName = hostName, relayOrigin = relayOrigin, paired = true, generation = myGeneration))
                scheduleReconnect()
            }
        }
    }

    private fun handleOuter(
        text: String,
        master: String,
        sessionId: String,
        clientRandomB64: String,
        myGeneration: Long,
        origin: String,
    ) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "server_hello" -> {
                    val payload = msg.optJSONObject("payload") ?: return
                    cipher = SealedTunnelCrypto.createClientCipher(
                        master,
                        sessionId,
                        clientRandomB64,
                        payload.optString("serverRandomB64"),
                        payload.optString("serverProofB64"),
                    )
                    startLoopback(myGeneration, origin, sessionId)
                    connecting.set(false)
                }
                "sealed" -> {
                    val payload = msg.optJSONObject("payload") ?: return
                    val opened = cipher?.open(SealedPayload(payload.optString("seq"), payload.optString("ciphertextB64"))) ?: return
                    loopback?.onInner(opened.optString("type"), opened.optJSONObject("payload") ?: JSONObject(), opened.optString("channel"))
                }
                "device_close", "close" -> if (!stopped && myGeneration == generation.get()) {
                    val reason = msg.optJSONObject("payload")?.optString("reason").orEmpty()
                    Log.w(TAG, "relay closed type=${msg.optString("type")} reason=$reason")
                    connecting.set(false)
                    if (reason.contains("revoked") || reason.contains("unbound")) {
                        handleRemoved(reason)
                        return
                    }
                    scheduleReconnect()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "relay handshake failed", error)
            if (!stopped && myGeneration == generation.get()) {
                connecting.set(false)
                if (error !is E2eeException) {
                    publish(state.copy(phase = "ERROR", message = error.message ?: "握手失败"))
                }
                scheduleReconnect()
            }
        }
    }

    private fun startLoopback(myGeneration: Long, origin: String, sessionId: String) {
        dropLoopback("replace")
        localToken = SealedTunnelCrypto.encodeBase64Url(SealedTunnelCrypto.randomBytes(24))
        val server = DshRelayLoopbackServer(localToken) { type, payload, channel -> sendInner(type, payload, channel, sessionId) }
        val port = server.start()
        loopback = server
        Log.i(TAG, "loopback listening port=$port")
        publish(DshRelayNativeState("READY", "扫码隧道已连接", localPort = port, localToken = localToken, hostId = hostId, hostName = hostName, relayOrigin = origin, clientId = secrets?.clientId().orEmpty(), paired = true, generation = myGeneration))
        val scanStartedAt = pairStartedAt
        pairStartedAt = 0L
        if (scanStartedAt > 0) {
            Log.i(TAG, "PAIR_TIMING scan_to_tunnel_ready_ms=${System.currentTimeMillis() - scanStartedAt}")
        }
        val token = localToken
        thread(name = "agent-host-bus", isDaemon = true) {
            val stored = secrets ?: return@thread
            runCatching {
                AgentHostBus.start(port, token, stored, appContext) {
                    !stopped && generation.get() == myGeneration
                }
            }.onFailure { error -> Log.w(TAG, "agent-host bus stopped", error) }
        }
    }

    private fun loopbackListening(): Boolean {
        val server = loopback ?: return false
        return state.phase == "READY" &&
            state.localPort > 0 &&
            server.port == state.localPort &&
            server.isListening()
    }

    private fun dropLoopback(reason: String) {
        val port = loopback?.port ?: 0
        loopback?.stop()
        loopback = null
        localToken = ""
        if (port > 0) Log.i(TAG, "loopback stopped port=$port reason=$reason")
    }

    private fun dropSocket() {
        val current = socket
        socket = null
        if (current == null) return
        droppingSocket = true
        current.cancel()
    }

    private fun sendInner(type: String, payload: JSONObject, channel: String, sessionId: String) {
        val failed = synchronized(outbound) {
            val current = cipher ?: return
            val currentSocket = socket ?: return
            val sealed = current.seal(envelope(type, payload, channel))
            !currentSocket.send(
                envelope(
                    "sealed",
                    JSONObject()
                        .put("accessSessionId", sessionId)
                        .put("seq", sealed.seq)
                        .put("ciphertextB64", sealed.ciphertextB64),
                ).toString(),
            )
        }
        if (failed && !stopped) {
            Log.w(TAG, "inner send dropped type=$type channel=$channel")
            connecting.set(false)
            scheduleReconnect()
        }
    }

    private fun tunnelCloseMessage(error: Throwable): String? {
        val raw = error.message.orEmpty()
        if (raw.equals("Socket closed", ignoreCase = true) ||
            raw.equals("Socket is closed", ignoreCase = true) ||
            raw.contains("Software caused connection abort", ignoreCase = true) ||
            (error is java.net.SocketException && raw.contains("closed", ignoreCase = true)) ||
            error is java.io.EOFException
        ) {
            return null
        }
        return raw.ifBlank { "隧道失败" }
    }

    private fun envelope(type: String, payload: JSONObject, channel: String? = null): JSONObject =
        JSONObject().put("v", 1).put("type", type).apply { if (channel != null) put("channel", channel) }.put("id", UUID.randomUUID().toString()).put("ts", System.currentTimeMillis()).put("payload", payload)

    /**
     * 电脑端移除了这台手机（或解散整组）。
     *
     * 访问票被拒、隧道以 4001/4003 关闭都走这里：停掉重连，清掉本机这台电脑的
     * 配对凭据，回到扫码入口；不能再拿已作废的 token 反复重试。
     */
    @Synchronized
    private fun handleRemoved(reason: String) {
        if (removed) return
        removed = true
        stopped = true
        generation.incrementAndGet()
        connecting.set(false)
        reconnectScheduled.set(false)
        unregisterNetwork()
        dropSocket()
        cipher = null
        accessSessionId = ""
        localToken = ""
        dropLoopback("removed")
        AgentHostBus.clear()
        stopForeground()
        val current = hostId
        Log.w(TAG, "device removed by host: $reason hostId=$current")
        if (current.isNotBlank()) secrets?.clear(current)
        hostId = ""
        hostName = ""
        relayOrigin = ""
        publish(
            DshRelayNativeState(
                "REMOVED",
                "这台电脑已移除本机，请重新扫码",
                hostId = current,
                paired = false,
            ),
        )
    }

    private fun scheduleReconnect() {
        if (stopped || removed) return
        if (!reconnectScheduled.compareAndSet(false, true)) return
        dropSocket()
        dropLoopback("reconnect")
        publish(
            state.copy(
                phase = "RECONNECTING",
                message = "扫码连接重试中",
                localPort = 0,
                localToken = "",
            ),
        )
        thread(name = "dsh-relay-retry") {
            try {
                Thread.sleep(2_000)
                if (stopped) {
                    reconnectScheduled.set(false)
                    return@thread
                }
                // 先放开标志再尝试连接：这次尝试如果失败，会自己再 schedule 下一拍；
                // 否则内层 scheduleReconnect 会被这里仍为 true 的标志吞掉，永久停住。
                reconnectScheduled.set(false)
                if (connecting.compareAndSet(false, true)) connectInternal()
            } catch (error: InterruptedException) {
                reconnectScheduled.set(false)
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                reconnectScheduled.set(false)
                Log.e(TAG, "relay retry failed", error)
                scheduleReconnect()
            }
        }
    }

    private fun parsePairQr(raw: String): PairLink {
        val uri = Uri.parse(raw.trim())
        val fragment = uri.fragment.orEmpty()
        val query = fragment.substringAfter('?', "").ifBlank { uri.query.orEmpty() }
        val params = query.split('&').associate { part ->
            val idx = part.indexOf('=')
            if (idx < 0) part to ""
            else Uri.decode(part.substring(0, idx)) to Uri.decode(part.substring(idx + 1))
        }
        val id = params["id"].orEmpty()
        val key = params["key"].orEmpty()
        require(id.isNotBlank() && key.isNotBlank()) { "二维码缺少配对参数" }
        return PairLink("${uri.scheme}://${uri.authority}", id, key)
    }

    private fun toWs(origin: String): String =
        if (origin.startsWith("https:")) origin.replaceFirst("https:", "wss:") else origin.replaceFirst("http:", "ws:")

    private fun androidUserAgent(): String =
        "DSH-Android/${Build.VERSION.RELEASE} (Linux; Android ${Build.VERSION.RELEASE}; Mobile)"

    private fun startForeground(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, DshRelayForegroundService::class.java)
        val start: () -> Unit = {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            Unit
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) start()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(start)
    }

    private fun stopForeground() {
        appContext?.stopService(Intent(appContext, DshRelayForegroundService::class.java))
    }

    private fun registerNetwork() {
        val context = appContext ?: return
        unregisterNetwork()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!stopped && state.phase != "READY" && state.phase != "CONNECTING" && state.phase != "HANDSHAKING") {
                    thread(name = "dsh-relay-net") {
                        if (connecting.compareAndSet(false, true)) connectInternal()
                    }
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    private fun unregisterNetwork() {
        networkCallback?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
        networkCallback = null
    }

    private fun publish(next: DshRelayNativeState) {
        state = next
        listeners.forEach { listener -> runCatching { listener(next) } }
    }

    private data class PairLink(val origin: String, val pairId: String, val masterKey: String)
}
