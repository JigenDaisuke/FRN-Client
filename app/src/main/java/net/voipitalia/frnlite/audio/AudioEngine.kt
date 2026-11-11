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
 * The microphone is opened at a rate the device actually supports (8 kHz if it
 * works, otherwise 48/44.1/16 kHz) and resampled down to 8 kHz — many phones
 * return silence from a direct 8 kHz AudioRecord. Capture delivers fixed
 * 1600-sample (200 ms) packets to [onPacket]; playback queues decoded packets.
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
    private var captureRate = RATE
    private var resampler: Resampler? = null
    @Volatile private var running = false
    private var capThread: Thread? = null
    private var playThread: Thread? = null

    private val playQ = ConcurrentLinkedQueue<ShortArray>()
    private val capAcc = ArrayList<Short>(PACKET * 2)

    fun start(): Boolean {
        stop()
        error = null
        running = true
        val (rec, rate) = openRecord()
        if (rec == null) { error = "Microfono non disponibile"; running = false; return false }
        record = rec
        captureRate = rate
        resampler = if (rate != RATE) Resampler(rate.toDouble(), RATE.toDouble()) else null
        try {
            val outBuf = maxOf(AudioTrack.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), PACKET * 4)
            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                outBuf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            rec.startRecording()
            track!!.play()
        } catch (e: Exception) {
            error = "Audio non disponibile: ${e.message}"
            return false
        }
        capThread = thread(name = "frn-cap", isDaemon = true) { captureLoop() }
        playThread = thread(name = "frn-play", isDaemon = true) { playLoop() }
        return true
    }

    /** Try to open the mic at 8 kHz, else a common rate we can resample from. */
    private fun openRecord(): Pair<AudioRecord?, Int> {
        for (rate in intArrayOf(RATE, 48000, 44100, 16000)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val buf = maxOf(min, rate / 5 * 2 * 2)   // ~400 ms headroom
            val r = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
            } catch (e: Exception) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r to rate
            runCatching { r?.release() }
        }
        return null to 0
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

    fun play(pcm: ShortArray) {
        playQ.add(pcm)
        while (playQ.size > MAX_QUEUE) playQ.poll()
    }

    fun flushPlayback() = playQ.clear()
    val isPlaying: Boolean get() = playQ.isNotEmpty()

    private fun captureLoop() {
        val r = record ?: return
        val raw = ShortArray(captureRate / 5)            // ~200 ms at the capture rate
        val rs = resampler
        while (running) {
            val n = r.read(raw, 0, raw.size)
            if (n <= 0) continue
            val f = FloatArray(n) { raw[it] / 32768f }
            val f8 = rs?.process(f) ?: f
            var peak = 0.0
            synchronized(capAcc) {
                for (v in f8) {
                    var s = (v * 32768f * txGain).toInt()
                    if (s > 32767) s = 32767 else if (s < -32768) s = -32768
                    capAcc.add(s.toShort())
                    val d = s / 32768.0; peak += d * d
                }
                while (capAcc.size >= PACKET) {
                    val pkt = ShortArray(PACKET) { capAcc[it] }
                    repeat(PACKET) { capAcc.removeAt(0) }
                    onPacket(pkt)
                }
            }
            if (f8.isNotEmpty()) {
                val rms = Math.sqrt(peak / f8.size)
                inLevelDb = if (rms > 1e-5) (20 * Math.log10(rms)).toFloat() else -90f
            }
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
                val d = v / 32768.0; peak += d * d
            }
            val rms = Math.sqrt(peak / out.size)
            outLevelDb = if (rms > 1e-5) (20 * Math.log10(rms)).toFloat() else -90f
            t.write(out, 0, out.size)
        }
    }
}
