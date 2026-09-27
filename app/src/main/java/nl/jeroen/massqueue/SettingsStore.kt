package nl.jeroen.massqueue

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "mass_settings")

private val KEY_URL = stringPreferencesKey("server_url")
private val KEY_TOKEN = stringPreferencesKey("api_token")
private val KEY_ACTIVE_PLAYLIST = stringPreferencesKey("active_playlist")
private val KEY_ACTIVE_URI = stringPreferencesKey("active_uri")
private val KEY_HOME_LAT = doublePreferencesKey("home_lat")
private val KEY_HOME_LON = doublePreferencesKey("home_lon")
private val KEY_LOCATIONS = stringPreferencesKey("locations_json")
private val KEY_ACTIVE_LOCATION_ID = stringPreferencesKey("active_location_id")
private val KEY_VOLUME_PLAYERS = stringSetPreferencesKey("volume_players")
private val KEY_LOCAL_PLAYERS = stringSetPreferencesKey("local_players")
private val KEY_HIDDEN_PLAYERS = stringSetPreferencesKey("hidden_players")
private val KEY_PLAYER_ALIASES = stringSetPreferencesKey("player_aliases")
private val KEY_RADIO_HISTORY_STATION = stringPreferencesKey("radio_history_station")
private val KEY_RADIO_HISTORY_JSON = stringPreferencesKey("radio_history_json")
private val KEY_PLAYLIST_USAGE = stringPreferencesKey("playlist_usage_json")
private val KEY_RADIO_USAGE = stringPreferencesKey("radio_usage_json")
private val KEY_THEME = stringPreferencesKey("app_theme")
private val KEY_COMPACT_HEADER = booleanPreferencesKey("compact_header")
private val KEY_DISCO_PLAYER = stringPreferencesKey("disco_player_id")
private val KEY_PINNED_PLAYERS = stringSetPreferencesKey("pinned_players")

data class SettingsData(
    val url: String,
    val token: String,
    val activePlaylistName: String?,
    val activePlaylistUri: String?,
    val locations: List<MassLocation>,
    val activeLocationId: String?,
    val volumeControlPlayerIds: Set<String>,
    val localPlayerIds: Set<String>,
    val hiddenPlayerIds: Set<String>,
    val playerAliases: Map<String, String>,
    val radioHistoryStationUri: String?,
    val radioHistory: List<RadioHistoryEntry>,
    val playlistUsage: Map<String, Int>,
    val radioUsage: Map<String, Int>,
    val selectedTheme: String,
    val showCompactHeader: Boolean,
    val discoPlayerId: String?,
    val pinnedPlayerIds: Set<String>
)

class SettingsStore(private val context: Context) {

    /**
     * Eenmalige migratie: vroeger stonden deze spelers als hardcoded standaard in de code en werden
     * ze pas opgeslagen na een wijziging. Bestaande installaties (server al ingesteld) krijgen ze nu
     * vast in DataStore; nieuwe installaties beginnen leeg. Mag weg zodra alle apparaten bijgewerkt zijn.
     */
    private suspend fun migrateLegacyPlayerDefaults() {
        val prefs = context.dataStore.data.first()
        if (prefs[KEY_URL].isNullOrBlank()) return
        val legacyPlayers = setOf("tuin", "yamaha living", "binnen&buiten", "kijkpaal")
        context.dataStore.edit {
            if (it[KEY_VOLUME_PLAYERS] == null) it[KEY_VOLUME_PLAYERS] = legacyPlayers
            if (it[KEY_LOCAL_PLAYERS] == null) it[KEY_LOCAL_PLAYERS] = legacyPlayers
            if (it[KEY_PLAYER_ALIASES] == null) it[KEY_PLAYER_ALIASES] = setOf(
                "yamaha living:Woonkamer",
                "tuin:Buiten",
                "binnen&buiten:Binnen & Buiten"
            )
        }
    }

