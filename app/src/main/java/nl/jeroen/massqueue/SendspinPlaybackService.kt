package nl.jeroen.massqueue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * Let op: sendspin-jvm kent (nog) geen Noise-encryptie; dit is het legacy-pad. MA 2.10+
 * accepteert dat alleen met "Allow legacy clients" aan.
 */
@OptIn(UnstableApi::class)
class SendspinPlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var sessionPlayer: SendspinSessionPlayer
    private lateinit var session: MediaSession
    private var client: SendSpinClient? = null
    private var authClient: SendspinAuthClient? = null
    private var audioPlayer: SendspinAudioPlayer? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
        // Binnen 5 s na startForegroundService() moet er een notificatie staan;
        // Media3 vervangt deze daarna (zelfde ID).
        startForegroundCompat(placeholderNotification())

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIF_ID)
                .setChannelId(CHANNEL)
                .setChannelName(R.string.sendspin_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_media) }
        )

        sessionPlayer = SendspinSessionPlayer(
            sendCommand = { client?.sendControllerCommand(it) },
            sendSeek = { client?.sendSeek(it) },
            idleArtist = defaultSendspinClientName(this)
        )
        session = MediaSession.Builder(this, sessionPlayer)
            .setId("sendspin")
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        addSession(session)

        publish(PhonePlayerStatus(running = true, text = "Starten…"))
        scope.launch { start(SettingsStore(applicationContext).loadSendspin()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /** Altijd foreground houden, ook tijdens pauze: anders kan MA ons op de achtergrond niet meer wekken. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, true)
    }

    /** App uit de recente apps geveegd: blijf gewoon speler. */
    override fun onTaskRemoved(rootIntent: Intent?) {}

    override fun onDestroy() {
        scope.cancel()
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
        val okHttp = SendspinAuthClient(
            delegate = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build(),
            // Alleen de proxy via het server-adres wil een token; de directe poort niet
            tokenForUrl = { url -> settings.token.takeIf { it.isNotBlank() && url == settings.externalUrl } },
            clientId = settings.clientId
        )
        authClient = okHttp
        val moshi = Moshi.Builder()
            .add(JsonOptionalAdapterFactory())
            .addLast(KotlinJsonAdapterFactory())
            .build()
        val prefs = ClientPreferences(
            supportedFormats = listOf(
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 48_000, bitDepth = 16),
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 44_100, bitDepth = 16)
            ),
            artworkChannels = listOf(ArtworkChannel(source = "album", mediaWidth = 600, mediaHeight = 600)),
            supportedOptionalRoles = setOf(
                OptionalRole.PLAYER, OptionalRole.METADATA, OptionalRole.ARTWORK, OptionalRole.CONTROLLER
            )
        )
        val c = SendSpinClient(
            okHttpClient = okHttp,
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

        observe(c)
        connectLoop(c, settings)
    }

    /**
     * Probeert eerst het server-adres (Tailscale, met API-token via MA's proxy), daarna de
     * directe Sendspin-poort thuis. Na een verbroken verbinding beginnen we weer vooraan.
     * Mislukt een hele ronde, dan wachten we steeds langer (5 s → 60 s), zodat we de
     * server niet bestoken.
     */
    private fun connectLoop(c: SendSpinClient, settings: SendspinSettings) = scope.launch {
        val urls = listOfNotNull(settings.externalUrl, settings.localUrl).distinct()
        val connected = setOf(ClientState.CLOCK_SYNCING, ClientState.STREAMING)
        val lost = setOf(ClientState.ERROR, ClientState.DISCONNECTED)
        var attempt = 0
        var failedRounds = 0
        while (isActive) {
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

    private fun observe(c: SendSpinClient) {
        // Nu-speelt-info: MA stuurt alleen gewijzigde velden (Absent = ongewijzigd laten)
        scope.launch {
            c.serverState.collect { st ->
                val md = st.metadata ?: return@collect
                val progress = md.progress
                sessionPlayer.update(
                    title = md.title.merge(currentTitle).also { currentTitle = it },
                    artist = md.artist.merge(currentArtist).also { currentArtist = it },
                    album = md.album.merge(currentAlbum).also { currentAlbum = it },
                    positionMs = progress?.trackProgress ?: 0L,
                    durationMs = progress?.trackDuration ?: 0L
                )
            }
        }
        scope.launch {
            c.albumArtwork.collect { sessionPlayer.update(artwork = it) }
        }
        scope.launch {
            combine(c.groupPlaybackState, c.streamFormat) { group, format -> group to format }
                .collect { (group, format) ->
                    val playing = group == GroupPlaybackState.PLAYING
                    sessionPlayer.update(playing = playing)
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
        private const val CHANNEL = "sendspin"
        // Anders dan PlaybackService (1001), anders overschrijven ze elkaars melding
        private const val NOTIF_ID = 2001
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val RETRY_BASE_MS = 5_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val DUCK_GAIN = 0.2f

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
