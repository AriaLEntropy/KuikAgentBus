package com.kuikagentbus.data.storage

import com.kuikagentbus.data.host.DshConnectionMode
import com.kuikagentbus.data.session.DshLegacyRemoteProfile
import com.kuikagentbus.data.message.DshMessage
import com.kuikagentbus.data.session.DshRelayProfile
import com.kuikagentbus.data.session.DshRemoteProfile
import com.kuikagentbus.data.session.DshSession
/** Small durable cache used to make the native client feel continuous across launches. */
interface DshLocalStore {
    fun loadApiKey(): String
    fun saveApiKey(apiKey: String)
    fun loadLastConnectionMode(): DshConnectionMode
    fun saveLastConnectionMode(mode: DshConnectionMode)
    fun loadRemoteProfile(): DshRemoteProfile?
    fun saveRemoteProfile(profile: DshRemoteProfile)
    fun loadRelayProfile(): DshRelayProfile?
    fun saveRelayProfile(profile: DshRelayProfile)
    fun clearRelayProfile()
    fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile): Boolean
    fun clearLegacyRemotePreferenceKeys() = Unit

    fun loadSessions(scopeId: String): List<DshSession>
    fun replaceSessions(scopeId: String, sessions: List<DshSession>)
    fun loadMessages(scopeId: String, sessionId: String): List<DshMessage>
    fun replaceMessages(scopeId: String, sessionId: String, messages: List<DshMessage>)

    fun clearScope(scopeId: String)

    /**
     * Bus snapshot cache. Platforms that have not wired it yet keep the
     * no-op defaults; the data layer still works in memory.
     */
    fun loadBusCache(scopeId: String): String? = null
    fun saveBusCache(scopeId: String, value: String) = Unit
    fun clearBusCache(scopeId: String) = Unit

    /** 通用小设置，给“上次用的 Agent”“首次授权已确认”这类本机偏好用。 */
    fun loadSetting(key: String): String? = null
    fun saveSetting(key: String, value: String) = Unit

    /**
     * 永久删除一个会话及其所有消息。
     * 与 archive 不同，此操作不可恢复。
     * 如果 sessionId 不存在，静默成功（幂等）。
     */
    fun deleteSession(scopeId: String, sessionId: String)
}

expect fun createDshLocalStore(
    path: String,
    legacyProfile: DshLegacyRemoteProfile? = null,
): DshLocalStore
