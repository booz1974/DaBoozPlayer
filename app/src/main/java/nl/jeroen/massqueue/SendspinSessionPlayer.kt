package nl.jeroen.massqueue

import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Media3-"speler" voor lockscreen en notificatie van de telefoon-als-speler.
 *
 * Speelt zelf niets af: de audio komt van [SendspinAudioPlayer]. Deze klasse toont wat MA
 * via Sendspin meldt (titel, artiest, hoes, speelstatus) en stuurt knopdrukken terug als
 * Sendspin-controllercommando's, zodat MA de baas blijft over de wachtrij.
 *
 * De afspeellijst bestaat altijd uit drie items (vorige · huidige · volgende) met de huidige
 * in het midden. Media3 biedt "volgende"/"vorige" alleen aan als er een item naast staat;
 * een sprong naar zo'n buur-item vertalen we naar het MA-commando next/previous.
 *
 * Alleen aanroepen op de main thread.
 */
@OptIn(UnstableApi::class)
class SendspinSessionPlayer(
    private val sendCommand: (String) -> Unit,
    private val sendSeek: (Long) -> Unit
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var playing = false
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var artwork: ByteArray? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var idleText = "Wacht op muziek…"

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

    /** Tekst op de notificatie zolang er niets speelt (bijv. "Verbonden met MA"). */
    fun setIdleText(text: String) {
        if (text == idleText) return
        idleText = text
        invalidateState()
    }

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: idleText)
            .setArtist(artist ?: DEFAULT_SENDSPIN_CLIENT_NAME)
            .setAlbumTitle(album)
            .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()

        val current = MediaItemData.Builder("current")
            .setMediaMetadata(metadata)
            .apply { if (durationMs > 0) setDurationUs(durationMs * 1000) }
            .build()

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        COMMAND_PLAY_PAUSE,
                        COMMAND_STOP,
                        COMMAND_SEEK_TO_NEXT,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS,
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_TIMELINE,
                        COMMAND_GET_METADATA
                    )
                    .build()
            )
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("previous").build(),
                    current,
                    MediaItemData.Builder("next").build()
                )
            )
            .setCurrentMediaItemIndex(1)
            .setContentPositionMs(positionMs)
            // Altijd READY: zo blijft de notificatie (en dus de foreground-service) staan
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        sendCommand(if (playWhenReady) "play" else "pause")
        // Optimistisch; MA bevestigt via group/update
        playing = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        sendCommand("stop")
        playing = false
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when {
            mediaItemIndex > 1 -> sendCommand("next")
            mediaItemIndex == 0 -> sendCommand("previous")
            positionMs >= 0 -> {
                sendSeek(positionMs)
                this.positionMs = positionMs
            }
        }
        return Futures.immediateVoidFuture()
    }
}
