package com.maxrave.data.loginsync

import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.io.fileDir
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.model.loginsync.AppearanceSettingsBundle
import com.maxrave.domain.data.model.loginsync.AudioSettingsBundle
import com.maxrave.domain.data.model.loginsync.ContentSettingsBundle
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.loginsync.SettingsBundle
import com.maxrave.domain.data.model.loginsync.SettingsCategory
import com.maxrave.domain.data.player.ReverbPreset
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.flow.first

/** Reads this device's sign-ins, and writes a received bundle in the same shape the login screens do. */
internal class LoginSyncStore(
    private val dataStore: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val commonRepository: CommonRepository,
) {
    suspend fun signedIn(): List<LoginSyncService> =
        buildList {
            if (dataStore.loggedIn.first() == TRUE && dataStore.cookie.first().isNotEmpty()) add(LoginSyncService.YOUTUBE)
            if (dataStore.spdc.first().isNotEmpty()) add(LoginSyncService.SPOTIFY)
            if (dataStore.discordToken.first().isNotEmpty()) add(LoginSyncService.DISCORD)
            if (dataStore.lastfmSessionKey.first().isNotEmpty()) add(LoginSyncService.LASTFM)
        }

    suspend fun bundle(services: Set<LoginSyncService>): LoginBundle =
        LoginBundle(
            youtube =
                if (LoginSyncService.YOUTUBE in services) {
                    YouTubeLogin(
                        accounts = localDataSource.getGoogleAccounts().map { it.toWire() },
                        cookie = dataStore.cookie.first(),
                        pageId = dataStore.pageId.first().ifEmpty { null },
                        authUser = dataStore.authUser.first(),
                        dataSyncId = dataStore.dataSyncId.first(),
                    )
                } else {
                    null
                },
            spotifySpDc = if (LoginSyncService.SPOTIFY in services) dataStore.spdc.first() else null,
            discordToken = if (LoginSyncService.DISCORD in services) dataStore.discordToken.first() else null,
            lastfm =
                if (LoginSyncService.LASTFM in services) {
                    LastfmLogin(dataStore.lastfmSessionKey.first(), dataStore.lastfmUsername.first())
                } else {
                    null
                },
        )

    suspend fun bundleSettings(categories: Set<SettingsCategory>): SettingsBundle =
        SettingsBundle(
            audio =
                if (SettingsCategory.AUDIO in categories) {
                    AudioSettingsBundle(
                        quality = dataStore.quality.first(),
                        downloadQuality = dataStore.downloadQuality.first(),
                        normalizeVolume = dataStore.normalizeVolume.first() == TRUE,
                        skipSilent = dataStore.skipSilent.first() == TRUE,
                        radioAudioOnly = dataStore.radioAudioOnly.first() == TRUE,
                        maxParallelDownloads = dataStore.maxParallelDownloads.first(),
                        maxSongCacheSize = dataStore.maxSongCacheSize.first(),
                        crossfadeEnabled = dataStore.crossfadeEnabled.first() == TRUE,
                        crossfadeDuration = dataStore.crossfadeDuration.first(),
                        crossfadeDjMode = dataStore.crossfadeDjMode.first() == TRUE,
                        crossfadeSkipAlbum = dataStore.crossfadeSkipAlbum.first() == TRUE,
                        equalizerEnabled = dataStore.equalizerEnabled.first() == TRUE,
                        equalizerBands = dataStore.equalizerBands.first(),
                        equalizerPreamp = dataStore.equalizerPreamp.first(),
                        equalizerAutoEqProfile = dataStore.equalizerAutoEqProfile.first(),
                        delayEnabled = dataStore.delayEnabled.first() == TRUE,
                        delayTimeMs = dataStore.delayTimeMs.first(),
                        delayFeedback = dataStore.delayFeedback.first(),
                        delayMix = dataStore.delayMix.first(),
                        reverbEnabled = dataStore.reverbEnabled.first() == TRUE,
                        reverbPreset = dataStore.reverbPreset.first(),
                        reverbMix = dataStore.reverbMix.first(),
                    )
                } else {
                    null
                },
            appearance =
                if (SettingsCategory.APPEARANCE in categories) {
                    AppearanceSettingsBundle(
                        themeMode = dataStore.themeMode.first(),
                        themeColorSource = dataStore.themeColorSource.first(),
                        customThemeColor = dataStore.customThemeColor.first(),
                        nowPlayingStyle = dataStore.nowPlayingStyle.first(),
                        lyricsStyle = dataStore.lyricsStyle.first(),
                        liquidGlassEnabled = dataStore.enableLiquidGlass.first() == TRUE,
                    )
                } else {
                    null
                },
            content =
                if (SettingsCategory.CONTENT in categories) {
                    ContentSettingsBundle(
                        location = dataStore.location.first(),
                        language = dataStore.language.first(),
                        lyricsProvider = dataStore.lyricsProvider.first(),
                        enableTranslateLyric = dataStore.enableTranslateLyric.first() == TRUE,
                        translationLanguage = dataStore.translationLanguage.first(),
                        romanizationLanguages = dataStore.romanizationLanguages.first(),
                        lyricsOffsetMs = dataStore.lyricsOffsetMs.first(),
                        explicitContentEnabled = dataStore.explicitContentEnabled.first() == TRUE,
                        sponsorBlockEnabled = dataStore.sponsorBlockEnabled.first() == TRUE,
                        sponsorBlockCategories = dataStore.getSponsorBlockCategories(),
                        spotifyLyrics = dataStore.spotifyLyrics.first() == TRUE,
                        spotifyCanvas = dataStore.spotifyCanvas.first() == TRUE,
                        amAnimatedArtwork = dataStore.amAnimatedArtwork.first() == TRUE,
                        syncFollowToYouTube = dataStore.syncFollowToYouTube.first() == TRUE,
                        endlessQueue = dataStore.endlessQueue.first() == TRUE,
                        combineLocalAndYouTubeLiked = dataStore.combineLocalAndYouTubeLiked.first() == TRUE,
                        lastfmScrobbleEnabled = dataStore.lastfmScrobbleEnabled.first() == TRUE,
                        richPresenceEnabled = dataStore.richPresenceEnabled.first() == TRUE,
                    )
                } else {
                    null
                },
        )

    suspend fun restoreSettings(bundle: SettingsBundle): List<SettingsCategory> =
        buildList {
            bundle.audio?.let { audio ->
                audio.quality?.let { dataStore.setQuality(it) }
                audio.downloadQuality?.let { dataStore.setDownloadQuality(it) }
                audio.normalizeVolume?.let { dataStore.setNormalizeVolume(it) }
                audio.skipSilent?.let { dataStore.setSkipSilent(it) }
                audio.radioAudioOnly?.let { dataStore.setRadioAudioOnly(it) }
                audio.maxParallelDownloads?.let { dataStore.setMaxParallelDownloads(it) }
                audio.maxSongCacheSize?.let { dataStore.setMaxSongCacheSize(it) }
                audio.crossfadeEnabled?.let { dataStore.setCrossfadeEnabled(it) }
                audio.crossfadeDuration?.let { dataStore.setCrossfadeDuration(it) }
                audio.crossfadeDjMode?.let { dataStore.setCrossfadeDjMode(it) }
                audio.crossfadeSkipAlbum?.let { dataStore.setCrossfadeSkipAlbum(it) }
                audio.equalizerEnabled?.let { dataStore.setEqualizerEnabled(it) }
                audio.equalizerBands?.let { bandsStr ->
                    val bands = bandsStr.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (bands.isNotEmpty()) dataStore.setEqualizerBands(bands)
                }
                audio.equalizerPreamp?.let { dataStore.setEqualizerPreamp(it) }
                audio.equalizerAutoEqProfile?.let { profile ->
                    val parts = profile.split("\n", limit = 2)
                    if (parts.size == 2) {
                        val label = parts[0]
                        val bands = parts[1].split(",").mapNotNull { b -> b.trim().toFloatOrNull() }
                        dataStore.setEqualizerAutoEqProfile(label, bands)
                    }
                }
                audio.delayEnabled?.let { dataStore.setDelayEnabled(it) }
                audio.delayTimeMs?.let { dataStore.setDelayTimeMs(it) }
                audio.delayFeedback?.let { dataStore.setDelayFeedback(it) }
                audio.delayMix?.let { dataStore.setDelayMix(it) }
                audio.reverbEnabled?.let { dataStore.setReverbEnabled(it) }
                audio.reverbPreset?.let { presetName ->
                    ReverbPreset.entries.firstOrNull { it.name == presetName }?.let { dataStore.setReverbPreset(it) }
                }
                audio.reverbMix?.let { dataStore.setReverbMix(it) }
                add(SettingsCategory.AUDIO)
            }
            bundle.appearance?.let { app ->
                app.themeMode?.let { dataStore.setThemeMode(it) }
                app.themeColorSource?.let { dataStore.setThemeColorSource(it) }
                app.customThemeColor?.let { dataStore.setCustomThemeColor(it) }
                app.nowPlayingStyle?.let { dataStore.setNowPlayingStyle(it) }
                app.lyricsStyle?.let { dataStore.setLyricsStyle(it) }
                app.liquidGlassEnabled?.let { dataStore.setEnableLiquidGlass(it) }
                add(SettingsCategory.APPEARANCE)
            }
            bundle.content?.let { c ->
                c.location?.let { dataStore.setLocation(it) }
                c.language?.let { dataStore.putString(SELECTED_LANGUAGE, it) }
                c.lyricsProvider?.let { dataStore.setLyricsProvider(it) }
                c.enableTranslateLyric?.let { dataStore.setEnableTranslateLyric(it) }
                c.translationLanguage?.let { dataStore.setTranslationLanguage(it) }
                c.romanizationLanguages?.let { dataStore.setRomanizationLanguages(it) }
                c.lyricsOffsetMs?.let { dataStore.setLyricsOffsetMs(it) }
                c.explicitContentEnabled?.let { dataStore.setExplicitContentEnabled(it) }
                c.sponsorBlockEnabled?.let { dataStore.setSponsorBlockEnabled(it) }
                c.sponsorBlockCategories?.let { dataStore.setSponsorBlockCategories(ArrayList(it)) }
                c.spotifyLyrics?.let { dataStore.setSpotifyLyrics(it) }
                c.spotifyCanvas?.let { dataStore.setSpotifyCanvas(it) }
                c.amAnimatedArtwork?.let { dataStore.setAMAnimatedArtwork(it) }
                c.syncFollowToYouTube?.let { dataStore.setSyncFollowToYouTube(it) }
                c.endlessQueue?.let { dataStore.setEndlessQueue(it) }
                c.combineLocalAndYouTubeLiked?.let { dataStore.setCombineLocalAndYouTubeLiked(it) }
                c.lastfmScrobbleEnabled?.let { dataStore.setLastfmScrobbleEnabled(it) }
                c.richPresenceEnabled?.let { dataStore.setRichPresenceEnabled(it) }
                add(SettingsCategory.CONTENT)
            }
        }

    /** Writes [bundle] over this device's sign-ins and returns the services it carried. */
    suspend fun restore(bundle: LoginBundle): List<LoginSyncService> =
        buildList {
            bundle.youtube?.let {
                restoreYouTube(it)
                add(LoginSyncService.YOUTUBE)
            }
            bundle.spotifySpDc?.takeIf { it.isNotEmpty() }?.let { spDc ->
                dataStore.setSpdc(spDc)
                // A personal token minted for the previous sp_dc would otherwise stay in use until it
                // expires — the same reset the Spotify logout does.
                dataStore.setSpotifyPersonalToken("")
                dataStore.setSpotifyPersonalTokenExpires(0)
                add(LoginSyncService.SPOTIFY)
            }
            bundle.discordToken?.takeIf { it.isNotEmpty() }?.let {
                dataStore.setDiscordToken(it)
                add(LoginSyncService.DISCORD)
            }
            bundle.lastfm?.takeIf { it.sessionKey.isNotEmpty() }?.let {
                dataStore.setLastfmSession(sessionKey = it.sessionKey, username = it.username)
                add(LoginSyncService.LASTFM)
            }
        }

    /**
     * Mirrors SettingsViewModel.setUsedAccount. Order matters: the account rows go in BEFORE the
     * cookie, because CommonRepositoryImpl rewrites the yt-dlp cookie file from the used account the
     * moment the cookie changes — written the other way round it would write the old account's file.
     */
    private suspend fun restoreYouTube(login: YouTubeLogin) {
        localDataSource.getGoogleAccounts().forEach { localDataSource.updateGoogleAccountUsed(it.email, false) }
        login.accounts.forEach { localDataSource.insertGoogleAccount(it.toEntity()) }
        val used = login.accounts.firstOrNull { it.isUsed }
        dataStore.putString("AccountName", used?.name.orEmpty())
        dataStore.putString("AccountThumbUrl", used?.thumbnailUrl.orEmpty())
        used?.netscapeCookie?.let { commonRepository.writeTextToFile(it, "${fileDir()}/ytdlp-cookie.txt") }
        if (login.dataSyncId.isNotEmpty()) dataStore.setDataSyncId(login.dataSyncId)
        dataStore.setCookie(login.cookie, login.pageId, login.authUser)
        dataStore.setLoggedIn(true)
        Logger.i("Auth", "YouTube: signed in through Login Sync, ${login.accounts.size} account(s)")
    }
}

private fun GoogleAccountEntity.toWire() = GoogleAccountWire(email, name, thumbnailUrl, pageId, authUser, cache, isUsed, netscapeCookie)

private fun GoogleAccountWire.toEntity() =
    GoogleAccountEntity(
        email = email,
        name = name,
        thumbnailUrl = thumbnailUrl,
        pageId = pageId,
        authUser = authUser,
        cache = cache,
        isUsed = isUsed,
        netscapeCookie = netscapeCookie,
    )
