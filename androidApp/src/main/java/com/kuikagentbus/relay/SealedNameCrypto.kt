package com.kuikagentbus.relay

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 手机名密文：组密钥 K + AAD `client`
 *
 * 当前阶段复用隧道 master key 充当组密钥，等 FR-3.3 的组密钥接通后只换 key 来源。
 * 输出是 base64url(nonce || ciphertext)，Relay 只存不解。
 */
internal object SealedNameCrypto {
    private const val AAD = "client"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    fun seal(masterKeyB64: String, name: String): String? = runCatching {
        val key = SealedTunnelCrypto.decodeBase64Url(masterKeyB64, KEY_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(name.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(NONCE_BYTES + sealed.size)
        nonce.copyInto(payload)
        sealed.copyInto(payload, NONCE_BYTES)
        SealedTunnelCrypto.encodeBase64Url(payload)
    }.getOrNull()

    fun open(masterKeyB64: String, sealed: String): String? = runCatching {
        val key = SealedTunnelCrypto.decodeBase64Url(masterKeyB64, KEY_BYTES)
        val payload = SealedTunnelCrypto.decodeBase64Url(sealed)
        require(payload.size > NONCE_BYTES)
        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(payload.copyOfRange(NONCE_BYTES, payload.size)), Charsets.UTF_8)
    }.getOrNull()
}
