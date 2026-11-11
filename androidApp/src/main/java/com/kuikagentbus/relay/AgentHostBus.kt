package com.kuikagentbus.relay

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kuikagentbus.protocol.BusChannel
import com.kuikagentbus.protocol.BusArtifactEntry
import com.kuikagentbus.protocol.BusStore
import com.kuikagentbus.protocol.busArtifactEviction
import com.kuikagentbus.protocol.busArtifactFileName
import com.kuikagentbus.protocol.busArtifactIdUsable
import com.kuikagentbus.protocol.busUploadContentTypeAllowed
import com.kuikagentbus.protocol.busUploadFileTypeAllowed
import com.kuikagentbus.protocol.busUploadSizeAllowed
import com.kuikagentbus.protocol.BusSync
import com.kuikagentbus.protocol.DescribeAttempts
import com.kuikagentbus.protocol.DescribeDecision
import com.kuikagentbus.protocol.SnapshotReceipt
import com.kuikagentbus.protocol.busParse
import com.kuikagentbus.protocol.busSkillListKey
import com.kuikagentbus.protocol.busVersionAtLeast
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * After the existing tunnel is up, ask /bus/describe. A 404 keeps the current plugin path.
 * agent-host then follows /bus/ws, falling back to the snapshot long poll.
 */
internal object AgentHostBus {
    private const val TAG = "AgentHostBus"

    @Volatile
    private var latestHomeJson: String? = null

    @Volatile
    private var latestHostKind: String = ""

    @Volatile
    private var commandPort: Int = 0

    @Volatile
    private var commandToken: String = ""

    @Volatile
    private var appContext: Context? = null

    /** taskId -> host `taskMessages` payload JSON, updated from /bus/ws events. */
    @Volatile
    private var messagesByTask: Map<String, String> = emptyMap()

    /** busSkillListKey -> latest `skillList` payload JSON. */
    @Volatile
    private var skillListsByKey: Map<String, String> = emptyMap()

    /** skillId -> latest `skillContent` payload JSON. */
    @Volatile
    private var skillContentsById: Map<String, String> = emptyMap()

    fun busHomeJson(): String? = latestHomeJson

    fun busHostKind(): String = latestHostKind

    fun taskMessagesJson(taskId: String): String? = messagesByTask[taskId]

    fun skillListJson(key: String): String? = skillListsByKey[key]

    fun skillContentJson(skillId: String): String? = skillContentsById[skillId]

    /** Clear cached snapshot/commands when the relay is disconnected or forgotten. */
    fun clear() {
        latestHomeJson = null
        latestHostKind = ""
        commandPort = 0
        commandToken = ""
        appContext = null
        BusNotifyCenter.clear()
        messagesByTask = emptyMap()
        skillListsByKey = emptyMap()
        skillContentsById = emptyMap()
    }

    /** 前台横幅读取最近一条 notify；读完即清。 */
    fun takeBusNotify(): String? = BusNotifyCenter.take()

