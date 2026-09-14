package nl.jeroen.massqueue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Praat rechtstreeks met de Music Assistant server (niet via HA Ingress).
 * Gebruikt dezelfde JSON-RPC-achtige body { message_id, command, args }
 * die ook al in de web-dashboardkaart werkt, maar dan zonder de
 * sessie-cookie-afhankelijkheid van Ingress: gewoon een POST naar
 * <baseUrl>/api op je lokale netwerk of via je eigen tunnel.
 *
 * baseUrl voorbeeld: http://192.168.x.x:8095
 */
class MassApiClient(baseUrl: String, private var authToken: String? = null) {

    private var _baseUrl: String = ""

    init {
        updateConfig(baseUrl, authToken)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun updateConfig(newBaseUrl: String, newToken: String?) {
        var url = newBaseUrl.trim().trimEnd('/')
        if (!url.startsWith("http") && url.isNotBlank()) {
            url = "http://$url"
        }
        _baseUrl = url
        authToken = newToken
        aiRadioOptionsCache = null
    }

    private val jsonMediaType = "application/json".toMediaType()

    suspend fun call(command: String, args: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("message_id", 1) // Terug naar integer, string veroorzaakte 500 errors
                put("command", command)
                put("args", args)
            }

            val requestBuilder = Request.Builder()
                .url("$_baseUrl/api")
                .post(body.toString().toRequestBody(jsonMediaType))
            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            client.newCall(requestBuilder.build()).execute().use { resp ->
                val code = resp.code
                val text = resp.body?.string()?.trim() ?: "{}"
                
                if (!resp.isSuccessful) {
                    throw MassApiException("Server fout $code: $text")
                }
                
                return@withContext when {
                    text.startsWith("[") -> {
                        JSONObject().put("result", JSONArray(text))
                    }
                    text.startsWith("{") -> {
                        val json = JSONObject(text)
                        if (json.has("error_code")) {
                            throw MassApiException(json.optString("details", json.optString("error_code")))
                        }
                        // HA REST API geeft soms direct een lijst terug, of een result object
                        if (json.has("result")) json else JSONObject().put("result", json)
                    }
                    else -> {
                        // HA REST API geeft soms 200 OK met lege body of "OK"
                        JSONObject().put("result", text)
                    }
                }
            }
        }

    /**
     * Roept een Home Assistant service aan via de REST API.
     * Endpoint: /api/services/<domain>/<service>
     */
    suspend fun callHaService(domain: String, service: String, data: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val url = "$_baseUrl/api/services/$domain/$service"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(data.toString().toRequestBody(jsonMediaType))
            
            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            client.newCall(requestBuilder.build()).execute().use { resp ->
                val text = resp.body?.string()?.trim() ?: "[]"
                if (!resp.isSuccessful) {
                    throw MassApiException("HA Service fout ${resp.code}: $text")
                }
                // HA geeft meestal een lijst van state-changes terug
                return@withContext if (text.startsWith("[")) {
                    JSONObject().put("result", JSONArray(text))
                } else if (text.startsWith("{")) {
                    JSONObject(text)
                } else {
                    JSONObject().put("result", text)
                }
            }
        }

