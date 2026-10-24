package com.github.zzwtsy.chuanmen.lib.profile

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PersistentProfileRepositoryTest {
    private class RecordingFile(var bytes: ByteArray? = null) : ProfileFile {
        var failWrite = false
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw ProfileStorageException(ProfileStorageException.Reason.Write)
            bytes = plaintext.copyOf()
        }
    }

    private suspend fun open(file: ProfileFile, clock: () -> Long = { 1000L }): PersistentProfileRepository =
        PersistentProfileRepository(file, clock = clock, codec = TestCoreConfigCodec()).also { it.load() }

    private fun core(name: String = "合成网络 🌏") = CoreConfig(CoreInstanceId("synthetic-instance"), name, null,
        networkSecret = NetworkSecret.of("synthetic-p03-secret"), peers = listOf(PeerEndpoint("tcp://192.0.2.1:11010")))

    /** 保存 Unicode 配置并改本地名后重新创建仓库，身份、密码、修订、顺序和名称均从同一文档恢复。 */
    @Test fun reopen_restoresMetadataAndRevisionWithoutCouplingNameToCore() = runTest {
        val file = RecordingFile()
        val repository = open(file) { 1000L }
        val first = repository.create(core().testDocument(), AndroidVpnPolicy(), " 家里 ").requireSuccess()
        repository.rename(first.profileId, "本地新名")
        assertEquals(first, repository.findSaved(first.profileId))
        val second = repository.save(first.profileId, first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, first.revision).requireSuccess()
        val other = repository.create(core("另一个网络").testDocument(), AndroidVpnPolicy(), "本地新名").requireSuccess()
        val reopened = open(file) { 2000L }
        assertEquals(second, reopened.findSaved(first.profileId))
        assertEquals(2L, second.revision)
        assertEquals(first.core.instanceId, second.core.instanceId)
        assertEquals(listOf(first.profileId, other.profileId), reopened.profiles.value.map { it.saved.profileId })
        assertEquals("本地新名", reopened.profiles.value.first().displayName)
        assertEquals(1000L, reopened.profiles.value.first().metadata.createdAtMillis)
    }

    /** 已有有效文档时模拟磁盘提交中断，保存、改名和删除都不得发布半次变更或覆盖旧字节。 */
    @Test fun failedCommit_keepsOldBytesAndPublishedState() = runTest {
        val file = RecordingFile()
        val repository = open(file)
        val first = repository.create(core().testDocument(), AndroidVpnPolicy(), "旧名称").requireSuccess()
        val previous = file.bytes!!.copyOf()
        val published = repository.profiles.value
        file.failWrite = true
        listOf<suspend () -> WriteResult<*>>(
            { repository.save(first.profileId, first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, first.revision) },
            { repository.rename(first.profileId, "新名称") },
            { repository.delete(first.profileId) },
            { repository.create(core().testDocument(), AndroidVpnPolicy()) },
        ).forEach { operation ->
            assertEquals(WriteResult.Failure(WriteError.WriteFailed), operation())
            assertArrayEquals(previous, file.bytes)
            assertEquals(published, repository.profiles.value)
        }
        assertEquals(first, open(file).findSaved(first.profileId))
    }

    /** 磁盘 schema 1 经改名保存时保留未知字段、显式空列表和大整数，不经过浮点转换。 */
    @Test fun schemaOneAndUnknownFields_surviveWriteAndReopen() = runTest {
        val fixture = javaClass.getResourceAsStream("/profiles/schema-1.json")!!.use { it.readBytes() }
        val file = RecordingFile(fixture)
        val repository = open(file)
        val id = repository.profiles.value.single().saved.profileId
        assertEquals("旧的中文名称", repository.profiles.value.single().displayName)
        repository.rename(id, "新的中文名称")
        val tree = JsonParser.parseString(file.bytes!!.toString(Charsets.UTF_8)).asJsonObject
        assertEquals(1, tree["schemaVersion"].asInt)
        assertEquals("18446744073709551615", tree["futureCounter"].asString)
        val record = tree.getAsJsonArray("profiles")[0].asJsonObject
        assertEquals("保留原值", record["unknownKey"].asString)
        assertTrue(record["coreToml"].asString.contains("listeners = []"))
        assertTrue(record["coreToml"].asString.contains("future_flag = false"))
        assertEquals("新的中文名称", open(file).profiles.value.single().displayName)
    }

    /** 新 schema 或损坏文档加载失败时保留原件，错误对象不包含输入中的合成密码。 */
    @Test fun unreadableDocument_isNeverReplacedWithEmptyStore() = runTest {
        listOf("{\"schemaVersion\":99}", "invalid synthetic-p03-secret").forEach { input ->
            val bytes = input.toByteArray()
            val file = RecordingFile(bytes)
            val loaded = open(file)
            assertTrue(loaded.state.value is RepositoryState.ReadFailed)
            assertFalse(loaded.state.value.toString().contains("synthetic-p03-secret"))
            assertEquals(WriteResult.Failure(WriteError.ReadUnavailable), loaded.create(core().testDocument(), AndroidVpnPolicy()))
            assertArrayEquals(bytes, file.bytes)
        }
    }

    /** 相同显示名的两份配置按 ID 删除；重开后只缺少被选中的那份。 */
    @Test fun delete_usesStableIdWhenNamesAreEqual() = runTest {
        val file = RecordingFile()
        val repository = open(file)
        val a = repository.create(core().testDocument(), AndroidVpnPolicy(), "相同名称").requireSuccess()
        val b = repository.create(core().testDocument(), AndroidVpnPolicy(), "相同名称").requireSuccess()
        assertEquals(WriteResult.Success(Unit), repository.delete(a.profileId))
        assertEquals(WriteResult.Failure(WriteError.NotFound), repository.delete(a.profileId))
        assertEquals(listOf(b), open(file).profiles.value.map { it.saved })
    }

    /** 相同内容重复保存不增修订；改名后保存核心新值时合并最新名称，名称本身不变成待应用参数。 */
    @Test fun unchangedSaveAndRename_preserveRevisionAndLatestMetadata() = runTest {
        val file = RecordingFile()
        val repository = open(file)
        val first = repository.create(core().testDocument(), AndroidVpnPolicy(), "旧名称").requireSuccess()
        assertEquals(first, repository.save(first.profileId, first.core.testDocument(), first.vpnPolicy, first.revision).requireSuccess())
        repository.rename(first.profileId, "新名称").requireSuccess()
        val second = repository.save(first.profileId, first.core.copy(hostname = "新主机名").testDocument(), first.vpnPolicy, first.revision).requireSuccess()
        assertEquals(first.revision + 1, second.revision)
        assertEquals("新名称", repository.profiles.value.single().displayName)
        assertEquals("新名称", open(file).profiles.value.single().displayName)
    }

    /** 过期草稿或更换核心身份均被明确拒绝，拒绝后内存和磁盘字节保持上一次提交。 */
    @Test fun staleRevisionAndIdentityChange_areRejectedWithoutWrites() = runTest {
        val file = RecordingFile()
        val repository = open(file)
        val first = repository.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
        val second = repository.save(first.profileId, first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, first.revision).requireSuccess()
        val bytes = file.bytes!!.copyOf()
        assertEquals(WriteResult.Failure(WriteError.Conflict),
            repository.save(first.profileId, first.core.testDocument(), first.vpnPolicy, first.revision))
        assertEquals(WriteResult.Failure(WriteError.ValidationError), repository.save(first.profileId, second.core.copy(instanceId = CoreInstanceId("another-identity")).testDocument(), second.vpnPolicy, second.revision))
        assertArrayEquals(bytes, file.bytes)
        assertEquals(second, repository.findSaved(first.profileId))
    }

    /** 两份基于同一修订的并发保存只能提交一份；另一份收到 Conflict，不能悄然覆盖成功者。 */
    @Test fun concurrentSaves_haveOneCommitAndOneConflict() = runTest {
        val file = RecordingFile()
        val repository = open(file)
        val first = repository.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
        val results = coroutineScope {
            val a = async { repository.save(first.profileId, first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, first.revision) }
            val b = async { repository.save(first.profileId, first.core.copy(mtu = 1450).testDocument(), first.vpnPolicy, first.revision) }
            listOf(a.await(), b.await())
        }
        assertEquals(1, results.count { it is WriteResult.Success })
        assertEquals(1, results.count { it == WriteResult.Failure(WriteError.Conflict) })
        val saved = (results.first { it is WriteResult.Success } as WriteResult.Success).value
        assertEquals(saved, repository.findSaved(first.profileId))
        assertEquals(saved, open(file).findSaved(first.profileId))
    }

    /** 既有修订已达 Long 上限时仍允许无改动保存，实际变化返回 RevisionExhausted 并保持原件。 */
    @Test fun exhaustedRevision_neverWrapsOrOverwrites() = runTest {
        val tree = JsonParser.parseString(javaClass.getResourceAsStream("/profiles/schema-1.json")!!.use { it.reader().readText() }).asJsonObject
        tree.getAsJsonArray("profiles")[0].asJsonObject.addProperty("configRevision", Long.MAX_VALUE)
        val file = RecordingFile(tree.toString().toByteArray())
        val repository = open(file)
        val first = repository.profiles.value.single().saved
        val bytes = file.bytes!!.copyOf()
        assertEquals(first, repository.save(first.profileId, first.document, first.vpnPolicy, first.revision).requireSuccess())
        assertEquals(WriteResult.Failure(WriteError.RevisionExhausted),
            repository.save(first.profileId, first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, first.revision))
        assertArrayEquals(bytes, file.bytes)
    }

    /** 使用 JVM 合成 AES 密钥写真实原子文件，密文不含密码且随机 IV 不同；篡改后拒绝解密并保留密文。 */
    @Test fun encryptedFile_authenticatesContentAndUsesFreshNonce() {
        val directory = Files.createTempDirectory("p03-profile-test").toFile()
        try {
            val target = File(directory, "profiles.bin")
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val file = EncryptedProfileFile(target) { key }
            val secret = "synthetic-p03-secret".toByteArray()
            file.write(secret)
            val first = target.readBytes()
            assertFalse(first.toString(Charsets.UTF_8).contains("synthetic-p03-secret"))
            assertArrayEquals(secret, file.read())
            file.write(secret)
            assertFalse(first.contentEquals(target.readBytes()))
            val corrupted = target.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            target.writeBytes(corrupted)
            assertThrows(ProfileStorageException::class.java) { file.read() }
            assertArrayEquals(corrupted, target.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
    /** 最后连接目标只经明确提交更新；普通保存和改名不改变目标，删除同事务清引用，失败保持旧目标。 */
    @Test fun connectionTarget_isAtomicAndIndependentFromViewingAndSaving() = runTest {
        val file = RecordingFile(); val repository = open(file)
        val a = repository.create(core("A").testDocument(), AndroidVpnPolicy()).requireSuccess()
        val b = repository.create(core("B").testDocument(), AndroidVpnPolicy()).requireSuccess()
        assertNull(repository.lastConnectionTarget)
        repository.rememberConnection(a).requireSuccess()
        repository.rename(b.profileId, "新名").requireSuccess()
        repository.save(b.profileId, b.core.copy(mtu = 1400).testDocument(), b.vpnPolicy, 1).requireSuccess()
        assertEquals(a.profileId, open(file).lastConnectionTarget)
        file.failWrite = true
        assertEquals(WriteResult.Failure(WriteError.WriteFailed), repository.delete(a.profileId))
        assertEquals(a.profileId, repository.lastConnectionTarget)
        assertNotNull(repository.findSaved(a.profileId))
        file.failWrite = false; repository.delete(a.profileId).requireSuccess()
        assertNull(repository.lastConnectionTarget)
        assertNull(open(file).lastConnectionTarget)
        assertNull(open(file).findSaved(a.profileId))
    }

    /** 只改 TOML 排版仍保存原文，但不增加修订；未知字段在仓库重建后继续保留。 */
    @Test fun formattingOnlySave_keepsRevisionAndUnknownSource() = runTest {
        val file = RecordingFile(); val repository = open(file)
        val raw = "dhcp=true\n[network_identity]\nnetwork_name='network'\n[future]\nenabled=false\n"
        val document = (TestCoreConfigCodec().parse(raw, CoreInstanceId("synthetic")) as ConfigResult.Success).value
        val first = repository.create(document, AndroidVpnPolicy()).requireSuccess()
        val formatted = (TestCoreConfigCodec().parse("# formatting\n" + raw, first.core.instanceId) as ConfigResult.Success).value
        val saved = repository.save(first.profileId, formatted, first.vpnPolicy, first.revision).requireSuccess()
        assertEquals(first.revision, saved.revision)
        val restored = open(file).findSaved(first.profileId)!!
        assertEquals("# formatting\n" + raw, restored.document.raw)
        assertTrue(restored.document.raw.contains("enabled=false"))
    }

    /** 在 AtomicFile 打开、部分写入、提交前三个位置注入故障；重开仓库只能读到完整旧密文。 */
    @Test fun atomicFailureAtEachWriteStep_preservesPreviousRevision() = runTest {
        val directory = Files.createTempDirectory("p03-atomic-test").toFile()
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            for (step in listOf("open", "write", "commit")) {
                val target = File(directory, "profiles-$step.bin")
                val originalFile = EncryptedProfileFile(target) { key }
                val repository = open(originalFile)
                val first = repository.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
                val previous = target.readBytes()
                val atomic = object : android.util.AtomicFile(target) {
                    override fun startWrite(): java.io.FileOutputStream {
                        if (step == "open") throw java.io.IOException("synthetic-open")
                        val stream = super.startWrite()
                        if (step != "write") return stream
                        return object : java.io.FileOutputStream(stream.fd) {
                            override fun write(bytes: ByteArray) {
                                stream.write(bytes, 0, bytes.size / 2)
                                throw java.io.IOException("synthetic-write")
                            }
                        }
                    }
                    override fun finishWrite(stream: java.io.FileOutputStream?) {
                        if (step == "commit") throw java.io.IOException("synthetic-commit")
                        super.finishWrite(stream)
                    }
                }
                val failing = open(EncryptedProfileFile(atomic) { key })
                assertEquals(WriteResult.Failure(WriteError.WriteFailed), failing.save(first.profileId,
                    first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, first.revision))
                assertEquals(first, failing.findSaved(first.profileId))
                assertEquals(first, open(originalFile).findSaved(first.profileId))
                assertArrayEquals(previous, target.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    /** 已有文件缺密钥时只尝试读取，不生成替代密钥或覆盖字节，读取失败保留原件。 */
    @Test fun missingKey_doesNotRecreateOrOverwriteStorage() = runTest {
        val directory = Files.createTempDirectory("p03-key-test").toFile()
        try {
            val target = File(directory, "profiles.bin")
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val writer = open(EncryptedProfileFile(target) { key })
            writer.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
            val previous = target.readBytes()
            val repository = open(EncryptedProfileFile(target) { create ->
                assertEquals(ProfileKeyAccess.Existing, create)
                throw ProfileStorageException(ProfileStorageException.Reason.KeyUnavailable)
            })
            assertEquals(RepositoryState.ReadFailed(ProfileStorageException.Reason.KeyUnavailable), repository.state.value)
            assertEquals(WriteResult.Failure(WriteError.ReadUnavailable), repository.create(core().testDocument(), AndroidVpnPolicy()))
            assertArrayEquals(previous, target.readBytes())
        } finally { directory.deleteRecursively() }
    }

    /** 单文档和完整 JSON 分别执行上限保护；超限写入不截断、不改变旧快照和文件。 */
    @Test fun storageLimits_rejectOversizedDocumentsAndWholeStore() = runTest {
        val file = RecordingFile(); val repository = open(file)
        val first = repository.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
        val previous = file.bytes!!.copyOf()
        val oversized = CoreConfigDocument("#".repeat(CoreConfigDocument.MAX_BYTES + 1), first.core, "oversized", emptyList())
        assertEquals(WriteResult.Failure(WriteError.DocumentTooLarge), repository.save(first.profileId, oversized, first.vpnPolicy, 1))
        assertArrayEquals(previous, file.bytes)
        assertEquals(first, repository.findSaved(first.profileId))
        val tree = JsonParser.parseString(previous.toString(Charsets.UTF_8)).asJsonObject
        tree.addProperty("future", "x".repeat(ProfileDocumentCodec.MAX_BYTES))
        val document = ProfileDocument(tree, repository.profiles.value)
        assertEquals(ProfileStorageException.Reason.StoreTooLarge,
            assertThrows(ProfileStorageException::class.java) { ProfileDocumentCodec.encode(document, document.profiles) }.reason)
    }

    /** 已加载密文后密钥丢失，保存不得生成新密钥；旧快照和密文保留，恢复密钥后可重读旧修订。 */
    @Test fun keyLostAfterLoad_blocksWriteAndKeepsLastSnapshot() = runTest {
        val directory = Files.createTempDirectory("p03-key-loss").toFile()
        try {
            val target = File(directory, "profiles.bin")
            var current: javax.crypto.SecretKey? = null
            var creations = 0
            val provider = ProfileKeyProvider { access ->
                if (current == null && access == ProfileKeyAccess.InitializeNewStore) {
                    creations++; current = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                }
                current ?: throw ProfileStorageException(ProfileStorageException.Reason.KeyUnavailable)
            }
            val repository = open(EncryptedProfileFile(target, provider))
            val first = repository.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
            val original = target.readBytes(); val published = repository.profiles.value; val savedKey = current
            current = null
            assertEquals(WriteResult.Failure(WriteError.KeyUnavailable), repository.save(first.profileId,
                first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, first.revision))
            assertEquals(1, creations); assertArrayEquals(original, target.readBytes())
            assertEquals(published, repository.profiles.value)
            assertEquals(RepositoryState.ReadFailed(ProfileStorageException.Reason.KeyUnavailable), repository.state.value)
            assertEquals(WriteResult.Failure(WriteError.ReadUnavailable), repository.rename(first.profileId, "新名"))
            current = savedKey; repository.load()
            assertEquals(RepositoryState.Ready, repository.state.value); assertEquals(first, repository.findSaved(first.profileId))
        } finally { directory.deleteRecursively() }
    }

    /** 只有备份密文时仍需原密钥，写入在打开 AtomicFile 前失败且备份字节不变。 */
    @Test fun backupOnlyStore_neverInitializesReplacementKey() {
        val directory = Files.createTempDirectory("p03-key-backup").toFile()
        try {
            val target = File(directory, "profiles.bin")
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            EncryptedProfileFile(target) { key }.write("synthetic".toByteArray())
            val backup = File(target.path + ".bak"); assertTrue(target.renameTo(backup)); val original = backup.readBytes()
            val file = EncryptedProfileFile(target) { access ->
                assertEquals(ProfileKeyAccess.Existing, access)
                throw ProfileStorageException(ProfileStorageException.Reason.KeyUnavailable)
            }
            assertThrows(ProfileStorageException::class.java) { file.write("next".toByteArray()) }
            assertArrayEquals(original, backup.readBytes()); assertFalse(target.exists())
        } finally { directory.deleteRecursively() }
    }

    /** 首次提交中断后复用已有密钥重试，生成新 IV，只有成功提交才发布一份配置。 */
    @Test fun interruptedFirstWrite_retriesWithSameKeyAndFreshIv() = runTest {
        val directory = Files.createTempDirectory("p03-first-write").toFile()
        try {
            val target = File(directory, "profiles.bin")
            var fail = true; var interrupted: ByteArray? = null
            val atomic = object : android.util.AtomicFile(target) {
                override fun finishWrite(stream: java.io.FileOutputStream?) {
                    if (fail) { interrupted = File(target.path + ".new").readBytes(); throw java.io.IOException("synthetic") }
                    super.finishWrite(stream)
                }
            }
            var current: javax.crypto.SecretKey? = null; var creations = 0
            val file = EncryptedProfileFile(atomic) { access ->
                if (current == null) {
                    assertEquals(ProfileKeyAccess.InitializeNewStore, access); creations++
                    current = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                }
                current!!
            }
            val repository = open(file)
            assertEquals(WriteResult.Failure(WriteError.WriteFailed), repository.create(core().testDocument(), AndroidVpnPolicy()))
            assertTrue(repository.profiles.value.isEmpty())
            fail = false; repository.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
            assertEquals(1, creations); assertEquals(1, repository.profiles.value.size)
            assertFalse(interrupted!!.copyOfRange(4, 16).contentEquals(target.readBytes().copyOfRange(4, 16)))
        } finally { directory.deleteRecursively() }
    }

    /** 候选提交不重新解析 TOML；改名和连接目标写入零解析，重开仓库按配置数量解析且恢复相同内容。 */
    @Test fun candidateCommit_avoidsWholeStoreReparse() = runTest {
        var parses = 0
        val codec = object : CoreConfigCodec by TestCoreConfigCodec() {
            override suspend fun parse(raw: String, identity: CoreInstanceId): ConfigResult<CoreConfigDocument> {
                parses++; return TestCoreConfigCodec().parse(raw, identity)
            }
        }
        val file = RecordingFile()
        val repository = PersistentProfileRepository(file, codec = codec).also { it.load() }
        val first = repository.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
        repository.create(core("B").testDocument(), AndroidVpnPolicy()).requireSuccess()
        repository.rename(first.profileId, "改名").requireSuccess(); repository.rememberConnection(first).requireSuccess()
        val next = repository.save(first.profileId, first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, 1).requireSuccess()
        assertEquals(0, parses)
        val reopened = PersistentProfileRepository(file, codec = codec).also { it.load() }
        assertEquals(2, parses); assertEquals(next, reopened.findSaved(first.profileId))
        assertEquals(first.profileId, reopened.lastConnectionTarget); assertEquals("改名", reopened.profiles.value.first().displayName)
    }

    /** 候选树和编码字节均防御性复制，修改调用方返回值不能更改下一次提交所依据的快照。 */
    @Test fun preparedCommit_doesNotExposeMutableTreeOrBytes() = runTest {
        val file = RecordingFile(); val repository = open(file)
        repository.create(core().testDocument(), AndroidVpnPolicy()).requireSuccess()
        val loaded = ProfileDocumentCodec.decode(file.bytes!!, TestCoreConfigCodec())
        val prepared = ProfileDocumentCodec.prepare(loaded, loaded.profiles)
        val bytes = prepared.bytes(); bytes.fill(0)
        val tree = prepared.snapshot.copyTree(); tree.addProperty("schemaVersion", 99)
        val restored = ProfileDocumentCodec.decode(prepared.bytes(), TestCoreConfigCodec())
        assertEquals(loaded.profiles, restored.profiles)
        assertEquals(1, prepared.snapshot.copyTree()["schemaVersion"].asInt)
        assertThrows(UnsupportedOperationException::class.java) { (prepared.snapshot.profiles as MutableList).clear() }
    }

    /** 密钥提供者返回无效 AES 长度时，读写均报告 KeyUnavailable，原密文不被覆盖；不代替设备 Keystore 实测。 */
    @Test fun unusableCipherKey_isReportedWithoutOverwritingCiphertext() {
        val directory = Files.createTempDirectory("p03-invalid-key").toFile()
        try {
            val target = File(directory, "profiles.bin")
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            EncryptedProfileFile(target) { key }.write("synthetic".toByteArray())
            val bytes = target.readBytes()
            val invalid = EncryptedProfileFile(target) { javax.crypto.spec.SecretKeySpec(ByteArray(1), "AES") }
            assertEquals(ProfileStorageException.Reason.KeyUnavailable, assertThrows(ProfileStorageException::class.java) { invalid.read() }.reason)
            assertEquals(ProfileStorageException.Reason.KeyUnavailable, assertThrows(ProfileStorageException::class.java) { invalid.write("next".toByteArray()) }.reason)
            assertArrayEquals(bytes, target.readBytes())
        } finally { directory.deleteRecursively() }
    }

}
