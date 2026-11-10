package net.voipitalia.frnlite.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Full-duplex 8 kHz mono audio for FRN.
 *
 * Capture delivers fixed 1600-sample (200 ms) packets to [onPacket].
 * Playback queues decoded 1600-sample packets with a short jitter buffer.
 */
class AudioEngine(private val onPacket: (ShortArray) -> Unit) {

    companion object {
        const val RATE = 8000
        const val PACKET = 1600       // 200 ms
        private const val MAX_QUEUE = 6
    }

    @Volatile var rxGain = 1.0f
    @Volatile var txGain = 1.0f
    @Volatile var muted = false
    @Volatile var inLevelDb = -90f; private set
    @Volatile var outLevelDb = -90f; private set
    var error: String? = null; private set

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var capThread: Thread? = null
    private var playThread: Thread? = null

    private val playQ = ConcurrentLinkedQueue<ShortArray>()
    private val capAcc = ArrayList<Short>(PACKET * 2)

    fun start(): Boolean {
        stop()
        error = null
        running = true
        try {
            val inBuf = maxOf(AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), PACKET * 2)
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inBuf)
            val outBuf = maxOf(AudioTrack.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), PACKET * 4)
            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                outBuf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            record!!.startRecording()
            track!!.play()
        } catch (e: Exception) {
            error = "Audio non disponibile: ${e.message}"
            return false
        }
        capThread = thread(name = "frn-cap", isDaemon = true) { captureLoop() }
        playThread = thread(name = "frn-play", isDaemon = true) { playLoop() }
        return true
    }

    fun stop() {
        running = false
        capThread?.join(500); playThread?.join(500)
        capThread = null; playThread = null
        runCatching { record?.stop(); record?.release() }
        runCatching { track?.stop(); track?.release() }
        record = null; track = null
        playQ.clear(); synchronized(capAcc) { capAcc.clear() }
    }

    /** Queue a decoded 8 kHz packet coming from the network. */
    fun play(pcm: ShortArray) {
        playQ.add(pcm)
        while (playQ.size > MAX_QUEUE) playQ.poll()
    }

    fun flushPlayback() = playQ.clear()

    val isPlaying: Boolean get() = playQ.isNotEmpty()

    private fun captureLoop() {
        val r = record ?: return
        val buf = ShortArray(PACKET)
        while (running) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) continue
            var peak = 0.0
            synchronized(capAcc) {
                for (i in 0 until n) {
                    var v = (buf[i] * txGain).toInt()
                    if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                    capAcc.add(v.toShort())
                    peak += (v.toDouble() / 32768.0) * (v.toDouble() / 32768.0)
                }
                while (capAcc.size >= PACKET) {
                    val pkt = ShortArray(PACKET) { capAcc[it] }
                    repeat(PACKET) { capAcc.removeAt(0) }
                    onPacket(pkt)
                }
            }
            val rms = Math.sqrt(peak / n)
            inLevelDb = if (rms > 1e-5) (20 * Math.log10(rms)).toFloat() else -90f
        }
    }

    /** Return (and clear) audio captured since the last full packet, zero padded. */
    fun takePartial(): ShortArray {
        synchronized(capAcc) {
            val out = ShortArray(PACKET)
            for (i in 0 until minOf(capAcc.size, PACKET)) out[i] = capAcc[i]
            capAcc.clear()
            return out
        }
    }

    private fun playLoop() {
        val t = track ?: return
        val silence = ShortArray(160)
        while (running) {
            val pcm = playQ.poll()
            if (pcm == null) { t.write(silence, 0, silence.size); continue }
            val out = ShortArray(pcm.size)
            var peak = 0.0
            for (i in pcm.indices) {
                var v = if (muted) 0 else (pcm[i] * rxGain).toInt()
                if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                out[i] = v.toShort()
                peak += (v.toDouble() / 32768.0) * (v.toDouble() / 32768.0)
            }
            val rms = Math.sqrt(peak / out.size)
            outLevelDb = if (rms > 1e-5) (20 * Math.log10(rms)).toFloat() else -90f
            t.write(out, 0, out.size)
        }
    }
}
