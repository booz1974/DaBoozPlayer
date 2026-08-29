package nl.jeroen.massqueue

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Wat er nu speelt, in een vorm die de MediaSession/notificatie kan tonen. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val artUrl: String?,
    val isPlaying: Boolean
)

/** Transport-acties die vanaf lockscreen / notificatie / bluetooth binnenkomen. */
enum class Transport { PLAY_PAUSE, NEXT, PREVIOUS, STOP }

/**
 * Procesbrede brug tussen [MassViewModel] (kent de wachtrij, praat met de
 * server) en [PlaybackService] (toont de MediaSession-notificatie en vangt
 * lockscreen- / bluetooth-knoppen op).
 *
 * - [MassViewModel] schrijft elke poll de nu-speelt-info naar [state] en
 *   voert de acties uit die via [commands] binnenkomen.
 * - [PlaybackService] leest [state] om de notificatie te bouwen en stuurt
 *   knopdrukken door via [send].
 */
object NowPlayingBus {

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<Transport>(extraBufferCapacity = 8)
    val commands: SharedFlow<Transport> = _commands.asSharedFlow()

    fun publish(nowPlaying: NowPlaying?) {
        _state.value = nowPlaying
    }

    fun send(command: Transport) {
        _commands.tryEmit(command)
    }
}
