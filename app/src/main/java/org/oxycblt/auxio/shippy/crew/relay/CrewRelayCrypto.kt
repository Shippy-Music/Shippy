/*
 * Copyright (c) 2026 Shippy contributors
 * CrewRelayCrypto.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.relay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite

private const val GCM_TAG_BITS = 128
private const val GCM_TAG_BYTES = 16
private const val MAX_HELLO_BYTES = 512

internal enum class CrewRelayDirection(val label: String) {
    HOST_TO_JOIN("host-to-join"),
    JOIN_TO_HOST("join-to-host"),
}

/** Per-route directional AEAD with exact receive sequencing. */
internal class CrewRelayRouteCrypto(
    invite: CrewInvite,
    private val route: CrewRelayRouteId,
    localRole: CrewRelayRole,
) {
    private val identity = canonicalIdentity(invite)
    private val identityHash = sha256(identity)
    private val salt = sha256(identity + route.bytes)
    private val sendDirection =
        if (localRole == CrewRelayRole.HOST) CrewRelayDirection.HOST_TO_JOIN else CrewRelayDirection.JOIN_TO_HOST
    private val receiveDirection =
        if (localRole == CrewRelayRole.HOST) CrewRelayDirection.JOIN_TO_HOST else CrewRelayDirection.HOST_TO_JOIN
    private val secret = invite.secret.value.toByteArray(Charsets.UTF_8)
    private val sendKey = key(sendDirection, "key", 32)
    private val receiveKey = key(receiveDirection, "key", 32)
    private val sendPrefix = key(sendDirection, "nonce", 4)
    private val receivePrefix = key(receiveDirection, "nonce", 4)
    private var sendSequence = 0L
    private var receiveSequence = 0L

    fun encrypt(plain: ByteArray): ByteArray {
        require(plain.size <= CREW_RELAY_MAX_DATA_BYTES - Long.SIZE_BYTES - GCM_TAG_BYTES)
        check(sendSequence != Long.MAX_VALUE) { "Relay send sequence exhausted" }
        val sequence = sendSequence++
        val encrypted =
            cipher(Cipher.ENCRYPT_MODE, sendKey, nonce(sendPrefix, sequence), aad(sendDirection, sequence))
                .doFinal(plain)
        return ByteArrayOutputStream(Long.SIZE_BYTES + encrypted.size).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeLong(sequence)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
    }

    fun decrypt(frame: ByteArray): ByteArray {
        require(frame.size in (Long.SIZE_BYTES + GCM_TAG_BYTES)..CREW_RELAY_MAX_DATA_BYTES)
        val sequence = ByteBuffer.wrap(frame, 0, Long.SIZE_BYTES).long
        require(sequence == receiveSequence && receiveSequence != Long.MAX_VALUE) { "Relay replay or out-of-order frame" }
        val plain =
            cipher(Cipher.DECRYPT_MODE, receiveKey, nonce(receivePrefix, sequence), aad(receiveDirection, sequence))
                .doFinal(frame.copyOfRange(Long.SIZE_BYTES, frame.size))
        receiveSequence++
        return plain
    }

    private fun key(direction: CrewRelayDirection, purpose: String, length: Int) =
        hkdf(secret, salt, "shippy-relay-v1/${direction.label}/$purpose".toByteArray(Charsets.US_ASCII), length)

    private fun aad(direction: CrewRelayDirection, sequence: Long) =
        identityHash + route.bytes + direction.label.toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(8).putLong(sequence).array()
}

internal data class CrewRelayHello(val memberId: String, val displayName: String)

internal object CrewRelayHelloCodec {
    fun encode(hello: CrewRelayHello): ByteArray {
        val member = hello.memberId.toByteArray(Charsets.UTF_8)
        val name = hello.displayName.toByteArray(Charsets.UTF_8)
        require(member.size in 1..128 && name.size in 1..80)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(1)
                output.writeShort(member.size)
                output.write(member)
                output.writeShort(name.size)
                output.write(name)
            }
            bytes.toByteArray().also { require(it.size <= MAX_HELLO_BYTES) }
        }
    }

    fun decode(bytes: ByteArray): CrewRelayHello {
        require(bytes.size in 6..MAX_HELLO_BYTES)
        return DataInputStream(bytes.inputStream()).use { input ->
            require(input.readUnsignedByte() == 1)
            val hello = CrewRelayHello(readUtf8(input, 128), readUtf8(input, 80))
            require(input.available() == 0)
            hello
        }
    }

    private fun readUtf8(input: DataInputStream, max: Int): String {
        val size = input.readUnsignedShort()
        require(size in 1..max)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Relay hello is not valid UTF-8")
        }
    }
}

private fun canonicalIdentity(invite: CrewInvite): ByteArray =
    ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(invite.protocolVersion.value)
            output.writeLong(invite.issuedAtEpochMs)
            output.writeLong(invite.expiresAtEpochMs)
            writeIdentityString(output, invite.sessionLocator.value)
            writeIdentityString(output, invite.inviteId.value)
            output.writeBoolean(invite.relayLocator != null)
            invite.relayLocator?.let { writeIdentityString(output, it.value) }
        }
        bytes.toByteArray()
    }

private fun writeIdentityString(output: DataOutputStream, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    output.writeShort(bytes.size)
    output.write(bytes)
}

private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    var previous = ByteArray(0)
    val result = ByteArrayOutputStream()
    var counter = 1
    while (result.size() < length) {
        mac.update(previous)
        mac.update(info)
        mac.update(counter.toByte())
        previous = mac.doFinal()
        result.write(previous)
        counter++
    }
    return result.toByteArray().copyOf(length)
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun nonce(prefix: ByteArray, sequence: Long) = prefix + ByteBuffer.allocate(8).putLong(sequence).array()

private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(aad)
    }
