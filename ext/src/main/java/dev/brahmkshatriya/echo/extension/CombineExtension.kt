package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

class CombineExtension : ExtensionClient, MusicExtensionsProvider,
    HomeFeedClient, SearchFeedClient, TrackClient,
    AlbumClient, ArtistClient, PlaylistClient, PlaylistEditClient,
    SaveClient, LikeClient, ShareClient, FollowClient, HideClient, RadioClient,
    LibraryFeedClient {

    companion object {
        const val COMBINE_ID = "echo_combine"
        const val KEY_METADATA_EXT = "metadata_extension"
        const val KEY_STREAM_EXT = "stream_extension"
        const val KEY_FIRST_AVAILABLE = "first_available"
        const val KEY_FALLBACK_BEST_ONLY = "fallback_best_only"

        // Track matching / lookup tuning
        const val STREAM_LOOKUP_TIMEOUT_MS = 12_000L
        // First Available Mode: backup extensions are only started if the preferred one
        // fails/misses, or hasn't answered within this time (so a slow source can't stall playback).
        const val FALLBACK_START_DELAY_MS = 4_000L
        const val MATCH_CACHE_SIZE = 200
        const val MATCH_TTL_MS = 30 * 60 * 1000L
        const val MISS_TTL_MS = 5 * 60 * 1000L
        const val MIN_MATCH_SCORE = 55
        const val KEY_INCLUDE_META_FALLBACK = "include_meta_fallback"
    }

    private lateinit var settings: Settings

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> {
        val extensions = awaitExtensions()
        val extIds = extensions.map { it.id }
        val extNames = extensions.map { it.name }

        val altIds = listOf("none") + extIds
        val altNames = listOf("None") + extNames

        val settingsList = mutableListOf<Setting>(
            SettingList(
                "Metadata Extension",
                KEY_METADATA_EXT,
                "Extension to use for metadata (search, feed, album info, etc.)",
                extNames,
                extIds,
                0
            ),
            SettingSwitch(
                "First Available Mode",
                KEY_FIRST_AVAILABLE,
                "Return only streams from the highest priority extension that finds the track. " +
                    "Keeps the app's Highest/Medium/Lowest quality mapped to that extension's own " +
                    "tiers (e.g. FLAC / 320 / 128) instead of a mix of all sources",
                true
            ),
            SettingSwitch(
                "Fallback Sources: Best Quality Only",
                KEY_FALLBACK_BEST_ONLY,
                "If the preferred extension can't find the track and a backup one is used " +
                    "(e.g. YouTube), expose only its highest quality stream, " +
                    "regardless of the app's Medium/Lowest quality setting",
                true
            ),
            SettingSwitch(
                "Include Metadata Extension As Fallback Stream",
                KEY_INCLUDE_META_FALLBACK,
                "If disabled, the Metadata Extension (e.g. Spotify) is NOT added as an extra " +
                    "stream source when it isn't explicitly picked. Keeping this off avoids " +
                    "low-bitrate metadata previews (e.g. Spotify 30s previews) polluting the " +
                    "quality list and breaking 'Medium' quality selection in the main app.",
                false
            ),
            SettingList(
                "Preferred Stream Extension",
                KEY_STREAM_EXT,
                "The primary extension to use for audio streaming",
                extNames,
                extIds,
                0
            )
        )

        var lastKey = KEY_STREAM_EXT
        val suffixes = listOf("2nd", "3rd", "4th", "5th")
        for (i in suffixes.indices) {
            val value = runCatching { settings.getString(lastKey) }.getOrNull()
            if (value != null && value != "none") {
                val currentKey = "${KEY_STREAM_EXT}_${i + 1}"
                settingsList.add(
                    SettingList(
                        "${suffixes[i]} Alternative Stream Extension",
                        currentKey,
                        "Backup extension if the previous ones fail to find the track",
                        altNames,
                        altIds,
                        0
                    )
                )
                lastKey = currentKey
            } else {
                break
            }
        }

        return settingsList
    }

    // --- MusicExtensionsProvider ---

    override val requiredMusicExtensions: List<String> = emptyList()

    private val extensionsFlow = MutableStateFlow<List<MusicExtension>?>(null)

    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        extensionsFlow.value = extensions.filter { it.id != COMBINE_ID }
    }

    private suspend fun awaitExtensions(): List<MusicExtension> =
        extensionsFlow.first { it != null }!!

    private suspend fun getMetadataExtension(): MusicExtension {
        return getSelectedExtension(KEY_METADATA_EXT)
    }

    private suspend fun getStreamExtensions(): List<MusicExtension> {
        val exts = awaitExtensions()
        val result = mutableListOf<MusicExtension>()
        val metaExt = getMetadataExtension()

        fun findExt(id: String?): MusicExtension? {
            if (id == null || id == "none") return null
            return exts.find { it.id == id || it.name == id }
        }

        // Migration
        val selectedSet = runCatching { settings.getStringSet(KEY_STREAM_EXT) }.getOrNull()
        if (!selectedSet.isNullOrEmpty()) {
            return exts.filter { it.id in selectedSet }
        }

        // Priority Chain: Preferred -> 2nd -> ...
        findExt(runCatching { settings.getString(KEY_STREAM_EXT) }.getOrNull())?.let { result.add(it) }
        for (i in 1..5) {
            val id = runCatching { settings.getString("${KEY_STREAM_EXT}_$i") }.getOrNull()
            findExt(id)?.let { result.add(it) } ?: break
        }

        val includeMetaFallback = settings.getBoolean(KEY_INCLUDE_META_FALLBACK) == true
        if (includeMetaFallback && result.none { it.id == metaExt.id }) {
            result.add(metaExt)
        }

        if (result.isEmpty() && exts.isNotEmpty()) {
            result.add(exts.first())
        }
        return result.distinctBy { it.id }
    }

    private suspend fun getSelectedExtension(key: String): MusicExtension {
        val exts = awaitExtensions()
        val selectedValue = runCatching { settings.getString(key) }.getOrNull()

        if (selectedValue == null) {
            val setValues = runCatching { settings.getStringSet(key) }.getOrNull()
            if (!setValues.isNullOrEmpty()) {
                val match = exts.find { it.id in setValues }
                if (match != null) return match
            }
            return exts.firstOrNull() ?: throw Exception("No music extensions installed")
        }

        val match = exts.find { it.id == selectedValue || it.name == selectedValue }
        if (match != null) return match

        val index = selectedValue.toIntOrNull()
        if (index != null && index in exts.indices) {
            return exts[index]
        }

        return exts.firstOrNull() ?: throw Exception("No music extensions installed")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T> MusicExtension.client(): T {
        return instance.value().getOrThrow() as T
    }

    private suspend inline fun <reified T> MusicExtension.clientOrNull(): T? {
        return instance.value().getOrNull() as? T
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> = getMetadataExtension().client<HomeFeedClient>().loadHomeFeed()

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val client = getMetadataExtension().clientOrNull<LibraryFeedClient>()
            ?: throw Exception("The selected metadata extension does not support the Library feed.")
        return client.loadLibraryFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = getMetadataExtension().client<SearchFeedClient>().loadSearchFeed(query)

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val metaExt = getMetadataExtension()
        val metaClient = metaExt.client<TrackClient>()
        val metaTrack = metaClient.loadTrack(track, isDownload)

        val streamExts = getStreamExtensions()
        val isFirstAvailable = settings.getBoolean(KEY_FIRST_AVAILABLE) != false
        val fallbackBestOnly = settings.getBoolean(KEY_FALLBACK_BEST_ONLY) != false

        val allStreamables = if (isFirstAvailable) {
            val found = selectFirstFound(metaTrack, streamExts, metaExt)
            if (found == null) {
                metaTrack.streamables
            } else {
                val (index, list) = found
                // index 0 = preferred extension: keep all its tiers (FLAC/320/128), so the app's
                // Highest/Medium/Lowest map onto them. Any backup extension: best stream only.
                if (index > 0 && fallbackBestOnly) {
                    listOfNotNull(list.maxByOrNull { it.quality })
                } else list
            }
        } else {
            coroutineScope {
                val deferreds = streamExts.mapIndexed { index, ext ->
                    // huge priority shift: preferred = 100000000 bonus. do better
                    val priorityBonus = (streamExts.size - index) * 10_000_000
                    if (ext.id == metaExt.id) {
                        async { metaTrack.streamables.map { it.copy(quality = it.quality + priorityBonus) } }
                    } else {
                        async { findTrackInStreamExtension(metaTrack, ext)?.streamables?.map { it.copy(quality = it.quality + priorityBonus) } ?: emptyList() }
                    }
                }
                deferreds.awaitAll().flatten().sortedByDescending { it.quality }.map { it }
            }
        }

        return metaTrack.copy(streamables = allStreamables)
    }

    private class Found(val list: List<Streamable>?)

    /**
     * Lazy, priority-ordered lookup: only the preferred extension is queried at first.
     * The next one is started when the previous one found nothing / failed, or when it is
     * still silent after [FALLBACK_START_DELAY_MS]. Returns (index in [exts], streamables).
     */
    private suspend fun selectFirstFound(
        metaTrack: Track,
        exts: List<MusicExtension>,
        metaExt: MusicExtension
    ): Pair<Int, List<Streamable>>? = coroutineScope {
        val deferreds = ArrayList<Deferred<Found>>()
        var next = 0

        fun launchNext() {
            val ext = exts[next++]
            deferreds += async {
                if (ext.id == metaExt.id) {
                    Found(metaTrack.streamables.ifEmpty { null })
                } else {
                    Found(tryOrNull { findTrackInStreamExtension(metaTrack, ext)?.streamables?.ifEmpty { null } })
                }
            }
        }

        if (exts.isNotEmpty()) launchNext()
        var idx = 0
        while (idx < deferreds.size) {
            val d = deferreds[idx]
            val canStartMore = next < exts.size
            // null = still running after the delay (not "found nothing")
            val res = if (canStartMore) withTimeoutOrNull(FALLBACK_START_DELAY_MS) { d.await() } else d.await()
            when {
                res == null -> launchNext()
                res.list != null -> {
                    deferreds.forEach { it.cancel() }
                    return@coroutineScope idx to res.list
                }
                else -> {
                    idx++
                    if (idx == deferreds.size && next < exts.size) launchNext()
                }
            }
        }
        null
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val streamExtId = streamable.extras["stream_ext_id"]
        val exts = awaitExtensions()
        val ext = if (streamExtId != null) exts.find { it.id == streamExtId } else getMetadataExtension()
        return ext?.client<TrackClient>()?.loadStreamableMedia(streamable, isDownload) ?: throw Exception("Extension not found")
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = getMetadataExtension().client<TrackClient>().loadFeed(track)

    // --- Track matching: metadata track -> best matching track in a stream extension ---

    private class Lookup(val track: Track?, val cacheable: Boolean)
    private class CacheEntry(val track: Track?, val expiresAt: Long)

    private val matchCache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MATCH_CACHE_SIZE
    }

    // Like runCatching().getOrNull(), but never swallows coroutine cancellation (needed for timeouts).
    private inline fun <T> tryOrNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun findTrackInStreamExtension(metaTrack: Track, streamExt: MusicExtension): Track? {
        val key = "${metaTrack.id}|${streamExt.id}"
        val cached = synchronized(matchCache) {
            val hit = matchCache[key]
            if (hit != null && hit.expiresAt <= System.currentTimeMillis()) {
                matchCache.remove(key)
                null
            } else hit
        }
        if (cached != null) return cached.track

        // Timeout (null) is never cached, so a slow/hanging extension is retried next time.
        val lookup = withTimeoutOrNull(STREAM_LOOKUP_TIMEOUT_MS) {
            searchStreamTrack(metaTrack, streamExt)
        } ?: return null

        if (lookup.cacheable) {
            val ttl = if (lookup.track != null) MATCH_TTL_MS else MISS_TTL_MS
            synchronized(matchCache) {
                matchCache[key] = CacheEntry(lookup.track, System.currentTimeMillis() + ttl)
            }
        }
        return lookup.track
    }

    private suspend fun searchStreamTrack(metaTrack: Track, streamExt: MusicExtension): Lookup {
        val searchClient = tryOrNull { streamExt.client<SearchFeedClient>() } ?: return Lookup(null, false)
        val trackClient = tryOrNull { streamExt.client<TrackClient>() } ?: return Lookup(null, false)

        val artistName = metaTrack.artists.firstOrNull()?.name ?: ""
        val query = "${metaTrack.title} $artistName".trim()
        if (query.isEmpty()) return Lookup(null, true)

        val feed = tryOrNull { searchClient.loadSearchFeed(query) } ?: return Lookup(null, false)
        val shelves = tryOrNull {
            feed.getPagedData(feed.tabs.firstOrNull()).pagedData.loadPage(null).data
        } ?: return Lookup(null, false)

        val candidates = shelves.flatMap { tracksIn(it) }
            .map { it to scoreMatch(metaTrack, it) }
            .filter { it.second >= MIN_MATCH_SCORE }
            .sortedByDescending { it.second }
            .distinctBy { it.first.id }
            .take(3)

        var cacheable = true
        for ((candidate, _) in candidates) {
            val loaded = tryOrNull { trackClient.loadTrack(candidate, false) }
            if (loaded == null) {
                cacheable = false
                continue
            }
            if (loaded.streamables.isNotEmpty()) {
                return Lookup(
                    loaded.copy(streamables = loaded.streamables.map { s ->
                        s.copy(extras = s.extras + mapOf("stream_ext_id" to streamExt.id))
                    }),
                    true
                )
            }
        }
        return Lookup(null, cacheable)
    }

    private fun tracksIn(shelf: Shelf): List<Track> = when (shelf) {
        is Shelf.Item -> listOfNotNull(shelf.media as? Track)
        is Shelf.Lists.Tracks -> shelf.list
        is Shelf.Lists.Items -> shelf.list.filterIsInstance<Track>()
        else -> emptyList()
    }

    private val parenRegex = Regex("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]")
    private val dashSuffixRegex = Regex("\\s[-\u2013\u2014]\\s.*$")
    private val featRegex = Regex("\\b(feat|ft|featuring)\\b\\.?.*$")
    private val nonWordRegex = Regex("[^\\p{L}\\p{N}\\s]")
    private val spacesRegex = Regex("\\s+")
    private val versionTags = listOf(
        "live", "remix", "acoustic", "instrumental", "karaoke", "cover",
        "slowed", "sped up", "reverb", "nightcore", "mashup", "demo"
    )

    private fun normalize(s: String): String =
        s.lowercase().replace(nonWordRegex, " ").replace(spacesRegex, " ").trim()

    // "Song (feat. X) - Remastered 2011" -> "song"
    private fun baseTitle(s: String): String {
        val stripped = s.lowercase()
            .replace(parenRegex, " ")
            .replace(dashSuffixRegex, " ")
            .replace(featRegex, " ")
        return normalize(stripped).ifEmpty { normalize(s) }
    }

    private fun versionTagsOf(title: String): Set<String> {
        val n = " ${normalize(title)} "
        return versionTags.filter { n.contains(" $it ") }.toSet()
    }

    /**
     * How well [cand] (a search result from a stream extension) matches [meta].
     * ISRC match wins outright; otherwise title + artist + duration + version tags (live/remix/...).
     * Returns a score; >= MIN_MATCH_SCORE counts as a match.
     */
    private fun scoreMatch(meta: Track, cand: Track): Int {
        val metaIsrc = meta.isrc
        val candIsrc = cand.isrc
        if (!metaIsrc.isNullOrBlank() && metaIsrc.equals(candIsrc, ignoreCase = true)) return 1000

        val mt = baseTitle(meta.title)
        val ct = baseTitle(cand.title)
        if (mt.isEmpty() || ct.isEmpty()) return 0

        var score = when {
            mt == ct -> 60
            (mt.length >= 4 && ct.contains(mt)) || (ct.length >= 4 && mt.contains(ct)) -> 30
            else -> return 0
        }

        val metaArtists = meta.artists.map { normalize(it.name) }.filter { it.isNotEmpty() }
        val candArtists = cand.artists.map { normalize(it.name) }.filter { it.isNotEmpty() }
        if (metaArtists.isNotEmpty() && candArtists.isNotEmpty()) {
            val full = metaArtists.any { m -> candArtists.any { c -> c == m || c.contains(m) || m.contains(c) } }
            val partial = metaArtists.any { m ->
                m.split(" ").any { part -> part.length > 2 && candArtists.any { c -> c.contains(part) } }
            }
            score += when {
                full -> 30
                partial -> 15
                else -> -40
            }
        }

        val md = meta.duration
        val cd = cand.duration
        if (md != null && cd != null && md > 0 && cd > 0) {
            val diff = abs(md - cd)
            score += when {
                diff <= 3_000 -> 25
                diff <= 8_000 -> 10
                diff <= 15_000 -> -10
                else -> -50
            }
        }

        val metaTags = versionTagsOf(meta.title)
        val candTags = versionTagsOf(cand.title)
        score -= 40 * (candTags - metaTags).size
        score -= 30 * (metaTags - candTags).size

        return score
    }

    override suspend fun loadAlbum(album: Album): Album = getMetadataExtension().client<AlbumClient>().loadAlbum(album)
    override suspend fun loadTracks(album: Album): Feed<Track>? = getMetadataExtension().client<AlbumClient>().loadTracks(album)
    override suspend fun loadFeed(album: Album): Feed<Shelf>? = getMetadataExtension().client<AlbumClient>().loadFeed(album)
    override suspend fun loadArtist(artist: Artist): Artist = getMetadataExtension().client<ArtistClient>().loadArtist(artist)
    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = getMetadataExtension().client<ArtistClient>().loadFeed(artist)
    override suspend fun loadPlaylist(playlist: Playlist): Playlist = getMetadataExtension().client<PlaylistClient>().loadPlaylist(playlist)
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = getMetadataExtension().client<PlaylistClient>().loadTracks(playlist)
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = getMetadataExtension().client<PlaylistClient>().loadFeed(playlist)
    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.listEditablePlaylists(track) ?: emptyList()
    override suspend fun createPlaylist(title: String, description: String?): Playlist = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.createPlaylist(title, description) ?: throw Exception("Not supported")
    override suspend fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>, position: Int, previousTracks: List<Track>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.addTracksToPlaylist(playlist, tracks, position, previousTracks) ?: throw Exception("Not supported")
    override suspend fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.removeTracksFromPlaylist(playlist, tracks, indexes) ?: throw Exception("Not supported")
    override suspend fun moveTrackInPlaylist(playlist: Playlist, tracks: List<Track>, fromPosition: Int, toPosition: Int) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.moveTrackInPlaylist(playlist, tracks, fromPosition, toPosition) ?: throw Exception("Not supported")
    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.editPlaylistMetadata(playlist, title, description) ?: throw Exception("Not supported")
    override suspend fun deletePlaylist(playlist: Playlist) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.deletePlaylist(playlist) ?: throw Exception("Not supported")
    override suspend fun isItemSaved(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<SaveClient>()?.isItemSaved(item) ?: false
    override suspend fun saveToLibrary(item: EchoMediaItem, isSave: Boolean) = getMetadataExtension().clientOrNull<SaveClient>()?.saveToLibrary(item, isSave) ?: throw Exception("Not supported")
    override suspend fun isItemLiked(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<LikeClient>()?.isItemLiked(item) ?: false
    override suspend fun likeItem(item: EchoMediaItem, isLike: Boolean) = getMetadataExtension().clientOrNull<LikeClient>()?.likeItem(item, isLike) ?: throw Exception("Not supported")
    override suspend fun onShare(item: EchoMediaItem): String = getMetadataExtension().clientOrNull<ShareClient>()?.onShare(item) ?: throw Exception("Not supported")
    override suspend fun isFollowing(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<FollowClient>()?.isFollowing(item) ?: false
    override suspend fun followItem(item: EchoMediaItem, isFollow: Boolean) = getMetadataExtension().clientOrNull<FollowClient>()?.followItem(item, isFollow) ?: throw Exception("Not supported")
    override suspend fun getFollowersCount(item: EchoMediaItem): Long? = getMetadataExtension().clientOrNull<FollowClient>()?.getFollowersCount(item)
    override suspend fun isItemHidden(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<HideClient>()?.isItemHidden(item) ?: false
    override suspend fun hideItem(item: EchoMediaItem, isHide: Boolean) = getMetadataExtension().clientOrNull<HideClient>()?.hideItem(item, isHide) ?: throw Exception("Not supported")
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.radio(item, context) ?: throw Exception("Not supported")
    override suspend fun loadRadio(radio: Radio): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.loadRadio(radio) ?: throw Exception("Not supported")
    override suspend fun loadTracks(radio: Radio): Feed<Track> = getMetadataExtension().clientOrNull<RadioClient>()?.loadTracks(radio) ?: throw Exception("Not supported")
}
