package com.hem.hybridphotoassist

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Makes a photo take on the colours / brightness of a reference photo. Plain statistics, no AI:
 *
 * - [Mode.LOOK]: matches the average and spread of brightness and colour (Reinhard-style transfer in
 *   YCbCr). Shifts the overall colour cast and brightness towards the reference.
 * - [Mode.VIBE]: [Mode.LOOK] plus the full brightness curve (histogram match), so contrast, how dark the
 *   shadows are and how bright the highlights are follow the reference too.
 *
 * It does not copy objects, lighting direction or style details. The statistics are measured on small
 * copies ([plan]); the change is then applied pixel by pixel to the full photo ([Transfer.apply]) so a
 * 4096 px photo doesn't need extra full-size buffers. Pure Kotlin, unit-tested.
 */
object LookMatch {
    enum class Mode { LOOK, VIBE }

    /** Long side of the copies the statistics are measured on. */
    const val SAMPLE_SIDE = 512
    /** Most a channel's spread may be stretched or squeezed, so a flat reference can't wreck a photo. */
    private const val MAX_RATIO = 2.5f
    private const val MIN_RATIO = 0.4f

    class Transfer internal constructor(
        private val yLut: FloatArray,
        private val mbS: Float, private val mbR: Float, private val rb: Float,
        private val mrS: Float, private val mrR: Float, private val rr: Float
    ) {
        /** New image: [src] moved towards the reference by [strength] (0..1), only under [mask] if given. */
        fun apply(src: Pixels, strength: Float, mask: Mask? = null): Pixels {
            require(mask == null || (mask.width == src.width && mask.height == src.height)) { "mask size mismatch" }
            val k = strength.coerceIn(0f, 1f)
            val out = IntArray(src.data.size)
            for (i in src.data.indices) {
                val c = src.data[i]
                val a = (mask?.get(i) ?: 255) / 255f * k
                if (a <= 0f) { out[i] = c; continue }
                val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                val cb = -0.168736f * r - 0.331264f * g + 0.5f * b
                val cr = 0.5f * r - 0.418688f * g - 0.081312f * b
                val y2 = y + (yLut[y.roundToInt().coerceIn(0, 255)] - y) * a
                val cb2 = cb + (((cb - mbS) * rb + mbR) - cb) * a
                val cr2 = cr + (((cr - mrS) * rr + mrR) - cr) * a
                out[i] = Pixels.argb(
                    (c ushr 24) and 0xFF,
                    (y2 + 1.402f * cr2).roundToInt(),
                    (y2 - 0.344136f * cb2 - 0.714136f * cr2).roundToInt(),
                    (y2 + 1.772f * cb2).roundToInt()
                )
            }
            return Pixels(src.width, src.height, out)
        }
    }

    /** Measures [src] (under [mask] if given) and [ref]. Big images are measured on small copies. */
    fun plan(src: Pixels, mask: Mask?, ref: Pixels, mode: Mode): Transfer {
        require(mask == null || (mask.width == src.width && mask.height == src.height)) { "mask size mismatch" }
        val s = sample(src); val m = mask?.let { sampleMask(it, s.width, s.height) }; val r = sample(ref)
        val (sY, sCb, sCr) = ycc(s); val (rY, rCb, rCr) = ycc(r)
        val w = m?.let { mm -> FloatArray(mm.data.size) { mm[it] / 255f } }
        val (myS, sdyS) = stats(sY, w); val (mbS, sdbS) = stats(sCb, w); val (mrS, sdrS) = stats(sCr, w)
        val (myR, sdyR) = stats(rY, null); val (mbR, sdbR) = stats(rCb, null); val (mrR, sdrR) = stats(rCr, null)
        val lut = if (mode == Mode.VIBE) histogramLut(sY, w, rY)
                  else FloatArray(256) { (it - myS) * ratio(sdyR, sdyS) + myR }
        return Transfer(lut, mbS, mbR, ratio(sdbR, sdbS), mrS, mrR, ratio(sdrR, sdrS))
    }

    /** One-step convenience (used by tests and small images). */
    fun apply(src: Pixels, ref: Pixels, mode: Mode, strength: Float, mask: Mask? = null): Pixels =
        plan(src, mask, ref, mode).apply(src, strength, mask)

    private fun sample(p: Pixels): Pixels {
        val long = max(p.width, p.height)
        if (long <= SAMPLE_SIDE) return p
        val sc = SAMPLE_SIDE.toFloat() / long
        return p.resized(max(1, (p.width * sc).roundToInt()), max(1, (p.height * sc).roundToInt()))
    }

    private fun sampleMask(m: Mask, w: Int, h: Int) = if (m.width == w && m.height == h) m else m.resized(w, h)

    private fun ycc(p: Pixels): Triple<FloatArray, FloatArray, FloatArray> {
        val y = FloatArray(p.data.size); val cb = FloatArray(p.data.size); val cr = FloatArray(p.data.size)
        for (i in p.data.indices) {
            val c = p.data[i]
            val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
            y[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
            cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
        }
        return Triple(y, cb, cr)
    }

    private fun ratio(refSd: Float, srcSd: Float): Float =
        if (srcSd < 1e-3f) 1f else (refSd / srcSd).coerceIn(MIN_RATIO, MAX_RATIO)

    /** Weighted mean and standard deviation. */
    internal fun stats(v: FloatArray, w: FloatArray?): Pair<Float, Float> {
        var sw = 0.0; var s = 0.0
        for (i in v.indices) { val wi = w?.get(i)?.toDouble() ?: 1.0; sw += wi; s += wi * v[i] }
        if (sw <= 0.0) return Pair(0f, 0f)
        val mean = s / sw
        var sq = 0.0
        for (i in v.indices) { val wi = w?.get(i)?.toDouble() ?: 1.0; val d = v[i] - mean; sq += wi * d * d }
        return Pair(mean.toFloat(), sqrt(sq / sw).toFloat())
    }

    /** Brightness histogram matching: each source level goes to the reference level at the same rank. */
    private fun histogramLut(src: FloatArray, w: FloatArray?, ref: FloatArray): FloatArray {
        val hs = DoubleArray(256); val hr = DoubleArray(256)
        for (i in src.indices) hs[src[i].roundToInt().coerceIn(0, 255)] += w?.get(i)?.toDouble() ?: 1.0
        for (v in ref) hr[v.roundToInt().coerceIn(0, 255)] += 1.0
        val cs = cdf(hs); val cr = cdf(hr)
        val lut = FloatArray(256)
        var j = 0
        for (i in 0 until 256) {
            while (j < 255 && cr[j] < cs[i] - 1e-9) j++
            lut[i] = j.toFloat()
        }
        return lut
    }

    private fun cdf(h: DoubleArray): DoubleArray {
        val total = h.sum().takeIf { it > 0 } ?: 1.0
        val out = DoubleArray(256); var acc = 0.0
        for (i in 0 until 256) { acc += h[i]; out[i] = acc / total }
        return out
    }
}
