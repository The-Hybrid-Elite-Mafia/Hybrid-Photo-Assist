package com.hem.hybridphotoassist

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Axis-aligned box, right/bottom exclusive. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun isEmpty() = width <= 0 || height <= 0
    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun clampTo(w: Int, h: Int) = Box(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
}

/**
 * 8-bit coverage mask (0 = outside, 255 = fully inside). Pure Kotlin so it can be unit-tested on the JVM.
 */
class Mask(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "mask size must be positive" }
        require(data.size == width * height) { "mask data size mismatch" }
    }

    operator fun get(i: Int): Int = data[i].toInt() and 0xFF
    operator fun get(x: Int, y: Int): Int = get(y * width + x)
    operator fun set(i: Int, v: Int) { data[i] = v.coerceIn(0, 255).toByte() }

    fun copy() = Mask(width, height, data.copyOf())

    fun isEmpty(): Boolean {
        for (b in data) if (b.toInt() != 0) return false
        return true
    }

    fun clear() = data.fill(0)

    /** Bounding box of pixels with value >= [threshold], or null when there are none. */
    fun bounds(threshold: Int = 1): Box? {
        var l = width; var t = height; var r = -1; var b = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (get(row + x) >= threshold) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        return if (r < 0) null else Box(l, t, r + 1, b + 1)
    }

    fun inverted(): Mask {
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (255 - get(i)).toByte()
        return Mask(width, height, out)
    }

    /** Hard 0/255 mask from a threshold. */
    fun binarized(threshold: Int = 128): Mask {
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = if (get(i) >= threshold) 255.toByte() else 0
        return Mask(width, height, out)
    }

    /** Grows the selected area by [radius] pixels (square neighbourhood, O(n)). */
    fun dilated(radius: Int): Mask {
        if (radius <= 0) return binarized(1)
        val w = width; val h = height
        val on = IntArray(w * h) { if (get(it) > 0) 1 else 0 }
        val horiz = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var count = 0
            // window [x - radius, x + radius]
            for (x in 0 until min(radius, w)) count += on[row + x]
            for (x in 0 until w) {
                val add = x + radius
                if (add < w) count += on[row + add]
                val rem = x - radius - 1
                if (rem >= 0) count -= on[row + rem]
                horiz[row + x] = if (count > 0) 1 else 0
            }
        }
        val out = ByteArray(w * h)
        for (x in 0 until w) {
            var count = 0
            for (y in 0 until min(radius, h)) count += horiz[y * w + x]
            for (y in 0 until h) {
                val add = y + radius
                if (add < h) count += horiz[add * w + x]
                val rem = y - radius - 1
                if (rem >= 0) count -= horiz[rem * w + x]
                out[y * w + x] = if (count > 0) 255.toByte() else 0
            }
        }
        return Mask(w, h, out)
    }

    /** Softens edges with two separable box-blur passes (close to a Gaussian). */
    fun feathered(radius: Int): Mask {
        if (radius <= 0) return copy()
        var cur = IntArray(data.size) { get(it) }
        repeat(2) {
            cur = boxBlurVertical(boxBlurHorizontal(cur, width, height, radius), width, height, radius)
        }
        val out = ByteArray(cur.size)
        for (i in cur.indices) out[i] = cur[i].coerceIn(0, 255).toByte()
        return Mask(width, height, out)
    }

    /** Bilinear resize. */
    fun resized(newW: Int, newH: Int): Mask {
        if (newW == width && newH == height) return copy()
        val out = ByteArray(newW * newH)
        val sx = width.toFloat() / newW
        val sy = height.toFloat() / newH
        for (y in 0 until newH) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(y0 + 1, height - 1); val dy = fy - y0
            for (x in 0 until newW) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(x0 + 1, width - 1); val dx = fx - x0
                val top = get(x0, y0) * (1 - dx) + get(x1, y0) * dx
                val bot = get(x0, y1) * (1 - dx) + get(x1, y1) * dx
                out[y * newW + x] = (top * (1 - dy) + bot * dy).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return Mask(newW, newH, out)
    }

    fun crop(box: Box): Mask {
        val b = box.clampTo(width, height)
        require(!b.isEmpty()) { "empty crop" }
        val out = ByteArray(b.width * b.height)
        for (y in 0 until b.height) {
            System.arraycopy(data, (b.top + y) * width + b.left, out, y * b.width, b.width)
        }
        return Mask(b.width, b.height, out)
    }

    /** Paints (add = true) or erases a filled circle. */
    fun paintCircle(cx: Float, cy: Float, radius: Float, add: Boolean) {
        val r2 = radius * radius
        val x0 = max(0, (cx - radius).toInt()); val x1 = min(width - 1, (cx + radius).toInt() + 1)
        val y0 = max(0, (cy - radius).toInt()); val y1 = min(height - 1, (cy + radius).toInt() + 1)
        val v: Byte = if (add) 255.toByte() else 0
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                if (dx * dx + dy * dy <= r2) data[y * width + x] = v
            }
        }
    }

    /** Paints a stroke segment as a chain of circles. */
    fun paintLine(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, add: Boolean) {
        val dx = x1 - x0; val dy = y1 - y0
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val steps = max(1, (dist / max(1f, radius * 0.4f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            paintCircle(x0 + dx * t, y0 + dy * t, radius, add)
        }
    }

    fun coverage(): Int {
        var n = 0
        for (b in data) if (b.toInt() != 0) n++
        return n
    }

    companion object {
        /** Builds a mask from 0..1 confidences. */
        fun fromConfidence(w: Int, h: Int, conf: FloatArray, threshold: Float = 0.5f): Mask {
            require(conf.size == w * h) { "confidence size mismatch" }
            val out = ByteArray(w * h)
            for (i in conf.indices) out[i] = if (conf[i] >= threshold) 255.toByte() else 0
            return Mask(w, h, out)
        }

        internal fun boxBlurHorizontal(src: IntArray, w: Int, h: Int, r: Int): IntArray {
            val out = IntArray(src.size)
            val div = 2 * r + 1
            for (y in 0 until h) {
                val row = y * w
                var sum = 0
                for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    out[row + x] = sum / div
                    sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
                }
            }
            return out
        }

        internal fun boxBlurVertical(src: IntArray, w: Int, h: Int, r: Int): IntArray {
            val out = IntArray(src.size)
            val div = 2 * r + 1
            for (x in 0 until w) {
                var sum = 0
                for (k in -r..r) sum += src[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = sum / div
                    sum += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
                }
            }
            return out
        }
    }
}
