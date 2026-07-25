/*
 * Copyright (c) 2026 Shippy contributors
 * CrewInviteCodec.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.invite

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

private const val DEEP_LINK_PREFIX = "shippy://crew/invite/"
private const val FORMAT_VERSION = 1
private const val CHECKSUM_BYTES = 16
private const val MAX_DEEP_LINK_LENGTH = 2_048
private const val MAX_PAYLOAD_BYTES = 768
private const val MAX_SESSION_LOCATOR_LENGTH = 128
private const val MAX_INVITE_ID_LENGTH = 64
private const val MAX_SECRET_LENGTH = 128
private const val MAX_RELAY_LOCATOR_LENGTH = 256
private const val MIN_SECRET_LENGTH = 22
private const val DEFAULT_MAX_INVITE_LIFETIME_MS = 15 * 60 * 1000L
private const val DEFAULT_CLOCK_SKEW_TOLERANCE_MS = 2 * 60 * 1000L
private val OPAQUE_ID_PATTERN = Regex("[A-Za-z0-9_-]+")
private val RAW_IPV4_PATTERN = Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")

@JvmInline
value class CrewSessionLocator(val value: String) {
    init {
        requireOpaqueIdentifier(value, "Session locator", MAX_SESSION_LOCATOR_LENGTH)
    }
}

@JvmInline
value class CrewInviteId(val value: String) {
    init {
        requireOpaqueIdentifier(value, "Invite ID", MAX_INVITE_ID_LENGTH)
    }
}

/** Secret is transport-authentication material and must never appear in logs or diagnostics. */
@JvmInline
value class CrewInviteSecret(val value: String) {
    init {
        requireOpaqueIdentifier(
            value,
            "Invite secret",
            MAX_SECRET_LENGTH,
            minimumLength = MIN_SECRET_LENGTH,
        )
    }

    override fun toString() = "CrewInviteSecret(redacted)"
}

@JvmInline
value class CrewRelayLocator(val value: String) {
    init {
        validateRelayLocator(value)
    }
}

data class CrewInvite(
    val protocolVersion: ProtocolVersion,
    /** Opaque rendezvous/session identifier, never a device address. */
    val sessionLocator: CrewSessionLocator,
    val inviteId: CrewInviteId,
    val secret: CrewInviteSecret,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val relayLocator: CrewRelayLocator? = null,
) {
    init {
        require(issuedAtEpochMs >= 0) { "Invite issue time cannot be negative" }
        require(expiresAtEpochMs > issuedAtEpochMs) { "Invite expiry must follow issue time" }
    }
}

sealed interface CrewInviteDecodeResult {
    data class Accepted(val invite: CrewInvite) : CrewInviteDecodeResult

    enum class Rejected : CrewInviteDecodeResult {
        MALFORMED,
        CORRUPTED,
        UNSUPPORTED_FORMAT,
        UNSUPPORTED_PROTOCOL,
        EXPIRED,
        INVALID_LIFETIME,
    }
}

/**
 * Pure link/QR codec. Its checksum detects accidental corruption only; the invitation secret
 * remains the credential used by the later authenticated join protocol.
 */