    /** 当前 Host 绑定的手机列表（FR-3.6）；未连接或 Host 未配对时返回 null。 */
    fun devicesJson(): String? {
        val port = commandPort
        val token = commandToken
        if (port <= 0 || token.isEmpty()) return null
        return runCatching {
            OkHttpClient.Builder()
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
                .newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$port/bus/devices")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build(),
                )
                .execute()
                .use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
        }.getOrNull()
    }

    /** 解散整组：Relay 吊销全部手机和电脑凭据；返回 HTTP 状态码。 */
    fun revokeGroup(): Int {
        val port = commandPort
        val token = commandToken
        if (port <= 0 || token.isEmpty()) return 0
        return runCatching {
            OkHttpClient.Builder()
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
                .newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$port/bus/group/revoke")
                        .header("Authorization", "Bearer $token")
                        .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build(),
                )
                .execute()
                .use { it.code }
        }.getOrDefault(0)
    }

    /** 移除同一台电脑上的另一台手机；返回 HTTP 状态码，0 表示隧道未就绪。 */
    fun removeDevice(clientId: String): Int {
        val port = commandPort
        val token = commandToken
        if (port <= 0 || token.isEmpty() || clientId.isBlank()) return 0
        return runCatching {
            OkHttpClient.Builder()
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
                .newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$port/bus/devices/${Uri.encode(clientId)}/revoke")
                        .header("Authorization", "Bearer $token")
                        .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build(),
                )
                .execute()
                .use { it.code }
        }.getOrDefault(0)
    }

    fun command(json: String): BusCommandAck? {
        val port = commandPort
        val token = commandToken
        if (port <= 0 || token.isEmpty()) return null
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/bus/commands")
            .header("Authorization", "Bearer $token")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    BusCommandAck(response.code, response.body?.string().orEmpty())
                }
        }.getOrNull()
    }

    fun start(
        port: Int,
        token: String,
        notes: DshRelaySecrets,
        context: Context?,
        stillCurrent: () -> Boolean,
    ) {
        latestHomeJson = null
        latestHostKind = ""
        commandPort = port
        commandToken = token
        appContext = context
        BusNotifyCenter.clear()
        messagesByTask = emptyMap()
        skillListsByKey = emptyMap()
        skillContentsById = emptyMap()
        val describeClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        val pollClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val socketClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        // bus note 只是缓存：Keystore 轮换/文件损坏时不能中断整条 bus 通道。
        val saved = runCatching { notes.readBus() }.getOrNull()
        val remembered = saved?.takeIf { it.kind == "agent-host" && busVersionAtLeast(it.protocolVersion) }
        val decision = remembered?.let {
            DescribeDecision.AgentHost(it.agentId, it.protocolVersion, it.forwardsDsh)
        } ?: identify(describeClient, port, token, stillCurrent)
        latestHostKind = when (decision) {
            is DescribeDecision.AgentHost -> "agent-host"
            DescribeDecision.Plugin -> "dsh-plugin"
            DescribeDecision.Unknown -> "unknown"
        }
        if (decision is DescribeDecision.Plugin) {
            notes.saveBus(BusNote("dsh-plugin", "", "", false, -1))
        }
        if (decision !is DescribeDecision.AgentHost || !stillCurrent()) return
        if (!busVersionAtLeast(decision.protocolVersion)) {
            Log.w(TAG, "请升级电脑端程序 protocolVersion=${decision.protocolVersion}")
            notes.saveBus(BusNote("agent-host", decision.agentId, decision.protocolVersion, decision.forwardsDsh, saved?.seq ?: -1))
            return
        }
        val note = AtomicReference(
            BusNote("agent-host", decision.agentId, decision.protocolVersion, decision.forwardsDsh, remembered?.seq ?: -1),
        )
        notes.saveBus(note.get())
        Log.i(TAG, "host is agent-host agentId=${decision.agentId}")
        val homeStore = BusStore()
        homeStore.bindDescribe(decision)
        val sync = BusSync()
        sync.bind(decision.agentId)
        // 不能直接用持久化的 seq 续传：手机没有持久化完整 snapshot，
        // 若 since 等于电脑当前 seq，服务端不会补发快照，页面会一直空着。
        // 重连由本次进程内的 sync.seq 负责，进程重启则重新拉全量。
        val retired = AtomicBoolean(false)
        val current = { stillCurrent() && !retired.get() }
        if (remembered != null) {
            thread(name = "agent-host-describe", isDaemon = true) {
                refresh(describeClient, port, token, notes, note, sync, retired, current)
            }
        }
        while (current()) {
            if (sync.channel == BusChannel.WebSocket) {
                if (!readSocket(socketClient, port, token, sync, notes, note, homeStore, current)) sync.onHandshakeFailure()
            } else {
                val body = get(pollClient, port, token, sync.pollPath())
                if (body != null) {
                    logSnapshot(notes, note, sync.onSnapshotResponse(body))
                    applySnapshotBody(homeStore, body)
                }
                if (!current()) return
                Thread.sleep(1_000)
                sync.onPollTick(1_000)
            }
        }
    }

    private fun refresh(
        client: OkHttpClient,
        port: Int,
        token: String,
        notes: DshRelaySecrets,
        note: AtomicReference<BusNote>,
        sync: BusSync,
        retired: AtomicBoolean,
        stillCurrent: () -> Boolean,
    ) {
        when (val fresh = describeOnce(client, port, token, stillCurrent)) {
            is DescribeDecision.Plugin -> {
                notes.saveBus(BusNote("dsh-plugin", "", "", false, -1))
                retired.set(true)
            }
            is DescribeDecision.AgentHost -> {
                if (!busVersionAtLeast(fresh.protocolVersion)) {
                    Log.w(TAG, "请升级电脑端程序 protocolVersion=${fresh.protocolVersion}")
                    notes.saveBus(BusNote("agent-host", fresh.agentId, fresh.protocolVersion, fresh.forwardsDsh, sync.seq))
                    retired.set(true)
                    return
                }
                sync.bind(fresh.agentId)
                val updated = note.get().copy(
                    agentId = fresh.agentId,
                    protocolVersion = fresh.protocolVersion,
                    forwardsDsh = fresh.forwardsDsh,
                    seq = sync.seq,
                )
                note.set(updated)
                notes.saveBus(updated)
            }
            else -> Unit
        }
    }

    private fun identify(client: OkHttpClient, port: Int, token: String, stillCurrent: () -> Boolean): DescribeDecision {
        val attempts = DescribeAttempts()
        while (stillCurrent()) {
            val response = runCatching { getResponse(client, port, token, "/bus/describe") }.getOrNull()
            val decision = if (response == null) {
                attempts.onTransportFailure()
            } else {
                attempts.onHttp(response.code, response.body?.string().orEmpty())
            }
            if (decision != null) return decision
        }
        return DescribeDecision.Unknown
    }

    private fun describeOnce(client: OkHttpClient, port: Int, token: String, stillCurrent: () -> Boolean): DescribeDecision {
        if (!stillCurrent()) return DescribeDecision.Unknown
        val response = runCatching { getResponse(client, port, token, "/bus/describe") }.getOrNull()
            ?: return DescribeDecision.Unknown
        return DescribeAttempts().onHttp(response.code, response.body?.string().orEmpty()) ?: DescribeDecision.Unknown
    }

    private fun readSocket(
        client: OkHttpClient,
        port: Int,
        token: String,
        sync: BusSync,
        notes: DshRelaySecrets,
        note: AtomicReference<BusNote>,
        homeStore: BusStore,
        stillCurrent: () -> Boolean,
    ): Boolean {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port${sync.socketPath()}")
            .header("Authorization", "Bearer $token")
            .build()
        val opened = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                sync.onHandshakeOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                applySocketFrame(homeStore, text)
                logSnapshot(notes, note, sync.onSocketFrame(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed.set(true)
            }
        })
        val startedAt = System.currentTimeMillis()
        while (stillCurrent() && !failed.get() && sync.channel == BusChannel.WebSocket) {
            if (!opened.get() && System.currentTimeMillis() - startedAt > 10_000) {
                failed.set(true)
                break
            }
            Thread.sleep(200)
        }
        socket.cancel()
        return opened.get() && !failed.get()
    }

    private fun applySnapshotBody(store: BusStore, body: String) {
        runCatching {
            store.applySnapshot(busParse(body))
            latestHomeJson = store.snapshotJson()
        }
    }

    private fun applySocketFrame(store: BusStore, text: String) {
        runCatching {
            val frame = busParse(text).jsonObject
            when ((frame["type"] as? JsonPrimitive)?.contentOrNull) {
                "snapshot" -> frame["snapshot"]?.let { store.applySnapshot(it) }
                "event" -> {
                    val event = frame["event"] as? JsonObject
                    if (event != null) {
                        store.applyEvent(event)
                        when (event.string("kind")) {
                            "taskMessages" -> {
                                val payload = event["taskMessages"] as? JsonObject
                                val taskId = payload?.string("taskId").orEmpty()
                                if (taskId.isNotEmpty()) {
                                    messagesByTask = messagesByTask + (taskId to payload.toString())
                                }
                            }
                            "skillList" -> {
                                val payload = event["skillList"] as? JsonObject
                                if (payload != null) {
                                    val key = busSkillListKey(
                                        connector = payload.string("connector").orEmpty(),
                                        connectorId = payload.string("connectorId"),
                                        projectPath = payload.string("projectPath").orEmpty(),
                                        taskId = payload.string("taskId").orEmpty(),
                                    )
                                    skillListsByKey = skillListsByKey + (key to payload.toString())
                                }
                            }
                            "skillContent" -> {
                                val payload = event["skillContent"] as? JsonObject
                                val skillId = payload?.string("skillId").orEmpty()
                                if (skillId.isNotEmpty()) {
                                    skillContentsById = skillContentsById + (skillId to payload.toString())
                                }
                            }
                            "notify" -> {
                                val payload = event["notify"] as? JsonObject
                                if (payload != null) BusNotifyCenter.accept(appContext, payload)
                            }
                        }
                    }
                }
            }
            latestHomeJson = store.snapshotJson()
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun logSnapshot(notes: DshRelaySecrets, note: AtomicReference<BusNote>, receipt: SnapshotReceipt?) {
        if (receipt == null) return
        Log.i(TAG, "snapshot seq=${receipt.seq} tasks=${receipt.taskCount}")
        val next = note.get().copy(seq = receipt.seq)
        note.set(next)
        notes.saveBus(next)
    }

    private fun get(client: OkHttpClient, port: Int, token: String, path: String): String? {
        return runCatching { getResponse(client, port, token, path)?.body?.string() }.getOrNull()
    }

    private fun getResponse(client: OkHttpClient, port: Int, token: String, path: String): Response? {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port$path")
            .header("Authorization", "Bearer $token")
            .build()
        return client.newCall(request).execute()
    }

    /**
     * 取一个产物的字节（FR-9.2）：`GET /bus/artifacts/:id` → 写本机缓存 → 回可读路径
     *
     * 只在后台线程调用，调用方是 KRDshRelayModule 里起的那条线程。
     * id 校验、文件命名和淘汰都调 shared 侧的同一套规则，两端不会各写一份。
     * 产物字节不进快照也不走事件帧，协议规定就是按需取
     */
    fun fetchArtifact(cacheRoot: File, artifactId: String, contentType: String): ArtifactFetch {
        if (!busArtifactIdUsable(artifactId)) return ArtifactFetch.Failed("artifact_id")
        val port = commandPort
        val token = commandToken
        if (port <= 0 || token.isEmpty()) return ArtifactFetch.Offline

        val dir = File(cacheRoot, ARTIFACT_CACHE_DIR_NAME).apply { mkdirs() }
        val target = File(dir, busArtifactFileName(artifactId, contentType))
        if (target.isFile && target.length() > 0) {
            // 命中缓存也要把「最后用到」往前推，否则正在看的图会被自己挤掉
            target.setLastModified(System.currentTimeMillis())
            return ArtifactFetch.Ready(target.absolutePath)
        }

        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/bus/artifacts/$artifactId")
            .header("Authorization", "Bearer $token")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    // 电脑那边 7 天保留期过了，或这个 id 从没写过
                    404 -> ArtifactFetch.NotFound
                    200 -> {
                        val bytes = response.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            ArtifactFetch.Failed("empty_body")
                        } else {
                            val temp = File(dir, "${target.name}.tmp")
                            temp.writeBytes(bytes)
                            if (!temp.renameTo(target)) {
                                temp.delete()
                                ArtifactFetch.Failed("write")
                            } else {
                                evictArtifactCache(dir, keep = target.name)
                                ArtifactFetch.Ready(target.absolutePath)
                            }
                        }
                    }
                    else -> ArtifactFetch.Failed("http_${response.code}")
                }
            }
        }.getOrElse { ArtifactFetch.Failed("network") }
    }

    /** 超量时按「最后一次用到」淘汰，刚写入的那条不参与 */
    private fun evictArtifactCache(dir: File, keep: String) {
        val entries = dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { BusArtifactEntry(fileName = it.name, bytes = it.length(), lastUsedAt = it.lastModified()) }
        for (victim in busArtifactEviction(entries, keep)) {
            runCatching { File(dir, victim.fileName).delete() }
        }
    }

    /**
     * 传一张图上去（FR-9.1）：`PUT /bus/uploads/:artifactId`
     *
     * 类型、大小和 id 三道闸门都复用 shared 里的同一套规则，客户端先挡一遍，
     * 电脑回的 413/415/400 再兜一遍，两边不会各写一份口径
     */
    fun uploadArtifact(
        artifactId: String,
        contentType: String,
        bytes: ByteArray,
        name: String = "",
    ): ArtifactUpload {
        if (!busArtifactIdUsable(artifactId)) return ArtifactUpload.Rejected("invalid_artifact_id")
        val isImage = busUploadContentTypeAllowed(contentType)
        if (!isImage && !busUploadFileTypeAllowed(contentType)) {
            return ArtifactUpload.Rejected("unsupported_media_type")
        }
        if (!isImage && name.isBlank()) return ArtifactUpload.Rejected("invalid_filename")
        if (!busUploadSizeAllowed(bytes.size.toLong())) return ArtifactUpload.Rejected("too_large")
        val port = commandPort
        val token = commandToken
        if (port <= 0 || token.isEmpty()) return ArtifactUpload.Offline

        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val requestBuilder = Request.Builder()
            .url("http://127.0.0.1:$port/bus/uploads/$artifactId")
            .header("Authorization", "Bearer $token")
            .put(bytes.toRequestBody(contentType.toMediaType()))
        if (name.isNotBlank()) {
            requestBuilder.header("x-bus-filename", Uri.encode(name))
        }
        val request = requestBuilder.build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    201 -> ArtifactUpload.Uploaded(artifactId, bytes.size.toLong())
                    413 -> ArtifactUpload.Rejected("too_large")
                    415 -> ArtifactUpload.Rejected("unsupported_media_type")
                    400 -> ArtifactUpload.Rejected("invalid_artifact_id")
                    else -> ArtifactUpload.Failed("http_${response.code}")
                }
            }
        }.getOrElse { ArtifactUpload.Failed("network") }
    }

    /**
     * 清掉本机缓存的产物字节（FR-9.2 验收：解除配对时清掉这台电脑的缓存）
     *
     * 缓存文件名只有 artifactId + 扩展名，不带 hostId，所以整个目录删掉就等于
     * 「这台电脑的缓存」。多台电脑并存时要先改成按 hostId 分子目录
     */
    fun clearArtifactCache(cacheRoot: File) {
        runCatching { File(cacheRoot, ARTIFACT_CACHE_DIR_NAME).deleteRecursively() }
    }
}

/** 产物缓存单独一个子目录，方便整体清理，也不会和别的缓存文件混在一起 */
private const val ARTIFACT_CACHE_DIR_NAME = "bus-artifacts"

/** 取字节的结果；手机按状态显示不同文案，FR-9.2 要求离线时说明「电脑离线，暂时无法加载」 */
internal sealed class ArtifactFetch {
    data class Ready(val path: String) : ArtifactFetch()
    object Offline : ArtifactFetch()
    object NotFound : ArtifactFetch()
    data class Failed(val reason: String) : ArtifactFetch()
}

/** 上传的结果；rejected 带的是电脑回的那几个错误码，界面不显示它本身 */
internal sealed class ArtifactUpload {
    data class Uploaded(val artifactId: String, val size: Long) : ArtifactUpload()
    object Offline : ArtifactUpload()
    data class Rejected(val reason: String) : ArtifactUpload()
    data class Failed(val reason: String) : ArtifactUpload()
}

internal data class BusCommandAck(val status: Int, val body: String)
