package com.hem.hybridphotoassist

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke

/**
 * Tap-to-select using Google's MediaPipe Interactive Segmenter (API as in Google's official sample).
 * Works on a downscaled copy for speed; the caller scales the mask back to full size.
 */
class Selector(context: Context) : AutoCloseable {
    private val segmenter: InteractiveSegmenter = InteractiveSegmenter.createFromOptions(
        context,
        InteractiveSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .build()
    )
    private var imageW = 0
    private var imageH = 0

    /** Call once per photo (with the downscaled working copy). */
    fun setImage(bitmap: Bitmap) {
        imageW = bitmap.width
        imageH = bitmap.height
        segmenter.setImage(BitmapImageBuilder(bitmap).build())
    }

    /**
     * Returns a mask (in the size of the image passed to [setImage]) for the object under the
     * normalised points (0..1).
     */
    fun select(points: List<Pair<Float, Float>>): Mask {
        check(imageW > 0) { "no image set" }
        val stroke = Stroke.builder()
            .setBrushMode(Stroke.BrushMode.POSITIVE)
            .setPoints(points.map { NormalizedKeypoint.create(it.first.coerceIn(0f, 1f), it.second.coerceIn(0f, 1f)) })
            .setCompleted(true)
            .build()
        val mp = segmenter.segment(listOf(stroke))
        val w = mp.width; val h = mp.height
        val buf = ByteBufferExtractor.extract(mp).asFloatBuffer()
        buf.rewind()
        val conf = FloatArray(w * h)
        buf.get(conf)
        val mask = Mask.fromConfidence(w, h, conf, 0.5f)
        return if (w == imageW && h == imageH) mask else mask.resized(imageW, imageH).binarized()
    }

    override fun close() = segmenter.close()

    companion object {
        const val MODEL = "interactive_segmentation.task"
        const val WORK_SIZE = 1024
    }
}