    suspend fun getAllPlayers(): List<MassPlayer> {
        val result = call("players/all")
        val arr: JSONArray = result.optJSONArray("result") ?: JSONArray()
        val list = mutableListOf<MassPlayer>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val id = p.optString("player_id")
            if (id.isBlank()) continue
            
            // We zoeken naar de actieve bron (playlist, radio, etc)
            val activeSource = p.optString("active_source").takeIf { it.isNotBlank() }
            val provider = p.optString("provider").takeIf { it.isNotBlank() }
            val model = p.optJSONObject("device_info")?.optString("model")?.takeIf { it.isNotBlank() }

            list.add(
                MassPlayer(
                    id = id,
                    name = p.optString("display_name", p.optString("name", id)),
                    playbackState = p.optString("playback_state", null),
                    volumeLevel = if (p.has("volume_level")) p.optInt("volume_level") else null,
                    volumeMuted = if (p.has("volume_muted")) p.optBoolean("volume_muted") else null,
                    activeSource = activeSource,
                    provider = provider,
                    model = model
                )
            )
        }
        return list.sortedBy { it.name.lowercase() }
    }

    private val nameCache = mutableMapOf<String, String>()

    suspend fun resolveMediaName(uri: String): String? {
        if (uri.isBlank() || uri.lowercase() == "queue") return null
        if (nameCache.containsKey(uri)) return nameCache[uri]
        
        return try {
            val result = call("music/item", JSONObject().put("uri", uri))
            val item = result.optJSONObject("result") ?: result
            
            val name = item.optString("name")
                .ifBlank { item.optJSONObject("metadata")?.optString("title") }
            
            val finalName = name?.takeIf { it.isNotBlank() }
            if (finalName != null) {
                nameCache[uri] = finalName
            }
            finalName
        } catch (ignore: Exception) {
            null
        }
    }

    /**
     * Lichte samenvatting van alle wachtrijen in één call (i.p.v. per speler `player_queues/get`
     * te moeten doen). Gebruikt om te bepalen welke spelers een wachtrij geladen hebben, ook als
     * `players/all` geen `active_source` teruggeeft (bv. bij synced groepen zoals "SPZ").
     */
    suspend fun getAllQueueSummaries(): List<QueueSummary> {
        val result = call("player_queues/all")
        val arr: JSONArray = result.optJSONArray("result") ?: JSONArray()
        val list = mutableListOf<QueueSummary>()
        for (i in 0 until arr.length()) {
            val q = arr.getJSONObject(i)
            val queueId = q.optString("queue_id")
            if (queueId.isBlank()) continue
            list.add(
                QueueSummary(
                    queueId = queueId,
                    hasItems = q.optInt("items", 0) > 0,
                    isPlaying = q.optString("state").equals("playing", ignoreCase = true)
                )
            )
        }
        return list
    }

    suspend fun getQueue(playerId: String): QueueState? {
        val queueResult = call("player_queues/get", JSONObject().put("queue_id", playerId)).optJSONObject("result")
            ?: return null

        val currentIndex = queueResult.optInt("current_index", 0)
        val totalItems = queueResult.optInt("items", 0)
        val active = queueResult.optBoolean("active", false)
        val shuffleEnabled = queueResult.optBoolean("shuffle_enabled", false)
        val activeSourceUri = queueResult.optString("active_source").takeIf { it.isNotBlank() }
        val elapsedTime = if (queueResult.has("elapsed_time")) queueResult.optInt("elapsed_time") else null
        
        // We proberen de naam te bepalen. We negeren namen die op 'Queue' lijken.
        var playlistName = queueResult.optString("display_name")
            .takeIf { it.isNotBlank() && !it.equals("queue", true) && !it.equals("wachtrij", true) }

        // Als we een URI hebben, is dat ALTIJD de meest betrouwbare bron voor de echte naam.
        // We vragen de server om deze URI te vertalen naar een menselijke naam.
        if (activeSourceUri != null && activeSourceUri.lowercase() != "queue") {
            val resolvedName = resolveMediaName(activeSourceUri)
            if (resolvedName != null) {
                playlistName = resolvedName
            }
        }

        // Fallback naar stream_title voor radio als we nog steeds niets hebben
        if (playlistName == null) {
            val currentItem = queueResult.optJSONObject("current_item")
            playlistName = currentItem?.optString("stream_title")?.takeIf { it.isNotBlank() }
        }

        val fromIndex = maxOf(0, currentIndex - 2)
        val toIndex = minOf(totalItems - 1, currentIndex + 50)
        val limit = toIndex - fromIndex + 1

        val tracks = mutableListOf<QueueTrack>()
        if (limit > 0) {
            val itemsResult = call(
                "player_queues/items",
                JSONObject().put("queue_id", playerId).put("limit", limit).put("offset", fromIndex)
            )
            val arr = itemsResult.optJSONArray("result") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                tracks.add(parseQueueTrack(it, fromIndex + i))
            }
        }

        // `player_queues/items` levert de streamdetails lang niet altijd mee, maar
        // `player_queues/get` -> `current_item` wél. Die live radio-metadata plakken
        // we op de nu-spelende track zodat de UI artiest + songtitel kan tonen.
        val currentMeta = extractStreamMeta(queueResult.optJSONObject("current_item"))
        if (currentMeta != null) {
            val idx = tracks.indexOfFirst { it.absoluteIndex == currentIndex }
            if (idx >= 0 && !tracks[idx].hasStreamInfo) {
                tracks[idx] = tracks[idx].copy(
                    streamArtist = currentMeta.artist,
                    streamTrack = currentMeta.track,
                    streamAlbum = currentMeta.album ?: tracks[idx].streamAlbum,
                    streamImage = currentMeta.image ?: tracks[idx].streamImage
                )
            }
        }

        return QueueState(
            active = active, 
            currentIndex = currentIndex, 
            items = tracks,
            shuffleEnabled = shuffleEnabled,
            playlistName = playlistName,
            activeSourceUri = activeSourceUri,
            elapsedTime = elapsedTime
        )
    }

    private fun resolveImageUrl(item: JSONObject?, media: JSONObject?): String? {
        val rawPath = findPathInJson(item) ?: findPathInJson(media)
            ?: findPathInJson(item?.optJSONObject("metadata"))
            ?: findPathInJson(media?.optJSONObject("metadata"))
        
        if (rawPath.isNullOrBlank()) return null
        
        var finalUrl = rawPath
        
        // 1. We herschrijven ELKE lokale URL of URL van een bekende tunnel naar ons huidige actieve adres.
        // Dit zorgt ervoor dat plaatjes altijd laden via de verbinding die we nu gebruiken (IP of Tailscale).
        val isInternal = finalUrl.startsWith("/") || 
                         finalUrl.contains("localhost") || 
                         finalUrl.contains("127.0.0.1") || 
                         finalUrl.contains(".local") ||
                         finalUrl.contains(".ts.net") ||
                         finalUrl.contains("8095")
                         
        if (isInternal) {
            val pathOnly = try {
                val url = java.net.URL(finalUrl)
                url.path + (if (url.query != null) "?" + url.query else "")
            } catch (ignore: Exception) {
                val doubleSlash = finalUrl.indexOf("//")
                if (doubleSlash != -1) {
                    val firstSlash = finalUrl.indexOf("/", doubleSlash + 2)
                    if (firstSlash != -1) finalUrl.substring(firstSlash) else "/"
                } else {
                    if (finalUrl.startsWith("/")) finalUrl else "/$finalUrl"
                }
            }
            finalUrl = "${_baseUrl.trimEnd('/')}${if (pathOnly.startsWith("/")) "" else "/"}$pathOnly"
        }
        
        // 2. VOEG TOKEN ALTIJD TOE AAN LOKALE LINKS
        // Voor plaatjes van onze eigen server is dit de meest betrouwbare methode.
        if (finalUrl.startsWith(_baseUrl) && !authToken.isNullOrBlank() && !finalUrl.contains("token=")) {
            val separator = if (finalUrl.contains("?")) "&" else "?"
            finalUrl = "$finalUrl${separator}token=$authToken"
        }
        
        return finalUrl
    }

    private fun findPathInJson(obj: JSONObject?): String? {
        if (obj == null) return null
        
        // 1. Check "images" lijst (meest voorkomend in MA)
        obj.optJSONArray("images")?.let { arr ->
            if (arr.length() > 0) {
                val first = arr.getJSONObject(0)
                return first.optString("path", "").takeIf { it.isNotBlank() } 
                    ?: first.optString("url", "").takeIf { it.isNotBlank() }
            }
        }
        
        // 2. Check "image" object
        obj.optJSONObject("image")?.let { imageObj ->
            return imageObj.optString("path", "").takeIf { it.isNotBlank() } 
                ?: imageObj.optString("url", "").takeIf { it.isNotBlank() }
        }
        
        // 3. Check directe velden "path" of "url"
        val p = obj.optString("path", "")
        if (p.isNotBlank()) return p
        
        val u = obj.optString("url", "")
        if (u.isNotBlank()) return u

        // MA 2.0 radio stations vaak logo of icon
        obj.optString("logo").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("icon").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("thumbnail").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("image").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("picture").takeIf { it.isNotBlank() }?.let { return it }

        // 4. Check "metadata" object dieper
        obj.optJSONObject("metadata")?.let { meta ->
            meta.optJSONArray("images")?.let { arr ->
                if (arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    return first.optString("path").takeIf { it.isNotBlank() } 
                        ?: first.optString("url").takeIf { it.isNotBlank() }
                }
            }
            listOf("logo", "icon", "thumbnail", "poster", "fanart", "image", "picture").forEach { key ->
                meta.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        
        return null
    }

    private data class StreamMeta(
        val artist: String?,
        val track: String?,
        val album: String?,
        val image: String?
    )

    /**
     * Haalt de live radio-metadata uit een queue-item. Music Assistant zet die in
     * `streamdetails.stream_metadata` ({title, artist, image_url}); als dat ontbreekt
     * valt hij terug op de gecombineerde `stream_title` ("Artiest - Titel"), zowel
     * op `streamdetails`-niveau als (oudere MA) op het item zelf.
     */
    /** org.json geeft voor een JSON-null-waarde de string "null" terug bij optString(); filter dat weg. */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun extractStreamMeta(item: JSONObject?): StreamMeta? {
        if (item == null) return null
        val sd = item.optJSONObject("streamdetails")
        val sm = sd?.optJSONObject("stream_metadata")

        var artist = sm?.stringOrNull("artist")
        var track = sm?.stringOrNull("title")
        val album = sm?.stringOrNull("album")
        var image = sm?.stringOrNull("image_url")
        if (image == null) image = sm?.stringOrNull("image")

        val combined = sd?.stringOrNull("stream_title")
            ?: item.stringOrNull("stream_title")
        if ((artist == null || track == null) && combined != null) {
            if (combined.contains(" - ")) {
                if (artist == null) artist = combined.substringBefore(" - ").trim().takeIf { it.isNotBlank() }
                if (track == null) track = combined.substringAfter(" - ").trim().takeIf { it.isNotBlank() }
            } else if (track == null) {
                track = combined.trim()
            }
        }

        // Sommige zenders proppen "Artiest - Titel" óók in het titelveld; haal het dubbele
        // artiest-voorvoegsel er dan af zodat de titelregel alleen de songtitel toont.
        if (!artist.isNullOrBlank() && !track.isNullOrBlank()) {
            val prefix = "$artist - "
            if (track.startsWith(prefix, ignoreCase = true)) {
                track = track.substring(prefix.length).trim().takeIf { it.isNotBlank() } ?: track
            }
        }
        // Spiegelbeeld: soms zit "Artiest - Titel" juist in het artiestveld.
        if (!artist.isNullOrBlank() && !track.isNullOrBlank() && !artist.equals(track, ignoreCase = true)) {
            val suffix = " - $track"
            if (artist.endsWith(suffix, ignoreCase = true)) {
                artist = artist.dropLast(suffix.length).trim().takeIf { it.isNotBlank() } ?: artist
            }
        }

        if (artist == null && track == null && album == null && image == null) return null
        return StreamMeta(artist, track, album, image)
    }

    /**
     * Zoekt het jaar van uitgave in de track- of albummetadata (`metadata.year`).
     * Music Assistant zet dit op zowel het track- als het albumniveau; we proberen
     * beide en negeren onzinwaarden buiten een plausibel bereik.
     */
    private fun extractYear(media: JSONObject?): Int? {
        if (media == null) return null
        val candidates = listOf(
            media.optJSONObject("metadata")?.optInt("year", 0),
            media.optInt("year", 0),
            media.optJSONObject("album")?.optJSONObject("metadata")?.optInt("year", 0),
            media.optJSONObject("album")?.optInt("year", 0)
        )
        return candidates.firstOrNull { it != null && it in 1900..2100 }
    }

    private fun parseQueueTrack(item: JSONObject, absoluteIndex: Int): QueueTrack {
        val media = item.optJSONObject("media_item")
        val title = media?.optString("name")?.takeIf { it.isNotBlank() }
            ?: item.optString("name", "Onbekende track")

        val artists = media?.optJSONArray("artists")
        val artistNames = mutableListOf<String>()
        if (artists != null) {
            for (i in 0 until artists.length()) {
                artists.getJSONObject(i).optString("name").takeIf { it.isNotBlank() }?.let(artistNames::add)
            }
        }
        val albumName = media?.optJSONObject("album")?.optString("name")

        val subtitle = listOfNotNull(
            artistNames.joinToString(", ").takeIf { it.isNotBlank() },
            albumName?.takeIf { it.isNotBlank() }
        ).joinToString(" - ")

        val duration = if (item.has("duration")) item.optInt("duration") else null
        val queueItemId = item.optString("queue_item_id", item.optString("item_id", null))
        val trackUri = media?.optString("uri") ?: item.optString("uri", null)
        val mediaType = (media?.optString("media_type").takeIf { !it.isNullOrBlank() }
            ?: item.optString("media_type").takeIf { it.isNotBlank() })
        val stream = extractStreamMeta(item)

        return QueueTrack(
            absoluteIndex = absoluteIndex,
            queueItemId = queueItemId,
            title = title,
            subtitle = subtitle,
            durationSeconds = duration,
            imagePath = resolveImageUrl(item, media),
            uri = trackUri,
            mediaType = mediaType,
            streamArtist = stream?.artist,
            streamTrack = stream?.track,
            streamAlbum = stream?.album,
            streamImage = stream?.image,
            year = extractYear(media),
            artist = artistNames.joinToString(", ").takeIf { it.isNotBlank() }
        )
    }

    suspend fun getFavoritePlaylists(): List<MassPlaylist> {
        val result = call(
            "music/playlists/library_items",
            JSONObject().put("favorite", true).put("limit", 50)
        )
        val arr: JSONArray = result.optJSONArray("result") ?: JSONArray()
        val list = mutableListOf<MassPlaylist>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val uri = p.optString("uri", null) ?: continue
            
            list.add(
                MassPlaylist(
                    uri = uri,
                    name = p.optString("name", "Naamloze playlist"),
                    trackCount = if (p.has("track_count")) p.optInt("track_count") else null,
                    imagePath = resolveImageUrl(p, null)
                )
            )
        }
        return list.sortedBy { it.name.lowercase() }
    }

    suspend fun getFavoriteRadios(): List<MassRadio> {
        val list = mutableListOf<MassRadio>()
        
        // Poging 1: music/radios/library_items (zoals gespecificeerd voor JSON-RPC)
        try {
            val args = JSONObject().apply {
                put("favorite", true)
                put("order_by", "name")
            }
            val resp = call("music/radios/library_items", args)
            parseRadioItems(resp, list)
        } catch (ignore: Exception) {}

        // Poging 2: music/radios/library_items ZONDER favorite filter (client-side filtering fallback)
        if (list.isEmpty()) {
            try {
                val resp = call("music/radios/library_items", JSONObject().put("limit", 200))
                parseRadioItems(resp, list, filterFavorites = true)
            } catch (ignore: Exception) {}
        }

        // Poging 3: music/library (overeenkomstig met HA actie)
        if (list.isEmpty()) {
            try {
                val args = JSONObject().apply {
                    put("media_type", "radio")
                    put("favorite", true)
                }
                val resp = call("music/library", args)
                parseRadioItems(resp, list)
            } catch (ignore: Exception) {}
        }

        // Poging 2: music/radio/library_items
        if (list.isEmpty()) {
            try {
                val resp = call("music/radio/library_items", JSONObject().put("favorite", true).put("limit", 100))
                parseRadioItems(resp, list)
            } catch (ignore: Exception) {}
        }

        // Poging 3: music/radio/all
        if (list.isEmpty()) {
            try {
                val resp = call("music/radio/all", JSONObject().put("favorite", true).put("limit", 100))
                parseRadioItems(resp, list)
            } catch (ignore: Exception) {}
        }

        // Poging 4: music/radio_stations varianten
        if (list.isEmpty()) {
            try {
                val resp = call("music/radio_stations/library_items", JSONObject().put("favorite", true).put("limit", 100))
                parseRadioItems(resp, list)
            } catch (ignore: Exception) {}
            
            if (list.isEmpty()) {
                try {
                    val resp = call("music/radio_stations/all", JSONObject().put("favorite", true).put("limit", 100))
                    parseRadioItems(resp, list)
                } catch (ignore: Exception) {}
            }
        }
        
        // Fallback: Als favorieten leeg zijn, probeer ALLE radiozenders uit de library
        if (list.isEmpty()) {
            val fallbacks = listOf("music/radio/library_items", "music/radio_stations/library_items", "music/radio/all", "music/radio_stations/all")
            for (fb in fallbacks) {
                try {
                    val resp = call(fb, JSONObject().put("limit", 100))
                    parseRadioItems(resp, list)
                    if (list.isNotEmpty()) break
                } catch (ignore: Exception) {}
            }
        }

        return list.distinctBy { it.uri }.sortedBy { it.name.lowercase() }
    }

    private fun parseRadioItems(resp: JSONObject, list: MutableList<MassRadio>, filterFavorites: Boolean = false) {
        val resultObj = resp.opt("result")
        val arr: JSONArray = when (resultObj) {
            is JSONArray -> resultObj
            is JSONObject -> resultObj.optJSONArray("items") ?: resultObj.optJSONArray("result") ?: JSONArray()
            else -> JSONArray()
        }
        
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val uri = r.optString("uri", null) ?: continue
            
            // Als we client-side filteren, checken we het 'favorite' veld
            if (filterFavorites && !r.optBoolean("favorite", false)) {
                continue
            }

            list.add(
                MassRadio(
                    uri = uri,
                    name = r.optString("name", "Onbekend station"),
                    imagePath = resolveImageUrl(r, null)
                )
            )
        }
    }

    /**
     * Zoekt een nummer in Music Assistant (alle providers) en geeft de URI van de
     * beste match terug, of null als er niets bruikbaars is. Probeert een paar
     * arg-vormen omdat MA-versies verschillen.
     */
    suspend fun searchTrackUri(query: String): String? {
        if (query.isBlank()) return null
        val trackTypes = JSONArray(listOf("track"))
        val variants = listOf(
            JSONObject().put("search_query", query).put("media_types", trackTypes).put("limit", 8),
            JSONObject().put("query", query).put("media_types", trackTypes).put("limit", 8),
            JSONObject().put("search_query", query).put("limit", 8),
            JSONObject().put("name", query).put("media_types", trackTypes).put("limit", 8)
        )
        for (args in variants) {
            val resp = try { call("music/search", args) } catch (e: Exception) { continue }
            pickTrackUri(resp, query)?.let { return it }
        }
        return null
    }

    private fun pickTrackUri(resp: JSONObject, query: String): String? {
        val root = resp.opt("result")
        val tracks: JSONArray = when {
            root is JSONObject && root.optJSONArray("tracks") != null -> root.getJSONArray("tracks")
            root is JSONObject && root.optJSONArray("items") != null -> root.getJSONArray("items")
            root is JSONArray -> root
            resp.optJSONArray("tracks") != null -> resp.getJSONArray("tracks")
            else -> return null
        }
        if (tracks.length() == 0) return null

        val q = query.lowercase()
        var fallback: String? = null
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val uri = t.optString("uri").takeIf { it.isNotBlank() } ?: continue
            if (fallback == null) fallback = uri

            val name = t.optString("name").lowercase()
            val artistNames = mutableListOf<String>()
            t.optJSONArray("artists")?.let { a ->
                for (j in 0 until a.length()) {
                    a.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }
                        ?.let { artistNames.add(it.lowercase()) }
                }
            }
            // Sterke match: zowel de artiest als (een deel van) de titel komt in de query voor.
            if (name.isNotBlank() && q.contains(name) && artistNames.any { q.contains(it) }) {
                return uri
            }
        }
        return fallback
    }

    /** option: "play", "replace", "next", "add" */
    suspend fun playMedia(
        playerId: String,
        mediaUri: String,
        option: String
    ) {
        val args = JSONObject().apply {
            put("queue_id", playerId)
            put("media_item", mediaUri) 
            put("media", mediaUri)
            put("enqueue_mode", option)
            put("option", option)
        }
        call("player_queues/play_media", args)
    }

    suspend fun clearQueue(playerId: String) {
        val args = JSONObject().apply {
            put("queue_id", playerId)
        }
        call("player_queues/clear", args)
    }

    suspend fun shuffleQueue(playerId: String, shuffle: Boolean) {
        val args = JSONObject().apply {
            put("queue_id", playerId)
            // We sturen zowel 'shuffle' als 'shuffle_enabled' voor maximale compatibiliteit
            put("shuffle_enabled", shuffle)
            put("shuffle", shuffle)
        }
        // Dit commando husselt de huidige wachtrij direct door elkaar
        call("player_queues/shuffle", args)
    }

    suspend fun playIndex(playerId: String, index: Int) {
        call(
            "player_queues/play_index",
            JSONObject().put("queue_id", playerId).put("index", index)
        )
    }

    suspend fun moveItemNext(playerId: String, item: QueueTrack, currentIndex: Int) {
        val queueItemId = item.queueItemId ?: throw MassApiException("Geen item-id gevonden voor deze track.")
        val targetIndex = currentIndex + 1
        val posShift = targetIndex - item.absoluteIndex
        if (posShift == 0) return
        call(
            "player_queues/move_item",
            JSONObject().put("queue_id", playerId).put("queue_item_id", queueItemId).put("pos_shift", posShift)
        )
    }

    /** Verschuift een wachtrij-item [posShift] plaatsen (negatief = naar voren). */
    suspend fun moveQueueItem(playerId: String, item: QueueTrack, posShift: Int) {
        if (posShift == 0) return
        val queueItemId = item.queueItemId ?: throw MassApiException("Geen item-id gevonden voor deze track.")
        call(
            "player_queues/move_item",
            JSONObject().put("queue_id", playerId).put("queue_item_id", queueItemId).put("pos_shift", posShift)
        )
    }

    suspend fun transferQueue(sourceId: String, targetId: String) {
        val args = JSONObject().apply {
            put("source_queue_id", sourceId)
            put("target_queue_id", targetId)
            put("auto_play", true)
        }
        call("player_queues/transfer", args)
    }

    suspend fun announce(playerId: String, uri: String) {
        val args = JSONObject().apply {
            put("player_id", playerId)
            put("uri", uri)
        }
        // Music Assistant 2.0 commando voor announcements
        call("players/announcement", args)
    }

    suspend fun sendPlayerCommand(command: String, playerId: String, extraArgs: JSONObject = JSONObject()) {
        val args = JSONObject(extraArgs.toString()).put("player_id", playerId)
        call(command, args)
    }

    /**
     * Probeert een reeks command-varianten totdat er één een geldige respons geeft.
     * Anders dan het oude gedrag worden fouten NIET stil ingeslikt: als geen enkele
     * variant werkt, wordt de laatste serverfout doorgegooid zodat de UI hem toont.
     * De ruwe respons wordt gelogd (tag "MassAiRadio") voor diagnose in logcat.
     */
    private fun isInvalidCommand(e: Throwable?) =
        e?.message?.contains("Invalid Command", ignoreCase = true) == true

    private suspend fun callAiRadio(variants: List<String>, args: JSONObject = JSONObject()): JSONObject {
        var firstError: Exception? = null
        var realError: Exception? = null   // een fout die NIET "Invalid Command" is (informatiever)
        for (cmd in variants) {
            try {
                val resp = call(cmd, args)
                android.util.Log.d("MassAiRadio", "OK '$cmd' args=$args -> $resp")
                return resp
            } catch (e: Exception) {
                android.util.Log.w("MassAiRadio", "FAIL '$cmd' args=$args : ${e.message}")
                if (firstError == null) firstError = e
                if (realError == null && !isInvalidCommand(e)) realError = e
            }
        }
        // Een 500 van de canonieke variant zegt meer dan een 400 "Invalid Command" van een alias.
        val chosen = realError ?: firstError
        val msg = chosen?.message.orEmpty()
        if (msg.contains("scope", ignoreCase = true) || msg.contains(" 403")) {
            throw MassApiException(
                "Je API-token heeft geen toegang tot AI Radio. " +
                "Maak in Music Assistant een long-lived token aan vanuit een admin-account " +
                "(scopes config.providers.read + config.providers.write) en zet dat in Instellingen. " +
                "Serverdetail: $msg"
            )
        }
        throw chosen ?: MassApiException("Geen AI Radio-commando beschikbaar: $variants")
    }

    /** Endpoints die deze MA-server niet kent, onthouden we zodat we ze niet elke poll opnieuw proberen. */
    private val unsupportedAiRadioCommands = mutableSetOf<String>()

    suspend fun getAiRadioStations(): List<AiRadioStation> {
        val resp = callAiRadio(
            listOf("ai_radio/stations/list", "mass/ai_radio/stations/list", "plugin/ai_radio/stations/list")
        )
        return parseAiRadioList(resp) { obj ->
            AiRadioStation(
                id = obj.optString("id", obj.optString("station_id", "")),
                name = obj.optString("name", "Onbekend Station"),
                sourcePlaylistId = obj.optString("source_playlist_id"),
                sourcePlaylistProvider = obj.optString("source_playlist_provider"),
                hostId = obj.optString("host_id"),
                defaultPlayerId = obj.optString("default_player_id"),
                maxDurationMinutes = obj.optInt("max_duration_minutes", 0),
                shuffleSourceTracks = obj.optBoolean("shuffle_source_tracks", true)
            )
        }
    }

    private fun parseAiRadioHost(obj: JSONObject): AiRadioHost {
        val optionsMap = mutableMapOf<String, String>()
        obj.optJSONObject("options")?.let { optObj ->
            optObj.keys().forEach { key -> optionsMap[key] = optObj.optString(key) }
        }
        val sectionIds = mutableListOf<String>()
        obj.optJSONArray("section_ids")?.let { secArr ->
            for (j in 0 until secArr.length()) { sectionIds.add(secArr.getString(j)) }
        }
        return AiRadioHost(
            id = obj.optString("id", obj.optString("host_id", "")),
            name = obj.optString("name", "Onbekende Host"),
            instructions = obj.optString("instructions").takeIf { it.isNotBlank() },
            ttsEngine = obj.optString("tts_engine").takeIf { it.isNotBlank() },
            language = obj.optString("language").takeIf { it.isNotBlank() },
            options = optionsMap,
            sectionIds = sectionIds,
            sectionOrderJson = obj.optJSONArray("section_order")?.toString(),
            mergeSectionId = obj.optString("merge_section_id").takeIf { it.isNotBlank() }
        )
    }

    private fun parseAiRadioSection(obj: JSONObject): AiRadioSection = AiRadioSection(
        id = obj.optString("id", obj.optString("section_id", "")),
        name = obj.optString("name", "Onbekend Segment"),
        type = obj.optString("type", "ai_text"),
        webSearch = obj.optString("web_search", "disabled"),
        prompt = obj.optString("prompt").takeIf { it.isNotBlank() },
        maxChars = obj.optJSONObject("constraints")?.optInt("max_chars", 0) ?: obj.optInt("max_chars", 0)
    )

    suspend fun getAiRadioHosts(): List<AiRadioHost> {
        val resp = callAiRadio(
            listOf("ai_radio/hosts/list", "mass/ai_radio/hosts/list", "plugin/ai_radio/hosts/list")
        )
        return parseAiRadioList(resp) { parseAiRadioHost(it) }
    }

    suspend fun getAiRadioSections(): List<AiRadioSection> {
        val resp = callAiRadio(
            listOf("ai_radio/sections/list", "mass/ai_radio/sections/list", "plugin/ai_radio/sections/list")
        )
        return parseAiRadioList(resp) { parseAiRadioSection(it) }
    }

    private fun <T> parseAiRadioList(resp: JSONObject, mapper: (JSONObject) -> T): List<T> {
        val resultObj = resp.opt("result") ?: resp
        val list = mutableListOf<T>()
        fun processArray(arr: JSONArray?) {
            arr?.let { for (i in 0 until it.length()) { list.add(mapper(it.getJSONObject(i))) } }
        }
        when (resultObj) {
            is JSONArray -> processArray(resultObj)
            is JSONObject -> {
                val keys = listOf("items", "stations", "hosts", "sections", "result", "data")
                for (key in keys) {
                    val arr = resultObj.optJSONArray(key)
                    if (arr != null) { processArray(arr); return list }
                }
                if (resultObj.has("id") || resultObj.has("station_id") || resultObj.has("host_id")) {
                    list.add(mapper(resultObj))
                }
            }
        }
        return list
    }

    private fun cmd(suffix: String) =
        listOf("ai_radio/$suffix", "mass/ai_radio/$suffix", "plugin/ai_radio/$suffix")

    /**
     * Sommige save-endpoints crashen (500) omdat ze het object genest verwachten
     * i.p.v. platte kwargs. We proberen bekende vormen tot er één lukt en onthouden
     * de winnende wrapper-key zodat volgende calls die meteen gebruiken.
     */
    // Onthouden per familie (stations/hosts/sections): "" = platte kwargs, anders wrapper-key.
    private val aiRadioSaveWrapKey = mutableMapOf<String, String>()

    private suspend fun callAiRadioSave(suffix: String, payload: JSONObject): JSONObject {
        val family = suffix.substringBefore('/')                 // "stations" | "hosts" | "sections"
        val guess = family.trimEnd('s')                          // "station" | "host" | "section"
        val wrapKeys = listOf(guess, "", "config", "item", "data", "station", "host", "section")
        val ordered = (listOfNotNull(aiRadioSaveWrapKey[family]) + wrapKeys).distinct()
        var last: Exception? = null
        for (key in ordered) {
            val args = if (key.isEmpty()) payload else JSONObject().put(key, payload)
            try {
                val resp = callAiRadio(cmd(suffix), args)
                aiRadioSaveWrapKey[family] = key
                android.util.Log.d("MassAiRadio", "save '$suffix' OK met wrap='${key.ifEmpty { "(plat)" }}'")
                return resp
            } catch (e: Exception) {
                last = e
                if (isInvalidCommand(e)) throw e // endpoint bestaat niet: verder proberen zinloos
            }
        }
        throw last ?: MassApiException("save $suffix mislukt")
    }

    /** Delete: probeert bekende arg-vormen tot er één lukt ({id}, {<ding>_id}, genest). */
    private suspend fun callAiRadioDelete(suffix: String, id: String): JSONObject {
        val singular = suffix.substringBefore('/').trimEnd('s')  // station | host | section
        val shapes = listOf(
            JSONObject().put(singular, JSONObject().put("id", id)),
            JSONObject().put("${singular}_id", id),
            JSONObject().put("id", id)
        )
        var last: Exception? = null
        for (args in shapes) {
            try { return callAiRadio(cmd(suffix), args) }
            catch (e: Exception) { last = e; if (isInvalidCommand(e)) throw e }
        }
        throw last ?: MassApiException("delete $suffix mislukt")
    }

    // ---- Stations ------------------------------------------------------------

    /** Spiegelt exact het schema uit `ai_radio/stations/list` (key `id`, leeg = nieuw). */
    private fun stationArgs(station: AiRadioStation) = JSONObject().apply {
        put("id", station.id)
        put("name", station.name)
        put("host_id", station.hostId ?: "")
        put("source_playlist_id", station.sourcePlaylistId ?: "")
        put("source_playlist_provider", station.sourcePlaylistProvider ?: "")
        put("default_player_id", station.defaultPlayerId ?: "")
        put("max_duration_minutes", station.maxDurationMinutes)
        put("shuffle_source_tracks", station.shuffleSourceTracks)
    }

    suspend fun getAiRadioStationTemplate(): AiRadioStation {
        val resp = callAiRadio(cmd("stations/template"))
        val obj = resp.optJSONObject("result") ?: resp
        return AiRadioStation(
            id = "",
            name = obj.optString("name", "Nieuw station"),
            sourcePlaylistId = obj.optString("source_playlist_id").takeIf { it.isNotBlank() },
            sourcePlaylistProvider = obj.optString("source_playlist_provider").takeIf { it.isNotBlank() },
            hostId = obj.optString("host_id").takeIf { it.isNotBlank() },
            defaultPlayerId = obj.optString("default_player_id").takeIf { it.isNotBlank() },
            maxDurationMinutes = obj.optInt("max_duration_minutes", 0),
            shuffleSourceTracks = obj.optBoolean("shuffle_source_tracks", true)
        )
    }

    /** Best-effort: dit endpoint geeft op sommige servers een 500; nooit blokkerend. */
    suspend fun validateAiRadioStation(station: AiRadioStation) {
        runCatching { callAiRadioSave("stations/validate", stationArgs(station)) }
    }

    suspend fun saveAiRadioStation(station: AiRadioStation) {
        callAiRadioSave("stations/save", stationArgs(station))
    }

    suspend fun deleteAiRadioStation(stationId: String) {
        callAiRadioDelete("stations/delete", stationId)
    }

    // ---- Hosts (presentatoren) --------------------------------------------------

    private fun hostArgs(host: AiRadioHost) = JSONObject().apply {
        put("id", host.id)
        put("name", host.name)
        put("instructions", host.instructions ?: "")
        put("tts_engine", host.ttsEngine ?: "")
        put("language", host.language ?: "")
        put("options", JSONObject().apply { host.options.forEach { (k, v) -> put(k, v) } })
        put("section_ids", JSONArray(host.sectionIds))
        host.sectionOrderJson?.takeIf { it.isNotBlank() }?.let {
            runCatching { put("section_order", JSONArray(it)) }
        }
        put("merge_section_id", host.mergeSectionId ?: "")
    }

    suspend fun getAiRadioHostTemplate(): AiRadioHost {
        val resp = callAiRadio(cmd("hosts/template"))
        val obj = resp.optJSONObject("result") ?: resp
        return parseAiRadioHost(obj).copy(id = "")
    }

    suspend fun saveAiRadioHost(host: AiRadioHost) {
        callAiRadioSave("hosts/save", hostArgs(host))
    }

    suspend fun deleteAiRadioHost(hostId: String) {
        callAiRadioDelete("hosts/delete", hostId)
    }

    /**
     * Best-effort: haalt de keuzelijsten (TTS-engines, talen) op voor de presentator-editor.
     * De AI Radio-plugin exposet dit endpoint niet gedocumenteerd; we proberen wat
     * kandidaten en loggen de ruwe respons onder tag "MassAiRadio" zodat we het kunnen
     * fijnslijpen. Levert lege lijsten op als niets werkt.
     */
    private var aiRadioOptionsCache: AiRadioOptions? = null

    suspend fun getAiRadioOptions(): AiRadioOptions {
        aiRadioOptionsCache?.let { return it }
        var engines = emptyList<String>()
        var languages = emptyList<String>()

        fun absorb(resp: JSONObject) {
            val (e2, l2) = parseAiRadioOptions(resp)
            if (engines.isEmpty()) engines = e2
            if (languages.isEmpty()) languages = l2
        }

        // 1) AI Radio-plugin (meestal niet aanwezig, maar goedkoop te proberen)
        for (c in listOf("hosts/options", "options", "tts/engines")) {
            runCatching { absorb(callAiRadio(cmd(c))) }
            if (engines.isNotEmpty()) break
        }
        // 2) Music Assistant core: providerlijst -> TTS-instances eruit filteren
        if (engines.isEmpty()) {
            for (c in listOf("tts/engine/list", "tts/providers", "providers", "config/providers")) {
                val resp = runCatching { call(c) }.getOrNull() ?: continue
                android.util.Log.d("MassAiRadio", "options via '$c' -> $resp")
                engines = extractTtsProviders(resp)
                if (engines.isNotEmpty()) break
            }
        }
        return AiRadioOptions(ttsEngines = engines.distinct(), languages = languages.distinct())
            .also { aiRadioOptionsCache = it }
    }

    /** Haalt uit een MA providerlijst de TTS-instances (domain/instance_id/naam). */
    private fun extractTtsProviders(resp: JSONObject): List<String> {
        val arr: JSONArray = when (val r = resp.opt("result") ?: resp) {
            is JSONArray -> r
            is JSONObject -> r.optJSONArray("providers") ?: r.optJSONArray("items")
                ?: r.optJSONArray("engines") ?: JSONArray()
            else -> JSONArray()
        }
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val type = (p.optString("type") + p.optString("provider_type") + p.optString("domain")).lowercase()
            val isTts = type.contains("tts") ||
                p.optString("engine_id").isNotBlank() ||
                p.optJSONArray("supported_languages") != null
            if (!isTts) continue
            listOf("instance_id", "engine_id", "id", "domain", "name")
                .firstNotNullOfOrNull { k -> p.optString(k).takeIf { it.isNotBlank() } }
                ?.let(out::add)
        }
        return out
    }

    private fun parseAiRadioOptions(resp: JSONObject): Pair<List<String>, List<String>> {
        val root = resp.opt("result") ?: resp
        val engines = linkedSetOf<String>()
        val languages = linkedSetOf<String>()

        fun harvest(arr: JSONArray?, into: MutableSet<String>) {
            arr ?: return
            for (i in 0 until arr.length()) {
                when (val v = arr.opt(i)) {
                    is String -> if (v.isNotBlank()) into += v
                    is JSONObject -> listOf("id", "engine_id", "value", "name", "code", "language")
                        .firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() } }
                        ?.let { into += it }
                }
            }
        }
        fun walk(obj: JSONObject) {
            obj.keys().forEach { key ->
                val lk = key.lowercase()
                val target = when {
                    lk.contains("engine") || lk.contains("voice") || lk == "tts" -> engines
                    lk.contains("lang") -> languages
                    else -> null
                }
                when (val v = obj.opt(key)) {
                    is JSONArray -> if (target != null) harvest(v, target)
                    is JSONObject -> {
                        if (target != null) harvest(v.names(), target) // keys als waarden (bv. {engine_id: {...}})
                        walk(v)
                    }
                }
            }
        }
        when (root) {
            is JSONObject -> walk(root)
            is JSONArray -> harvest(root, engines) // kale lijst = waarschijnlijk engines
        }
        return engines.toList() to languages.toList()
    }

    // ---- Sections (segmenten) -------------------------------------------------

    private fun sectionArgs(section: AiRadioSection) = JSONObject().apply {
        put("id", section.id)
        put("name", section.name)
        put("type", section.type)
        put("prompt", section.prompt ?: "")
        // ai_meta-segmenten hebben geen web_search/constraints (zoals in sections/list).
        if (section.type != "ai_meta") {
            put("web_search", section.webSearch)
            put("constraints", JSONObject().put("max_chars", section.maxChars))
        }
    }

    suspend fun getAiRadioSectionTemplate(): AiRadioSection {
        val resp = callAiRadio(cmd("sections/template"))
        val obj = resp.optJSONObject("result") ?: resp
        return parseAiRadioSection(obj).copy(id = "")
    }

    suspend fun saveAiRadioSection(section: AiRadioSection) {
        callAiRadioSave("sections/save", sectionArgs(section))
    }

    suspend fun deleteAiRadioSection(sectionId: String) {
        callAiRadioDelete("sections/delete", sectionId)
    }

    // ---- Afspelen ----------------------------------------------------------

    suspend fun getAiRadioQueueStatus(playerId: String): AiRadioQueueStatus? {
        if ("queue/status" in unsupportedAiRadioCommands) return null
        val resp = try {
            callAiRadio(cmd("queue/status"), JSONObject().put("queue_id", playerId))
        } catch (e: Exception) {
            // Deze MA-server kent het endpoint niet: onthouden en niet meer proberen elke poll.
            if (isInvalidCommand(e)) unsupportedAiRadioCommands += "queue/status"
            return null
        }
        val obj = resp.optJSONObject("result") ?: return null
        return AiRadioQueueStatus(
            queueId = obj.optString("queue_id", playerId),
            activeHostId = obj.optString("active_host_id"),
            activeHostName = obj.optString("active_host_name"),
            isDjActive = obj.optBoolean("is_dj_active", false)
        )
    }

    /** Exacte vorm die de MA-web-app stuurt (WS-frame): platte args met player_id_override. */
    suspend fun startAiRadio(playerId: String, station: AiRadioStation) {
        callAiRadio(
            cmd("start"),
            JSONObject().put("station_id", station.id).put("player_id_override", playerId)
        )
    }

    suspend fun stopAiRadio(playerId: String) {
        callAiRadio(
            cmd("stop"),
            JSONObject().put("player_id_override", playerId).put("queue_id", playerId)
        )
    }
}

class MassApiException(message: String) : Exception(message)
