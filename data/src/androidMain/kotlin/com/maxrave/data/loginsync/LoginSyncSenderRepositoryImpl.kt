package com.maxrave.data.loginsync

import com.maxrave.domain.data.model.loginsync.DesktopInfo
import com.maxrave.domain.data.model.loginsync.LoginSyncException
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.loginsync.SettingsCategory
import com.maxrave.domain.data.model.loginsync.SyncDirection
import com.maxrave.domain.repository.LoginSyncSenderRepository
import org.simpmusic.loginsync.LoginSyncClient
import org.simpmusic.loginsync.LoginSyncInvite
import org.simpmusic.loginsync.LoginSyncTransportException

/** Android: turns this device's sign-ins and settings into a payload and exchanges with the loginSync service. */
internal class LoginSyncSenderRepositoryImpl(
    private val store: LoginSyncStore,
    private val client: LoginSyncClient,
) : LoginSyncSenderRepository {
    override suspend fun signedInServices(): List<LoginSyncService> = store.signedIn()

    override fun isInvite(qrText: String): Boolean = LoginSyncInvite.parse(qrText) != null

    override suspend fun connect(qrText: String): DesktopInfo {
        val invite = LoginSyncInvite.parse(qrText) ?: throw LoginSyncException(LoginSyncException.Reason.FAILED, "not a login code")
        val device = transport { client.connect(invite) }
        val availableLogins = device.availableLogins.mapNotNull { name -> LoginSyncService.entries.firstOrNull { it.name == name } }
        return DesktopInfo(device.name, device.os, availableLogins, device.hasSettings)
    }

    override suspend fun send(services: Set<LoginSyncService>): List<LoginSyncService> =
        sync(SyncDirection.PUSH, services, emptySet()).first

    override suspend fun sync(
        direction: SyncDirection,
        services: Set<LoginSyncService>,
        settings: Set<SettingsCategory>,
    ): Pair<List<LoginSyncService>, List<SettingsCategory>> {
        val loginBundle = if (direction == SyncDirection.PUSH) store.bundle(services) else null
        val settingsBundle = if (direction == SyncDirection.PUSH) store.bundleSettings(settings) else null
        val bundle =
            LoginBundle(
                youtube = loginBundle?.youtube,
                spotifySpDc = loginBundle?.spotifySpDc,
                discordToken = loginBundle?.discordToken,
                lastfm = loginBundle?.lastfm,
                settings = settingsBundle,
                direction = direction,
                requestedLogins = if (direction == SyncDirection.PULL) services.map { it.name } else emptyList(),
                requestedSettings = if (direction == SyncDirection.PULL) settings.map { it.name } else emptyList(),
            )
        val payload = loginSyncJson.encodeToString(LoginBundle.serializer(), bundle).encodeToByteArray()
        val reply = transport { client.send(payload) }
        val ack = loginSyncJson.decodeFromString(LoginSyncAck.serializer(), reply.decodeToString())

        val appliedServices =
            if (direction == SyncDirection.PULL && ack.returnLogins != null) {
                store.restore(ack.returnLogins)
            } else {
                ack.services.mapNotNull { name -> LoginSyncService.entries.firstOrNull { it.name == name } }
            }

        val appliedSettings =
            if (direction == SyncDirection.PULL && ack.returnSettings != null) {
                store.restoreSettings(ack.returnSettings)
            } else {
                ack.settings.mapNotNull { name -> SettingsCategory.entries.firstOrNull { it.name == name } }
            }

        return Pair(appliedServices, appliedSettings)
    }

    override fun disconnect() = client.disconnect()

    private inline fun <T> transport(block: () -> T): T =
        try {
            block()
        } catch (e: LoginSyncTransportException) {
            val reason =
                when (e.kind) {
                    LoginSyncTransportException.Kind.UNREACHABLE -> LoginSyncException.Reason.UNREACHABLE
                    LoginSyncTransportException.Kind.REFUSED -> LoginSyncException.Reason.CODE_EXPIRED
                    LoginSyncTransportException.Kind.FAILED -> LoginSyncException.Reason.FAILED
                }
            throw LoginSyncException(reason, e.detail)
        }
}
