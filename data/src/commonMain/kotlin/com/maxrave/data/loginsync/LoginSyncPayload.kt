package com.maxrave.data.loginsync

import com.maxrave.domain.data.model.loginsync.SettingsBundle
import com.maxrave.domain.data.model.loginsync.SyncDirection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * What travels inside the loginSync service's sealed payload. The service only moves bytes; these are
 * the sign-ins and cross-platform settings those bytes mean.
 */

internal val loginSyncJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class LoginBundle(
    val youtube: YouTubeLogin? = null,
    val spotifySpDc: String? = null,
    val discordToken: String? = null,
    val lastfm: LastfmLogin? = null,
    val settings: SettingsBundle? = null,
    val direction: SyncDirection = SyncDirection.PUSH,
    val requestedLogins: List<String> = emptyList(),
    val requestedSettings: List<String> = emptyList(),
)

@Serializable
internal data class YouTubeLogin(
    val accounts: List<GoogleAccountWire>,
    val cookie: String,
    val pageId: String?,
    val authUser: Int,
    val dataSyncId: String,
)

/** GoogleAccountEntity on the wire — the Room entity itself is not serializable. */
@Serializable
internal data class GoogleAccountWire(
    val email: String,
    val name: String,
    val thumbnailUrl: String,
    val pageId: String?,
    val authUser: Int,
    val cache: String?,
    val isUsed: Boolean,
    val netscapeCookie: String?,
)

@Serializable
internal data class LastfmLogin(
    val sessionKey: String,
    val username: String,
)

/** Service names and settings rather than the domain enum, so the payload does not depend on its class shape. */
@Serializable
internal data class LoginSyncAck(
    val services: List<String> = emptyList(),
    val settings: List<String> = emptyList(),
    val direction: SyncDirection = SyncDirection.PUSH,
    val returnLogins: LoginBundle? = null,
    val returnSettings: SettingsBundle? = null,
)
