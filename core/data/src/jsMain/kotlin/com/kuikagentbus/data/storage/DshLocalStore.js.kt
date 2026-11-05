package com.kuikagentbus.data.storage

import com.kuikagentbus.data.host.DshConnectionMode
import com.kuikagentbus.data.session.DshLegacyRemoteProfile
import com.kuikagentbus.data.message.DshMessage
import com.kuikagentbus.data.session.DshRelayProfile
import com.kuikagentbus.data.session.DshRemoteProfile
import com.kuikagentbus.data.session.DshSession
actual fun createDshLocalStore(path: String, legacyProfile: DshLegacyRemoteProfile?): DshLocalStore = EmptyDshLocalStore

private object EmptyDshLocalStore : DshLocalStore {
    override fun loadApiKey(): String = ""
    override fun saveApiKey(apiKey: String) = Unit
    override fun loadLastConnectionMode(): DshConnectionMode = DshConnectionMode.RELAY
    override fun saveLastConnectionMode(mode: DshConnectionMode) = Unit
    override fun loadRemoteProfile(): DshRemoteProfile? = null
    override fun saveRemoteProfile(profile: DshRemoteProfile) = Unit
    override fun loadRelayProfile(): DshRelayProfile? = null
    override fun saveRelayProfile(profile: DshRelayProfile) = Unit
    override fun clearRelayProfile() = Unit
    override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile): Boolean = false
    override fun loadSessions(connectionId: String): List<DshSession> = emptyList()
    override fun replaceSessions(connectionId: String, sessions: List<DshSession>) = Unit
    override fun loadMessages(connectionId: String, sessionId: String): List<DshMessage> = emptyList()
    override fun replaceMessages(connectionId: String, sessionId: String, messages: List<DshMessage>) = Unit
    override fun clearScope(scopeId: String) = Unit
    override fun deleteSession(scopeId: String, sessionId: String) = Unit
}
