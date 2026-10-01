package nl.jeroen.massqueue

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * De browse tree die Android Auto toont: root → "Favorieten" (favoriete playlists) en
 * "Radio" (favoriete zenders), plus zoekresultaten. Media-ID's van afspeelbare items zijn
 * gewoon de MA-URI's, zodat [SendspinPlaybackService] ze direct aan play_media kan geven.
 */
class AutoBrowseTree(private val api: suspend () -> MassApiClient?) {

    /** Laatst opgebouwde items, voor onGetItem. */
    private val known = ConcurrentHashMap<String, MediaItem>()

    fun root(): MediaItem = folder(ROOT, "SpinFlow", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun rootExtras(): Bundle = Bundle().apply {
        putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
        putInt(EXTRA_BROWSABLE_HINT, STYLE_LIST)
        putInt(EXTRA_PLAYABLE_HINT, STYLE_GRID)
        putBoolean(EXTRA_SEARCH_SUPPORTED, true)
    }

    fun isFolder(id: String) = id == ROOT || id == FAVORITES || id == RADIO

    fun item(id: String): MediaItem? = when (id) {
        ROOT -> root()
        FAVORITES -> favoritesFolder()
        RADIO -> radioFolder()
        else -> known[id]
    }

    suspend fun children(parentId: String): List<MediaItem> {
        val client = api() ?: return emptyList()
        return when (parentId) {
            ROOT -> listOf(favoritesFolder(), radioFolder())
            FAVORITES -> client.getFavoritePlaylists().map {
                playable(it.uri, it.name, it.trackCount?.let { n -> "$n nummers" }, it.imagePath, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            RADIO -> client.getFavoriteRadios().map {
                playable(it.uri, it.name, null, it.imagePath, MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            }
            else -> emptyList()
        }
    }

    suspend fun search(query: String): List<MediaItem> {
        val r = api()?.search(query) ?: return emptyList()
        return r.tracks.map { playable(it.uri, it.title, it.subtitle, it.imagePath, MediaMetadata.MEDIA_TYPE_MUSIC, "Nummers") } +
            r.artists.map { playable(it.uri, it.name, null, it.imagePath, MediaMetadata.MEDIA_TYPE_ARTIST, "Artiesten") } +
            r.albums.map { playable(it.uri, it.name, it.subtitle, it.imagePath, MediaMetadata.MEDIA_TYPE_ALBUM, "Albums") } +
            r.playlists.map { playable(it.uri, it.name, null, it.imagePath, MediaMetadata.MEDIA_TYPE_PLAYLIST, "Playlists") }
    }

    /**
     * Wat er bij een gesproken opdracht ("speel X op SpinFlow") moet spelen: eerst een exacte
     * naam (playlist, artiest, album, nummer), anders het eerste nummer. Lege opdracht
     * ("speel muziek") → de eerste favoriete playlist.
     */
    suspend fun bestMatchUri(query: String): String? {
        val client = api() ?: return null
        val q = query.trim()
        if (q.isEmpty()) return client.getFavoritePlaylists().firstOrNull()?.uri
        val r = client.search(q)
        fun String.same() = trim().equals(q, ignoreCase = true)
        return r.playlists.firstOrNull { it.name.same() }?.uri
            ?: r.artists.firstOrNull { it.name.same() }?.uri
            ?: r.albums.firstOrNull { it.name.same() }?.uri
            ?: r.tracks.firstOrNull { it.title.same() }?.uri
            ?: r.tracks.firstOrNull()?.uri
            ?: r.playlists.firstOrNull()?.uri
            ?: r.artists.firstOrNull()?.uri
            ?: r.albums.firstOrNull()?.uri
    }

    private fun favoritesFolder() = folder(FAVORITES, "Favorieten", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
    private fun radioFolder() = folder(RADIO, "Radio", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)

    private fun folder(id: String, title: String, type: Int) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build()
        )
        .build()

    private fun playable(
        uri: String,
        title: String,
        subtitle: String?,
        imagePath: String?,
        type: Int,
        group: String? = null
    ): MediaItem = MediaItem.Builder()
        .setMediaId(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(ArtworkProvider.uriFor(imagePath))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(type)
                .apply { group?.let { setExtras(Bundle().apply { putString(EXTRA_GROUP_TITLE, it) }) } }
                .build()
        )
        .build()
        .also { known[uri] = it }

    companion object {
        const val ROOT = "root"
        const val FAVORITES = "favorites"
        const val RADIO = "radio"

        // Android Auto-extra's (androidx.media.utils.MediaConstants), hier letterlijk om geen extra dependency te hoeven
        private const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val EXTRA_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val EXTRA_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val EXTRA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val EXTRA_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        private const val STYLE_LIST = 1
        private const val STYLE_GRID = 2
    }
}
