package nl.jeroen.massqueue

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Eén item uit de MA-wachtrij, voor de wachtrijweergave in Android Auto. */
data class SessionQueueItem(
    val absoluteIndex: Int,
    val title: String,
    val subtitle: String,
    val artworkUri: Uri?,
    val durationMs: Long
)

/**
 * Media3-"speler" voor lockscreen, notificatie en Android Auto van de telefoon-als-speler.
 *
 * Speelt zelf niets af: de audio komt van [SendspinAudioPlayer]. Deze klasse toont wat MA
 * via Sendspin meldt (titel, artiest, hoes, speelstatus) en stuurt knopdrukken terug als
 * Sendspin-controllercommando's, zodat MA de baas blijft over de wachtrij.
 *
 * Zolang de MA-wachtrij niet bekend is (zie [setQueue]; alleen met Android Auto) bestaat de
 * afspeellijst uit drie items (vorige · huidige · volgende) met de huidige in het midden.
 * Media3 biedt "volgende"/"vorige" alleen aan als er een item naast staat; een sprong naar
 * zo'n buur-item vertalen we naar het MA-commando next/previous. Met de echte wachtrij gaat
 * een sprong naar een verder item via [onJumpTo].
 *
 * Alleen aanroepen op de main thread.
 */
@OptIn(UnstableApi::class)
class SendspinSessionPlayer(
    private val sendCommand: (String) -> Unit,
    private val sendSeek: (Long) -> Unit,
    /** Tweede regel op de melding zolang MA geen artiest meldt (standaard-spelernaam). */
    private val idleArtist: String,
    /** Android Auto koos iets om af te spelen (browse-item of gesproken zoekopdracht). */
    private val onPlayRequest: (MediaItem) -> Unit = {},
    /** Sprong naar een wachtrij-item dat niet direct naast het huidige staat (absolute MA-index). */
    private val onJumpTo: (Int) -> Unit = {}
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var playing = false
    /** Laatste speelstatus die MA zelf meldde; [playing] kan daar tijdelijk optimistisch van afwijken. */
    private var confirmedPlaying = false
    private val handler = Handler(Looper.getMainLooper())
    /** MA meldt alleen wijzigingen: blijft een bevestiging uit (bijv. lege wachtrij), dan terug naar de echte status. */
    private val revertOptimistic = Runnable {
        if (playing != confirmedPlaying) {
            playing = confirmedPlaying
            invalidateState()
        }
    }
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var artwork: ByteArray? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var idleText = "Wacht op muziek…"

    private var queue: List<SessionQueueItem>? = null
    private var queueCurrent = -1
    private var queueWraps = false
    /** Per afspeellijst-positie de absolute MA-index; null voor de vorige/volgende-plaatshouders. */
    private var indexMap: List<Int?> = emptyList()
    private var currentListIndex = 1

    /** Na "speel dit item" stuurt Media3 nog een play; MA start zelf al, dus die slaan we over. */
    private var suppressPlayUntil = 0L

    fun update(
        playing: Boolean = this.playing,
        title: String? = this.title,
        artist: String? = this.artist,
        album: String? = this.album,
        artwork: ByteArray? = this.artwork,
        positionMs: Long = this.positionMs,
        durationMs: Long = this.durationMs
    ) {
        this.playing = playing
        this.title = title
        this.artist = artist
        this.album = album
        this.artwork = artwork
        this.positionMs = positionMs
        this.durationMs = durationMs
        invalidateState()
    }

    /** Speelstatus zoals MA die meldt (group/update). */
    fun confirmPlaying(playing: Boolean) {
        handler.removeCallbacks(revertOptimistic)
        confirmedPlaying = playing
        update(playing = playing)
    }

    /** Tekst op de notificatie zolang er niets speelt (bijv. "Verbonden met MA"). */
    fun setIdleText(text: String) {
        if (text == idleText) return
        idleText = text
        invalidateState()
    }

    /**
     * De MA-wachtrij rond het huidige nummer; null = onbekend (dan de drie-itemsvorm).
     * [wraps]: na het laatste item gaat MA verder (herhalen), dus "volgende" blijft mogelijk.
     */
    fun setQueue(items: List<SessionQueueItem>?, currentIndex: Int, wraps: Boolean) {
        queue = items
        queueCurrent = currentIndex
        queueWraps = wraps
        invalidateState()
    }

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: idleText)
            .setArtist(artist ?: idleArtist)
            .setAlbumTitle(album)
            .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()

        val q = queue
        val currentPos = q?.indexOfFirst { it.absoluteIndex == queueCurrent } ?: -1

        val current = MediaItemData.Builder(if (currentPos >= 0) "q$queueCurrent" else "current")
            .setMediaMetadata(metadata)
            .apply { if (durationMs > 0) setDurationUs(durationMs * 1000) }
            .build()

        val playlist: List<MediaItemData>
        if (q != null && currentPos >= 0) {
            val items = q.mapIndexed { i, item -> if (i == currentPos) current else queueItemData(item) }
            val map = q.map<SessionQueueItem, Int?> { it.absoluteIndex }
            if (currentPos == q.lastIndex && queueWraps) {
                playlist = items + MediaItemData.Builder("next").build()
                indexMap = map + null
            } else {
                playlist = items
                indexMap = map
            }
            currentListIndex = currentPos
        } else {
            playlist = listOf(
                MediaItemData.Builder("previous").build(),
                current,
                MediaItemData.Builder("next").build()
            )
            indexMap = listOf(null, null, null)
            currentListIndex = 1
        }

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        COMMAND_PLAY_PAUSE,
                        COMMAND_STOP,
                        COMMAND_PREPARE,
                        COMMAND_SET_MEDIA_ITEM,
                        COMMAND_SEEK_TO_NEXT,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS,
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        COMMAND_SEEK_TO_MEDIA_ITEM,
                        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_TIMELINE,
                        COMMAND_GET_METADATA
                    )
                    .build()
            )
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentListIndex)
            .setContentPositionMs(positionMs)
            // Altijd READY: zo blijft de notificatie (en dus de foreground-service) staan
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .build()
    }

    private fun queueItemData(item: SessionQueueItem): MediaItemData =
        MediaItemData.Builder("q${item.absoluteIndex}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.subtitle)
                    .setArtworkUri(item.artworkUri)
                    .build()
            )
            .apply { if (item.durationMs > 0) setDurationUs(item.durationMs * 1000) }
            .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady || SystemClock.elapsedRealtime() > suppressPlayUntil) {
            sendCommand(if (playWhenReady) "play" else "pause")
        }
        // Optimistisch; MA bevestigt via group/update (zie confirmPlaying)
        playing = playWhenReady
        expectConfirmation()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        sendCommand("stop")
        playing = false
        expectConfirmation()
        return Futures.immediateVoidFuture()
    }

    private fun expectConfirmation() {
        handler.removeCallbacks(revertOptimistic)
        handler.postDelayed(revertOptimistic, CONFIRM_TIMEOUT_MS)
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(revertOptimistic)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val item = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
        if (item != null) {
            suppressPlayUntil = SystemClock.elapsedRealtime() + SUPPRESS_PLAY_MS
            onPlayRequest(item)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = indexMap.getOrNull(mediaItemIndex)
        when {
            mediaItemIndex == currentListIndex || mediaItemIndex < 0 -> if (positionMs >= 0) {
                sendSeek(positionMs)
                this.positionMs = positionMs
            }
            // Plaatshouder naast de huidige (drie-itemsvorm of einde van de wachtrij)
            target == null -> sendCommand(if (mediaItemIndex > currentListIndex) "next" else "previous")
            target == queueCurrent + 1 -> {
                sendCommand("next")
                queueCurrent = target
            }
            target == queueCurrent - 1 -> {
                sendCommand("previous")
                queueCurrent = target
            }
            else -> {
                onJumpTo(target)
                queueCurrent = target
            }
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val SUPPRESS_PLAY_MS = 3_000L
        const val CONFIRM_TIMEOUT_MS = 5_000L
    }
}
