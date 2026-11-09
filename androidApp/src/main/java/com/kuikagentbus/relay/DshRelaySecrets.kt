package com.kuikagentbus.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class BusNote(
    val kind: String,
    val agentId: String,
    val protocolVersion: String,
    val forwardsDsh: Boolean,
    val seq: Long,
)

/** Wraps Relay pairing secrets with Android Keystore AES-GCM. SQLite never stores tokens. */
internal class DshRelaySecrets(context: Context) {
    private val root = File(context.filesDir, "relay-secrets").apply { mkdirs() }
    private val hostsRoot = File(root, "hosts").apply { mkdirs() }
    private val alias = "dsh.relay.secret-store"

    fun save(
        masterKeyB64: String,
        clientToken: String,
        hostId: String,
        hostName: String,
        relayOrigin: String,
    ) {
        writeSet(root, masterKeyB64, clientToken, hostId, hostName, relayOrigin)
        if (hostId.isNotBlank()) {
            val hostDir = hostDir(hostId).apply { mkdirs() }
            writeSet(hostDir, masterKeyB64, clientToken, hostId, hostName, relayOrigin)
        }
    }

    /** FR-3.5：切到另一台已保存的电脑；返回是否找到它的密钥。 */
    @Synchronized
    fun select(hostId: String): Boolean {
        val hostDir = hostDir(hostId)
        val master = read("master.bin", hostDir)?.toString(Charsets.UTF_8) ?: return false
        val client = read("client.bin", hostDir)?.toString(Charsets.UTF_8) ?: return false
        val host = read("host.bin", hostDir)?.toString(Charsets.UTF_8) ?: return false
        val name = read("name.bin", hostDir)?.toString(Charsets.UTF_8).orEmpty()
        val origin = read("origin.bin", hostDir)?.toString(Charsets.UTF_8).orEmpty()
        writeSet(root, master, client, host, name, origin)
        File(root, "bus.bin").delete()
        return true
    }

    fun masterKey(): String? = read("master.bin")?.toString(Charsets.UTF_8)
    fun clientToken(): String? = read("client.bin")?.toString(Charsets.UTF_8)
    fun hostId(): String? = read("host.bin")?.toString(Charsets.UTF_8)
    fun hostName(): String? = read("name.bin")?.toString(Charsets.UTF_8)
    fun relayOrigin(): String? = read("origin.bin")?.toString(Charsets.UTF_8)
    fun hasPairing(): Boolean = masterKey()?.isNotBlank() == true && clientToken()?.isNotBlank() == true

    fun savedHostIds(): List<String> =
        hostsRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }

    /**
     * bus note 只是 Host 类型/序号的缓存，写失败不能影响已建立的隧道；
     * 采用临时文件 + rename，避免和读线程/其他写入撞出半截密文。
     */
    @Synchronized
    fun saveBus(note: BusNote) {
        val text = listOf(
            note.kind,
            note.agentId,
            note.protocolVersion,
            note.forwardsDsh.toString(),
            note.seq.toString(),
        ).joinToString("\n")
        runCatching { write("bus.bin", text.toByteArray()) }
    }

    /** 读到损坏/旧 KeyStore 加密的缓存时返回 null，让上层回退到 /bus/describe。 */
    @Synchronized
    fun readBus(): BusNote? {
        val lines = read("bus.bin")?.toString(Charsets.UTF_8)?.split('\n') ?: return null
        if (lines.size < 5) return null
        return BusNote(
            kind = lines[0],
            agentId = lines[1],
            protocolVersion = lines[2],
            forwardsDsh = lines[3] == "true",
            seq = lines[4].toLongOrNull() ?: -1L,
        )
    }

    fun clear() {
        listOf("master.bin", "client.bin", "host.bin", "name.bin", "origin.bin", "bus.bin").forEach { File(root, it).delete() }
        hostsRoot.deleteRecursively()
        hostsRoot.mkdirs()
    }

    /** FR-3.5：移除一台电脑的密钥；如果它正是当前活动电脑，同时清掉活动文件。 */
    @Synchronized
    fun clear(hostId: String) {
        val dir = hostDir(hostId)
        if (hostId().orEmpty() == hostId) {
            listOf("master.bin", "client.bin", "host.bin", "name.bin", "origin.bin", "bus.bin")
                .forEach { File(root, it).delete() }
        }
        dir.deleteRecursively()
    }

    private fun hostDir(hostId: String): File =
        File(hostsRoot, hostId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun writeSet(
        dir: File,
        masterKeyB64: String,
        clientToken: String,
        hostId: String,
        hostName: String,
        relayOrigin: String,
    ) {
        write("master.bin", masterKeyB64.toByteArray(), dir)
        write("client.bin", clientToken.toByteArray(), dir)
        write("host.bin", hostId.toByteArray(), dir)
        write("name.bin", hostName.toByteArray(), dir)
        write("origin.bin", relayOrigin.toByteArray(), dir)
    }

    @Synchronized
    private fun write(name: String, bytes: ByteArray, dir: File = root) {
        dir.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        try {
            temp.outputStream().use { output ->
                output.write(cipher.iv.size)
                output.write(cipher.iv)
                output.write(cipher.doFinal(bytes))
                output.flush()
            }
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "cannot replace $name" }
            }
        } finally {
            if (temp.isFile) temp.delete()
            bytes.fill(0)
        }
    }

    @Synchronized
    private fun read(name: String, dir: File = root): ByteArray? {
        val file = File(dir, name)
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { input ->
                val ivLength = input.read()
                require(ivLength in 12..32)
                val iv = ByteArray(ivLength)
                input.readFully(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(input.readBytes())
            }
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}

private fun InputStream.readFully(buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        require(count >= 0)
        offset += count
    }
}
