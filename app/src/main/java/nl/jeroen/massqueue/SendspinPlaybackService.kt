package nl.jeroen.massqueue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.sendspin.protocol.ArtworkChannel
import com.sendspin.protocol.AudioFormat
import com.sendspin.protocol.ClientPreferences
import com.sendspin.protocol.ClientSettingsStore
import com.sendspin.protocol.ClientState
import com.sendspin.protocol.GroupPlaybackState
import com.sendspin.protocol.JsonOptional
import com.sendspin.protocol.JsonOptionalAdapterFactory
import com.sendspin.protocol.OptionalRole
import com.sendspin.protocol.SendSpinClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Wat de instellingen en de spelerslijst over de telefoon-als-speler moeten weten. */
data class PhonePlayerStatus(
    val running: Boolean = false,
    /** Korte statustekst voor Instellingen, bv. "Verbonden (lokaal)". */
    val text: String = "Uit",
    val clientId: String? = null,
    val isError: Boolean = false
)

/**
 * Laat de telefoon zelf als Music Assistant-speler meedoen via Sendspin.
 *
 * Verbindt (client-initiated) met ws://<MA>/sendspin: eerst het lokale adres, anders het
 * externe (Tailscale) adres. MA ziet ons als speler met een vaste client-ID; de audio wordt
 * door [SendspinAudioPlayer] afgespeeld, lockscreen/notificatie via Media3.
 *
 * Draait als foreground-service zolang "Telefoon als speler" aan staat, zodat MA ook kan
 * beginnen met afspelen als de app op de achtergrond staat. Wake- en wifilock worden alleen
 * vastgehouden zolang er daadwerkelijk audio binnenkomt.
 *
 * Android Auto: als MediaLibraryService biedt deze service ook een browse tree
 * ([AutoBrowseTree]), zoeken, de MA-wachtrij en knoppen voor shuffle/herhalen/favoriet.
 * Die extra's (wachtrij en knoppen) staan alleen aan zolang Android Auto verbonden is, zodat
 * melding en lockscreen op de telefoon er verder precies zo uitzien als zonder auto. Koppelt
 * Android Auto terwijl "Telefoon als speler" uit staat, dan doet de telefoon tijdelijk mee
 * en stopt de service weer als de auto weg is.
 *
 * Let op: sendspin-jvm kent (nog) geen Noise-encryptie; dit is het legacy-pad. MA 2.10+
 * accepteert dat alleen met "Allow legacy clients" aan.
 */
