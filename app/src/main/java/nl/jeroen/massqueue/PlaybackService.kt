package nl.jeroen.massqueue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Foreground-service die een [MediaSessionCompat] beheert zodat het nu spelende
 * nummer op het lockscreen en in de notificatiebalk verschijnt, met werkende
 * play/pause/vorige/volgende (ook via bluetooth- en autoknoppen).
 *
 * De service speelt zelf geen audio af: knopdrukken gaan via [NowPlayingBus]
 * naar [MassViewModel], die het commando naar Music Assistant stuurt. De
 * nu-speelt-info komt langs dezelfde bus terug.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lastArtUrl: String? = null
    private var artBitmap: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        session = MediaSessionCompat(this, "MassQueue").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = NowPlayingBus.send(Transport.PLAY_PAUSE)
                override fun onPause() = NowPlayingBus.send(Transport.PLAY_PAUSE)
                override fun onSkipToNext() = NowPlayingBus.send(Transport.NEXT)
                override fun onSkipToPrevious() = NowPlayingBus.send(Transport.PREVIOUS)
                override fun onStop() = NowPlayingBus.send(Transport.STOP)
            })
            isActive = true
        }

        // Meteen een (lege) notificatie tonen; anders klaagt het systeem dat
        // startForeground niet binnen ~5s is aangeroepen.
        startForegroundCompat(buildNotification(null))

        scope.launch {
            NowPlayingBus.state.collectLatest { np ->
                if (np == null) {
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                if (np.artUrl != lastArtUrl) {
                    lastArtUrl = np.artUrl
                    artBitmap = np.artUrl?.let { loadArt(it) }
                }
                updateSession(np)
                notify(buildNotification(np))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We zijn (mogelijk) via startForegroundService() gestart; dan MOET er
        // binnen ~5s een startForeground() volgen — ook als we meteen weer willen
        // stoppen. Anders killt het systeem ons met
        // ForegroundServiceDidNotStartInTimeException.
        val np = NowPlayingBus.state.value
        startForegroundCompat(buildNotification(np))

        MediaButtonReceiver.handleIntent(session, intent)

        if (np == null) {
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    // ---- Session / notificatie ------------------------------------------------

    private fun updateSession(np: NowPlaying) {
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (np.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .build()
        )

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, np.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, np.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, np.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, np.artist)
                .apply { artBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
    }

    private fun action(icon: Int, title: String, playbackAction: Long) = NotificationCompat.Action(
        icon, title, MediaButtonReceiver.buildMediaButtonPendingIntent(this, playbackAction)
    )

    private fun buildNotification(np: NowPlaying?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_media)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(np?.title ?: getString(R.string.app_name))
            .setContentText(np?.artist ?: "Verbinden…")
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
            )

        artBitmap?.let { builder.setLargeIcon(it) }

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session.sessionToken)

        if (np != null) {
            builder.addAction(
                action(android.R.drawable.ic_media_previous, "Vorige", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            )
            builder.addAction(
                if (np.isPlaying)
                    action(android.R.drawable.ic_media_pause, "Pauze", PlaybackStateCompat.ACTION_PLAY_PAUSE)
                else
                    action(android.R.drawable.ic_media_play, "Afspelen", PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
            builder.addAction(
                action(android.R.drawable.ic_media_next, "Volgende", PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            )
            mediaStyle.setShowActionsInCompactView(0, 1, 2)
        }

        return builder.setStyle(mediaStyle).build()
    }

    private fun notify(notification: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
    }

    private suspend fun loadArt(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(this@PlaybackService)
                .data(url)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        } catch (_: Exception) {
            null
        }
    }

    // ---- Foreground helpers -------------------------------------------------

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "Afspelen", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1001

        /** Start (of re-attach) de service. Veilig om vaak aan te roepen. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, PlaybackService::class.java)
                )
            } catch (_: Exception) {
                // bv. ForegroundServiceStartNotAllowedException als de app op de
                // achtergrond staat; dan pakken we het de volgende keer weer op.
            }
        }
    }
}
