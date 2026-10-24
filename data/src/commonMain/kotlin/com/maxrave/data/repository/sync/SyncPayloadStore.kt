package com.maxrave.data.repository.sync

import com.maxrave.data.db.datasource.AnalyticsDatasource
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.io.fileDir
import com.maxrave.data.io.fileSystem
import com.maxrave.data.loginsync.LoginBundle
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.data.loginsync.loginSyncJson
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.loginsync.SettingsBundle
import com.maxrave.domain.data.model.loginsync.SettingsCategory
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.logger.Logger
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import org.simpmusic.sync.SyncBlob
import org.simpmusic.sync.SyncCrypto
import org.simpmusic.sync.SyncEnvelopeHelper

@Serializable
internal data class SyncLocalState(
    val entityHashes: Map<String, String> = emptyMap(),
    val lastAnalyticsTimestampIso: String? = null,
)

@Serializable
internal data class SyncSongWire(
    val videoId: String,
    val albumId: String? = null,
    val albumName: String? = null,
    val artistId: List<String>? = null,
    val artistName: List<String>? = null,
    val duration: String = "",
    val durationSeconds: Int = 0,
    val isAvailable: Boolean = true,
    val isExplicit: Boolean = false,
    val likeStatus: String = "INDIFFERENT",
    val thumbnails: String? = null,
    val title: String = "",
    val videoType: String = "",
    val category: String? = null,
    val resultType: String? = null,
    val liked: Boolean = false,
    val totalPlayTime: Long = 0,
    val favoriteAtIso: String? = null,
    val inLibraryIso: String? = null,
    val canvasUrl: String? = null,
    val canvasThumbUrl: String? = null,
)

@Serializable
internal data class SyncPlaylistTrackPairWire(
    val songId: String,
    val position: Int,
    val inPlaylistIso: String,
)

@Serializable
internal data class SyncLocalPlaylistWire(
    val title: String,
    val thumbnail: String? = null,
    val inLibraryIso: String? = null,
    val youtubePlaylistId: String? = null,
    val syncState: Int = LocalPlaylistEntity.YouTubeSyncState.NotSynced,
    val tracks: List<String> = emptyList(),
    val pairs: List<SyncPlaylistTrackPairWire> = emptyList(),
    val songs: List<SyncSongWire> = emptyList(),
)

@Serializable
internal data class SyncArtistWire(
    val channelId: String,
    val name: String,
    val thumbnails: String? = null,
    val followed: Boolean = true,
    val followedAtIso: String? = null,
    val inLibraryIso: String? = null,
    val nameLogoUrl: String? = null,
    val nameLogoColor: String? = null,
)

@Serializable
internal data class SyncAlbumWire(
    val browseId: String,
    val artistId: List<String?>? = null,
    val artistName: List<String>? = null,
    val audioPlaylistId: String = "",
    val description: String = "",
    val duration: String? = null,
    val durationSeconds: Int = 0,
    val thumbnails: String? = null,
    val title: String = "",
    val trackCount: Int = 0,
    val tracks: List<String>? = null,
    val type: String = "",
    val year: String? = null,
    val liked: Boolean = true,
    val inLibraryIso: String? = null,
    val favoriteAtIso: String? = null,
)

@Serializable
internal data class SyncRemotePlaylistWire(
    val id: String,
    val author: String? = "",
    val description: String = "",
    val duration: String = "",
    val durationSeconds: Int = 0,
    val privacy: String = "PRIVATE",
    val thumbnails: String = "",
    val title: String = "",
    val trackCount: Int = 0,
    val tracks: List<String>? = null,
    val year: String? = null,
    val liked: Boolean = true,
    val inLibraryIso: String? = null,
    val favoriteAtIso: String? = null,
)

@Serializable
internal data class SyncPodcastWire(
    val podcastId: String,
    val title: String,
    val authorId: String,
    val authorName: String,
    val authorThumbnail: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val isFavorite: Boolean = true,
    val inLibraryIso: String? = null,
    val favoriteTimeIso: String? = null,
    val listEpisodes: List<String> = emptyList(),
)

