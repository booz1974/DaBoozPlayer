package nl.jeroen.massqueue

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.launch
import nl.jeroen.massqueue.ui.PlayerScreen
import nl.jeroen.massqueue.ui.SettingsScreen
import nl.jeroen.massqueue.ui.theme.AppTheme
import nl.jeroen.massqueue.ui.theme.MassQueueTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MassViewModel by viewModels()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val state = viewModel.uiState.value
            val selectedPlayer = state.players.find { it.id == state.selectedPlayerId } ?: return super.onKeyDown(keyCode, event)
            
            // Check of de geselecteerde speler in de lijst staat voor volume-bediening via hardware knoppen
            val useInAppVolume = state.volumeControlPlayerIds.contains(selectedPlayer.id) || 
                               state.volumeControlPlayerIds.contains(selectedPlayer.name.lowercase().trim())

            if (useInAppVolume) {
                val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down"
                viewModel.setVolume(direction)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Locatie is alleen nodig voor het thuis-filter (150 m), dus zuinig: netwerk/wifi-nauwkeurigheid,
     * hooguit eens per minuut, en alleen zolang de app in beeld is (zie onStart/onStop).
     */
    private fun startLocationUpdates() {
        if (locationCallback != null || !hasLocationPermission()) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { viewModel.updateLocation(it.latitude, it.longitude) }
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000)
            .setMinUpdateIntervalMillis(60_000)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { viewModel.updateLocation(it.latitude, it.longitude) }
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, mainLooper)
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    override fun onStart() {
        super.onStart()
        startLocationUpdates()
        viewModel.setAppInForeground(true)
    }

    override fun onStop() {
        stopLocationUpdates()
        viewModel.setAppInForeground(false)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }

        // Toestemming voor de afspeel-notificatie (Android 13+).
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 1002)
        }

        setContent {
            var selectedTheme by remember { mutableStateOf(AppTheme.CASSETTE) }
            var showCompactHeader by remember { mutableStateOf(false) }

            MassQueueTheme(appTheme = selectedTheme) {
                var loadedUrl by remember { mutableStateOf<String?>(null) }
                var loadedToken by remember { mutableStateOf("") }
                val state by viewModel.uiState.collectAsState()
                var showSettings by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                // Start de MediaSession-service zodra er iets speelt (of klaarstaat).
                LaunchedEffect(Unit) {
                    NowPlayingBus.state.collect { np ->
                        if (np != null) PlaybackService.start(this@MainActivity)
                    }
                }

                LaunchedEffect(Unit) {
                    val settings = settingsStore.load()
                    val configured = settingsStore.isConfigured()
                    loadedUrl = settings.url
                    loadedToken = settings.token
                    selectedTheme = AppTheme.entries.firstOrNull { it.name == settings.selectedTheme } ?: AppTheme.CASSETTE
                    showCompactHeader = settings.showCompactHeader
                    viewModel.setDiscoPlayerId(settings.discoPlayerId)
                    viewModel.setPinnedPlayerIds(settings.pinnedPlayerIds)
                    if (configured && settings.url.isNotBlank()) {
                        viewModel.configureServer(
                            baseUrl = settings.url, 
                            authToken = settings.token.ifBlank { null }, 
                            initialPlaylistName = settings.activePlaylistName,
                            initialPlaylistUri = settings.activePlaylistUri,
                            locations = settings.locations,
                            activeLocationId = settings.activeLocationId,
                            volumeControlPlayerIds = settings.volumeControlPlayerIds,
                            localPlayerIds = settings.localPlayerIds,
                            hiddenPlayerIds = settings.hiddenPlayerIds,
                            playerAliases = settings.playerAliases,
                            initialRadioHistoryStationUri = settings.radioHistoryStationUri,
                            initialRadioHistory = settings.radioHistory,
                            playlistUsageCounts = settings.playlistUsage,
                            radioUsageCounts = settings.radioUsage,
                            saveCallback = { name, uri -> settingsStore.saveActivePlaylist(name, uri) },
                            saveLocationsCallback = { locs, id -> settingsStore.saveLocations(locs, id) },
                            saveVolumeCallback = { ids -> settingsStore.saveVolumePlayers(ids) },
                            saveLocalCallback = { ids -> settingsStore.saveLocalPlayers(ids) },
                            saveHiddenCallback = { ids -> settingsStore.saveHiddenPlayers(ids) },
                            saveAliasesCallback = { aliases -> settingsStore.savePlayerAliases(aliases) },
                            saveRadioHistoryCallback = { uri, history -> settingsStore.saveRadioHistory(uri, history) },
                            savePlaylistUsageCallback = { usage -> settingsStore.savePlaylistUsage(usage) },
                            saveRadioUsageCallback = { usage -> settingsStore.saveRadioUsage(usage) }
                        )
                    } else {
                        showSettings = true
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            loadedUrl == null -> {
                                // Toon een simpel laadscherm in plaats van een zwart scherm
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            showSettings -> {
                                // Systeem-terugknop sluit Instellingen i.p.v. de hele app (zolang er een server is)
                                BackHandler(enabled = loadedUrl?.isNotBlank() == true) { showSettings = false }
                                SettingsScreen(
                                    initialUrl = loadedUrl ?: "",
                                    initialToken = loadedToken,
                                    locations = state.locations,
                                    activeLocationId = state.activeLocationId,
                                    players = state.players,
                                    volumeControlPlayerIds = state.volumeControlPlayerIds,
                                    localPlayerIds = state.localPlayerIds,
                                    hiddenPlayerIds = state.hiddenPlayerIds,
                                    playerAliases = state.playerAliases,
                                    onSave = { url, token ->
                                        scope.launch {
                                            settingsStore.save(url, token)
                                            loadedUrl = url
                                            loadedToken = token
                                            showSettings = false
                                            viewModel.configureServer(
                                                baseUrl = url, 
                                                authToken = token.ifBlank { null },
                                                locations = state.locations,
                                                activeLocationId = state.activeLocationId,
                                                volumeControlPlayerIds = state.volumeControlPlayerIds,
                                                localPlayerIds = state.localPlayerIds,
                                                hiddenPlayerIds = state.hiddenPlayerIds,
                                                playerAliases = state.playerAliases,
                                                playlistUsageCounts = state.playlistUsageCounts,
                                                radioUsageCounts = state.radioUsageCounts,
                                                saveCallback = { n, u -> settingsStore.saveActivePlaylist(n, u) },
                                                saveLocationsCallback = { locs, id -> settingsStore.saveLocations(locs, id) },
                                                saveVolumeCallback = { ids -> settingsStore.saveVolumePlayers(ids) },
                                                saveLocalCallback = { ids -> settingsStore.saveLocalPlayers(ids) },
                                                saveHiddenCallback = { ids -> settingsStore.saveHiddenPlayers(ids) },
                                                saveAliasesCallback = { al -> settingsStore.savePlayerAliases(al) },
                                                saveRadioHistoryCallback = { uri, history -> settingsStore.saveRadioHistory(uri, history) },
                                                savePlaylistUsageCallback = { usage -> settingsStore.savePlaylistUsage(usage) },
                                                saveRadioUsageCallback = { usage -> settingsStore.saveRadioUsage(usage) }
                                            )
                                        }
                                    },
                                    onAddLocation = viewModel::addLocation,
                                    onDeleteLocation = viewModel::deleteLocation,
                                    onSelectLocation = viewModel::selectLocation,
                                    onPinLocation = viewModel::pinHomeLocation,
                                    onToggleVolumePlayer = { viewModel.toggleVolumePlayer(it) },
                                    onToggleLocalPlayer = { viewModel.toggleLocalPlayer(it) },
                                    onToggleHiddenPlayer = { viewModel.toggleHiddenPlayer(it) },
                                    onSetPlayerAlias = { id, alias -> viewModel.setPlayerAlias(id, alias) },
                                    selectedTheme = selectedTheme,
                                    onSelectTheme = { theme ->
                                        selectedTheme = theme
                                        scope.launch { settingsStore.saveTheme(theme.name) }
                                    },
                                    showCompactHeader = showCompactHeader,
                                    onToggleCompactHeader = { compact ->
                                        showCompactHeader = compact
                                        scope.launch { settingsStore.saveCompactHeader(compact) }
                                    },
                                    discoPlayerId = state.discoPlayer()?.id,
                                    onSelectDiscoPlayer = { id ->
                                        viewModel.setDiscoPlayerId(id)
                                        scope.launch { settingsStore.saveDiscoPlayer(id) }
                                    },
                                    pinnedPlayerIds = state.pinnedPlayerIds,
                                    onTogglePinnedPlayer = { id ->
                                        val next = state.pinnedPlayerIds.let { if (id in it) it - id else it + id }
                                        viewModel.setPinnedPlayerIds(next)
                                        scope.launch { settingsStore.savePinnedPlayers(next) }
                                    },
                                    onClose = if (loadedUrl?.isNotBlank() == true) { { showSettings = false } } else null
                                )
                            }
                            else -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    PlayerScreen(
                                        viewModel = viewModel,
                                        onOpenSettings = { showSettings = true },
                                        showCompactHeader = showCompactHeader
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
