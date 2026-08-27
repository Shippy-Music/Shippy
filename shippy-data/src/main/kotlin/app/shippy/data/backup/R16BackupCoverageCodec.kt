/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupCoverageCodec.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.shippy.data.backup

import org.json.JSONObject

/** Strict codec for the two small app-owned payloads carried beside R16 table rows. */
internal object R16BackupCoverageCodec {
    fun encodePortableSettings(snapshot: R16PortableSettingsSnapshot): ByteArray {
        val settings = encodeValues(snapshot.values, R16BackupAllowlist.portableSettingKinds)
        return bounded(
            JSONObject()
                .put("format", PORTABLE_FORMAT)
                .put("formatVersion", FORMAT_VERSION)
                .put("settings", settings)
                .toString()
                .toByteArray(Charsets.UTF_8)
        )
    }

    fun decodePortableSettings(bytes: ByteArray): R16PortableSettingsSnapshot {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Portable settings payload is too large" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.keys().asSequence().toSet() == setOf("format", "formatVersion", "settings")) {
            "Portable settings payload contains unknown fields"
        }
        require(json.getString("format") == PORTABLE_FORMAT) {
            "Portable settings payload format is unsupported"
        }
        require(json.getInt("formatVersion") == FORMAT_VERSION) {
            "Portable settings payload version is unsupported"
        }
        return R16PortableSettingsSnapshot(
            decodeValues(json.getJSONObject("settings"), R16BackupAllowlist.portableSettingKinds)
        )
    }

    fun encodeLastFmConfig(config: R16SanitizedLastFmConfig): ByteArray {
        config.username?.let(::validateText)
        val preferences = encodeValues(config.preferences, R16BackupAllowlist.lastFmPreferenceKinds)
        return bounded(
            JSONObject()
                .put("format", LASTFM_FORMAT)
                .put("formatVersion", FORMAT_VERSION)
                .put("username", config.username ?: JSONObject.NULL)
                .put("preferences", preferences)
                .toString()
                .toByteArray(Charsets.UTF_8)
        )
    }

    fun decodeLastFmConfig(bytes: ByteArray): R16SanitizedLastFmConfig {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Last.fm config payload is too large" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(
            json.keys().asSequence().toSet() ==
                setOf("format", "formatVersion", "username", "preferences")
        ) {
            "Last.fm config payload contains unknown fields"
        }
        require(json.getString("format") == LASTFM_FORMAT) {
            "Last.fm config payload format is unsupported"
        }
        require(json.getInt("formatVersion") == FORMAT_VERSION) {
            "Last.fm config payload version is unsupported"
        }
        val username =
            json.get("username").let { raw ->
                if (raw === JSONObject.NULL) null
                else
                    raw.also { require(it is String) { "Last.fm username must be text or null" } }
                        .toString()
                        .also(::validateText)
            }
        return R16SanitizedLastFmConfig(
            username = username,
            preferences =
                decodeValues(
                    json.getJSONObject("preferences"),
                    R16BackupAllowlist.lastFmPreferenceKinds,
                ),
        )
    }

    private fun encodeValues(
        values: Map<String, R16BackupValue>,
        allowlist: Map<String, R16BackupValueKind>,
    ): JSONObject {
        require(values.size <= MAX_VALUE_COUNT) { "Backup snapshot contains too many values" }
        val output = JSONObject()
        values.toSortedMap().forEach { (key, value) ->
            val expectedKind = validateKey(key, allowlist)
            require(valueKind(value) == expectedKind) {
                "Backup snapshot value type does not match $key"
            }
            output.put(key, encodeValue(value, key))
        }
        return output
    }

    private fun decodeValues(
        json: JSONObject,
        allowlist: Map<String, R16BackupValueKind>,
    ): Map<String, R16BackupValue> {
        require(json.length() <= MAX_VALUE_COUNT) { "Backup snapshot contains too many values" }
        return json.keys().asSequence().toList().sorted().associateWith { key ->
            val expectedKind = validateKey(key, allowlist)
            decodeValue(json.getJSONObject(key), key).also { value ->
                require(valueKind(value) == expectedKind) {
                    "Backup snapshot value type does not match $key"
                }
            }
        }
    }

    private fun validateKey(
        key: String,
        allowlist: Map<String, R16BackupValueKind>,
    ): R16BackupValueKind {
        require(key.isNotBlank() && key.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES) {
            "Backup snapshot key is invalid"
        }
        require(!SECRET_LIKE_KEY.matches(key)) {
            "Backup snapshot contains a secret-like key: $key"
        }
        return requireNotNull(allowlist[key]) { "Backup snapshot contains an unknown key: $key" }
    }

    private fun valueKind(value: R16BackupValue): R16BackupValueKind =
        when (value) {
            is R16BackupValue.Text -> R16BackupValueKind.TEXT
            is R16BackupValue.BooleanValue -> R16BackupValueKind.BOOLEAN
            is R16BackupValue.LongValue -> R16BackupValueKind.LONG
            is R16BackupValue.DoubleValue -> R16BackupValueKind.DOUBLE
        }

    private fun encodeValue(value: R16BackupValue, key: String): JSONObject =
        when (value) {
            is R16BackupValue.Text -> {
                validateText(value.value)
                typedValue("string", value.value)
            }
            is R16BackupValue.BooleanValue -> typedValue("boolean", value.value)
            is R16BackupValue.LongValue -> typedValue("long", value.value)
            is R16BackupValue.DoubleValue -> {
                require(value.value.isFinite()) { "Backup snapshot number is not finite: $key" }
                typedValue("double", value.value)
            }
        }

    private fun decodeValue(json: JSONObject, key: String): R16BackupValue {
        require(json.keys().asSequence().toSet() == setOf("type", "value")) {
            "Backup snapshot value contains unknown fields: $key"
        }
        val raw = json.get("value")
        require(raw !== JSONObject.NULL) { "Backup snapshot value cannot be null: $key" }
        return when (val type = json.getString("type")) {
            "string" -> {
                require(raw is String) { "Backup snapshot value is not text: $key" }
                validateText(raw)
                R16BackupValue.Text(raw)
            }
            "boolean" -> {
                require(raw is Boolean) { "Backup snapshot value is not boolean: $key" }
                R16BackupValue.BooleanValue(raw)
            }
            "long" -> {
                require(raw is Byte || raw is Short || raw is Int || raw is Long) {
                    "Backup snapshot value is not an integer: $key"
                }
                R16BackupValue.LongValue((raw as Number).toLong())
            }
            "double" -> {
                require(raw is Number) { "Backup snapshot value is not numeric: $key" }
                val value = raw.toDouble()
                require(value.isFinite()) { "Backup snapshot number is not finite: $key" }
                R16BackupValue.DoubleValue(value)
            }
            else -> error("Backup snapshot value type is unsupported: $type")
        }
    }

    private fun typedValue(type: String, value: Any): JSONObject =
        JSONObject().put("type", type).put("value", value)

    private fun validateText(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
            "Backup snapshot text is too large"
        }
        require(value.none(Char::isISOControl)) {
            "Backup snapshot text contains a control character"
        }
    }

    private fun bounded(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Backup snapshot payload is too large" }
        return bytes
    }

    private const val PORTABLE_FORMAT = "ShippyPortableSettingsV1"
    private const val LASTFM_FORMAT = "ShippySanitizedLastFmConfigV1"
    private const val FORMAT_VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 256 * 1024
    private const val MAX_VALUE_COUNT = 64
    private const val MAX_KEY_BYTES = 128
    private const val MAX_TEXT_BYTES = 4 * 1024
    private val SECRET_LIKE_KEY =
        Regex(
            "(?i)(password|passwd|secret|session|token|cookie|authorization|auth|api[-_]key|credential|private)"
        )
}
