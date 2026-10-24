package com.github.zzwtsy.chuanmen.lib.profile

import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 仅用于 JVM 状态测试的仓库；遵守正式写入结果和修订契约，不提供磁盘保证。 */
class MemoryProfileRepository(initial: List<ProfileSummary>) : ProfileRepository {
    private val values = initial.associate {
        it.saved.profileId to it.copy(saved = it.saved.frozen(), metadata = it.metadata.copy(
            localDisplayName = it.displayName.trim().takeIf(String::isNotEmpty)))
    }.toMutableMap()
    override var lastConnectionTarget: LocalProfileId? = null
        private set
    override suspend fun rememberConnection(saved: SavedRevision): WriteResult<Unit> {
        if (findSaved(saved.profileId) != saved) return WriteResult.Failure(WriteError.Conflict)
        lastConnectionTarget = saved.profileId
        return WriteResult.Success(Unit)
    }
    override val state = MutableStateFlow<RepositoryState>(RepositoryState.Ready).asStateFlow()
    private val mutableProfiles = MutableStateFlow<List<ProfileSummary>>(emptyList())
    override val profiles = mutableProfiles.asStateFlow()
    init { require(values.size == initial.size); publish() }
    override suspend fun load() = Unit
    override fun findSaved(profileId: LocalProfileId) = values[profileId]?.saved

    override suspend fun save(profileId: LocalProfileId, document: CoreConfigDocument, policy: AndroidVpnPolicy, expectedRevision: Long): WriteResult<SavedRevision> {
        val old = values[profileId] ?: return WriteResult.Failure(WriteError.NotFound)
        if (old.saved.revision != expectedRevision) return WriteResult.Failure(WriteError.Conflict)
        if (old.saved.core.instanceId != document.core.instanceId) return WriteResult.Failure(WriteError.ValidationError)
        val sameValues = old.saved.document.sameValues(document) && old.saved.vpnPolicy == policy
        if (sameValues && old.saved.document == document) return WriteResult.Success(old.saved)
        if (!sameValues && old.saved.revision == Long.MAX_VALUE) return WriteResult.Failure(WriteError.RevisionExhausted)
        val saved = SavedRevision(profileId, if (sameValues) old.saved.revision else old.saved.revision + 1, document, policy).frozen()
        values[profileId] = old.copy(saved = saved)
        publish()
        return WriteResult.Success(saved)
    }

    override suspend fun rename(profileId: LocalProfileId, localDisplayName: String?): WriteResult<Unit> {
        val old = values[profileId] ?: return WriteResult.Failure(WriteError.NotFound)
        values[profileId] = old.copy(metadata = old.metadata.copy(localDisplayName = localDisplayName?.trim()?.takeIf(String::isNotEmpty)))
        publish()
        return WriteResult.Success(Unit)
    }

    override suspend fun create(document: CoreConfigDocument, policy: AndroidVpnPolicy, localDisplayName: String?): WriteResult<SavedRevision> {
        val saved = SavedRevision(LocalProfileId(UUID.randomUUID().toString()), 1,
            document.withIdentity(CoreInstanceId(UUID.randomUUID().toString())), policy).frozen()
        values[saved.profileId] = ProfileSummary(saved, localDisplayName.orEmpty(),
            LocalProfileMetadata(localDisplayName?.trim()?.takeIf(String::isNotEmpty)))
        publish()
        return WriteResult.Success(saved)
    }

    override suspend fun delete(profileId: LocalProfileId): WriteResult<Unit> {
        if (values.remove(profileId) == null) return WriteResult.Failure(WriteError.NotFound)
        if (lastConnectionTarget == profileId) lastConnectionTarget = null
        publish()
        return WriteResult.Success(Unit)
    }

    private fun publish() {
        mutableProfiles.value = Collections.unmodifiableList(values.values.map {
            it.copy(displayName = it.metadata.localDisplayName ?: it.saved.core.networkName)
        })
    }
}
