package nl.jeroen.massqueue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Nederlandse standaardteksten voor een nieuwe presentator / een nieuw segment. */
private const val DEFAULT_HOST_INSTRUCTIONS_NL =
    "Host-persoonlijkheid: warm, energiek en muziekkundig; klinkt natuurlijk en spontaan, nooit formeel. " +
    "Programma-instructies: schrijf voor gesproken presentatie, houd elk segment kort en concreet, " +
    "vermijd opsommingen en clichés, noem concrete details wanneer die beschikbaar zijn, spreek " +
    "vloeiend Nederlands en zorg voor een geloofwaardige radioflow tussen de segmenten."

/**
 * Naam van het vaste hulp-station dat de Muziek Wizard hergebruikt wanneer je
 * een playlist met presentator start. MA's AI Radio start alleen via een station,
 * dus we overschrijven hierin telkens de bron-playlist, de host en de speler.
 */
private const val WIZARD_STATION_NAME = "Wizard"

private const val DEFAULT_SECTION_PROMPT_NL =
    "De vorige track was <prev_songinfo> en de volgende track is <next_songinfo>. " +
    "Schrijf een korte, natuurlijke overgang van één of twee zinnen die beide nummers met elkaar verbindt. " +
    "Schrijf voor gesproken presentatie in het Nederlands, zonder opvulling of herhaling."

class MassViewModel : ViewModel() {

    private var client: MassApiClient? = null
    private var pollJob: Job? = null
    private var sleepJob: Job? = null

    /**
     * Epoch-ms waarop elke speler voor het laatst begon met afspelen (overgang naar "playing").
     * Gebruikt om de spelers-dropdown op het hoofdscherm te sorteren: de speler waar het meest
     * recent iets is gestart staat bovenaan.
     */
    private val playerLastPlayingAt = mutableMapOf<String, Long>()

    /** Push-verbinding met MA; vervangt het snelle pollen. */
    private val eventSocket = MassEventSocket()
    private var wsCollectorStarted = false

    // Radio-geschiedenis: onthoudt de actieve zender + het huidige nummer zodat we
    // bij een songwissel het vórige nummer in "Eerder op deze zender" kunnen zetten.
    private var lastRadioStationUri: String? = null
    private var lastRadioTrackKey: String? = null
    private var currentRadioTrack: RadioHistoryEntry? = null
    /** Laatst bekende URI van de spelende radiozender, om na een tussendoor-nummer te hervatten. */
    private var lastRadioUri: String? = null
    /**
     * Gezet zodra de gebruiker een nummer uit "Eerder op deze zender" aantikt: tot de
     * zender weer speelt (of dit na [PENDING_RADIO_RESUME_MS] verloopt) mag de
     * geschiedenislijst niet gewist worden door het tussendoor-nummer.
     */
    private var pendingRadioResumeUri: String? = null
    private var pendingRadioResumeSetAt: Long = 0L

    private var onSavePlaylist: (suspend (String?, String?) -> Unit)? = null
    private var onSaveLocations: (suspend (List<MassLocation>, String) -> Unit)? = null
    private var onSaveVolumePlayers: (suspend (Set<String>) -> Unit)? = null
    private var onSaveLocalPlayers: (suspend (Set<String>) -> Unit)? = null
    private var onSaveHiddenPlayers: (suspend (Set<String>) -> Unit)? = null
    private var onSavePlayerAliases: (suspend (Map<String, String>) -> Unit)? = null

    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // Tijdstip van de laatste handmatige playlist-keuze op dit device
    private var lastLocalChangeTime: Long = 0

    init {
        // Knopdrukken vanaf lockscreen / notificatie / bluetooth uitvoeren.
        viewModelScope.launch {
            NowPlayingBus.commands.collect { cmd ->
                when (cmd) {
                    Transport.PLAY_PAUSE -> sendCommand("players/cmd/play_pause")
                    Transport.NEXT -> sendCommand("players/cmd/next")
                    Transport.PREVIOUS -> sendCommand("players/cmd/previous")
                    Transport.STOP -> sendCommand("players/cmd/play_pause")
                }
            }
        }
    }

