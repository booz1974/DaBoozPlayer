package nl.jeroen.massqueue

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.PcmDriftCorrector
import com.sendspin.protocol.StreamFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speelt de PCM-stream van Sendspin af via een [AudioTrack].
 *
 * De bibliotheek zet binnenkomende chunks op servertijd in de [AudioBuffer]; deze speler
 * haalt ze eruit op het moment dat ze (na alles wat al in de AudioTrack zit) precies op
 * hun geplande tijd uit de speaker komen. Kleine afwijkingen worden weggewerkt met de
 * [PcmDriftCorrector] (onhoorbaar ±0,2% sneller/trager), grote door chunks over te slaan.
 *
 * Alle methodes worden door de bibliotheek vanaf één coroutine-thread aangeroepen; het
 * afspelen zelf draait op een eigen thread met audio-prioriteit.
 */
class SendspinAudioPlayer(
    private val buffer: AudioBuffer,
    private val clockSync: ClockSync
) : AudioPlayer {

    private val lock = Object()

    @Volatile private var format: StreamFormat? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    /** Verhoogd bij flush/transition: de afspeelthread bouwt dan een schone AudioTrack op. */
    @Volatile private var generation = 0

    @Volatile private var serverGain = 1f
    /** Audio focus: 1 = normaal, 0.2 = geduckt, 0 = tijdelijk stil (bijv. telefoongesprek). */
    @Volatile private var focusGain = 1f
    @Volatile private var track: AudioTrack? = null

    @Volatile private var dropped = 0L

    override val isPlaying: Boolean get() = running
    override val droppedDecodeFrames: Long get() = dropped

    override fun configure(format: StreamFormat) {
        this.format = format
        generation++
    }

    override fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            thread = Thread(::playLoop, "sendspin-audio").also { it.start() }
        }
    }

    override fun flush() {
        buffer.flush()
        generation++
    }

    override fun transition(format: StreamFormat) {
        // MA stuurt bij een nummerwissel niet altijd stream/clear: oude chunks weg.
        buffer.flush()
        if (format != this.format) {
            this.format = format
        }
        generation++
    }

    override fun stop() {
        val t: Thread?
        synchronized(lock) {
            running = false
            t = thread
            thread = null
        }
        t?.interrupt()
        t?.join(500)
    }

    override fun setVolume(gain: Float) {
        serverGain = gain.coerceIn(0f, 1f)
        applyGain()
    }

    /** Audio focus vanuit de service. */
    fun setFocusGain(gain: Float) {
        focusGain = gain.coerceIn(0f, 1f)
        applyGain()
    }

    private fun applyGain() {
        track?.setVolume(serverGain * focusGain)
    }

    // ---- Afspeelthread -----------------------------------------------------------

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var myGeneration = -1
        var out: Output? = null
        try {
            while (running) {
                if (myGeneration != generation) {
                    myGeneration = generation
                    out?.release()
                    out = format?.let { createOutput(it) }
                    track = out?.track
                    applyGain()
                }
                val o = out
                if (o == null) {
                    Thread.sleep(IDLE_SLEEP_MS)
                    continue
                }

                val now = ClockSync.localMicros()
                // Moment waarop het eerstvolgende frame dat we schrijven hoorbaar wordt. Staat stil
                // zolang we niets schrijven, daarom kijken we iets vooruit (EARLY_MICROS).
                val playAt = o.nextFramePlayMicros(now)
                val chunk = buffer.poll(playAt + EARLY_MICROS)
                if (chunk == null) {
                    val wait = buffer.nextChunkDelayMicros(playAt + EARLY_MICROS)
                    if (wait == null && o.framesWritten > 0 && o.queuedMicros() <= 0) buffer.signalUnderrun()
                    val sleepMs = ((wait ?: (IDLE_SLEEP_MS * 1000)) / 1000).coerceIn(1, IDLE_SLEEP_MS)
                    Thread.sleep(sleepMs)
                    continue
                }

                val scheduled = clockSync.toLocalMicros(chunk.serverTimestampMicros, now) - buffer.staticDelayMicros
                // Positief: we lopen achter (chunk had eerder moeten klinken); negatief: we zijn te vroeg
                var drift = playAt - scheduled
                if (drift > HARD_DROP_MICROS) {
                    dropped++
                    o.corrector.reset()
                    continue
                }
                if (drift < -SILENCE_GAP_MICROS) {
                    // Gat in de tijdlijn (bijv. begin van de stream): opvullen met stilte
                    o.writeSilence(-drift)
                    drift = 0
                }

                val pcm = o.toShorts(chunk.data)
                val blockMicros = pcm.size.toLong() / o.channels * 1_000_000L / o.sampleRate
                val corrected = o.corrector.correct(pcm, drift, blockMicros)
                o.write(corrected)
            }
        } catch (_: InterruptedException) {
            // stop()
        } catch (e: Exception) {
            Log.e(TAG, "Afspeelthread gestopt", e)
        } finally {
            track = null
            out?.release()
            running = false
        }
    }

    private fun createOutput(format: StreamFormat): Output? {
        if (!format.codec.equals("pcm", ignoreCase = true) || format.bitDepth != 16) {
            Log.w(TAG, "Niet-ondersteund formaat: $format")
            return null
        }
        val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minSize = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minSize * 4)
            .build()
        t.play()
        return Output(t, format.sampleRate, format.channels)
    }

    private class Output(val track: AudioTrack, val sampleRate: Int, val channels: Int) {
        val corrector = PcmDriftCorrector(channels)
        var framesWritten = 0L
        private val ts = AudioTimestamp()

        fun queuedMicros(): Long {
            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            return (framesWritten - played).coerceAtLeast(0) * 1_000_000L / sampleRate
        }

        /** Lokale tijd (µs) waarop het eerstvolgende te schrijven frame hoorbaar wordt. */
        fun nextFramePlayMicros(now: Long): Long {
            if (framesWritten > 0 && track.getTimestamp(ts) && ts.framePosition > 0) {
                val t = ts.nanoTime / 1000 + (framesWritten - ts.framePosition) * 1_000_000L / sampleRate
                // Na een onderloop kan de timestamp verouderd zijn; nooit in het verleden plannen
                if (t >= now) return t
            }
            return now + queuedMicros() + START_LATENCY_MICROS
        }

        fun toShorts(data: ByteArray): ShortArray {
            val shorts = ShortArray(data.size / 2)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return shorts
        }

        fun write(pcm: ShortArray) {
            val n = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (n > 0) framesWritten += n / channels
        }

        fun writeSilence(micros: Long) {
            val frames = (micros * sampleRate / 1_000_000L).toInt()
            if (frames > 0) write(ShortArray(frames * channels))
        }

        fun release() {
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "SendspinAudio"
        private const val IDLE_SLEEP_MS = 10L
        /** Meer dan zoveel achter: chunk overslaan in plaats van langzaam inhalen. */
        private const val HARD_DROP_MICROS = 80_000L
        /** Zo ver vooruit mogen chunks opgehaald worden; kleine voorsprong lost de corrector op. */
        private const val EARLY_MICROS = 30_000L
        /** Meer dan zoveel te vroeg: eerst stilte schrijven. */
        private const val SILENCE_GAP_MICROS = 15_000L
        /** Geschatte tijd tussen eerste write en hoorbaar geluid als er nog geen timestamp is. */
        private const val START_LATENCY_MICROS = 40_000L
    }
}
