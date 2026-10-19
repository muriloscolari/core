package com.maxrave.domain.data.model.loginsync

import kotlinx.serialization.Serializable

@Serializable
data class AudioSettingsBundle(
    val quality: String? = null,
    val downloadQuality: String? = null,
    val normalizeVolume: Boolean? = null,
    val skipSilent: Boolean? = null,
    val radioAudioOnly: Boolean? = null,
    val maxParallelDownloads: Int? = null,
    val maxSongCacheSize: Int? = null,
    val crossfadeEnabled: Boolean? = null,
    val crossfadeDuration: Int? = null,
    val crossfadeDjMode: Boolean? = null,
    val crossfadeSkipAlbum: Boolean? = null,
    val equalizerEnabled: Boolean? = null,
    val equalizerBands: String? = null,
    val equalizerPreamp: Float? = null,
    val equalizerAutoEqProfile: String? = null,
    val delayEnabled: Boolean? = null,
    val delayTimeMs: Int? = null,
    val delayFeedback: Float? = null,
    val delayMix: Float? = null,
    val reverbEnabled: Boolean? = null,
    val reverbPreset: String? = null,
    val reverbMix: Float? = null,
)

@Serializable
data class AppearanceSettingsBundle(
    val themeMode: String? = null,
    val themeColorSource: String? = null,
    val customThemeColor: String? = null,
    val nowPlayingStyle: String? = null,
    val lyricsStyle: String? = null,
    val liquidGlassEnabled: Boolean? = null,
)

@Serializable
data class ContentSettingsBundle(
    val location: String? = null,
    val language: String? = null,
    val lyricsProvider: String? = null,
    val enableTranslateLyric: Boolean? = null,
    val translationLanguage: String? = null,
    val romanizationLanguages: String? = null,
    val lyricsOffsetMs: Int? = null,
    val explicitContentEnabled: Boolean? = null,
    val sponsorBlockEnabled: Boolean? = null,
    val sponsorBlockCategories: List<String>? = null,
    val spotifyLyrics: Boolean? = null,
    val spotifyCanvas: Boolean? = null,
    val amAnimatedArtwork: Boolean? = null,
    val syncFollowToYouTube: Boolean? = null,
    val endlessQueue: Boolean? = null,
    val combineLocalAndYouTubeLiked: Boolean? = null,
    val lastfmScrobbleEnabled: Boolean? = null,
    val richPresenceEnabled: Boolean? = null,
)

@Serializable
data class SettingsBundle(
    val audio: AudioSettingsBundle? = null,
    val appearance: AppearanceSettingsBundle? = null,
    val content: ContentSettingsBundle? = null,
)
