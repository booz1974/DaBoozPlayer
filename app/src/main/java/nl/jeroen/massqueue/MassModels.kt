package nl.jeroen.massqueue

data class MassPlayer(
    val id: String,
    val name: String,
    val playbackState: String?,
    val volumeLevel: Int?,
    val volumeMuted: Boolean?,
    val activeSource: String? = null,
    /** MA-provider-instance van deze speler, bv. "google_cast", "sonos", "airplay". */
    val provider: String? = null,
    val model: String? = null
) {
    /** Chromecast / Google Cast / Nest audio-apparaat. */
    val isCast: Boolean
        get() = provider?.contains("cast", ignoreCase = true) == true ||
            model?.contains("cast", ignoreCase = true) == true ||
            model?.contains("nest", ignoreCase = true) == true ||
            model?.contains("chromecast", ignoreCase = true) == true
}

data class QueueTrack(
    val absoluteIndex: Int,
    val queueItemId: String?,
    val title: String,
    val subtitle: String,
    val durationSeconds: Int?,
    val imagePath: String?,
    val uri: String? = null,
    val mediaType: String? = null,
    /** Live ICY-metadata van een radiostream (uit streamdetails.stream_metadata). */
    val streamArtist: String? = null,
    val streamTrack: String? = null,
    val streamAlbum: String? = null,
    /** Albumhoes van het nu spelende nummer op de radio, indien de stream die meegeeft. */
    val streamImage: String? = null,
    /** Jaar van uitgave, indien Music Assistant dat in de track- of albummetadata meegeeft. */
    val year: Int? = null,
    /** Artiestnaam (los van [subtitle], dat ook het album bevat), voor een iTunes-fallbackzoekopdracht. */
    val artist: String? = null
) {
    val isAiRadio: Boolean get() = uri?.startsWith("ai_radio://") == true

    val isRadio: Boolean
        get() = mediaType.equals("radio", ignoreCase = true) ||
            uri?.contains("radio", ignoreCase = true) == true

    /** True zodra er live artiest- of titelinfo van de stream beschikbaar is. */
    val hasStreamInfo: Boolean
        get() = !streamArtist.isNullOrBlank() || !streamTrack.isNullOrBlank()
}

data class MassPlaylist(
    val uri: String,
    val name: String,
    val trackCount: Int?,
    val imagePath: String?
) {
    /** MA-URI's hebben de vorm <provider>://playlist/<item_id>, bv. library://playlist/68 */
    val providerFromUri: String? get() = uri.substringBefore("://", "").ifBlank { null }
    val itemIdFromUri: String? get() = uri.substringAfterLast("/", "").ifBlank { null }
}

data class MassRadio(
    val uri: String,
    val name: String,
    val imagePath: String?
)

data class MassLocation(
    val id: String,
    val name: String,
    /** null zolang de gebruiker nog geen GPS-pin voor deze locatie heeft gezet. */
    val lat: Double? = null,
    val lon: Double? = null
)

/** Eén regel in "Eerder op deze zender" — een nummer dat net op de radio langskwam. */
data class RadioHistoryEntry(
    val artist: String?,
    val track: String?,
    val album: String? = null,
    val at: Long = System.currentTimeMillis()
)

/**
 * Komt 1-op-1 overeen met het server-schema van `ai_radio/stations/list` / `.../save`:
 * { id, name, source_playlist_id, source_playlist_provider, default_player_id,
 *   max_duration_minutes, shuffle_source_tracks, host_id }
 */
data class AiRadioStation(
    val id: String,
    val name: String,
    val sourcePlaylistId: String? = null,
    val sourcePlaylistProvider: String? = null,
    val hostId: String? = null,
    val defaultPlayerId: String? = null,
    val maxDurationMinutes: Int = 0,
    val shuffleSourceTracks: Boolean = true
)

/**
 * Server-schema van `ai_radio/hosts/list` / `.../save`:
 * { id, name, instructions, tts_engine, language, options, section_ids,
 *   section_order, merge_section_id }
 * `sectionOrderJson` bewaart de ruwe `section_order`-array (geneste flow-regels)
 * zodat we die kunnen tonen/terugsturen zonder informatieverlies.
 */
