package com.hem.hybridphotoassist

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ARGB_8888 pixel buffer. Pure Kotlin (no android.graphics) so the math is unit-testable. */
class Pixels(val width: Int, val height: Int, val data: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "image size must be positive" }
        require(data.size == width * height) { "pixel data size mismatch" }
    }

    operator fun get(x: Int, y: Int) = data[y * width + x]

    fun copy() = Pixels(width, height, data.copyOf())

    fun crop(box: Box): Pixels {
        val b = box.clampTo(width, height)
        require(!b.isEmpty()) { "empty crop" }
        val out = IntArray(b.width * b.height)
        for (y in 0 until b.height) System.arraycopy(data, (b.top + y) * width + b.left, out, y * b.width, b.width)
        return Pixels(b.width, b.height, out)
    }

    /** Copies [src] into this buffer with its top-left at (x, y). */
    fun paste(src: Pixels, x: Int, y: Int) {
        for (sy in 0 until src.height) {
            val dy = y + sy
            if (dy < 0 || dy >= height) continue
            val sx0 = max(0, -x); val sx1 = min(src.width, width - x)
            if (sx1 <= sx0) continue
            System.arraycopy(src.data, sy * src.width + sx0, data, dy * width + x + sx0, sx1 - sx0)
        }
    }

    /** Bilinear resize. */
    fun resized(newW: Int, newH: Int): Pixels {
        if (newW == width && newH == height) return copy()
        val out = IntArray(newW * newH)
        val sx = width.toFloat() / newW
        val sy = height.toFloat() / newH
        for (y in 0 until newH) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(y0 + 1, height - 1); val dy = fy - y0
            for (x in 0 until newW) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(x0 + 1, width - 1); val dx = fx - x0
                out[y * newW + x] = bilerp(get(x0, y0), get(x1, y0), get(x0, y1), get(x1, y1), dx, dy)
            }
        }
        return Pixels(newW, newH, out)
    }

    /** Box blur, [passes] times (3 passes ≈ Gaussian). */
    fun blurred(radius: Int, passes: Int = 3): Pixels {
        if (radius <= 0) return copy()
        val n = data.size
        var a = IntArray(n) { (data[it] ushr 24) and 0xFF }
        var r = IntArray(n) { (data[it] shr 16) and 0xFF }
        var g = IntArray(n) { (data[it] shr 8) and 0xFF }
        var b = IntArray(n) { data[it] and 0xFF }
        repeat(passes) {
            a = Mask.boxBlurVertical(Mask.boxBlurHorizontal(a, width, height, radius), width, height, radius)
            r = Mask.boxBlurVertical(Mask.boxBlurHorizontal(r, width, height, radius), width, height, radius)
            g = Mask.boxBlurVertical(Mask.boxBlurHorizontal(g, width, height, radius), width, height, radius)
            b = Mask.boxBlurVertical(Mask.boxBlurHorizontal(b, width, height, radius), width, height, radius)
        }
        return Pixels(width, height, IntArray(n) { argb(a[it], r[it], g[it], b[it]) })
    }

    /**
     * Brightens (percent > 0, towards white) or darkens (percent < 0, towards black) the pixels under
     * [mask] (null = whole image), blended by the mask's softness. Returns a new buffer.
     */
    fun brightness(percent: Int, mask: Mask?): Pixels {
        require(mask == null || (mask.width == width && mask.height == height)) { "mask size mismatch" }
        val p = percent.coerceIn(-100, 100) / 100f
        val out = IntArray(data.size)
        for (i in data.indices) {
            val c = data[i]
            val a = mask?.get(i) ?: 255
            if (a == 0 || p == 0f) { out[i] = c; continue }
            val k = p * a / 255f
            fun ch(v: Int) = if (k > 0) (v + (255 - v) * k).roundToInt() else (v * (1 + k)).roundToInt()
            out[i] = argb((c ushr 24) and 0xFF, ch((c shr 16) and 0xFF), ch((c shr 8) and 0xFF), ch(c and 0xFF))
        }
        return Pixels(width, height, out)
    }

    companion object {
        /**
         * Draws [top] onto a copy of [base] with its top-left at (x, y), using [alpha] (same size as
         * [top]) as coverage. Parts that fall outside [base] are clipped. Returns the new buffer.
         */
        fun overlay(base: Pixels, top: Pixels, alpha: Mask, x: Int, y: Int): Pixels {
            require(alpha.width == top.width && alpha.height == top.height) { "overlay mask size mismatch" }
            val out = base.copy()
            for (ty in 0 until top.height) {
                val by = y + ty
                if (by < 0 || by >= base.height) continue
                for (tx in 0 until top.width) {
                    val bx = x + tx
                    if (bx < 0 || bx >= base.width) continue
                    val a = alpha[ty * top.width + tx]
                    if (a == 0) continue
                    val bi = by * base.width + bx
                    out.data[bi] = lerp(base.data[bi], top.data[ty * top.width + tx], a)
                }
            }
            return out
        }

        fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        fun solid(w: Int, h: Int, color: Int) = Pixels(w, h, IntArray(w * h) { color or (0xFF shl 24) })

        /** Per-pixel blend: alpha 255 takes [top], 0 keeps [bottom]. */
        fun lerp(bottom: Int, top: Int, alpha: Int): Int {
            if (alpha >= 255) return top or (0xFF shl 24)
            if (alpha <= 0) return bottom
            val ia = 255 - alpha
            val r = (((top shr 16) and 0xFF) * alpha + ((bottom shr 16) and 0xFF) * ia + 127) / 255
            val g = (((top shr 8) and 0xFF) * alpha + ((bottom shr 8) and 0xFF) * ia + 127) / 255
            val b = ((top and 0xFF) * alpha + (bottom and 0xFF) * ia + 127) / 255
            return argb(255, r, g, b)
        }

        private fun bilerp(c00: Int, c10: Int, c01: Int, c11: Int, dx: Float, dy: Float): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            fun mix(s: Int): Int {
                val t = ch(c00, s) * (1 - dx) + ch(c10, s) * dx
                val bt = ch(c01, s) * (1 - dx) + ch(c11, s) * dx
                return (t * (1 - dy) + bt * dy).roundToInt()
            }
            return argb(mix(24), mix(16), mix(8), mix(0))
        }

        /** Writes top over bottom (same size) through [mask]; returns a new buffer. */
        fun composite(bottom: Pixels, top: Pixels, mask: Mask): Pixels {
            require(bottom.width == top.width && bottom.height == top.height) { "composite size mismatch" }
            require(mask.width == bottom.width && mask.height == bottom.height) { "mask size mismatch" }
            val out = IntArray(bottom.data.size)
            for (i in out.indices) out[i] = lerp(bottom.data[i], top.data[i], mask[i])
            return Pixels(bottom.width, bottom.height, out)
        }

        /**
         * Scales [src] to cover a w×h frame (centre crop), like a wallpaper.
         */
        fun coverFit(src: Pixels, w: Int, h: Int): Pixels {
            val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
            val sw = max(w, (src.width * scale).roundToInt())
            val sh = max(h, (src.height * scale).roundToInt())
            val scaled = src.resized(sw, sh)
            val ox = (sw - w) / 2; val oy = (sh - h) / 2
            return scaled.crop(Box(ox, oy, ox + w, oy + h))
        }
    }
}