    /** Roep dit aan zodra je het server-adres kent (uit instellingen/DataStore). */
    fun configureServer(
        baseUrl: String, 
        authToken: String?, 
        initialPlaylistName: String? = null, 
        initialPlaylistUri: String? = null,
        locations: List<MassLocation> = emptyList(),
        activeLocationId: String? = null,
        volumeControlPlayerIds: Set<String> = emptySet(),
        localPlayerIds: Set<String> = emptySet(),
        hiddenPlayerIds: Set<String> = emptySet(),
        playerAliases: Map<String, String> = emptyMap(),
        saveCallback: (suspend (String?, String?) -> Unit)? = null,
        saveLocationsCallback: (suspend (List<MassLocation>, String) -> Unit)? = null,
        saveVolumeCallback: (suspend (Set<String>) -> Unit)? = null,
        saveLocalCallback: (suspend (Set<String>) -> Unit)? = null,
        saveHiddenCallback: (suspend (Set<String>) -> Unit)? = null,
        saveAliasesCallback: (suspend (Map<String, String>) -> Unit)? = null
    ) {
        onSavePlaylist = saveCallback
        onSaveLocations = saveLocationsCallback
        onSaveVolumePlayers = saveVolumeCallback
        onSaveLocalPlayers = saveLocalCallback
        onSaveHiddenPlayers = saveHiddenCallback
        onSavePlayerAliases = saveAliasesCallback
        if (client == null) {
            client = MassApiClient(baseUrl, authToken)
        } else {
            client?.updateConfig(baseUrl, authToken)
        }

        eventSocket.updateConfig(baseUrl, authToken)
        eventSocket.start()
        startEventCollectors()
        _uiState.update { 
            it.copy(
                serverConfigured = true, 
                errorMessage = null, 
                isLoading = true, // We zetten hem even op loading bij een server-switch
                players = emptyList(), // Leegmaken om cache-fouten te voorkomen
                queue = null,
                authToken = authToken,
                serverUrl = baseUrl,
                activePlaylistName = initialPlaylistName ?: it.activePlaylistName,
                activePlaylistUri = initialPlaylistUri ?: it.activePlaylistUri,
                locations = if (locations.isEmpty()) listOf(MassLocation("default", "Thuis")) else locations,
                activeLocationId = activeLocationId ?: locations.firstOrNull()?.id ?: "default",
                volumeControlPlayerIds = volumeControlPlayerIds,
                localPlayerIds = localPlayerIds,
                hiddenPlayerIds = hiddenPlayerIds,
                playerAliases = playerAliases
            ) 
        }
        if (initialPlaylistName != null) {
            lastLocalChangeTime = System.currentTimeMillis()
        }
        loadFavoritePlaylists() 
        startPolling()
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        lastLat = latitude
        lastLon = longitude
        
        val targetLat = _uiState.value.homeLat
        val targetLon = _uiState.value.homeLon
        if (targetLat == null || targetLon == null) {
            // Geen thuislocatie ingesteld: geen geofencing, toon alle spelers.
            if (!_uiState.value.isNearLocation || _uiState.value.distanceToHome != null) {
                _uiState.update { it.copy(isNearLocation = true, distanceToHome = null) }
            }
            return
        }
        val results = FloatArray(1)
        android.location.Location.distanceBetween(latitude, longitude, targetLat, targetLon, results)
        val distanceInMeters = results[0]
        val near = distanceInMeters <= 150 // 150m

        if (_uiState.value.isNearLocation != near || _uiState.value.distanceToHome != distanceInMeters) {
            _uiState.update { it.copy(isNearLocation = near, distanceToHome = distanceInMeters) }
            if (near && !_uiState.value.isNearLocation) {
                tickOnce() 
            }
        }
    }

    fun addLocation(name: String) {
        val newLoc = MassLocation(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            lat = lastLat,
            lon = lastLon
        )
        val next = _uiState.value.locations + newLoc
        _uiState.update { it.copy(locations = next, activeLocationId = newLoc.id) }
        saveLocationsState()
        // Direct de afstand opnieuw berekenen
        updateLocation(lastLat, lastLon)
    }

    fun deleteLocation(id: String) {
        if (_uiState.value.locations.size <= 1) return
        val next = _uiState.value.locations.filter { it.id != id }
        var activeId = _uiState.value.activeLocationId
        if (activeId == id) activeId = next.firstOrNull()?.id
        _uiState.update { it.copy(locations = next, activeLocationId = activeId) }
        saveLocationsState()
        updateLocation(lastLat, lastLon)
    }

    fun selectLocation(id: String) {
        _uiState.update { it.copy(activeLocationId = id) }
        saveLocationsState()
        updateLocation(lastLat, lastLon)
    }

    private fun saveLocationsState() {
        val state = _uiState.value
        viewModelScope.launch {
            onSaveLocations?.invoke(state.locations, state.activeLocationId ?: "")
        }
    }

    fun pinHomeLocation() {
        // We overschrijven de actieve locatie met de huidige GPS-positie
        val activeId = _uiState.value.activeLocationId ?: return
        val next = _uiState.value.locations.map { 
            if (it.id == activeId) it.copy(lat = lastLat, lon = lastLon) else it
        }
        _uiState.update { it.copy(locations = next) }
        saveLocationsState()
        updateLocation(lastLat, lastLon)
    }

