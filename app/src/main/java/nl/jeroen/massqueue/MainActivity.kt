package nl.jeroen.massqueue

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.viewinterop.AndroidView
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

    private fun checkLocation(client: FusedLocationProviderClient) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        } else {
            // Directe check voor de laatste locatie
            client.lastLocation.addOnSuccessListener { location ->
                location?.let { viewModel.updateLocation(it.latitude, it.longitude) }
            }

            // Continue updates aanvragen (elke 5 seconden)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    for (location in locationResult.locations) {
                        viewModel.updateLocation(location.latitude, location.longitude)
                    }
                }
            }

            client.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            checkLocation(fusedLocationClient)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(applicationContext)
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        checkLocation(fusedLocationClient)

        // Toestemming voor de afspeel-notificatie (Android 13+).
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 1002)
        }

        setContent {
            MassQueueTheme {
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
                            saveCallback = { name, uri -> settingsStore.saveActivePlaylist(name, uri) },
                            saveLocationsCallback = { locs, id -> settingsStore.saveLocations(locs, id) },
                            saveVolumeCallback = { ids -> settingsStore.saveVolumePlayers(ids) },
                            saveLocalCallback = { ids -> settingsStore.saveLocalPlayers(ids) },
                            saveHiddenCallback = { ids -> settingsStore.saveHiddenPlayers(ids) },
                            saveAliasesCallback = { aliases -> settingsStore.savePlayerAliases(aliases) }
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
                                                saveCallback = { n, u -> settingsStore.saveActivePlaylist(n, u) },
                                                saveLocationsCallback = { locs, id -> settingsStore.saveLocations(locs, id) },
                                                saveVolumeCallback = { ids -> settingsStore.saveVolumePlayers(ids) },
                                                saveLocalCallback = { ids -> settingsStore.saveLocalPlayers(ids) },
                                                saveHiddenCallback = { ids -> settingsStore.saveHiddenPlayers(ids) },
                                                saveAliasesCallback = { al -> settingsStore.savePlayerAliases(al) }
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
                                    onSetPlayerAlias = { id, alias -> viewModel.setPlayerAlias(id, alias) }
                                )
                            }
                            else -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // Onzichtbare WebView om de sessie "warm" te houden.
                                    AndroidView(
                                        factory = { context ->
                                            WebView(context).apply {
                                                alpha = 0.01f
                                                layoutParams = ViewGroup.LayoutParams(10, 10)
                                                
                                                // Beveiligingsinstellingen
                                                settings.apply {
                                                    javaScriptEnabled = true
                                                    domStorageEnabled = true
                                                    databaseEnabled = true
                                                    allowFileAccess = false
                                                    allowContentAccess = false
                                                }
                                                
                                                val cookieManager = CookieManager.getInstance()
                                                cookieManager.setAcceptCookie(true)
                                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                                
                                                webViewClient = object : WebViewClient() {
                                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                        loadedUrl?.let { baseUrl ->
                                                            val cleanUrl = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
                                                            val cookieManager = CookieManager.getInstance()
                                                            cookieManager.setCookie(cleanUrl, "mass_token=$loadedToken; Path=/")
                                                            cookieManager.setCookie(cleanUrl, "access_token=$loadedToken; Path=/")
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        update = { webView ->
                                            loadedUrl?.let { url ->
                                                val finalUrl = if (url.startsWith("http")) url else "http://$url"
                                                if (webView.url != finalUrl) {
                                                    webView.loadUrl(finalUrl)
                                                }
                                            }
                                        }
                                    )

                                    PlayerScreen(viewModel, onOpenSettings = { showSettings = true })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
