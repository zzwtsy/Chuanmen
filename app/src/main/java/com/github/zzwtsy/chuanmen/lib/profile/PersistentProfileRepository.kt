package com.github.zzwtsy.chuanmen.lib.profile

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Application 唯一仓库；加载和全部写操作在同一后台串行队列中执行。
 * 原子落盘成功后才发布内存快照，读取失败不转成空仓库；同步查询不访问磁盘。
 */
class PersistentProfileRepository internal constructor(
    private val file: ProfileFile,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codec: CoreConfigCodec,
) : ProfileRepository {
    private val mutex = Mutex()
    @Volatile private var document = ProfileDocumentCodec.empty()
    private val mutableState = MutableStateFlow<RepositoryState>(RepositoryState.Loading)
    override val state = mutableState.asStateFlow()
    private val mutableProfiles = MutableStateFlow<List<ProfileSummary>>(emptyList())
    override val profiles = mutableProfiles.asStateFlow()

    override suspend fun load() = withContext(ioDispatcher) {
        mutex.withLock {
            if (state.value == RepositoryState.Ready) return@withLock
            mutableState.value = RepositoryState.Loading
            try {
                val loaded = file.read()?.let { ProfileDocumentCodec.decode(it, codec) } ?: ProfileDocumentCodec.empty()
                document = loaded
                mutableProfiles.value = loaded.profiles
                mutableState.value = RepositoryState.Ready
            } catch (error: ProfileStorageException) {
                mutableState.value = RepositoryState.ReadFailed(error.reason)
            }
        }
    }

    override val lastConnectionTarget: LocalProfileId? get() = document.connectionTarget

    override suspend fun rememberConnection(saved: SavedRevision): WriteResult<Unit> = write {
        if (findSaved(saved.profileId) != saved) return@write WriteResult.Failure(WriteError.Conflict)
        if (document.connectionTarget != saved.profileId) commit(document.profiles, saved.profileId)
        WriteResult.Success(Unit)
    }

    override fun findSaved(profileId: LocalProfileId) = this.document.profiles.find { it.saved.profileId == profileId }?.saved

    override suspend fun create(document: CoreConfigDocument, policy: AndroidVpnPolicy, localDisplayName: String?): WriteResult<SavedRevision> = write {
        val saved = SavedRevision(LocalProfileId(UUID.randomUUID().toString()), 1,
            document.withIdentity(CoreInstanceId(UUID.randomUUID().toString())), policy).frozen()
        val now = clock()
        val metadata = LocalProfileMetadata(localDisplayName?.trim()?.takeIf(String::isNotEmpty),
            this.document.profiles.maxOfOrNull { it.metadata.sortOrder }?.let { Math.addExact(it, 1) } ?: 0, now, now)
        commit(this.document.profiles + summary(saved, metadata))
        WriteResult.Success(saved)
    }

    override suspend fun save(profileId: LocalProfileId, document: CoreConfigDocument, policy: AndroidVpnPolicy, expectedRevision: Long): WriteResult<SavedRevision> = write {
        val old = this.document.profiles.find { it.saved.profileId == profileId }
            ?: return@write WriteResult.Failure(WriteError.NotFound)
        if (old.saved.revision != expectedRevision) return@write WriteResult.Failure(WriteError.Conflict)
        if (old.saved.core.instanceId != document.core.instanceId) return@write WriteResult.Failure(WriteError.ValidationError)
        val sameValues = old.saved.document.sameValues(document) && old.saved.vpnPolicy == policy
        if (sameValues && old.saved.document == document) return@write WriteResult.Success(old.saved)
        val revision = if (sameValues) old.saved.revision else Math.addExact(old.saved.revision, 1)
        val saved = SavedRevision(profileId, revision, document, policy).frozen()
        replace(summary(saved, old.metadata.copy(updatedAtMillis = maxOf(clock(), old.metadata.updatedAtMillis))))
        WriteResult.Success(saved)
    }

    override suspend fun rename(profileId: LocalProfileId, localDisplayName: String?): WriteResult<Unit> = write {
        val old = this.document.profiles.find { it.saved.profileId == profileId }
            ?: return@write WriteResult.Failure(WriteError.NotFound)
        val name = localDisplayName?.trim()?.takeIf(String::isNotEmpty)
        if (name != old.metadata.localDisplayName) replace(summary(old.saved, old.metadata.copy(localDisplayName = name,
            updatedAtMillis = maxOf(clock(), old.metadata.updatedAtMillis))))
        WriteResult.Success(Unit)
    }

    override suspend fun delete(profileId: LocalProfileId): WriteResult<Unit> = write {
        if (this.document.profiles.none { it.saved.profileId == profileId }) return@write WriteResult.Failure(WriteError.NotFound)
        commit(this.document.profiles.filterNot { it.saved.profileId == profileId }, document.connectionTarget.takeUnless { it == profileId })
        WriteResult.Success(Unit)
    }

    private suspend fun <T> write(operation: suspend () -> WriteResult<T>): WriteResult<T> = withContext(ioDispatcher) {
        mutex.withLock {
            if (state.value != RepositoryState.Ready) return@withLock WriteResult.Failure(WriteError.ReadUnavailable)
            try {
                operation()
            } catch (_: ArithmeticException) {
                WriteResult.Failure(WriteError.RevisionExhausted)
            } catch (_: IllegalArgumentException) {
                WriteResult.Failure(WriteError.ValidationError)
            } catch (error: ProfileStorageException) {
                if (error.reason == ProfileStorageException.Reason.KeyUnavailable) {
                    mutableState.value = RepositoryState.ReadFailed(error.reason)
                }
                WriteResult.Failure(when (error.reason) {
                    ProfileStorageException.Reason.KeyUnavailable -> WriteError.KeyUnavailable
                    ProfileStorageException.Reason.StoreTooLarge -> WriteError.StoreTooLarge
                    ProfileStorageException.Reason.DocumentTooLarge -> WriteError.DocumentTooLarge
                    else -> WriteError.WriteFailed
                })
            }
        }
    }

    private suspend fun replace(profile: ProfileSummary) = commit(this.document.profiles.map {
        if (profile.saved.profileId == it.saved.profileId) profile else it
    })
    private fun summary(saved: SavedRevision, metadata: LocalProfileMetadata) =
        ProfileSummary(saved, metadata.localDisplayName ?: saved.core.networkName, metadata)

    private suspend fun commit(profiles: List<ProfileSummary>, target: LocalProfileId? = document.connectionTarget) {
        val candidate = ProfileDocumentCodec.prepare(document, profiles, target)
        file.write(candidate.bytes())
        document = candidate.snapshot
        mutableProfiles.value = candidate.snapshot.profiles
    }

    companion object {
        fun open(context: Context, codec: CoreConfigCodec) = PersistentProfileRepository(EncryptedProfileFile.open(context), codec = codec)
    }
}