    fun toggleVolumePlayer(playerId: String) {
        val current = _uiState.value.volumeControlPlayerIds
        val next = if (current.contains(playerId)) {
            current - playerId
        } else {
            current + playerId
        }
        _uiState.update { it.copy(volumeControlPlayerIds = next) }
        viewModelScope.launch {
            onSaveVolumePlayers?.invoke(next)
        }
    }

    fun toggleLocalPlayer(playerId: String) {
        val current = _uiState.value.localPlayerIds
        val next = if (current.contains(playerId)) {
            current - playerId
        } else {
            current + playerId
        }
        _uiState.update { it.copy(localPlayerIds = next) }
        viewModelScope.launch {
            onSaveLocalPlayers?.invoke(next)
        }
    }

    /** Zet een speler aan/uit in de keuzelijst op het hoofdscherm (aangevinkt = verborgen). */
    fun toggleHiddenPlayer(playerId: String) {
        val current = _uiState.value.hiddenPlayerIds
        val next = if (current.contains(playerId)) {
            current - playerId
        } else {
            current + playerId
        }
        _uiState.update { it.copy(hiddenPlayerIds = next) }
        viewModelScope.launch {
            onSaveHiddenPlayers?.invoke(next)
        }
    }

    fun setPlayerAlias(playerId: String, alias: String) {
        val next = _uiState.value.playerAliases.toMutableMap()
        if (alias.isBlank()) {
            next.remove(playerId)
        } else {
            next[playerId] = alias
        }
        _uiState.update { it.copy(playerAliases = next) }
        viewModelScope.launch {
            onSavePlayerAliases?.invoke(next)
        }
    }

    fun selectPlayer(playerId: String) {
        _uiState.update { it.copy(selectedPlayerId = playerId, queue = null) }
        tickOnce()
    }

