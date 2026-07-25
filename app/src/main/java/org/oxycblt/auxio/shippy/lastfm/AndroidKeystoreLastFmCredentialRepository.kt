package org.oxycblt.auxio.shippy.lastfm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Credentials never enter Room or preferences. A corrupt envelope is deleted and treated as signed out. */
@Singleton
class AndroidKeystoreLastFmCredentialRepository @Inject constructor(@ApplicationContext context: Context) : LastFmCredentialRepository {
    private val backingFile = File(context.applicationContext.filesDir, "lastfm-credentials.bin")
    private val file = AtomicFile(backingFile)
    private val mutex = Mutex()
    override suspend fun load(): LastFmCredentials? = mutex.withLock {
        if (!backingFile.exists()) return@withLock null
        runCatching { decode(decrypt(readBoundedEnvelope())) }.getOrElse {
            file.delete()
            null
        }
    }
    override suspend fun save(credentials: LastFmCredentials) = mutex.withLock {
        val output = file.startWrite(); try { output.write(encrypt(encode(credentials))); file.finishWrite(output) } catch (e: Throwable) { file.failWrite(output); throw e }
    }
    override suspend fun clear() = mutex.withLock { file.delete() }
    private fun encode(c: LastFmCredentials) = listOf(c.apiKey, c.apiSecret, c.sessionKey, c.username).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    private fun decode(bytes: ByteArray): LastFmCredentials { val v = bytes.toString(Charsets.UTF_8).split('\u0000'); require(v.size == 4 && v.all(String::isNotBlank)); return LastFmCredentials(v[0], v[1], v[2], v[3]) }
    private fun readBoundedEnvelope(): ByteArray {
        val length = backingFile.length()
        require(length in MIN_ENVELOPE_BYTES.toLong()..MAX_ENVELOPE_BYTES.toLong())
        return DataInputStream(file.openRead()).use { input ->
            ByteArray(length.toInt()).also {
                input.readFully(it)
                require(input.read() == -1)
            }
        }
    }
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        require(cipher.iv.size == GCM_NONCE_BYTES)
        return byteArrayOf(FORMAT_VERSION.toByte()) + cipher.iv + cipher.doFinal(plain)
    }
    private fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size >= MIN_ENVELOPE_BYTES && envelope[0].toInt() == FORMAT_VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, envelope.copyOfRange(1, 1 + GCM_NONCE_BYTES)),
        )
        cipher.updateAAD(AAD)
        return cipher.doFinal(envelope.copyOfRange(1 + GCM_NONCE_BYTES, envelope.size))
    }
    private fun key(): SecretKey { val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }; return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()) }.generateKey() }
    private companion object {
        const val ALIAS = "shippy.lastfm.credentials.v1"
        const val FORMAT_VERSION = 1
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
        const val MIN_ENVELOPE_BYTES = 1 + GCM_NONCE_BYTES + GCM_TAG_BYTES + 7
        const val MAX_ENVELOPE_BYTES = 8 * 1024
        val AAD = "shippy:lastfm:credentials:v1".toByteArray(Charsets.UTF_8)
    }
}