@Serializable
internal data class SyncSearchHistoryWire(
    val query: String,
)

@Serializable
internal data class SyncAnalyticsEventWire(
    val timestampIso: String,
    val videoId: String,
    val albumBrowseId: String? = null,
    val durationSecond: Long = 0,
    val listenedSecond: Long = 0,
    val channelIds: List<String> = emptyList(),
)

internal class SyncPayloadStore(
    private val dataStore: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val analyticsDatasource: AnalyticsDatasource,
    commonRepository: CommonRepository,
) {
    private val loginSyncStore = LoginSyncStore(dataStore, localDataSource, commonRepository)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    companion object {
        private const val TAG = "SimpSyncStore"
        private const val STATE_FILE_NAME = "simpsync_state.json"

        const val TYPE_SETTINGS = "settings"
        const val ID_SETTINGS = "app_settings"

        const val TYPE_ACCOUNTS = "accounts"
        const val ID_ACCOUNTS = "service_logins"

        const val TYPE_SONG = "song"
        const val TYPE_LOCAL_PLAYLIST = "local_playlist"
        const val TYPE_ARTIST = "artist"
        const val TYPE_ALBUM = "album"
        const val TYPE_PLAYLIST = "playlist"
        const val TYPE_PODCAST = "podcast"
        const val TYPE_SEARCH_HISTORY = "search_history"
        const val TYPE_ANALYTICS = "analytics_event"
    }

    fun loadLocalState(): SyncLocalState {
        return try {
            val path = "${fileDir()}/$STATE_FILE_NAME".toPath()
            val fs = fileSystem()
            if (!fs.exists(path)) return SyncLocalState()
            val raw = fs.read(path) { readUtf8() }
            json.decodeFromString<SyncLocalState>(raw)
        } catch (e: Exception) {
            Logger.w(TAG, "Could not load sync state file: ${e.message}")
            SyncLocalState()
        }
    }

    fun saveLocalState(state: SyncLocalState) {
        try {
            val path = "${fileDir()}/$STATE_FILE_NAME".toPath()
            val raw = json.encodeToString(state)
            fileSystem().write(path) { writeUtf8(raw) }
        } catch (e: Exception) {
            Logger.e(TAG, "Could not save sync state file: ${e.message}")
        }
    }

    fun clearLocalState() {
        try {
            val path = "${fileDir()}/$STATE_FILE_NAME".toPath()
            val fs = fileSystem()
            if (fs.exists(path)) fs.delete(path)
        } catch (_: Exception) {
        }
    }

    suspend fun applyRemoteChanges(
        dek: ByteArray,
        changes: List<SyncBlob>,
        currentHashes: MutableMap<String, String>,
    ): String? {
        var latestAnalyticsIso: String? = null
        for (blob in changes) {
            if (blob.entityType == SyncEnvelopeHelper.KEYRING_ENTITY_TYPE) continue
            val mapKey = "${blob.entityType}:${blob.entityId}"
            if (blob.deleted) {
                deleteLocalEntity(blob.entityType, blob.entityId)
                currentHashes.remove(mapKey)
                continue
            }
            val plaintextBytes = SyncEnvelopeHelper.openFromBase64(
                key = dek,
                entityType = blob.entityType,
                entityId = blob.entityId,
                envelopeBase64 = blob.envelope,
            ) ?: continue
            val payloadText = plaintextBytes.decodeToString()
            try {
                val appliedCanonical = applyLocalEntity(blob.entityType, blob.entityId, payloadText)
                val hashTarget = appliedCanonical ?: payloadText
                currentHashes[mapKey] = SyncCrypto.sha256Hex(hashTarget.encodeToByteArray())
                if (blob.entityType == TYPE_ANALYTICS) {
                    val event = json.decodeFromString<SyncAnalyticsEventWire>(payloadText)
                    if (latestAnalyticsIso == null || event.timestampIso > latestAnalyticsIso) {
                        latestAnalyticsIso = event.timestampIso
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to apply remote entity $mapKey: ${e.message}")
            }
        }
        return latestAnalyticsIso
    }

    suspend fun buildPushChanges(
        dek: ByteArray,
        state: SyncLocalState,
    ): Pair<List<SyncBlob>, SyncLocalState> {
        val updatedHashes = state.entityHashes.toMutableMap()
        val currentSnapshot = collectCurrentSnapshot()
        val outgoing = mutableListOf<SyncBlob>()

        // 1. New or modified entities
        for ((key, jsonPayload) in currentSnapshot) {
            val (entityType, entityId) = splitKey(key) ?: continue
            val newHash = SyncCrypto.sha256Hex(jsonPayload.encodeToByteArray())
            if (updatedHashes[key] != newHash) {
                val envelope = SyncEnvelopeHelper.sealToBase64(
                    key = dek,
                    entityType = entityType,
                    entityId = entityId,
                    plaintext = jsonPayload.encodeToByteArray(),
                )
                outgoing.add(
                    SyncBlob(
                        entityType = entityType,
                        entityId = entityId,
                        envelope = envelope,
                        deleted = false,
                    ),
                )
                updatedHashes[key] = newHash
            }
        }

        // 2. Deleted entities (present in previous state, absent in current snapshot, excluding append-only analytics)
        val removedKeys = updatedHashes.keys.filter { key ->
            !key.startsWith("$TYPE_ANALYTICS:") && key !in currentSnapshot
        }
        for (key in removedKeys) {
            val (entityType, entityId) = splitKey(key) ?: continue
            val tombstoneEnvelope = SyncEnvelopeHelper.sealToBase64(
                key = dek,
                entityType = entityType,
                entityId = entityId,
                plaintext = "{}".encodeToByteArray(),
            )
            outgoing.add(
                SyncBlob(
                    entityType = entityType,
                    entityId = entityId,
                    envelope = tombstoneEnvelope,
                    deleted = true,
                ),
            )
            updatedHashes.remove(key)
        }

        // 3. Append-only analytics events since lastAnalyticsTimestampIso
        var newestAnalyticsIso = state.lastAnalyticsTimestampIso
        val recentEvents = if (newestAnalyticsIso != null) {
            val cutoff = runCatching { LocalDateTime.parse(newestAnalyticsIso) }.getOrNull()
            if (cutoff != null) {
                analyticsDatasource.getPlaybackEventsByOffsetAndTimestamp(0, 500, cutoff)
            } else {
                analyticsDatasource.getPlaybackEventsByOffset(0, 500)
            }
        } else {
            analyticsDatasource.getPlaybackEventsByOffset(0, 500)
        }

        for (event in recentEvents.asReversed()) {
            val iso = event.timestamp.toString()
            val eventId = analyticsEntityId(iso, event.videoId)
            val mapKey = "$TYPE_ANALYTICS:$eventId"
            if (mapKey in updatedHashes) {
                if (newestAnalyticsIso == null || iso > newestAnalyticsIso) newestAnalyticsIso = iso
                continue
            }
            val channels = analyticsDatasource.getEventArtistChannelIds(event.eventId)
            val wire = SyncAnalyticsEventWire(
                timestampIso = iso,
                videoId = event.videoId,
                albumBrowseId = event.albumBrowseId,
                durationSecond = event.durationSecond,
                listenedSecond = event.listenedSecond,
                channelIds = channels,
            )
            val jsonPayload = json.encodeToString(wire)
            val hash = SyncCrypto.sha256Hex(jsonPayload.encodeToByteArray())
            val envelope = SyncEnvelopeHelper.sealToBase64(
                key = dek,
                entityType = TYPE_ANALYTICS,
                entityId = eventId,
                plaintext = jsonPayload.encodeToByteArray(),
            )
            outgoing.add(
                SyncBlob(
                    entityType = TYPE_ANALYTICS,
                    entityId = eventId,
                    envelope = envelope,
                    deleted = false,
                ),
            )
            updatedHashes[mapKey] = hash
            if (newestAnalyticsIso == null || iso > newestAnalyticsIso) {
                newestAnalyticsIso = iso
            }
        }

        val newState = SyncLocalState(
            entityHashes = updatedHashes,
            lastAnalyticsTimestampIso = newestAnalyticsIso,
        )
        return outgoing to newState
    }

    private suspend fun collectCurrentSnapshot(): Map<String, String> {
        val snapshot = linkedMapOf<String, String>()

        // Settings
        val settingsBundle = loginSyncStore.bundleSettings(SettingsCategory.entries.toSet())
        snapshot["$TYPE_SETTINGS:$ID_SETTINGS"] = loginSyncJson.encodeToString(settingsBundle)

        // Accounts
        val signedIn = loginSyncStore.signedIn().toSet()
        if (signedIn.isNotEmpty()) {
            val loginBundle = loginSyncStore.bundle(signedIn)
            snapshot["$TYPE_ACCOUNTS:$ID_ACCOUNTS"] = loginSyncJson.encodeToString(loginBundle)
        }

        // Songs (liked songs + played songs in library)
        val likedSongs = localDataSource.getLikedSongs(5000, 0)
        val allSongs = localDataSource.getAllSongs(5000)
        val relevantSongs = (likedSongs + allSongs.filter { it.liked || it.totalPlayTime > 0 })
            .associateBy { it.videoId }
            .values
        for (song in relevantSongs) {
            if (song.videoId.isBlank()) continue
            snapshot["$TYPE_SONG:${song.videoId}"] = json.encodeToString(song.toWire())
        }

        // Local Playlists
        val localPlaylists = localDataSource.getAllLocalPlaylists(500, 0)
        for (playlist in localPlaylists) {
            val playlistKey = localPlaylistEntityId(playlist)
            val pairs = localDataSource.getAllPlaylistPairSongByPosition(playlist.id) ?: emptyList()
            val trackIds = (playlist.tracks.orEmpty() + pairs.map { it.songId }).distinct()
            val songEntities = if (trackIds.isNotEmpty()) {
                localDataSource.getSongByListVideoIdFull(trackIds)
            } else {
                emptyList()
            }
            val wire = SyncLocalPlaylistWire(
                title = playlist.title,
                thumbnail = playlist.thumbnail,
                inLibraryIso = playlist.inLibrary.toString(),
                youtubePlaylistId = playlist.youtubePlaylistId,
                syncState = playlist.syncState,
                tracks = playlist.tracks ?: pairs.sortedBy { it.position }.map { it.songId },
                pairs = pairs.map {
                    SyncPlaylistTrackPairWire(
                        songId = it.songId,
                        position = it.position,
                        inPlaylistIso = it.inPlaylist.toString(),
                    )
                },
                songs = songEntities.map { it.toWire() },
            )
            snapshot["$TYPE_LOCAL_PLAYLIST:$playlistKey"] = json.encodeToString(wire)
        }

        // Followed Artists
        val followedArtists = localDataSource.getFollowedArtists(2000, 0)
        for (artist in followedArtists) {
            if (artist.channelId.isBlank()) continue
            val wire = SyncArtistWire(
                channelId = artist.channelId,
                name = artist.name,
                thumbnails = artist.thumbnails,
                followed = artist.followed,
                followedAtIso = artist.followedAt?.toString(),
                inLibraryIso = artist.inLibrary.toString(),
                nameLogoUrl = artist.nameLogoUrl,
                nameLogoColor = artist.nameLogoColor,
            )
            snapshot["$TYPE_ARTIST:${artist.channelId}"] = json.encodeToString(wire)
        }

        // Liked Albums
        val likedAlbums = localDataSource.getLikedAlbums(2000, 0)
        for (album in likedAlbums) {
            if (album.browseId.isBlank()) continue
            val wire = SyncAlbumWire(
                browseId = album.browseId,
                artistId = album.artistId,
                artistName = album.artistName,
                audioPlaylistId = album.audioPlaylistId,
                description = album.description,
                duration = album.duration,
                durationSeconds = album.durationSeconds,
                thumbnails = album.thumbnails,
                title = album.title,
                trackCount = album.trackCount,
                tracks = album.tracks,
                type = album.type,
                year = album.year,
                liked = album.liked,
                inLibraryIso = album.inLibrary.toString(),
                favoriteAtIso = album.favoriteAt?.toString(),
            )
            snapshot["$TYPE_ALBUM:${album.browseId}"] = json.encodeToString(wire)
        }

        // Liked Remote Playlists
        val likedPlaylists = localDataSource.getLikedPlaylists(2000, 0)
        for (playlist in likedPlaylists) {
            if (playlist.id.isBlank()) continue
            val wire = SyncRemotePlaylistWire(
                id = playlist.id,
                author = playlist.author,
                description = playlist.description,
                duration = playlist.duration,
                durationSeconds = playlist.durationSeconds,
                privacy = playlist.privacy,
                thumbnails = playlist.thumbnails,
                title = playlist.title,
                trackCount = playlist.trackCount,
                tracks = playlist.tracks,
                year = playlist.year,
                liked = playlist.liked,
                inLibraryIso = playlist.inLibrary.toString(),
                favoriteAtIso = playlist.favoriteAt?.toString(),
            )
            snapshot["$TYPE_PLAYLIST:${playlist.id}"] = json.encodeToString(wire)
        }

        // Favorite Podcasts
        val favPodcasts = localDataSource.getFavoritePodcasts(500, 0)
        for (podcast in favPodcasts) {
            if (podcast.podcastId.isBlank()) continue
            val wire = SyncPodcastWire(
                podcastId = podcast.podcastId,
                title = podcast.title,
                authorId = podcast.authorId,
                authorName = podcast.authorName,
                authorThumbnail = podcast.authorThumbnail,
                description = podcast.description,
                thumbnail = podcast.thumbnail,
                isFavorite = podcast.isFavorite,
                inLibraryIso = podcast.inLibrary.toString(),
                favoriteTimeIso = podcast.favoriteTime?.toString(),
                listEpisodes = podcast.listEpisodes,
            )
            snapshot["$TYPE_PODCAST:${podcast.podcastId}"] = json.encodeToString(wire)
        }

        // Search History
        val searches = localDataSource.getSearchHistory()
        for (search in searches) {
            if (search.query.isBlank()) continue
            val id = SyncCrypto.sha256Hex(search.query.trim().lowercase().encodeToByteArray()).take(32)
            snapshot["$TYPE_SEARCH_HISTORY:$id"] = json.encodeToString(SyncSearchHistoryWire(search.query))
        }

        return snapshot
    }

    private suspend fun applyLocalEntity(
        entityType: String,
        entityId: String,
        payloadText: String,
    ): String? {
        return when (entityType) {
            TYPE_SETTINGS -> {
                val bundle = loginSyncJson.decodeFromString<SettingsBundle>(payloadText)
                loginSyncStore.restoreSettings(bundle)
                val current = loginSyncStore.bundleSettings(SettingsCategory.entries.toSet())
                loginSyncJson.encodeToString(current)
            }
            TYPE_ACCOUNTS -> {
                val bundle = loginSyncJson.decodeFromString<LoginBundle>(payloadText)
                loginSyncStore.restore(bundle)
                val signedIn = loginSyncStore.signedIn().toSet()
                if (signedIn.isNotEmpty()) loginSyncJson.encodeToString(loginSyncStore.bundle(signedIn)) else payloadText
            }
            TYPE_SONG -> {
                val wire = json.decodeFromString<SyncSongWire>(payloadText)
                val existing = localDataSource.getSong(wire.videoId)
                val mergedPlayTime = maxOf(existing?.totalPlayTime ?: 0L, wire.totalPlayTime)
                val entity = wire.toEntity(existing, mergedPlayTime)
                localDataSource.insertSong(entity)
                localDataSource.updateLiked(if (wire.liked) 1 else 0, wire.videoId)
                if (mergedPlayTime > 0) {
                    localDataSource.updateTotalPlayTimeIfGreater(wire.videoId, mergedPlayTime)
                }
                localDataSource.getSong(wire.videoId)?.let { json.encodeToString(it.toWire()) }
            }
            TYPE_LOCAL_PLAYLIST -> {
                val wire = json.decodeFromString<SyncLocalPlaylistWire>(payloadText)
                if (wire.songs.isNotEmpty()) {
                    localDataSource.insertSongs(wire.songs.map { it.toEntity(null, it.totalPlayTime) })
                }
                val existingAll = localDataSource.getAllLocalPlaylists(500, 0)
                val existing = existingAll.firstOrNull {
                    (!wire.youtubePlaylistId.isNullOrBlank() && it.youtubePlaylistId == wire.youtubePlaylistId) ||
                        localPlaylistEntityId(it) == entityId ||
                        it.title.equals(wire.title, ignoreCase = true)
                }
                val playlistId = if (existing != null) {
                    localDataSource.updateLocalPlaylistTitle(wire.title, existing.id)
                    wire.thumbnail?.let { localDataSource.updateLocalPlaylistThumbnail(it, existing.id) }
                    localDataSource.updateLocalPlaylistTracks(wire.tracks, existing.id)
                    if (wire.youtubePlaylistId != null) {
                        localDataSource.updateLocalPlaylistYouTubePlaylistId(existing.id, wire.youtubePlaylistId)
                        localDataSource.updateLocalPlaylistYouTubePlaylistSyncState(existing.id, wire.syncState)
                    }
                    existing.id
                } else {
                    localDataSource.insertLocalPlaylist(
                        LocalPlaylistEntity(
                            title = wire.title,
                            thumbnail = wire.thumbnail,
                            inLibrary = parseDateTimeOrNow(wire.inLibraryIso),
                            youtubePlaylistId = wire.youtubePlaylistId,
                            syncState = wire.syncState,
                            tracks = wire.tracks,
                        ),
                    )
                    val afterInsert = localDataSource.getAllLocalPlaylists(500, 0)
                    afterInsert.firstOrNull { it.title == wire.title }?.id ?: return payloadText
                }
                val pairsToInsert = if (wire.pairs.isNotEmpty()) {
                    wire.pairs.map {
                        PairSongLocalPlaylist(
                            playlistId = playlistId,
                            songId = it.songId,
                            position = it.position,
                            inPlaylist = parseDateTimeOrNow(it.inPlaylistIso),
                        )
                    }
                } else {
                    wire.tracks.mapIndexed { index, songId ->
                        PairSongLocalPlaylist(
                            playlistId = playlistId,
                            songId = songId,
                            position = index,
                            inPlaylist = now(),
                        )
                    }
                }
                if (pairsToInsert.isNotEmpty()) {
                    localDataSource.insertPairSongLocalPlaylists(pairsToInsert)
                }
                payloadText
            }
            TYPE_ARTIST -> {
                val wire = json.decodeFromString<SyncArtistWire>(payloadText)
                localDataSource.insertArtist(
                    ArtistEntity(
                        channelId = wire.channelId,
                        name = wire.name,
                        thumbnails = wire.thumbnails,
                        followed = wire.followed,
                        followedAt = wire.followedAtIso?.let { parseDateTimeOrNow(it) },
                        inLibrary = parseDateTimeOrNow(wire.inLibraryIso),
                        nameLogoUrl = wire.nameLogoUrl,
                        nameLogoColor = wire.nameLogoColor,
                    ),
                )
                localDataSource.updateFollowed(if (wire.followed) 1 else 0, wire.channelId)
                payloadText
            }
            TYPE_ALBUM -> {
                val wire = json.decodeFromString<SyncAlbumWire>(payloadText)
                localDataSource.insertAlbum(
                    AlbumEntity(
                        browseId = wire.browseId,
                        artistId = wire.artistId,
                        artistName = wire.artistName,
                        audioPlaylistId = wire.audioPlaylistId,
                        description = wire.description,
                        duration = wire.duration,
                        durationSeconds = wire.durationSeconds,
                        thumbnails = wire.thumbnails,
                        title = wire.title,
                        trackCount = wire.trackCount,
                        tracks = wire.tracks,
                        type = wire.type,
                        year = wire.year,
                        liked = wire.liked,
                        inLibrary = parseDateTimeOrNow(wire.inLibraryIso),
                        favoriteAt = wire.favoriteAtIso?.let { parseDateTimeOrNow(it) },
                    ),
                )
                localDataSource.updateAlbumLiked(if (wire.liked) 1 else 0, wire.browseId)
                payloadText
            }
            TYPE_PLAYLIST -> {
                val wire = json.decodeFromString<SyncRemotePlaylistWire>(payloadText)
                localDataSource.insertPlaylist(
                    PlaylistEntity(
                        id = wire.id,
                        author = wire.author,
                        description = wire.description,
                        duration = wire.duration,
                        durationSeconds = wire.durationSeconds,
                        privacy = wire.privacy,
                        thumbnails = wire.thumbnails,
                        title = wire.title,
                        trackCount = wire.trackCount,
                        tracks = wire.tracks,
                        year = wire.year,
                        liked = wire.liked,
                        inLibrary = parseDateTimeOrNow(wire.inLibraryIso),
                        favoriteAt = wire.favoriteAtIso?.let { parseDateTimeOrNow(it) },
                    ),
                )
                localDataSource.updatePlaylistLiked(if (wire.liked) 1 else 0, wire.id)
                payloadText
            }
            TYPE_PODCAST -> {
                val wire = json.decodeFromString<SyncPodcastWire>(payloadText)
                localDataSource.insertPodcast(
                    PodcastsEntity(
                        podcastId = wire.podcastId,
                        title = wire.title,
                        authorId = wire.authorId,
                        authorName = wire.authorName,
                        authorThumbnail = wire.authorThumbnail,
                        description = wire.description,
                        thumbnail = wire.thumbnail,
                        isFavorite = wire.isFavorite,
                        inLibrary = parseDateTimeOrNow(wire.inLibraryIso),
                        favoriteTime = wire.favoriteTimeIso?.let { parseDateTimeOrNow(it) },
                        listEpisodes = wire.listEpisodes,
                    ),
                )
                payloadText
            }
            TYPE_SEARCH_HISTORY -> {
                val wire = json.decodeFromString<SyncSearchHistoryWire>(payloadText)
                if (wire.query.isNotBlank()) {
                    localDataSource.insertSearchHistory(SearchHistory(wire.query))
                }
                payloadText
            }
            TYPE_ANALYTICS -> {
                val wire = json.decodeFromString<SyncAnalyticsEventWire>(payloadText)
                val ts = parseDateTimeOrNow(wire.timestampIso)
                analyticsDatasource.insertSyncedPlaybackEvent(
                    timestamp = ts,
                    videoId = wire.videoId,
                    channelIds = wire.channelIds,
                    albumBrowseId = wire.albumBrowseId,
                    durationSecond = wire.durationSecond,
                    listenedSecond = wire.listenedSecond,
                )
                payloadText
            }
            else -> payloadText
        }
    }

    private suspend fun deleteLocalEntity(
        entityType: String,
        entityId: String,
    ) {
        try {
            when (entityType) {
                TYPE_SONG -> localDataSource.updateLiked(0, entityId)
                TYPE_LOCAL_PLAYLIST -> {
                    val playlists = localDataSource.getAllLocalPlaylists(500, 0)
                    playlists.firstOrNull { localPlaylistEntityId(it) == entityId }?.let {
                        localDataSource.deleteLocalPlaylist(it.id)
                    }
                }
                TYPE_ARTIST -> localDataSource.updateFollowed(0, entityId)
                TYPE_ALBUM -> localDataSource.updateAlbumLiked(0, entityId)
                TYPE_PLAYLIST -> localDataSource.updatePlaylistLiked(0, entityId)
                TYPE_PODCAST -> localDataSource.favoritePodcast(entityId, false)
                TYPE_SEARCH_HISTORY -> {
                    val searches = localDataSource.getSearchHistory()
                    searches.firstOrNull {
                        SyncCrypto.sha256Hex(it.query.trim().lowercase().encodeToByteArray()).take(32) == entityId
                    }?.let { localDataSource.deleteSearchHistoryItem(it.query) }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to delete local entity $entityType:$entityId: ${e.message}")
        }
    }

    private fun localPlaylistEntityId(playlist: LocalPlaylistEntity): String {
        val raw = playlist.youtubePlaylistId?.takeIf { it.isNotBlank() }
            ?: "local:${playlist.title.trim().lowercase()}"
        return SyncCrypto.sha256Hex(raw.encodeToByteArray()).take(32)
    }

    private fun analyticsEntityId(timestampIso: String, videoId: String): String =
        SyncCrypto.sha256Hex("$timestampIso|$videoId".encodeToByteArray()).take(40)

    private fun splitKey(key: String): Pair<String, String>? {
        val idx = key.indexOf(':')
        if (idx <= 0 || idx >= key.length - 1) return null
        return key.substring(0, idx) to key.substring(idx + 1)
    }

    private fun parseDateTimeOrNow(iso: String?): LocalDateTime =
        if (iso.isNullOrBlank()) {
            now()
        } else {
            runCatching { LocalDateTime.parse(iso) }.getOrElse { now() }
        }
}

private fun SongEntity.toWire() =
    SyncSongWire(
        videoId = videoId,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        duration = duration,
        durationSeconds = durationSeconds,
        isAvailable = isAvailable,
        isExplicit = isExplicit,
        likeStatus = likeStatus,
        thumbnails = thumbnails,
        title = title,
        videoType = videoType,
        category = category,
        resultType = resultType,
        liked = liked,
        totalPlayTime = totalPlayTime,
        favoriteAtIso = favoriteAt?.toString(),
        inLibraryIso = inLibrary.toString(),
        canvasUrl = canvasUrl,
        canvasThumbUrl = canvasThumbUrl,
    )

private fun SyncSongWire.toEntity(existing: SongEntity?, mergedPlayTime: Long) =
    SongEntity(
        videoId = videoId,
        albumId = albumId ?: existing?.albumId,
        albumName = albumName ?: existing?.albumName,
        artistId = artistId ?: existing?.artistId,
        artistName = artistName ?: existing?.artistName,
        duration = duration.ifBlank { existing?.duration.orEmpty() },
        durationSeconds = if (durationSeconds > 0) durationSeconds else (existing?.durationSeconds ?: 0),
        isAvailable = isAvailable,
        isExplicit = isExplicit,
        likeStatus = if (liked) "LIKE" else likeStatus,
        thumbnails = thumbnails ?: existing?.thumbnails,
        title = title.ifBlank { existing?.title.orEmpty() },
        videoType = videoType.ifBlank { existing?.videoType.orEmpty() },
        category = category ?: existing?.category,
        resultType = resultType ?: existing?.resultType,
        liked = liked,
        totalPlayTime = mergedPlayTime,
        downloadState = existing?.downloadState ?: DownloadState.STATE_NOT_DOWNLOADED,
        favoriteAt = favoriteAtIso?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: existing?.favoriteAt ?: now(),
        downloadedAt = existing?.downloadedAt ?: now(),
        inLibrary = inLibraryIso?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: existing?.inLibrary ?: now(),
        canvasUrl = canvasUrl ?: existing?.canvasUrl,
        canvasThumbUrl = canvasThumbUrl ?: existing?.canvasThumbUrl,
    )
