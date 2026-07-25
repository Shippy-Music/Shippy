/*
 * Copyright (c) 2026 Shippy contributors
 * CrewRejoinLeaseStore.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.persistence.crew

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
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinLease
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinLeaseCodec
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinLeaseDecodeResult

interface CrewRejoinLeaseStore {
    suspend fun load(): CrewRejoinLease?

    /** Replaces the sole active lease; credentials for an old Crew cannot survive a new join. */
    suspend fun save(lease: CrewRejoinLease)

    /** Clears only an exact session, so stale cleanup cannot revoke a newer Crew. */
    suspend fun clear(sessionId: CrewSessionId): Boolean
}

/**
 * Single-file encrypted credential envelope. The AES-256 key is non-exportable Android Keystore
 * material; the file contains only a version, GCM nonce, and ciphertext. It is deliberately not
 * Room or SharedPreferences.
 */
@Singleton
internal class AndroidKeystoreCrewRejoinLeaseStore
@Inject
constructor(@ApplicationContext context: Context) : CrewRejoinLeaseStore {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val atomicFile = AtomicFile(file)
    private val mutex = Mutex()

    override suspend fun load(): CrewRejoinLease? =
        mutex.withLock {
            loadUnlocked()
        }

    override suspend fun save(lease: CrewRejoinLease) {
        mutex.withLock {
            val output = atomicFile.startWrite()
            try {
                output.write(encrypt(CrewRejoinLeaseCodec.encode(lease)))
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }
    }

    override suspend fun clear(sessionId: CrewSessionId): Boolean =
        mutex.withLock {
            val active = loadUnlocked() ?: return@withLock false
            if (active.sessionId != sessionId) return@withLock false
            atomicFile.delete()
            true
        }

    private fun loadUnlocked(): CrewRejoinLease? {
        if (!file.exists()) return null
        val decoded = runCatching { decrypt(readBoundedEnvelope()) }.getOrNull()
            ?.let(CrewRejoinLeaseCodec::decode)
        return (decoded as? CrewRejoinLeaseDecodeResult.Accepted)?.lease
            ?: run {
                atomicFile.delete()
                null
            }
    }

    private fun readBoundedEnvelope(): ByteArray {
        val length = file.length()
        require(length in MIN_ENVELOPE_BYTES.toLong()..MAX_ENVELOPE_BYTES.toLong()) {
            "Crew lease envelope length is invalid"
        }
        return DataInputStream(file.inputStream()).use { input ->
            ByteArray(length.toInt()).also {
                input.readFully(it)
                require(input.read() == -1) {
                    "Crew lease envelope changed while reading"
                }
            }
        }
    }

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        return byteArrayOf(FORMAT_VERSION.toByte()) + cipher.iv + cipher.doFinal(plainText)
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size > 1 + GCM_NONCE_BYTES + GCM_TAG_BYTES) { "Crew lease envelope is short" }
        require(envelope[0].toInt() == FORMAT_VERSION) { "Crew lease envelope version is invalid" }
        val nonce = envelope.copyOfRange(1, 1 + GCM_NONCE_BYTES)
        val ciphertext = envelope.copyOfRange(1 + GCM_NONCE_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    private companion object {
        const val FILE_NAME = "crew-rejoin-lease.bin"
        const val KEY_ALIAS = "shippy.crew.rejoin.lease.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
        const val MIN_ENVELOPE_BYTES = 1 + GCM_NONCE_BYTES + GCM_TAG_BYTES + 1
        const val MAX_ENVELOPE_BYTES = 4 * 1024
        val AAD = "shippy:crew:rejoin-lease:v1".toByteArray(Charsets.UTF_8)
    }
}