data class AiRadioHost(
    val id: String,
    val name: String,
    val instructions: String? = null,
    val ttsEngine: String? = null,
    val language: String? = null,
    val options: Map<String, String> = emptyMap(),
    val sectionIds: List<String> = emptyList(),
    val sectionOrderJson: String? = null,
    val mergeSectionId: String? = null
)

/**
 * Server-schema van `ai_radio/sections/list` / `.../save`:
 * { id, name, type, web_search, prompt, constraints: { max_chars } }
 */
data class AiRadioSection(
    val id: String,
    val name: String,
    val type: String = "ai_text",
    val webSearch: String = "disabled",
    val prompt: String? = null,
    val maxChars: Int = 0
)

/** Keuzelijsten voor de presentator-editor, opgehaald van de server (best-effort). */
data class AiRadioOptions(
    val ttsEngines: List<String> = emptyList(),
    val languages: List<String> = emptyList()
)

data class AiRadioQueueStatus(
    val queueId: String,
    val activeHostId: String?,
    val activeHostName: String?,
    val isDjActive: Boolean = false
)

/** Beknopte status van één speler-wachtrij, voor het sorteren van de spelerslijst. */
data class QueueSummary(
    val queueId: String,
    val hasItems: Boolean,
    val isPlaying: Boolean
)

data class QueueState(
    val active: Boolean,
    val currentIndex: Int,
    val items: List<QueueTrack>,
    val shuffleEnabled: Boolean = false,
    val playlistName: String? = null,
    val activeSourceUri: String? = null,
    val elapsedTime: Int? = null
) {
    val currentItem: QueueTrack? get() = items.find { it.absoluteIndex == currentIndex }
    val pastItems: List<QueueTrack> get() = items.filter { it.absoluteIndex < currentIndex }
    val nextItems: List<QueueTrack> get() = items.filter { it.absoluteIndex > currentIndex }
}

/** Simpele UI-state container die de ViewModel naar het scherm doorgeeft. */
data class UiState(
    val serverConfigured: Boolean = false,
    val players: List<MassPlayer> = emptyList(),
    val selectedPlayerId: String? = null,
    val queue: QueueState? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val favoritePlaylists: List<MassPlaylist> = emptyList(),
    val favoriteRadios: List<MassRadio> = emptyList(),
    val favoritesLoading: Boolean = false,
    val radiosLoading: Boolean = false,
    val authToken: String? = null,
    /** Het geconfigureerde server-adres; leeg tot configureServer() is aangeroepen. */
    val serverUrl: String = "",
    val activePlaylistName: String? = null,
    val activePlaylistUri: String? = null,
    val isNearLocation: Boolean = true,
    val distanceToHome: Float? = null,
    val locations: List<MassLocation> = emptyList(),
    val activeLocationId: String? = null,
    val aiRadioStations: List<AiRadioStation> = emptyList(),
    val aiRadioHosts: List<AiRadioHost> = emptyList(),
    val aiRadioSections: List<AiRadioSection> = emptyList(),
    val aiRadioOptions: AiRadioOptions = AiRadioOptions(),
    val activeDjStatus: AiRadioQueueStatus? = null,
    val aiRadioLoading: Boolean = false,
    val volumeControlPlayerIds: Set<String> = emptySet(),
    val localPlayerIds: Set<String> = emptySet(),
    /** Spelers die de gebruiker uit de keuzelijst op het hoofdscherm heeft verborgen. */
    val hiddenPlayerIds: Set<String> = emptySet(),
    val playerAliases: Map<String, String> = emptyMap(),
    /** Epoch-ms waarop de slaaptimer de muziek pauzeert; null = geen timer actief. */
    val sleepTimerEndsAtMs: Long? = null,
    /** Recent langsgekomen radionummers, nieuwste eerst (max 10). */
    val radioHistory: List<RadioHistoryEntry> = emptyList(),
    /** Epoch-ms waarop elke speler voor het laatst begon met afspelen, voor het sorteren van de spelerslijst. */
    val playerLastPlayingAtMs: Map<String, Long> = emptyMap(),
    /** Wachtrij-status per speler-id, voor het sorteren van de spelerslijst. */
    val queueSummaries: Map<String, QueueSummary> = emptyMap()
) {
    val activeLocation: MassLocation? get() = locations.find { it.id == activeLocationId }
    val homeLat: Double? get() = activeLocation?.lat
    val homeLon: Double? get() = activeLocation?.lon
}