    /**
     * Reageert op MA-push-events: bij een speler-/wachtrij-wijziging meteen
     * verversen (samengevoegd met een korte debounce). `queue_time_updated`
     * (elke seconde) negeren we — de voortgangsbalk loopt lokaal door.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startEventCollectors() {
        if (wsCollectorStarted) return
        wsCollectorStarted = true

        viewModelScope.launch {
            eventSocket.events
                .filter { e ->
                    (e.type.startsWith("player") || e.type.startsWith("queue")) &&
                        e.type != "queue_time_updated"
                }
                .debounce(250L)
                .collect { tickOnce() }
        }

        // Na (her)verbinden meteen bijwerken — er kunnen events gemist zijn.
        viewModelScope.launch {
            eventSocket.connected.collect { conn -> if (conn) tickOnce() }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                tick()
                // WS verbonden -> alleen een trage heartbeat als vangnet.
                // WS weg -> terugvallen op het oude snelle pollen.
                delay(if (eventSocket.connected.value) 30_000L else 3_000L)
            }
        }
    }

    private fun tickOnce() {
        viewModelScope.launch { tick() }
    }

    override fun onCleared() {
        eventSocket.stop()
        super.onCleared()
    }

    private suspend fun tick() {
        val c = client ?: return
        try {
            val currentState = _uiState.value
            
            // Haal spelers op
            val players = c.getAllPlayers()
            
            // Filter spelers op basis van locatie
            val filteredPlayers = if (_uiState.value.isNearLocation) {
                players
            } else {
                players.filter { player ->
                    !_uiState.value.localPlayerIds.contains(player.id) &&
                    !_uiState.value.localPlayerIds.contains(player.name.lowercase().trim())
                }
            }
            
            // Bepaal welke speler geselecteerd is
            val playingId = filteredPlayers.firstOrNull { it.playbackState?.lowercase() == "playing" }?.id
            val selected = currentState.selectedPlayerId?.let { id ->
                if (filteredPlayers.any { it.id == id }) id else null
            } ?: playingId ?: filteredPlayers.firstOrNull()?.id

            // Samenvatting van alle wachtrijen (o.a. voor synced groepen zoals "SPZ", waar
            // `players/all` geen `active_source` teruggeeft terwijl de wachtrij wel gevuld is).
            val queueSummaries = try {
                c.getAllQueueSummaries().associateBy { it.queueId }
            } catch (e: Exception) {
                currentState.queueSummaries
            }

            // Houd bij wanneer elke speler voor het laatst begon met afspelen, zodat de
            // spelers-dropdown de meest actuele speler bovenaan kan tonen.
            val now = System.currentTimeMillis()
            for (player in filteredPlayers) {
                val wasPlaying = currentState.players.find { it.id == player.id }
                    ?.playbackState?.lowercase() == "playing" ||
                    currentState.queueSummaries[player.id]?.isPlaying == true
                val isPlaying = player.playbackState?.lowercase() == "playing" ||
                    queueSummaries[player.id]?.isPlaying == true
                if (isPlaying && !wasPlaying) {
                    playerLastPlayingAt[player.id] = now
                }
            }

            _uiState.update {
                it.copy(
                    players = filteredPlayers,
                    selectedPlayerId = selected,
                    playerLastPlayingAtMs = playerLastPlayingAt.toMap(),
                    queueSummaries = queueSummaries
                )
            }

            // Haal de wachtrij op als er een speler geselecteerd is
            if (selected != null) {
                val queue = c.getQueue(selected)
                updateRadioHistory(queue)
                val selectedPlayer = players.find { it.id == selected }
                
                // AI Radio status polling
                val djStatus = try { c.getAiRadioQueueStatus(selected) } catch (e: Exception) { null }
                _uiState.update { it.copy(activeDjStatus = djStatus) }

                val currentUri = queue?.activeSourceUri ?: selectedPlayer?.activeSource
                
                // ALTIJD TONEN WAT OP DIT DEVICE GEKOZEN IS
                var finalPlaylistName = _uiState.value.activePlaylistName
                val localUri = _uiState.value.activePlaylistUri
                
                // Status checks
                val playbackState = selectedPlayer?.playbackState?.lowercase() ?: ""
                val isOff = playbackState == "off"
                val isPaused = (playbackState == "paused" || playbackState == "stopped")
                
                if (isOff || (queue == null || queue.items.isEmpty())) {
                    if (_uiState.value.activePlaylistName != null) {
                        viewModelScope.launch { onSavePlaylist?.invoke(null, null) }
                        _uiState.update { it.copy(activePlaylistName = null, activePlaylistUri = null) }
                    }
                    finalPlaylistName = null
                } else if (isPaused) {
                    // Bij pauze: behoud de huidige naam
                } else if (localUri != null && currentUri != null) {
                    val currentCore = currentUri.substringAfter("://").lowercase()
                    val localCore = localUri.substringAfter("://").lowercase()
                    if (currentUri.contains("playlist") && !currentCore.contains(localCore.substringBefore("?"))) {
                        if (System.currentTimeMillis() - lastLocalChangeTime > 20000) {
                            finalPlaylistName = null
                        }
                    }
                } else if (localUri == null) {
                    finalPlaylistName = null
                }

                if (finalPlaylistName != _uiState.value.activePlaylistName) {
                    _uiState.update { it.copy(activePlaylistName = finalPlaylistName) }
                }

                _uiState.update { it.copy(queue = queue, errorMessage = null, isLoading = false) }
                publishNowPlaying(queue, playbackState == "playing")
            } else {
                // Geen speler geselecteerd (bijv. door locatie-filter), dus ook de wachtrij leegmaken
                _uiState.update { it.copy(queue = null, isLoading = false) }
                publishNowPlaying(null, false)
            }
        } catch (e: Exception) {
            if (_uiState.value.players.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Verbindingsfout") }
            }
        }
    }

    /** Voedt de MediaSession/notificatie (lockscreen, bluetooth) met de nu-speelt-info. */
    private fun publishNowPlaying(queue: QueueState?, isPlaying: Boolean) {
        val cur = queue?.currentItem
        if (cur == null) {
            NowPlayingBus.publish(null)
            return
        }
        val title = cur.streamTrack?.takeIf { it.isNotBlank() }
            ?: cur.title.takeIf { it.isNotBlank() }
            ?: "Onbekend"
        val artist = cur.streamArtist?.takeIf { it.isNotBlank() }
            ?: cur.subtitle.takeIf { it.isNotBlank() }
            ?: _uiState.value.activePlaylistName.orEmpty()
        NowPlayingBus.publish(
            NowPlaying(
                title = title,
                artist = artist,
                artUrl = cur.streamImage?.takeIf { it.isNotBlank() } ?: cur.imagePath,
                isPlaying = isPlaying
            )
        )
    }