    suspend fun load(): SettingsData {
        migrateLegacyPlayerDefaults()
        val prefs = context.dataStore.data.first()
        val url = prefs[KEY_URL]
        val storedToken = prefs[KEY_TOKEN].orEmpty()
        val token = if (TokenCipher.isEncrypted(storedToken)) {
            TokenCipher.decrypt(storedToken).orEmpty()
        } else {
            // Oude, onversleutelde opslag: meteen versleuteld terugschrijven.
            if (storedToken.isNotEmpty()) {
                context.dataStore.edit { it[KEY_TOKEN] = TokenCipher.encrypt(storedToken) }
            }
            storedToken
        }

        return SettingsData(
            url = url ?: "",
            token = token,
            activePlaylistName = prefs[KEY_ACTIVE_PLAYLIST],
            activePlaylistUri = prefs[KEY_ACTIVE_URI],
            locations = parseLocations(prefs[KEY_LOCATIONS], prefs[KEY_HOME_LAT], prefs[KEY_HOME_LON]),
            activeLocationId = prefs[KEY_ACTIVE_LOCATION_ID] ?: "default",
            volumeControlPlayerIds = prefs[KEY_VOLUME_PLAYERS] ?: emptySet(),
            localPlayerIds = prefs[KEY_LOCAL_PLAYERS] ?: emptySet(),
            hiddenPlayerIds = prefs[KEY_HIDDEN_PLAYERS] ?: emptySet(),
            playerAliases = (prefs[KEY_PLAYER_ALIASES] ?: emptySet()).associate {
                val parts = it.split(":", limit = 2)
                (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
            }.filter { it.key.isNotBlank() },
            radioHistoryStationUri = prefs[KEY_RADIO_HISTORY_STATION],
            radioHistory = parseRadioHistory(prefs[KEY_RADIO_HISTORY_JSON]),
            playlistUsage = parseUsageMap(prefs[KEY_PLAYLIST_USAGE]),
            radioUsage = parseUsageMap(prefs[KEY_RADIO_USAGE]),
            selectedTheme = prefs[KEY_THEME] ?: "CASSETTE",
            showCompactHeader = prefs[KEY_COMPACT_HEADER] ?: false,
            discoPlayerId = prefs[KEY_DISCO_PLAYER],
            pinnedPlayerIds = prefs[KEY_PINNED_PLAYERS] ?: emptySet()
        )
    }

    private fun parseUsageMap(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optInt(it, 0) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseRadioHistory(json: String?): List<RadioHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<RadioHistoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    RadioHistoryEntry(
                        artist = obj.optString("artist").takeIf { it.isNotBlank() },
                        track = obj.optString("track").takeIf { it.isNotBlank() },
                        album = obj.optString("album").takeIf { it.isNotBlank() },
                        at = obj.optLong("at", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseLocations(json: String?, oldLat: Double?, oldLon: Double?): List<MassLocation> {
        if (json.isNullOrBlank()) {
            // Migratie van oude enkele locatie (coords blijven null als ze er nooit waren)
            return listOf(MassLocation("default", "Thuis", oldLat, oldLon))
        }
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<MassLocation>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(MassLocation(
                    obj.getString("id"),
                    obj.getString("name"),
                    if (obj.isNull("lat")) null else obj.getDouble("lat"),
                    if (obj.isNull("lon")) null else obj.getDouble("lon")
                ))
            }
            list
        } catch (e: Exception) {
            listOf(MassLocation("default", "Thuis", oldLat, oldLon))
        }
    }

    suspend fun saveLocations(locations: List<MassLocation>, activeId: String) {
        val arr = org.json.JSONArray()
        locations.forEach { 
            arr.put(org.json.JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("lat", it.lat ?: org.json.JSONObject.NULL)
                put("lon", it.lon ?: org.json.JSONObject.NULL)
            })
        }
        context.dataStore.edit { prefs ->
            prefs[KEY_LOCATIONS] = arr.toString()
            prefs[KEY_ACTIVE_LOCATION_ID] = activeId
        }
    }

    suspend fun save(url: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_URL] = url
            prefs[KEY_TOKEN] = TokenCipher.encrypt(token)
        }
    }

    suspend fun isConfigured(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs.contains(KEY_URL)
    }

    suspend fun saveActivePlaylist(name: String?, uri: String?) {
        context.dataStore.edit { prefs ->
            if (name == null) {
                prefs.remove(KEY_ACTIVE_PLAYLIST)
                prefs.remove(KEY_ACTIVE_URI)
            } else {
                prefs[KEY_ACTIVE_PLAYLIST] = name
                prefs[KEY_ACTIVE_URI] = uri ?: ""
            }
        }
    }

    suspend fun saveHomeLocation(lat: Double, lon: Double) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HOME_LAT] = lat
            prefs[KEY_HOME_LON] = lon
        }
    }

    suspend fun saveVolumePlayers(playerIds: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VOLUME_PLAYERS] = playerIds
        }
    }

    suspend fun saveLocalPlayers(playerIds: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LOCAL_PLAYERS] = playerIds
        }
    }

    suspend fun saveHiddenPlayers(playerIds: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HIDDEN_PLAYERS] = playerIds
        }
    }

    suspend fun savePlayerAliases(aliases: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PLAYER_ALIASES] = aliases.map { "${it.key}:${it.value}" }.toSet()
        }
    }

    suspend fun saveRadioHistory(stationUri: String?, history: List<RadioHistoryEntry>) {
        val arr = org.json.JSONArray()
        history.forEach { entry ->
            arr.put(org.json.JSONObject().apply {
                put("artist", entry.artist ?: "")
                put("track", entry.track ?: "")
                put("album", entry.album ?: "")
                put("at", entry.at)
            })
        }
        context.dataStore.edit { prefs ->
            if (stationUri.isNullOrBlank()) {
                prefs.remove(KEY_RADIO_HISTORY_STATION)
            } else {
                prefs[KEY_RADIO_HISTORY_STATION] = stationUri
            }
            prefs[KEY_RADIO_HISTORY_JSON] = arr.toString()
        }
    }

    suspend fun savePlaylistUsage(usage: Map<String, Int>) {
        val obj = org.json.JSONObject()
        usage.forEach { (uri, count) -> obj.put(uri, count) }
        context.dataStore.edit { prefs ->
            prefs[KEY_PLAYLIST_USAGE] = obj.toString()
        }
    }

    suspend fun saveRadioUsage(usage: Map<String, Int>) {
        val obj = org.json.JSONObject()
        usage.forEach { (uri, count) -> obj.put(uri, count) }
        context.dataStore.edit { prefs ->
            prefs[KEY_RADIO_USAGE] = obj.toString()
        }
    }

    suspend fun savePinnedPlayers(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PINNED_PLAYERS] = ids
        }
    }

    suspend fun saveDiscoPlayer(playerId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DISCO_PLAYER] = playerId
        }
    }

    suspend fun saveTheme(themeName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME] = themeName
        }
    }

    suspend fun saveCompactHeader(compact: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COMPACT_HEADER] = compact
        }
    }
}
