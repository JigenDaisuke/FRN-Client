package net.voipitalia.frnlite.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming resampler — a faithful port of the desktop client's StreamResampler
 * (frnclient/audio.py): linear interpolation with an anti-alias FIR low-pass on
 * decimation. Used to convert the microphone's native rate down to 8 kHz, since
 * many phones (notably Xiaomi) return silence from an 8 kHz AudioRecord.
 */
class Resampler(private val fin: Double, private val fout: Double) {
    private val step = fin / fout
    private var pos = 0.0
    private var last = 0f
    private val taps: FloatArray?
    private var hist: FloatArray?

    init {
        if (fout < fin) {
            taps = lowpassTaps(0.45 * fout / fin, 63)
            hist = FloatArray(taps.size - 1)
        } else {
            taps = null
            hist = null
        }
    }

    fun process(x: FloatArray): FloatArray {
        if (fin == fout) return x
        var xin = x
        val t = taps
        if (t != null) {
            val h = hist!!
            val ext = FloatArray(h.size + xin.size)
            System.arraycopy(h, 0, ext, 0, h.size)
            System.arraycopy(xin, 0, ext, h.size, xin.size)
            hist = ext.copyOfRange(ext.size - (t.size - 1), ext.size)
            xin = convolveValid(ext, t)
        }
        val buf = FloatArray(xin.size + 1)
        buf[0] = last
        System.arraycopy(xin, 0, buf, 1, xin.size)
        val end = buf.size - 1
        if (end <= 0) return FloatArray(0)
        val npts = floor((end - pos) / step).toInt() + 1
        if (npts <= 0) {
            pos -= end
            last = buf[buf.size - 1]
            return FloatArray(0)
        }
        val out = FloatArray(npts)
        for (i in 0 until npts) {
            val p = pos + step * i
            val i0 = floor(p).toInt()
            val frac = (p - i0).toFloat()
            val a = if (i0 in buf.indices) buf[i0] else buf[buf.size - 1]
            val b = if (i0 + 1 in buf.indices) buf[i0 + 1] else buf[buf.size - 1]
            out[i] = a + (b - a) * frac
        }
        pos = pos + step * npts - end
        last = buf[buf.size - 1]
        return out
    }

    private fun convolveValid(x: FloatArray, kernel: FloatArray): FloatArray {
        val n = x.size - kernel.size + 1
        if (n <= 0) return FloatArray(0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (k in kernel.indices) acc += x[i + k] * kernel[k]
            out[i] = acc
        }
        return out
    }

    private fun lowpassTaps(cutoff: Double, ntaps: Int): FloatArray {
        val h = DoubleArray(ntaps)
        var sum = 0.0
        for (i in 0 until ntaps) {
            val n = i - (ntaps - 1) / 2.0
            val s = if (n == 0.0) 1.0 else sin(2 * PI * cutoff * n) / (2 * PI * cutoff * n)
            val w = 0.54 - 0.46 * cos(2 * PI * i / (ntaps - 1))
            h[i] = s * w
            sum += h[i]
        }
        val out = FloatArray(ntaps)
        for (i in 0 until ntaps) out[i] = (h[i] / sum).toFloat()
        return out
    }
}