@OptIn(UnstableApi::class)
class SendspinPlaybackService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var sessionPlayer: SendspinSessionPlayer
    private lateinit var session: MediaLibrarySession
    private var client: SendSpinClient? = null
    private var observeJob: Job? = null
    private var authClient: SendspinAuthClient? = null
    private var audioPlayer: SendspinAudioPlayer? = null
    private var settings: SendspinSettings? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private lateinit var connectivity: ConnectivityManager
    /** Ruimere buffer actief (mobiel netwerk)? */
    private var onMobile = false

    // ---- Android Auto ---------------------------------------------------------------
    private val autoControllers = mutableSetOf<MediaSession.ControllerInfo>()
    private val browseTree = AutoBrowseTree { api() }
    private var apiClient: MassApiClient? = null
    /** Player-ID van deze telefoon in MA (universal player "up…", niet de Sendspin-client-ID). */
    private var maPlayerId: String? = null
    private var queueState: QueueState? = null
    private var queueJob: Job? = null
    /** Net als favoriet gemarkeerd (MA meldt dat niet per wachtrij-item); hart blijft dan gevuld. */
    private var favoritedUri: String? = null
    private val searchResults = mutableMapOf<String, List<MediaItem>>()

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        connectivity = getSystemService(ConnectivityManager::class.java)
        createChannel()
        // Binnen 5 s na startForegroundService() moet er een notificatie staan;
        // Media3 vervangt deze daarna (zelfde ID). Koppelt alleen Android Auto (bind, geen
        // start) terwijl de app op de achtergrond staat, dan mag dit niet; Media3 zet de
        // service dan zelf op de voorgrond zodra er iets speelt.
        try {
            startForegroundCompat(placeholderNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Nog niet op de voorgrond", e)
        }

        setMediaNotificationProvider(SendspinNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_stat_media) })

        sessionPlayer = SendspinSessionPlayer(
            sendCommand = { client?.sendControllerCommand(it) },
            sendSeek = { client?.sendSeek(it) },
            idleArtist = defaultSendspinClientName(this),
            onPlayRequest = ::playRequest,
            onJumpTo = ::jumpTo
        )
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setId("sendspin")
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        addSession(session)

        connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        ContextCompat.registerReceiver(
            this, carConnectionReceiver, IntentFilter(CAR_CONNECTION_ACTION), ContextCompat.RECEIVER_EXPORTED
        )

        publish(PhonePlayerStatus(running = true, text = "Starten…"))
        scope.launch {
            val s = SettingsStore(applicationContext).loadSendspin()
            settings = s
            start(s)
        }
        scope.launch {
            SettingsStore(applicationContext).serverChanges().drop(1).collect { onServerChanged() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    /** Altijd foreground houden, ook tijdens pauze: anders kan MA ons op de achtergrond niet meer wekken. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, true)
    }

    /** App uit de recente apps geveegd: blijf gewoon speler. */
    override fun onTaskRemoved(rootIntent: Intent?) {}

    override fun onDestroy() {
        scope.cancel()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(carConnectionReceiver) }
        client?.disconnect("shutdown")
        audioPlayer?.stop()
        releaseLocks()
        abandonFocus()
        session.release()
        sessionPlayer.release()
        publish(PhonePlayerStatus())
        super.onDestroy()
    }

    // ---- Sendspin ------------------------------------------------------------------

    private fun start(settings: SendspinSettings) {
        authClient = SendspinAuthClient(
            delegate = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build(),
            // Alleen de proxy via het server-adres wil een token; de directe poort niet
            tokenForUrl = { url -> settings.token.takeIf { it.isNotBlank() && url == settings.externalUrl } },
            clientId = settings.clientId
        )
        connectLoop(settings)
    }

    /**
     * Bouwt de Sendspin-client. Op mobiel netwerk vragen we MA om een ruimere buffer
     * (meer audio vooruit), zodat korte haperingen in de auto niet hoorbaar worden.
     */
    private fun newClient(settings: SendspinSettings, mobile: Boolean): SendSpinClient {
        val moshi = Moshi.Builder()
            .add(JsonOptionalAdapterFactory())
            .addLast(KotlinJsonAdapterFactory())
            .build()
        val base = ClientPreferences(
            supportedFormats = listOf(
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 48_000, bitDepth = 16),
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 44_100, bitDepth = 16)
            ),
            artworkChannels = listOf(ArtworkChannel(source = "album", mediaWidth = 600, mediaHeight = 600)),
            supportedOptionalRoles = setOf(
                OptionalRole.PLAYER, OptionalRole.METADATA, OptionalRole.ARTWORK, OptionalRole.CONTROLLER
            )
        )
        val prefs = if (mobile) base.copy(playerBufferCapacity = MOBILE_BUFFER_CAPACITY) else base
        val c = SendSpinClient(
            okHttpClient = authClient!!,
            moshi = moshi,
            preferences = prefs,
            clientId = settings.clientId,
            clientName = settings.clientName,
            manufacturer = Build.MANUFACTURER,
            productName = Build.MODEL,
            softwareVersion = BuildConfig.VERSION_NAME,
            audioPlayerFactory = { buffer, clock -> SendspinAudioPlayer(buffer, clock).also { audioPlayer = it } },
            // Herverbinden doen we zelf (connectLoop): de bibliotheek probeert anders elke seconde
            // opnieuw als de server ons meteen weer afsluit (bijv. "auth required").
            reconnectEnabled = false,
            settingsStore = PrefsClientSettingsStore(this)
        )
        client = c
        onMobile = mobile
        if (mobile) applyMobileBuffer(c, true)
        observeJob?.cancel()
        observeJob = observe(c)
        return c
    }

    private fun applyMobileBuffer(c: SendSpinClient, mobile: Boolean) {
        c.setRequiredLeadTimeMs(if (mobile) MOBILE_BUFFER_MS else 0)
        c.setMinBufferMs(if (mobile) MOBILE_BUFFER_MS else 0)
    }

    /**
     * Probeert eerst het server-adres (Tailscale, met API-token via MA's proxy), daarna de
     * directe Sendspin-poort thuis. Na een verbroken verbinding beginnen we weer vooraan.
     * Mislukt een hele ronde, dan wachten we steeds langer (5 s → 60 s), zodat we de
     * server niet bestoken. Is het netwerktype (wifi/mobiel) veranderd, dan bouwen we de
     * client opnieuw op met de bijbehorende buffergrootte.
     */
    private fun connectLoop(settings: SendspinSettings) = scope.launch {
        val urls = listOfNotNull(settings.externalUrl, settings.localUrl).distinct()
        val connected = setOf(ClientState.CLOCK_SYNCING, ClientState.STREAMING)
        val lost = setOf(ClientState.ERROR, ClientState.DISCONNECTED)
        var c: SendSpinClient? = null
        var clientMobile = false
        var attempt = 0
        var failedRounds = 0
        while (isActive) {
            val mobile = isOnMobileNetwork()
            if (c == null || mobile != clientMobile) {
                c?.disconnect("network")
                audioPlayer?.stop()
                c = newClient(settings, mobile)
                clientMobile = mobile
            }
            val url = urls[attempt % urls.size]
            val where = if (url == settings.externalUrl) "via server-adres" else "lokaal"
            publish(PhonePlayerStatus(true, "Verbinden ($where)…", settings.clientId))
            sessionPlayer.setIdleText("Verbinden met Music Assistant…")
            c.disconnect("switch")
            c.connect(url)

            // Verbonden, geweigerd/verbroken, of time-out (null)
            val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS + 2_000) {
                c.state.first { it in connected || it in lost }
            }
            if (result in connected) {
                failedRounds = 0
                publish(PhonePlayerStatus(true, "Verbonden ($where)", settings.clientId))
                sessionPlayer.setIdleText("Verbonden met Music Assistant")
                c.state.first { it in lost }
                publish(PhonePlayerStatus(true, "Verbinding kwijt, opnieuw proberen…", settings.clientId))
                delay(RECONNECT_DELAY_MS)
                attempt = 0
            } else {
                attempt++
                if (attempt % urls.size == 0) {
                    failedRounds++
                    val reason = authClient?.lastRejection?.let { ": $it" }.orEmpty()
                    publish(PhonePlayerStatus(true, "Geen verbinding met MA$reason", settings.clientId, isError = true))
                    sessionPlayer.setIdleText("Geen verbinding met Music Assistant")
                    delay((RETRY_BASE_MS shl (failedRounds - 1).coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS))
                }
            }
        }
    }

    private fun observe(c: SendSpinClient): Job = scope.launch {
        // Nu-speelt-info: MA stuurt alleen gewijzigde velden (Absent = ongewijzigd laten)
        launch {
            c.serverState.collect { st ->
                val md = st.metadata ?: return@collect
                val progress = md.progress
                val previousTitle = currentTitle
                sessionPlayer.update(
                    title = md.title.merge(currentTitle).also { currentTitle = it },
                    artist = md.artist.merge(currentArtist).also { currentArtist = it },
                    album = md.album.merge(currentAlbum).also { currentAlbum = it },
                    positionMs = progress?.trackProgress ?: 0L,
                    durationMs = progress?.trackDuration ?: 0L
                )
                // Ander nummer: wachtrij in Android Auto bijwerken
                if (currentTitle != previousTitle) refreshQueue(QUEUE_REFRESH_DELAY_MS)
            }
        }
        launch {
            c.albumArtwork.collect { sessionPlayer.update(artwork = it) }
        }
        launch {
            combine(c.groupPlaybackState, c.streamFormat) { group, format -> group to format }
                .collect { (group, format) ->
                    val playing = group == GroupPlaybackState.PLAYING
                    sessionPlayer.confirmPlaying(playing)
                    val streaming = format != null
                    if (streaming) acquireLocks() else releaseLocks()
                    if (streaming && playing) requestFocus() else if (!streaming) abandonFocus()
                }
        }
    }

    private var currentTitle: String? = null
    private var currentArtist: String? = null
    private var currentAlbum: String? = null

    private fun JsonOptional<String>.merge(current: String?): String? =
        if (this is JsonOptional.Present) value else current

    // ---- Netwerk / buffer ----------------------------------------------------------------

    /**
     * Mobiel = er is mobiele data en geen gevalideerde wifi/ethernet. Een autowifi zonder
     * internet (draadloos Android Auto) telt dus niet als wifi. VPN (Tailscale) negeren we.
     */
    @Suppress("DEPRECATION")
    private fun isOnMobileNetwork(): Boolean {
        val caps = connectivity.allNetworks
            .mapNotNull { connectivity.getNetworkCapabilities(it) }
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val cellular = caps.any {
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val wifi = caps.any {
            (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        return cellular && !wifi
    }

    /** Netwerk gewisseld terwijl de verbinding bleef staan: buffer-wens meteen aan MA doorgeven. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recheck()
        override fun onLost(network: Network) = recheck()

        private fun recheck() {
            scope.launch {
                val mobile = isOnMobileNetwork()
                if (mobile == onMobile) return@launch
                onMobile = mobile
                client?.let { applyMobileBuffer(it, mobile) }
            }
        }
    }

    // ---- Android Auto: afspelen, wachtrij, knoppen -----------------------------------

    private suspend fun api(): MassApiClient? {
        apiClient?.let { return it }
        val s = SettingsStore(applicationContext).load()
        if (s.url.isBlank()) return null
        return MassApiClient(s.url, s.token).also { apiClient = it }
    }

    /** Zoekt onze speler in MA; wacht zo nodig even tot de Sendspin-verbinding er is. */
    private suspend fun resolveMaPlayerId(): String? {
        maPlayerId?.let { return it }
        val api = api() ?: return null
        repeat(PLAYER_LOOKUP_ATTEMPTS) {
            val clientId = settings?.clientId
            val c = client
            if (clientId != null && c != null &&
                c.state.value in setOf(ClientState.CLOCK_SYNCING, ClientState.STREAMING)
            ) {
                val p = try {
                    api.getAllPlayers().firstOrNull { it.id == clientId || clientId in it.outputProtocolIds }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (p != null) return p.id.also { maPlayerId = it }
            }
            delay(1_000)
        }
        return null
    }

    /** Android Auto koos een item (of gesproken zoekopdracht): via MA op deze telefoon afspelen. */
    private fun playRequest(item: MediaItem) {
        scope.launch {
            try {
                val id = item.mediaId
                val uri = when {
                    id.isNotBlank() && !browseTree.isFolder(id) -> id
                    else -> browseTree.bestMatchUri(item.requestMetadata.searchQuery.orEmpty())
                } ?: return@launch
                val playerId = resolveMaPlayerId() ?: return@launch
                api()?.playMedia(playerId, uri, "replace")
                refreshQueue(QUEUE_REFRESH_DELAY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Afspelen via Android Auto mislukt", e)
            }
        }
    }

    private fun jumpTo(absoluteIndex: Int) {
        queueAction { id, api -> api.playIndex(id, absoluteIndex) }
    }

    private fun queueAction(block: suspend (String, MassApiClient) -> Unit) {
        scope.launch {
            try {
                val id = resolveMaPlayerId() ?: return@launch
                val api = api() ?: return@launch
                block(id, api)
                refreshQueue(QUEUE_REFRESH_DELAY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Wachtrij-actie mislukt", e)
            }
        }
    }

    /** Haalt de MA-wachtrij op voor Android Auto. Zonder auto doen we niets (telefoon blijft als voorheen). */
    private fun refreshQueue(delayMs: Long = 0) {
        if (autoControllers.isEmpty()) return
        queueJob?.cancel()
        queueJob = scope.launch {
            delay(delayMs)
            val q = try {
                resolveMaPlayerId()?.let { api()?.getQueue(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Wachtrij ophalen mislukt", e)
                null
            }
            if (autoControllers.isEmpty()) return@launch
            queueState = q
            sessionPlayer.setQueue(
                q?.items?.map {
                    SessionQueueItem(
                        absoluteIndex = it.absoluteIndex,
                        title = it.streamTrack ?: it.title,
                        subtitle = it.streamArtist ?: it.subtitle,
                        artworkUri = ArtworkProvider.uriFor(it.streamImage ?: it.imagePath),
                        durationMs = (it.durationSeconds ?: 0) * 1000L
                    )
                },
                q?.currentIndex ?: -1,
                wraps = q?.repeatMode == "all"
            )
            updateCustomLayout()
        }
    }

    /**
     * Server-adres of token gewijzigd terwijl de service draait (bijv. net ingesteld met Android
     * Auto al verbonden): nieuwe API-client, en wachtrij en bladermenu opnieuw laten ophalen.
     */
    private fun onServerChanged() {
        apiClient = null
        maPlayerId = null
        if (autoControllers.isEmpty()) return
        refreshQueue()
        listOf(AutoBrowseTree.ROOT, AutoBrowseTree.FAVORITES, AutoBrowseTree.RADIO).forEach {
            session.notifyChildrenChanged(it, Int.MAX_VALUE, null)
        }
    }

    private fun onAutoConnected() {
        refreshQueue()
        updateCustomLayout()
    }

    /**
     * Is de telefoon nu met een auto (of de DHU) verbonden? Zelfde bron als androidx.car.app's
     * CarConnection. Media3 merkt het wegvallen van Android Auto soms pas na minuten (of niet)
     * op; hiermee ruimen we wachtrij en knoppen meteen op. Onbekend telt als verbonden.
     */
    private suspend fun isCarConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(CAR_CONNECTION_URI, arrayOf(CAR_CONNECTION_COLUMN), null, null, null)
                ?.use { it.moveToFirst() && it.getInt(0) != 0 }
                ?: true
        } catch (e: Exception) {
            true
        }
    }

    private val carConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                if (autoControllers.isNotEmpty() && !isCarConnected()) {
                    autoControllers.clear()
                    onAutoDisconnected()
                }
            }
        }
    }

    private fun onAutoDisconnected() {
        queueJob?.cancel()
        queueState = null
        sessionPlayer.setQueue(null, -1, wraps = false)
        updateCustomLayout()
        // Was "Telefoon als speler" uit? Dan deed de telefoon alleen voor de auto mee.
        scope.launch {
            if (!SettingsStore(applicationContext).loadSendspin().enabled) stopSelf()
        }
    }

    /** Shuffle, herhalen en favoriet; leeg zonder Android Auto. */
    private fun customLayout(): List<CommandButton> {
        if (autoControllers.isEmpty()) return emptyList()
        val q = queueState
        val shuffle = q?.shuffleEnabled == true
        val repeat = q?.repeatMode ?: "off"
        val currentUri = q?.currentItem?.uri
        val favorited = currentUri != null && currentUri == favoritedUri
        return listOf(
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (shuffle) "Shuffle uit" else "Shuffle aan")
                .setSessionCommand(CMD_SHUFFLE)
                .build(),
            CommandButton.Builder(
                when (repeat) {
                    "all" -> CommandButton.ICON_REPEAT_ALL
                    "one" -> CommandButton.ICON_REPEAT_ONE
                    else -> CommandButton.ICON_REPEAT_OFF
                }
            )
                .setDisplayName("Herhalen")
                .setSessionCommand(CMD_REPEAT)
                .build(),
            CommandButton.Builder(if (favorited) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName("Favoriet maken")
                .setSessionCommand(CMD_FAVORITE)
                .setEnabled(currentUri != null)
                .build()
        )
    }

    /**
     * Android Auto leest de knoppen uit de systeem-mediasessie, en die volgt in Media3 de
     * knoppen van de notificatie-controller. De notificatie zelf toont ze niet
     * ([SendspinNotificationProvider]).
     */
    private fun updateCustomLayout() {
        val layout = customLayout()
        session.mediaNotificationControllerInfo?.let { session.setCustomLayout(it, layout) }
        autoControllers.forEach { session.setCustomLayout(it, layout) }
    }

    private fun onCustomAction(action: String) {
        val q = queueState
        when (action) {
            CMD_SHUFFLE.customAction -> {
                val on = !(q?.shuffleEnabled ?: false)
                queueState = q?.copy(shuffleEnabled = on)
                queueAction { id, api -> api.shuffleQueue(id, on) }
            }
            CMD_REPEAT.customAction -> {
                val next = when (q?.repeatMode) { "off", null -> "all"; "all" -> "one"; else -> "off" }
                queueState = q?.copy(repeatMode = next)
                queueAction { id, api -> api.setRepeat(id, next) }
            }
            CMD_FAVORITE.customAction -> {
                val uri = q?.currentItem?.uri ?: return
                favoritedUri = uri
                queueAction { _, api -> api.addToFavorites(uri) }
            }
        }
        updateCustomLayout()
    }

    /** Draait [block] als coroutine en levert het resultaat als Guava-future voor Media3. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            try {
                f.set(block())
            } catch (e: CancellationException) {
                f.cancel(false)
                throw e
            } catch (e: Exception) {
                f.setException(e)
            }
        }
        return f
    }

    private fun List<MediaItem>.page(page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return this
        val from = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
        return subList(from, (from + pageSize).coerceAtMost(size))
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val isAuto = session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)
            if (isAuto && autoControllers.add(controller) && autoControllers.size == 1) onAutoConnected()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(CMD_SHUFFLE)
                .add(CMD_REPEAT)
                .add(CMD_FAVORITE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .apply { if (isAuto || session.isMediaNotificationController(controller)) setCustomLayout(customLayout()) }
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (autoControllers.remove(controller) && autoControllers.isEmpty()) onAutoDisconnected()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            onCustomAction(customCommand.customAction)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Items van Android Auto hebben alleen een media-ID of zoekopdracht; de speler vertaalt die naar MA. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // "Recent" (hervatten na opstarten) bieden we niet aan
            if (params?.isRecent == true) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
            }
            val rootParams = LibraryParams.Builder().setExtras(browseTree.rootExtras()).build()
            return Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), rootParams))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = browseTree.item(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                LibraryResult.ofItemList(browseTree.children(parentId).page(page, pageSize), params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Bladeren mislukt ($parentId)", e)
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val results = try {
                    browseTree.search(query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Zoeken mislukt", e)
                    emptyList()
                }
                searchResults[query] = results
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                val results = searchResults[query] ?: browseTree.search(query).also { searchResults[query] = it }
                LibraryResult.ofItemList(results.page(page, pageSize), params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Zoekresultaat mislukt", e)
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }
    }

    /** Zelfde melding als altijd: de Android Auto-knoppen (custom layout) blijven eruit. */
    private class SendspinNotificationProvider(context: Context) :
        DefaultMediaNotificationProvider(context, { NOTIF_ID }, CHANNEL, R.string.sendspin_channel_name) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean
        ): ImmutableList<CommandButton> = super.getMediaButtons(session, playerCommands, ImmutableList.of(), showPauseButton)
    }

    // ---- Wake/wifi-locks --------------------------------------------------------------

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpinFlow:sendspin")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "SpinFlow:sendspin")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // ---- Audio focus -------------------------------------------------------------------


    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> audioPlayer?.setFocusGain(1f)
            // Navigatie-aanwijzing e.d.: zachter
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> audioPlayer?.setFocusGain(DUCK_GAIN)
            // Telefoongesprek: alleen deze telefoon stil. MA pauzeren zou een hele groep stilleggen.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> audioPlayer?.setFocusGain(0f)
            // Andere app speelt nu muziek: dan pauzeren we echt
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                client?.sendControllerCommand("pause")
            }
        }
    }

    private fun requestFocus() {
        if (hasFocus) return
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Ducken doen we zelf (de AudioTrack-gain), zodat ook MA's volume behouden blijft
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) {
            audioPlayer?.setFocusGain(1f)
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasFocus = false
    }

    // ---- Notificatie / foreground -------------------------------------------------------

    private fun placeholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_media)
            .setContentTitle(getString(R.string.sendspin_channel_name))
            .setContentText("Verbinden met Music Assistant…")
            .setOngoing(true)
            .build()

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, getString(R.string.sendspin_channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "SendspinService"
        private const val CHANNEL = "sendspin"
        // Anders dan PlaybackService (1001), anders overschrijven ze elkaars melding
        private const val NOTIF_ID = 2001
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val RETRY_BASE_MS = 5_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val DUCK_GAIN = 0.2f

        /** Op mobiel netwerk: ~5 s audio (48 kHz, 16 bit, stereo) i.p.v. de standaard 256 KB (~1,4 s). */
        private const val MOBILE_BUFFER_CAPACITY = 1_048_576
        /** Op mobiel netwerk: zoveel ms vooruit vragen we MA te sturen/bufferen. */
        private const val MOBILE_BUFFER_MS = 3_000

        /** MA heeft na een nummerwissel even nodig voordat current_index klopt. */
        private const val QUEUE_REFRESH_DELAY_MS = 800L
        private const val PLAYER_LOOKUP_ATTEMPTS = 15

        // Verbindingsstatus van Android Auto (zoals androidx.car.app.connection.CarConnection die leest)
        private val CAR_CONNECTION_URI = Uri.parse("content://androidx.car.app.connection")
        private const val CAR_CONNECTION_COLUMN = "CarConnectionState"
        private const val CAR_CONNECTION_ACTION = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"

        private val CMD_SHUFFLE = SessionCommand("nl.jeroen.massqueue.SHUFFLE", Bundle.EMPTY)
        private val CMD_REPEAT = SessionCommand("nl.jeroen.massqueue.REPEAT", Bundle.EMPTY)
        private val CMD_FAVORITE = SessionCommand("nl.jeroen.massqueue.FAVORITE", Bundle.EMPTY)

        private val _status = MutableStateFlow(PhonePlayerStatus())
        /** Status voor Instellingen en de spelerslijst. */
        val status: StateFlow<PhonePlayerStatus> = _status.asStateFlow()

        private fun publish(status: PhonePlayerStatus) {
            _status.value = status
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, SendspinPlaybackService::class.java))
            } catch (_: Exception) {
                // bv. ForegroundServiceStartNotAllowedException op de achtergrond
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SendspinPlaybackService::class.java))
        }
    }
}

/** Laat sendspin-jvm volume, mute en vertraging bewaren over herstarts heen. */
private class PrefsClientSettingsStore(context: Context) : ClientSettingsStore {
    private val prefs = context.getSharedPreferences("sendspin_client", Context.MODE_PRIVATE)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getString(key: String, default: String?) = prefs.getString(key, default)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
}
