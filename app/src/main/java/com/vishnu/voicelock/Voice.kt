package com.vishnu.voicelock

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.Locale
import kotlin.math.*

/** Record + MFCC features + DTW compare. Word AND voice are matched together (text-dependent). */
object Voice {
    const val SR = 16000
    private const val FL = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val NMEL = 26
    private const val NC = 12
    /** Chhota = zyada strict (1.0-1.6). Galat reject ho to badhao, dusra khul jaye to ghatao. */
    const val STRICTNESS = 1.25

    @Suppress("MissingPermission")
    fun record(seconds: Double = 2.5): FloatArray? {
        val n = (SR * seconds).toInt()
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192))
        } catch (e: Exception) { return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val buf = ShortArray(n)
        var read = 0
        rec.startRecording()
        while (read < n) {
            val r = rec.read(buf, read, n - read)
            if (r <= 0) break
            read += r
        }
        rec.stop(); rec.release()
        return FloatArray(read) { buf[it] / 32768f }
    }

    private val mel: Array<DoubleArray> by lazy {
        fun h2m(f: Double) = 2595 * log10(1 + f / 700.0)
        fun m2h(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
        val lo = h2m(100.0); val hi = h2m(7600.0)
        val pts = DoubleArray(NMEL + 2) { m2h(lo + (hi - lo) * it / (NMEL + 1)) }
        val bin = IntArray(NMEL + 2) { floor((NFFT + 1) * pts[it] / SR).toInt() }
        Array(NMEL) { m ->
            DoubleArray(NFFT / 2 + 1).also { f ->
                for (k in bin[m] until bin[m + 1]) f[k] = (k - bin[m]).toDouble() / max(1, bin[m + 1] - bin[m])
                for (k in bin[m + 1] until bin[m + 2]) f[k] = (bin[m + 2] - k).toDouble() / max(1, bin[m + 2] - bin[m + 1])
            }
        }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val t = re[i]; re[i] = re[j]; re[j] = t
                val u = im[i]; im[i] = im[j]; im[j] = u
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val nc = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nc
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun features(x: FloatArray): Array<FloatArray>? {
        if (x.size < FL * 10) return null
        val nf = (x.size - FL) / HOP + 1
        val en = DoubleArray(nf) { f ->
            var s = 0.0
            for (i in 0 until FL) { val v = x[f * HOP + i].toDouble(); s += v * v }
            s / FL
        }
        val peak = en.max()
        if (peak < 1e-5) return null                     // bahut dheemi awaaz
        val th = peak * 0.03
        val a = en.indexOfFirst { it > th }
        val b = en.indexOfLast { it > th }
        if (b - a < 20) return null
        val out = ArrayList<FloatArray>()
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        for (f in a..b) {
            val s = f * HOP
            re.fill(0.0); im.fill(0.0)
            for (i in 0 until FL) {
                val prev = if (i > 0) x[s + i - 1].toDouble() else 0.0
                re[i] = (x[s + i] - 0.97 * prev) * (0.54 - 0.46 * cos(2 * PI * i / (FL - 1)))
            }
            fft(re, im)
            val le = DoubleArray(NMEL) { m ->
                var e = 0.0
                val fb = mel[m]
                for (k in 0..NFFT / 2) if (fb[k] > 0) e += fb[k] * (re[k] * re[k] + im[k] * im[k])
                ln(e + 1e-10)
            }
            out.add(FloatArray(NC) { c ->
                var s2 = 0.0
                for (m in 0 until NMEL) s2 += le[m] * cos(PI * (c + 1) * (m + 0.5) / NMEL)
                s2.toFloat()
            })
        }
        for (c in 0 until NC) {
            var mu = 0.0
            for (v in out) mu += v[c]
            mu /= out.size
            var sd = 0.0
            for (v in out) sd += (v[c] - mu).pow(2)
            sd = sqrt(sd / out.size) + 1e-6
            for (v in out) v[c] = ((v[c] - mu) / sd).toFloat()
        }
        return out.toTypedArray()
    }

    fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): Double {
        val n = a.size; val m = b.size
        val ratio = n.toDouble() / m
        if (ratio < 0.6 || ratio > 1.67) return 1e9      // word ki length alag = alag word
        val big = Double.MAX_VALUE / 4
        var prev = DoubleArray(m + 1) { big }; prev[0] = 0.0
        for (i in 1..n) {
            val cur = DoubleArray(m + 1) { big }
            for (j in 1..m) {
                var d = 0.0
                for (c in 0 until NC) { val t = (a[i - 1][c] - b[j - 1][c]).toDouble(); d += t * t }
                cur[j] = sqrt(d) + min(prev[j - 1], min(prev[j], cur[j - 1]))
            }
            prev = cur
        }
        return prev[m] / (n + m)
    }

    private fun enc(f: Array<FloatArray>) = f.joinToString("|") { r ->
        r.joinToString(",") { "%.3f".format(Locale.US, it) }
    }
    private fun dec(s: String) = s.split("|").map { r ->
        r.split(",").map { it.toFloat() }.toFloatArray()
    }.toTypedArray()

    private fun prefs(c: Context) = c.getSharedPreferences("vl", Context.MODE_PRIVATE)

    fun enrolled(c: Context) = prefs(c).getInt("n", 0) > 0
    fun pinOk(c: Context, p: String) = p.isNotEmpty() && prefs(c).getString("pin", null) == p

    fun save(c: Context, t: List<Array<FloatArray>>, pin: String) {
        var sum = 0.0; var cnt = 0
        for (i in t.indices) for (j in i + 1 until t.size) { sum += dtw(t[i], t[j]); cnt++ }
        val thr = (sum / cnt) * STRICTNESS
        val e = prefs(c).edit().clear().putInt("n", t.size).putFloat("thr", thr.toFloat()).putString("pin", pin)
        t.forEachIndexed { i, f -> e.putString("t$i", enc(f)) }
        e.apply()
    }

    fun verify(c: Context, x: FloatArray): Boolean {
        val f = features(x) ?: return false
        val p = prefs(c)
        val n = p.getInt("n", 0)
        if (n == 0) return false
        val ds = (0 until n).map { dtw(f, dec(p.getString("t$it", "")!!)) }.sorted()
        return ds.take(3).average() <= p.getFloat("thr", 0f)
    }
}
