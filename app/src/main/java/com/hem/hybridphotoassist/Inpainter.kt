package com.hem.hybridphotoassist

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Anything that fills masked pixels: the real LaMa model, or a fake in tests. */
interface FillModel {
    /** Square input size the model expects (512 for LaMa). */
    val size: Int

    /**
     * [rgb]: size*size*3 floats in 0..1 (RGB, row-major). [mask]: size*size floats, 1 = fill, 0 = keep.
     * Returns size*size*3 RGB floats (any scale; callers normalise with [normalizeOutput]).
     */
    fun fill(rgb: FloatArray, mask: FloatArray): FloatArray
}

/**
 * Erase-and-fill around a mask: crops the area plus surrounding context, runs the model at its
 * fixed size, scales the result back and blends it in with a soft edge.
 */
class Inpainter(private val model: FillModel) {

    data class Result(val image: Pixels, val changed: Box)

    fun inpaint(image: Pixels, selection: Mask): Result? {
        require(selection.width == image.width && selection.height == image.height) { "mask/image size mismatch" }
        val grow = growRadius(image.width, image.height)
        val area = selection.dilated(grow)
        val bounds = area.bounds() ?: return null
        val crop = planCrop(image.width, image.height, bounds)

        val cropImg = image.crop(crop)
        val cropMask = area.crop(crop)
        val s = model.size
        val inImg = cropImg.resized(s, s)
        val inMask = cropMask.resized(s, s)

        val rgb = FloatArray(s * s * 3)
        val m = FloatArray(s * s)
        for (i in 0 until s * s) {
            val c = inImg.data[i]
            rgb[i * 3] = ((c shr 16) and 0xFF) / 255f
            rgb[i * 3 + 1] = ((c shr 8) and 0xFF) / 255f
            rgb[i * 3 + 2] = (c and 0xFF) / 255f
            m[i] = if (inMask[i] >= 64) 1f else 0f
        }
        val raw = model.fill(rgb, m)
        require(raw.size == s * s * 3) { "model output size ${raw.size}, expected ${s * s * 3}" }
        val out = normalizeOutput(raw)
        val filled = Pixels(s, s, IntArray(s * s) {
            Pixels.argb(255, (out[it * 3] * 255f).toInt(), (out[it * 3 + 1] * 255f).toInt(), (out[it * 3 + 2] * 255f).toInt())
        }).resized(cropImg.width, cropImg.height)

        val edge = cropMask.feathered(max(1, grow / 2))
        val blended = Pixels.composite(cropImg, filled, edge)
        val result = image.copy()
        result.paste(blended, crop.left, crop.top)
        return Result(result, crop)
    }

    companion object {
        /** How far the fill reaches past the selection so edges/shadows of the object go too. */
        fun growRadius(w: Int, h: Int): Int = max(4, ceil(max(w, h) / 250.0).toInt())

        /**
         * Picks the crop: about twice the object's size for context, at least 256 px, centred on it,
         * kept inside the image.
         */
        fun planCrop(w: Int, h: Int, b: Box): Box {
            val want = max(256, max(b.width, b.height) * 2 + 32)
            val cw = min(want, w); val ch = min(want, h)
            val cx = (b.left + b.right) / 2; val cy = (b.top + b.bottom) / 2
            var l = cx - cw / 2; var t = cy - ch / 2
            l = l.coerceIn(0, w - cw); t = t.coerceIn(0, h - ch)
            var box = Box(l, t, l + cw, t + ch)
            // Never cut through the selection itself.
            box = box.union(b).clampTo(w, h)
            return box
        }

        /** Model outputs may be 0..1, 0..255 or -1..1; bring them to 0..1. */
        fun normalizeOutput(raw: FloatArray): FloatArray {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (v in raw) { if (v < lo) lo = v; if (v > hi) hi = v }
            return when {
                hi > 1.5f -> FloatArray(raw.size) { (raw[it] / 255f).coerceIn(0f, 1f) }
                lo < -0.05f -> FloatArray(raw.size) { ((raw[it] + 1f) / 2f).coerceIn(0f, 1f) }
                else -> FloatArray(raw.size) { raw[it].coerceIn(0f, 1f) }
            }
        }
    }
}
