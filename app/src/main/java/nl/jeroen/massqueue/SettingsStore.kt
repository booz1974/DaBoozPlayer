package nl.jeroen.massqueue

import android.content.Context
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
    val radioUsage: Map<String, Int>
)

class SettingsStore(private val context: Context) {

    suspend fun load(): SettingsData {
        val prefs = context.dataStore.data.first()
        val url = prefs[KEY_URL]
        val token = prefs[KEY_TOKEN]

        return SettingsData(
            url = url ?: "",
            token = token ?: "",
            activePlaylistName = prefs[KEY_ACTIVE_PLAYLIST],
            activePlaylistUri = prefs[KEY_ACTIVE_URI],
            locations = parseLocations(prefs[KEY_LOCATIONS], prefs[KEY_HOME_LAT], prefs[KEY_HOME_LON]),
            activeLocationId = prefs[KEY_ACTIVE_LOCATION_ID] ?: "default",
            volumeControlPlayerIds = prefs[KEY_VOLUME_PLAYERS] ?: setOf(
                "tuin", "yamaha living", "binnen&buiten", "kijkpaal"
            ),
            localPlayerIds = prefs[KEY_LOCAL_PLAYERS] ?: setOf(
                "tuin", "yamaha living", "binnen&buiten", "kijkpaal"
            ),
            hiddenPlayerIds = prefs[KEY_HIDDEN_PLAYERS] ?: emptySet(),
            playerAliases = (prefs[KEY_PLAYER_ALIASES] ?: setOf(
                "yamaha living:Woonkamer",
                "tuin:Buiten",
                "binnen&buiten:Binnen & Buiten"
            )).associate { 
                val parts = it.split(":", limit = 2)
                (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
            }.filter { it.key.isNotBlank() },
            radioHistoryStationUri = prefs[KEY_RADIO_HISTORY_STATION],
            radioHistory = parseRadioHistory(prefs[KEY_RADIO_HISTORY_JSON]),
            playlistUsage = parseUsageMap(prefs[KEY_PLAYLIST_USAGE]),
            radioUsage = parseUsageMap(prefs[KEY_RADIO_USAGE])
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
            prefs[KEY_TOKEN] = token
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
}
