/*
 * Copyright (c) 2026 Auxio Project
 * CrewProfileSettings.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.settings

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.shippy.crew.core.CrewAvatarDescriptor
import org.oxycblt.auxio.shippy.crew.core.CrewDeviceId
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewProfileId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

private const val DEFAULT_CREW_DISPLAY_NAME = "Shippy listener"
private const val MAX_CREW_DISPLAY_NAME_UTF8_BYTES = 80

/** Persistent local Crew identity and listener-controlled display name. */
interface CrewProfileSettings : Settings<CrewProfileSettings.Listener> {
    /** An opaque stable installation member identifier for the supplied protocol version. */
    fun memberId(protocolVersion: ProtocolVersion): CrewMemberId

    /** Stable profile identity, separate from the installation/device identity. */
    val profileId: CrewProfileId

    /** Installation-local identity; never included in a public Crew member payload. */
    val deviceId: CrewDeviceId

    /** The complete, deliberately small active-Crew profile snapshot. */
    fun member(protocolVersion: ProtocolVersion): CrewMember

    /** The mutable name presented to other members of a Crew. */
    var displayName: String

    /** The bounded generated avatar presented to active Crew members. */
    val avatar: CrewAvatarDescriptor

    /** Replaces the generated avatar seed without exposing a local file or account identity. */
    fun regenerateAvatar(): CrewAvatarDescriptor

    interface Listener {
        /** Called when [displayName] changes after this listener is registered. */
        fun onDisplayNameChanged(displayName: String) {}

        /** Called when the generated avatar changes after this listener is registered. */
        fun onAvatarChanged(avatar: CrewAvatarDescriptor) {}
    }
}

class CrewProfileSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<CrewProfileSettings.Listener>(context), CrewProfileSettings {
    override fun memberId(protocolVersion: ProtocolVersion): CrewMemberId =
        crewMemberId(deviceId.value, protocolVersion)

    override val profileId: CrewProfileId
        get() = CrewProfileId(profileValue())

    override val deviceId: CrewDeviceId
        get() = CrewDeviceId(installationMemberValue())

    override fun member(protocolVersion: ProtocolVersion): CrewMember =
        CrewMember(
            id = memberId(protocolVersion),
            displayName = displayName,
            profileId = profileId,
            avatar = avatar,
        )

    override val avatar: CrewAvatarDescriptor
        get() = CrewAvatarDescriptor(avatarValue())

    override fun regenerateAvatar(): CrewAvatarDescriptor {
        val next =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(UUID.randomUUID().toString().toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
        sharedPreferences.edit().putString(getString(R.string.set_key_crew_avatar), next).apply()
        return CrewAvatarDescriptor(next)
    }

    override var displayName: String
        get() =
            normalizeCrewDisplayName(
                sharedPreferences.getString(
                    getString(R.string.set_key_crew_display_name),
                    DEFAULT_CREW_DISPLAY_NAME,
                ) ?: DEFAULT_CREW_DISPLAY_NAME
            )
        set(value) {
            sharedPreferences
                .edit()
                .putString(
                    getString(R.string.set_key_crew_display_name),
                    normalizeCrewDisplayName(value),
                )
                .apply()
        }

    override fun onSettingChanged(key: String, listener: CrewProfileSettings.Listener) {
        if (key == getString(R.string.set_key_crew_display_name)) {
            listener.onDisplayNameChanged(displayName)
        }
        if (key == getString(R.string.set_key_crew_avatar)) {
            listener.onAvatarChanged(avatar)
        }
    }

    private fun installationMemberValue(): String =
        synchronized(this) {
            sharedPreferences
                .getString(getString(R.string.set_key_crew_member_id), null)
                ?.takeIf(::isUuid)
                ?: UUID.randomUUID().toString().also { memberValue ->
                    sharedPreferences
                        .edit()
                        .putString(getString(R.string.set_key_crew_member_id), memberValue)
                        .apply()
                }
        }

    private fun profileValue(): String =
        synchronized(this) {
            sharedPreferences
                .getString(getString(R.string.set_key_crew_profile_id), null)
                ?.takeIf(::isUuid)
                ?: UUID.randomUUID().toString().also { profileValue ->
                    sharedPreferences
                        .edit()
                        .putString(getString(R.string.set_key_crew_profile_id), profileValue)
                        .apply()
                }
        }

    private fun avatarValue(): String =
        synchronized(this) {
            sharedPreferences.getString(getString(R.string.set_key_crew_avatar), null)?.takeIf {
                runCatching { CrewAvatarDescriptor(it) }.isSuccess
            }
                ?: CrewAvatarDescriptor.generated(profileId).value.also { value ->
                    sharedPreferences
                        .edit()
                        .putString(getString(R.string.set_key_crew_avatar), value)
                        .apply()
                }
        }
}

internal fun normalizeCrewDisplayName(value: String): String {
    val normalized = value.trim().replace(Regex("\\s+"), " ")
    require(normalized.isNotBlank()) { "Crew display name must not be blank" }
    require(normalized.toByteArray(Charsets.UTF_8).size <= MAX_CREW_DISPLAY_NAME_UTF8_BYTES) {
        "Crew display name exceeds $MAX_CREW_DISPLAY_NAME_UTF8_BYTES UTF-8 bytes"
    }
    return normalized
}

internal fun crewMemberId(value: String, protocolVersion: ProtocolVersion) =
    CrewMemberId(value, protocolVersion)

private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

@Module
@InstallIn(SingletonComponent::class)
abstract class CrewProfileSettingsModule {
    @Binds
    @Singleton
    abstract fun settings(implementation: CrewProfileSettingsImpl): CrewProfileSettings
}