    /**
     * Houdt "Eerder op deze zender" bij. Bij een songwissel op dezelfde zender
     * schuift het vórige nummer bovenaan de lijst (max 10). Bij zenderwissel of
     * zodra er geen radio meer speelt wordt de lijst gewist.
     */
    private fun updateRadioHistory(queue: QueueState?) {
        val cur = queue?.currentItem
        val isRadioNow = cur != null && cur.isRadio && cur.hasStreamInfo

        // Wacht de gebruiker nog op het hervatten van de zender na een handmatig
        // gekozen nummer? Laat de verlopen-check hier één keer draaien.
        val awaitingRadioResume = pendingRadioResumeUri != null &&
            System.currentTimeMillis() - pendingRadioResumeSetAt < PENDING_RADIO_RESUME_MS
        if (pendingRadioResumeUri != null && !awaitingRadioResume) {
            pendingRadioResumeUri = null
        }

        if (!isRadioNow) {
            // Speelt er tijdelijk een los nummer (uit de geschiedenis aangeklikt)?
            // Bewaar de lijst zolang de zender nog in de wachtrij staat óf we nog
            // op het hervatten wachten (de "add" kan een tel later komen dan de "replace").
            if (awaitingRadioResume) return
            if (queue?.items?.any { it.isRadio } == true) return
            if (currentRadioTrack != null || _uiState.value.radioHistory.isNotEmpty()) {
                currentRadioTrack = null
                lastRadioTrackKey = null
                lastRadioStationUri = null
                _uiState.update { it.copy(radioHistory = emptyList()) }
            }
            return
        }

        // De URI van het radio-item zelf is een stabielere zender-identiteit dan
        // active_source (dat kan wisselen tijdens een tussendoor-nummer).
        val stationUri = cur!!.uri ?: queue.activeSourceUri
        lastRadioUri = cur.uri ?: lastRadioUri
        val key = "${cur.streamArtist} ${cur.streamTrack}"

        // De zender is terug na een handmatig gekozen nummer: behandel dit als
        // dezelfde zender zodat de geschiedenis blijft staan.
        if (awaitingRadioResume) {
            pendingRadioResumeUri = null
            lastRadioStationUri = stationUri
            lastRadioTrackKey = key
            currentRadioTrack = RadioHistoryEntry(cur.streamArtist, cur.streamTrack, cur.streamAlbum)
            return
        }

        if (stationUri != lastRadioStationUri) {
            lastRadioStationUri = stationUri
            lastRadioTrackKey = key
            currentRadioTrack = RadioHistoryEntry(cur.streamArtist, cur.streamTrack, cur.streamAlbum)
            if (_uiState.value.radioHistory.isNotEmpty()) {
                _uiState.update { it.copy(radioHistory = emptyList()) }
            }
            return
        }

        if (key != lastRadioTrackKey) {
            val previous = currentRadioTrack
            lastRadioTrackKey = key
            currentRadioTrack = RadioHistoryEntry(cur.streamArtist, cur.streamTrack, cur.streamAlbum)
            if (previous != null) {
                val next = (listOf(previous) + _uiState.value.radioHistory).take(10)
                _uiState.update { it.copy(radioHistory = next) }
            }
        }
    }

    /**
     * Zoekt een eerder op de radio gehoord nummer op in Music Assistant en speelt
     * het nu af op de radio-speler. De radiozender wordt er direct achteraan in de
     * wachtrij gezet, zodat MA de stream vanzelf hervat zodra het nummer klaar is.
     */
    fun playHistoryTrack(entry: RadioHistoryEntry) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        val query = listOfNotNull(
            entry.artist?.takeIf { it.isNotBlank() },
            entry.track?.takeIf { it.isNotBlank() }
        ).joinToString(" ").trim()
        if (query.isBlank()) return

