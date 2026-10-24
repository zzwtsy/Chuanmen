package com.github.zzwtsy.chuanmen.lib.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 存储边界便于注入写入中断；正式实现只能保存经过认证加密的字节。 */
internal interface ProfileFile {
    fun read(): ByteArray?
    fun write(plaintext: ByteArray)
}

/** 只有不存在已保存文档的首次初始化可以创建密钥；已有密文始终要求原密钥。 */
internal enum class ProfileKeyAccess { Existing, InitializeNewStore }
internal fun interface ProfileKeyProvider { fun get(access: ProfileKeyAccess): SecretKey }

/**
 * AES-GCM 认证版本头及内容；每次写入由 provider 生成新 IV，原子替换后才确认成功。
 * 文件放 noBackupFilesDir，密钥只存在 Android Keystore；密钥失效时拒绝覆盖旧密文。
 */
internal class EncryptedProfileFile(private val atomic: AtomicFile, private val key: ProfileKeyProvider) : ProfileFile {
    constructor(file: File, key: ProfileKeyProvider) : this(AtomicFile(file), key)
    private var initialized = hasSavedFile()
    private fun hasSavedFile() = atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()

    override fun read(): ByteArray? {
        if (!hasSavedFile()) return null
        initialized = true
        return try {
            val bytes = atomic.openRead().use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > ProfileDocumentCodec.MAX_BYTES + HEADER.size + IV_SIZE + 16) {
                        throw ProfileStorageException(ProfileStorageException.Reason.StoreTooLarge)
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            require(bytes.size >= HEADER.size + IV_SIZE + 16 && bytes.take(HEADER.size).toByteArray().contentEquals(HEADER))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key.get(ProfileKeyAccess.Existing), GCMParameterSpec(128, bytes.copyOfRange(HEADER.size, HEADER.size + IV_SIZE)))
            cipher.updateAAD(HEADER)
            cipher.doFinal(bytes, HEADER.size + IV_SIZE, bytes.size - HEADER.size - IV_SIZE)
        } catch (_: java.security.InvalidKeyException) {
            throw ProfileStorageException(ProfileStorageException.Reason.KeyUnavailable)
        } catch (error: ProfileStorageException) {
            throw error
        } catch (_: Exception) {
            throw ProfileStorageException(ProfileStorageException.Reason.Read)
        }
    }

    override fun write(plaintext: ByteArray) {
        try {
            if (plaintext.size > ProfileDocumentCodec.MAX_BYTES) throw ProfileStorageException(ProfileStorageException.Reason.StoreTooLarge)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (hasSavedFile()) initialized = true
            cipher.init(Cipher.ENCRYPT_MODE, key.get(if (initialized) ProfileKeyAccess.Existing else ProfileKeyAccess.InitializeNewStore))
            require(cipher.iv.size == IV_SIZE)
            cipher.updateAAD(HEADER)
            val encrypted = HEADER + cipher.iv + cipher.doFinal(plaintext)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(encrypted)
                // finishWrite 会吞掉部分 fsync 错误；在确认前显式同步并传播失败。
                stream.fd.sync()
                atomic.finishWrite(stream)
                check(atomic.baseFile.exists() && !File(atomic.baseFile.path + ".new").exists())
                initialized = true
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        } catch (_: java.security.InvalidKeyException) {
            throw ProfileStorageException(ProfileStorageException.Reason.KeyUnavailable)
        } catch (error: ProfileStorageException) {
            throw error
        } catch (_: Exception) {
            throw ProfileStorageException(ProfileStorageException.Reason.Write)
        }
    }

    companion object {
        private val HEADER = byteArrayOf(67, 77, 80, 1)
        private const val IV_SIZE = 12
        private const val ALIAS = "chuanmen.profiles.v1"

        fun open(context: Context): EncryptedProfileFile = EncryptedProfileFile(File(context.noBackupFilesDir, "profiles.bin")) { access ->
            try {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (store.containsAlias(ALIAS)) store.getKey(ALIAS, null) as SecretKey
                else {
                    if (access != ProfileKeyAccess.InitializeNewStore) throw ProfileStorageException(ProfileStorageException.Reason.KeyUnavailable)
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).build())
                    }.generateKey()
                }
            } catch (_: Exception) {
                throw ProfileStorageException(ProfileStorageException.Reason.KeyUnavailable)
            }
        }
    }
}