class CrewInviteCodec(
    private val supportedProtocolVersions: Set<ProtocolVersion>,
    private val maxInviteLifetimeMs: Long = DEFAULT_MAX_INVITE_LIFETIME_MS,
    private val clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_TOLERANCE_MS,
) {
    init {
        require(supportedProtocolVersions.isNotEmpty()) {
            "At least one Crew protocol version must be supported"
        }
        require(maxInviteLifetimeMs in 1..(24 * 60 * 60 * 1000L)) {
            "Maximum invite lifetime must be positive and bounded"
        }
        require(clockSkewToleranceMs in 0..(10 * 60 * 1000L)) {
            "Invite clock-skew tolerance must be non-negative and bounded"
        }
    }

    fun encode(invite: CrewInvite): String {
        require(invite.protocolVersion in supportedProtocolVersions) {
            "Invite protocol version is not supported by this codec"
        }
        val payload = invite.toPayload()
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Invite payload is too large" }
        val checkedPayload = payload + checksum(payload)
        return DEEP_LINK_PREFIX + UrlSafeBase64.encode(checkedPayload)
    }

    fun decode(link: String, nowEpochMs: Long): CrewInviteDecodeResult {
        if (nowEpochMs < 0 || link.length > MAX_DEEP_LINK_LENGTH || !link.startsWith(DEEP_LINK_PREFIX)) {
            return CrewInviteDecodeResult.Rejected.MALFORMED
        }
        val encodedPayload = link.removePrefix(DEEP_LINK_PREFIX)
        val checkedPayload = UrlSafeBase64.decode(encodedPayload) ?: return CrewInviteDecodeResult.Rejected.MALFORMED
        if (checkedPayload.size !in (CHECKSUM_BYTES + 1)..(MAX_PAYLOAD_BYTES + CHECKSUM_BYTES)) {
            return CrewInviteDecodeResult.Rejected.MALFORMED
        }
        val payload = checkedPayload.copyOfRange(0, checkedPayload.size - CHECKSUM_BYTES)
        val checksum = checkedPayload.copyOfRange(checkedPayload.size - CHECKSUM_BYTES, checkedPayload.size)
        if (!MessageDigest.isEqual(checksum(payload), checksum)) {
            return CrewInviteDecodeResult.Rejected.CORRUPTED
        }

        val invite =
            try {
                payload.toInvite()
            } catch (_: UnsupportedInviteFormatException) {
                return CrewInviteDecodeResult.Rejected.UNSUPPORTED_FORMAT
            } catch (_: IllegalArgumentException) {
                return CrewInviteDecodeResult.Rejected.MALFORMED
            } catch (_: EOFException) {
                return CrewInviteDecodeResult.Rejected.MALFORMED
            } catch (_: Exception) {
                return CrewInviteDecodeResult.Rejected.MALFORMED
            }
        if (invite.protocolVersion !in supportedProtocolVersions) {
            return CrewInviteDecodeResult.Rejected.UNSUPPORTED_PROTOCOL
        }
        if (
            invite.issuedAtEpochMs - nowEpochMs > clockSkewToleranceMs ||
                invite.expiresAtEpochMs - invite.issuedAtEpochMs > maxInviteLifetimeMs
        ) {
            return CrewInviteDecodeResult.Rejected.INVALID_LIFETIME
        }
        if (nowEpochMs >= invite.expiresAtEpochMs) {
            return CrewInviteDecodeResult.Rejected.EXPIRED
        }
        return CrewInviteDecodeResult.Accepted(invite)
    }

    private fun CrewInvite.toPayload(): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(FORMAT_VERSION)
                output.writeInt(protocolVersion.value)
                output.writeLong(issuedAtEpochMs)
                output.writeLong(expiresAtEpochMs)
                output.writeSizedString(sessionLocator.value, MAX_SESSION_LOCATOR_LENGTH)
                output.writeSizedString(inviteId.value, MAX_INVITE_ID_LENGTH)
                output.writeSizedString(secret.value, MAX_SECRET_LENGTH)
                output.writeBoolean(relayLocator != null)
                relayLocator?.let { output.writeSizedString(it.value, MAX_RELAY_LOCATOR_LENGTH) }
            }
            bytes.toByteArray()
        }

    private fun ByteArray.toInvite(): CrewInvite =
        DataInputStream(ByteArrayInputStream(this)).use { input ->
            val formatVersion = input.readUnsignedByte()
            if (formatVersion != FORMAT_VERSION) throw UnsupportedInviteFormatException()
            val protocolVersion = ProtocolVersion(input.readInt())
            val issuedAtEpochMs = input.readLong()
            val expiresAtEpochMs = input.readLong()
            val sessionLocator = CrewSessionLocator(input.readSizedString(MAX_SESSION_LOCATOR_LENGTH))
            val inviteId = CrewInviteId(input.readSizedString(MAX_INVITE_ID_LENGTH))
            val secret = CrewInviteSecret(input.readSizedString(MAX_SECRET_LENGTH))
            val relayLocator =
                when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> CrewRelayLocator(input.readSizedString(MAX_RELAY_LOCATOR_LENGTH))
                    else -> throw IllegalArgumentException("Invalid relay presence marker")
                }
            require(input.available() == 0) { "Invite payload has trailing data" }
            CrewInvite(
                protocolVersion,
                sessionLocator,
                inviteId,
                secret,
                issuedAtEpochMs,
                expiresAtEpochMs,
                relayLocator,
            )
        }

    private fun DataOutputStream.writeSizedString(value: String, maxLength: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= maxLength) { "Invite value is too large" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readSizedString(maxLength: Int): String {
        val size = readUnsignedShort()
        require(size in 1..maxLength) { "Invalid invite value size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        val value = bytes.toString(StandardCharsets.UTF_8)
        require(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            "Invite value is not valid UTF-8"
        }
        return value
    }

    private fun checksum(payload: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(payload).copyOf(CHECKSUM_BYTES)
}

private fun requireOpaqueIdentifier(
    value: String,
    label: String,
    maxLength: Int,
    minimumLength: Int = 8,
) {
    require(value.length in minimumLength..maxLength && OPAQUE_ID_PATTERN.matches(value)) {
        "$label must be a URL-safe opaque identifier"
    }
}

private fun validateRelayLocator(value: String) {
    require(value.length <= MAX_RELAY_LOCATOR_LENGTH) { "Relay locator is too long" }
    val uri =
        try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Relay locator is invalid", error)
        }
    val host = uri.host ?: throw IllegalArgumentException("Relay locator must have a host")
    val normalizedHost = host.lowercase()
    require(uri.scheme == "https") { "Relay locator must use HTTPS" }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Relay locator must not contain credentials, query, or fragment"
    }
    require(
        '.' in normalizedHost &&
            normalizedHost != "localhost" &&
            !normalizedHost.endsWith(".local") &&
            !RAW_IPV4_PATTERN.matches(normalizedHost) &&
            ':' !in normalizedHost
    ) {
        "Relay locator must not contain a raw device address"
    }
}