        val radioUri = lastRadioUri
            ?: _uiState.value.queue?.currentItem?.takeIf { it.isRadio }?.uri
            ?: _uiState.value.activePlaylistUri

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            try {
                val trackUri = client?.searchTrackUri(query)
                if (trackUri.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(errorMessage = "Kon \"${entry.track ?: query}\" niet vinden in Music Assistant.")
                    }
                    return@launch
                }
                // Vanaf nu tot de zender weer speelt: "Eerder op deze zender" niet wissen.
                if (!radioUri.isNullOrBlank()) {
                    pendingRadioResumeUri = radioUri
                    pendingRadioResumeSetAt = System.currentTimeMillis()
                }
                client?.playMedia(playerId, trackUri, "replace")
                if (!radioUri.isNullOrBlank()) {
                    delay(700)
                    client?.playMedia(playerId, radioUri, "add")
                }
                delay(800)
                tick()
            } catch (e: Exception) {
                pendingRadioResumeUri = null
                _uiState.update { it.copy(errorMessage = "Nummer afspelen mislukt: ${e.message}") }
            }
        }
    }

    /** Zet of wist de slaaptimer. [minutes] null of <= 0 betekent uitzetten. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _uiState.update { it.copy(sleepTimerEndsAtMs = null) }
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _uiState.update { it.copy(sleepTimerEndsAtMs = endsAt) }
        sleepJob = viewModelScope.launch {
            val wait = endsAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val playerId = _uiState.value.selectedPlayerId
            val player = _uiState.value.players.find { it.id == playerId }
            if (playerId != null && player?.playbackState?.lowercase() == "playing") {
                try { client?.sendPlayerCommand("players/cmd/play_pause", playerId) } catch (_: Exception) {}
            }
            _uiState.update { it.copy(sleepTimerEndsAtMs = null) }
            tick()
        }
    }

    fun playIndex(index: Int) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        viewModelScope.launch {
            try {
                client?.playIndex(playerId, index)
                delay(400)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Afspelen mislukt: ${e.message}") }
            }
        }
    }

    fun playNext(track: QueueTrack) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        val currentIndex = _uiState.value.queue?.currentIndex ?: return
        viewModelScope.launch {
            try {
                client?.moveItemNext(playerId, track, currentIndex)
                delay(400)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Verplaatsen mislukt: ${e.message}") }
            }
        }
    }

    /**
     * Verplaatst een item in de "komt hierna"-lijst. [item] is het te verplaatsen
     * item, [delta] het aantal plaatsen (negatief = eerder afspelen). De lijst is
     * aaneengesloten, dus delta = nieuw-index − oud-index binnen die lijst.
     */
    fun moveUpcomingItem(item: QueueTrack, delta: Int) {
        if (delta == 0) return
        val playerId = _uiState.value.selectedPlayerId ?: return
        viewModelScope.launch {
            try {
                client?.moveQueueItem(playerId, item, delta)
                delay(400)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Verplaatsen mislukt: ${e.message}") }
            }
        }
    }

    fun sendCommand(command: String) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        viewModelScope.launch {
            try {
                client?.sendPlayerCommand(command, playerId)
                delay(300)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Commando mislukt: ${e.message}") }
            }
        }
    }

    fun setVolume(direction: String) {
        sendCommand("players/cmd/volume_$direction") 
    }

    fun shuffleQueue() {
        val playerId = _uiState.value.selectedPlayerId ?: return
        val currentShuffle = _uiState.value.queue?.shuffleEnabled ?: false
        viewModelScope.launch {
            try {
                client?.shuffleQueue(playerId, !currentShuffle)
                delay(600)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Shuffelen mislukt: ${e.message}") }
            }
        }
    }

    fun clearQueue() {
        val playerId = _uiState.value.selectedPlayerId ?: return
        viewModelScope.launch {
            try {
                client?.clearQueue(playerId)
                delay(500)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Wachtrij wissen mislukt: ${e.message}") }
            }
        }
    }

    fun transferQueue(targetPlayerId: String) {
        val sourceId = _uiState.value.selectedPlayerId ?: return
        if (sourceId == targetPlayerId) return
        
        viewModelScope.launch {
            try {
                client?.transferQueue(sourceId, targetPlayerId)
                _uiState.update { it.copy(selectedPlayerId = targetPlayerId) }
                delay(800)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Verhuizen mislukt: ${e.message}") }
            }
        }
    }

    fun loadFavoritePlaylists() {
        val c = client ?: return
        if (_uiState.value.favoritePlaylists.isNotEmpty() || _uiState.value.favoritesLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(favoritesLoading = true) }
            try {
                val playlists = c.getFavoritePlaylists()
                _uiState.update { it.copy(favoritePlaylists = playlists, favoritesLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(favoritesLoading = false, errorMessage = "Favorieten laden mislukt: ${e.message}")
                }
            }
        }
    }

    fun loadFavoriteRadios() {
        val c = client ?: return
        if (_uiState.value.favoriteRadios.isNotEmpty() || _uiState.value.radiosLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(radiosLoading = true) }
            try {
                val radios = c.getFavoriteRadios()
                _uiState.update { it.copy(favoriteRadios = radios, radiosLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(radiosLoading = false, errorMessage = "Radiozenders laden mislukt: ${e.message}")
                }
            }
        }
    }

    fun loadAiRadioData() {
        val c = client ?: return
        // Playlists zijn nodig als bron-keuze in de station-editor; los laden (self-guarded).
        loadFavoritePlaylists()
        viewModelScope.launch {
            _uiState.update { it.copy(aiRadioLoading = true, errorMessage = null) }

            // Stations, hosts en segmenten los ophalen zodat een fout in de één de ander niet wist.
            // Elke call krijgt één retry na 2s voor tijdelijke verbindingsproblemen.
            suspend fun <T> withRetry(block: suspend () -> T): Result<T> =
                runCatching { block() }.recoverCatching { delay(2000); block() }

            val stationsResult = withRetry { c.getAiRadioStations() }
            val hostsResult = withRetry { c.getAiRadioHosts() }
            val sectionsResult = withRetry { c.getAiRadioSections() }
            var options = runCatching { c.getAiRadioOptions() }.getOrDefault(AiRadioOptions())
            // Vul de keuzelijsten aan met waarden die al op bestaande presentatoren staan.
            hostsResult.getOrNull()?.let { hosts ->
                options = options.copy(
                    ttsEngines = (options.ttsEngines + hosts.mapNotNull { it.ttsEngine })
                        .filter { it.isNotBlank() }.distinct(),
                    languages = (options.languages + hosts.mapNotNull { it.language })
                        .filter { it.isNotBlank() }.distinct()
                )
            }

            val error = listOfNotNull(
                stationsResult.exceptionOrNull()?.let { "stations: ${it.message}" },
                hostsResult.exceptionOrNull()?.let { "hosts: ${it.message}" }
                // segmenten zijn optioneel: geen foutmelding als dat endpoint ontbreekt
            ).joinToString(" | ").ifBlank { null }

            _uiState.update {
                it.copy(
                    aiRadioStations = stationsResult.getOrDefault(it.aiRadioStations),
                    aiRadioHosts = hostsResult.getOrDefault(it.aiRadioHosts),
                    aiRadioSections = sectionsResult.getOrDefault(it.aiRadioSections),
                    aiRadioOptions = options,
                    aiRadioLoading = false,
                    errorMessage = error?.let { msg -> "AI Radio fout: $msg" }
                )
            }
        }
    }

    fun startAiRadio(station: AiRadioStation) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        viewModelScope.launch {
            try {
                client?.startAiRadio(playerId, station)
                delay(500)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "AI Radio start mislukt: ${e.message}") }
            }
        }
    }

    fun stopAiRadio() {
        val playerId = _uiState.value.selectedPlayerId ?: return
        viewModelScope.launch {
            try {
                client?.stopAiRadio(playerId)
                delay(500)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "AI Radio stop mislukt: ${e.message}") }
            }
        }
    }

    fun createStationTemplate(onResult: (AiRadioStation) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                onResult(c.getAiRadioStationTemplate())
            } catch (e: Exception) {
                // Geen server-template? Val terug op een leeg lokaal sjabloon.
                if (e.message?.contains("Invalid Command", ignoreCase = true) == true) {
                    onResult(AiRadioStation(id = "", name = "Nieuw station"))
                } else {
                    _uiState.update { it.copy(errorMessage = "Template laden mislukt: ${e.message}") }
                }
            }
        }
    }

    fun saveStation(station: AiRadioStation, onComplete: () -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.validateAiRadioStation(station) // best-effort, blokkeert niet
                c.saveAiRadioStation(station)
                loadAiRadioData() // lijst verversen
                onComplete()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Opslaan mislukt: ${e.message}") }
            }
        }
    }

    fun deleteStation(stationId: String) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.deleteAiRadioStation(stationId)
                loadAiRadioData()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Verwijderen mislukt: ${e.message}") }
            }
        }
    }

    // ---- Presentatoren (hosts) ------------------------------------------------

    fun createHostTemplate(onResult: (AiRadioHost) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            val base = try {
                c.getAiRadioHostTemplate()
            } catch (e: Exception) {
                if (e.message?.contains("Invalid Command", ignoreCase = true) != true) {
                    _uiState.update { it.copy(errorMessage = "Presentator-template laden mislukt: ${e.message}") }
                    return@launch
                }
                AiRadioHost(id = "", name = "Nieuwe presentator")
            }
            // Nieuwe presentator krijgt altijd de Nederlandse standaardinstructies.
            onResult(
                base.copy(
                    id = "",
                    name = base.name.ifBlank { "Nieuwe presentator" },
                    instructions = DEFAULT_HOST_INSTRUCTIONS_NL,
                    language = base.language?.ifBlank { "nl" } ?: "nl"
                )
            )
        }
    }

    fun saveHost(host: AiRadioHost, onComplete: () -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.saveAiRadioHost(host)
                loadAiRadioData()
                onComplete()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Presentator opslaan mislukt: ${e.message}") }
            }
        }
    }

    fun deleteHost(hostId: String) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.deleteAiRadioHost(hostId)
                loadAiRadioData()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Presentator verwijderen mislukt: ${e.message}") }
            }
        }
    }

    // ---- Segmenten (sections) -----------------------------------------------

    fun createSectionTemplate(onResult: (AiRadioSection) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            val base = try {
                c.getAiRadioSectionTemplate()
            } catch (e: Exception) {
                if (e.message?.contains("Invalid Command", ignoreCase = true) != true) {
                    _uiState.update { it.copy(errorMessage = "Segment-template laden mislukt: ${e.message}") }
                    return@launch
                }
                AiRadioSection(id = "", name = "Nieuw segment")
            }
            // Nieuw segment krijgt altijd de Nederlandse standaardprompt.
            onResult(
                base.copy(
                    id = "",
                    name = base.name.ifBlank { "Nieuw segment" },
                    prompt = DEFAULT_SECTION_PROMPT_NL
                )
            )
        }
    }

    fun saveSection(section: AiRadioSection, onComplete: () -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.saveAiRadioSection(section)
                loadAiRadioData()
                onComplete()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Segment opslaan mislukt: ${e.message}") }
            }
        }
    }

    fun deleteSection(sectionId: String) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.deleteAiRadioSection(sectionId)
                loadAiRadioData()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Segment verwijderen mislukt: ${e.message}") }
            }
        }
    }

    fun playPlaylistNow(playlist: MassPlaylist) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        lastLocalChangeTime = System.currentTimeMillis()
        _uiState.update { it.copy(activePlaylistName = playlist.name, activePlaylistUri = playlist.uri) }
        viewModelScope.launch {
            onSavePlaylist?.invoke(playlist.name, playlist.uri)
            try {
                client?.playMedia(playerId, playlist.uri, "replace")
                delay(1000)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Afspelen mislukt: ${e.message}") }
            }
        }
    }

    /**
     * Wizard-pad: start de gekozen playlist als AI Radio met een bestaande presentator.
     * Hergebruikt (of maakt) het vaste hulp-station [WIZARD_STATION_NAME], overschrijft
     * daarin bron-playlist + host + speler, slaat op en start het station.
     */
    fun startWizardRadioWithHost(playerId: String, playlist: MassPlaylist, host: AiRadioHost) {
        val c = client ?: return
        lastLocalChangeTime = System.currentTimeMillis()
        _uiState.update { it.copy(activePlaylistName = playlist.name, activePlaylistUri = playlist.uri) }
        viewModelScope.launch {
            try {
                val existing = runCatching { c.getAiRadioStations() }.getOrDefault(emptyList())
                    .firstOrNull { it.name.equals(WIZARD_STATION_NAME, ignoreCase = true) }
                val base = existing
                    ?: runCatching { c.getAiRadioStationTemplate() }.getOrNull()
                    ?: AiRadioStation(id = "", name = WIZARD_STATION_NAME)
                val station = base.copy(
                    name = WIZARD_STATION_NAME,
                    sourcePlaylistId = playlist.itemIdFromUri ?: base.sourcePlaylistId,
                    sourcePlaylistProvider = playlist.providerFromUri ?: base.sourcePlaylistProvider,
                    hostId = host.id,
                    defaultPlayerId = playerId
                )
                c.saveAiRadioStation(station)
                // Na opslaan het (mogelijk net aangemaakte) station-id ophalen.
                val toStart = runCatching { c.getAiRadioStations() }.getOrDefault(emptyList())
                    .firstOrNull { it.name.equals(WIZARD_STATION_NAME, ignoreCase = true) }
                    ?: station
                c.startAiRadio(playerId, toStart)
                delay(800)
                tick()
                loadAiRadioData()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "AI Radio met presentator starten mislukt: ${e.message}") }
            }
        }
    }

    fun playPlaylistNext(playlist: MassPlaylist) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        lastLocalChangeTime = System.currentTimeMillis()
        _uiState.update { it.copy(activePlaylistName = playlist.name, activePlaylistUri = playlist.uri) }
        viewModelScope.launch {
            onSavePlaylist?.invoke(playlist.name, playlist.uri)
            try {
                client?.playMedia(playerId, playlist.uri, "replace_next")
                delay(1200)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Vervangen mislukt: ${e.message}") }
            }
        }
    }

    fun playRadioNow(radio: MassRadio) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        lastLocalChangeTime = System.currentTimeMillis()
        _uiState.update { it.copy(activePlaylistName = radio.name, activePlaylistUri = radio.uri) }
        viewModelScope.launch {
            onSavePlaylist?.invoke(radio.name, radio.uri)
            try {
                client?.playMedia(playerId, radio.uri, "replace")
                delay(1000)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Afspelen mislukt: ${e.message}") }
            }
        }
    }

    private companion object {
        /** Hoe lang "Eerder op deze zender" beschermd blijft na een handmatige nummerkeuze. */
        const val PENDING_RADIO_RESUME_MS = 5 * 60 * 1000L
    }

    fun playRadioNext(radio: MassRadio) {
        val playerId = _uiState.value.selectedPlayerId ?: return
        lastLocalChangeTime = System.currentTimeMillis()
        _uiState.update { it.copy(activePlaylistName = radio.name, activePlaylistUri = radio.uri) }
        viewModelScope.launch {
            onSavePlaylist?.invoke(radio.name, radio.uri)
            try {
                client?.playMedia(playerId, radio.uri, "replace_next")
                delay(1200)
                tick()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Vervangen mislukt: ${e.message}") }
            }
        }
    }
}