private class UnsupportedInviteFormatException : IllegalArgumentException()

/** Small dependency-free RFC 4648 base64url codec without padding. */
private object UrlSafeBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(input: ByteArray): String {
        val output = StringBuilder((input.size * 4 + 2) / 3)
        var index = 0
        while (index + 2 < input.size) {
            val block =
                ((input[index++].toInt() and 0xFF) shl 16) or
                    ((input[index++].toInt() and 0xFF) shl 8) or
                    (input[index++].toInt() and 0xFF)
            output.append(ALPHABET[block ushr 18])
            output.append(ALPHABET[(block ushr 12) and 0x3F])
            output.append(ALPHABET[(block ushr 6) and 0x3F])
            output.append(ALPHABET[block and 0x3F])
        }
        when (input.size - index) {
            1 -> {
                val block = (input[index].toInt() and 0xFF) shl 16
                output.append(ALPHABET[block ushr 18])
                output.append(ALPHABET[(block ushr 12) and 0x3F])
            }
            2 -> {
                val block =
                    ((input[index].toInt() and 0xFF) shl 16) or
                        ((input[index + 1].toInt() and 0xFF) shl 8)
                output.append(ALPHABET[block ushr 18])
                output.append(ALPHABET[(block ushr 12) and 0x3F])
                output.append(ALPHABET[(block ushr 6) and 0x3F])
            }
        }
        return output.toString()
    }

    fun decode(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 4 == 1) return null
        val output = ByteArrayOutputStream(value.length * 3 / 4)
        var accumulator = 0
        var bits = 0
        value.forEach { character ->
            val index = ALPHABET.indexOf(character)
            if (index < 0) return null
            accumulator = (accumulator shl 6) or index
            bits += 6
            while (bits >= 8) {
                bits -= 8
                output.write(accumulator ushr bits)
            }
        }
        if (bits > 0 && accumulator and ((1 shl bits) - 1) != 0) return null
        return output.toByteArray()
    }
}
