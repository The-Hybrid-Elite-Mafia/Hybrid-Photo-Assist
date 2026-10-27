package com.hem.hybridphotoassist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the photo, the lime selection overlay and (while moving) the lifted cut-out.
 * One finger = the current tool. Two fingers = zoom/pan the view (or resize/move the cut-out in Move).
 */
class EditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { SELECT, BRUSH_ADD, BRUSH_REMOVE, MOVE, NONE }

    interface Listener {
        fun onTapImage(x: Float, y: Float)
        fun onBrushChanged()
    }

    var listener: Listener? = null
    var tool: Tool = Tool.SELECT
    /** Brush radius in screen pixels. */
    var brushRadiusPx: Float = 40f

    private var image: Bitmap? = null
    private var selection: Mask? = null
    private var overlay: Bitmap? = null
    private var overlayScale = 1f // mask pixels per overlay pixel

    // Floating cut-out while moving: position of its top-left in image coords and its scale.
    private var cutout: Bitmap? = null
    private var cutX = 0f
    private var cutY = 0f
    private var cutScale = 1f

    private val fitMatrix = Matrix()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val drawMatrix = Matrix()
    private val inverse = Matrix()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 120 }
    private val cutoutFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFC0FF3E.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val brushRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.WHITE
    }
    private var ringX = -1f
    private var ringY = -1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (tool == Tool.MOVE && cutout != null) {
                val newScale = (cutScale * d.scaleFactor).coerceIn(0.1f, 8f)
                // Keep the cut-out centred while resizing.
                val c = cutout!!
                val cx = cutX + c.width * cutScale / 2f
                val cy = cutY + c.height * cutScale / 2f
                cutScale = newScale
                cutX = cx - c.width * cutScale / 2f
                cutY = cy - c.height * cutScale / 2f
            } else {
                val old = zoom
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 12f)
                val f = zoom / old
                panX = d.focusX - (d.focusX - panX) * f
                panY = d.focusY - (d.focusY - panY) * f
            }
            updateMatrix()
            return true
        }
    })

    fun setImage(bmp: Bitmap?, resetView: Boolean) {
        image = bmp
        if (resetView) { zoom = 1f; panX = 0f; panY = 0f }
        updateMatrix()
    }

    fun refreshImage() = invalidate()

    // Original photo shown while "Hold: Before" is pressed (may be a smaller copy; drawn at image size).
    private var compare: Bitmap? = null

    fun showCompare(original: Bitmap?) { compare = original; invalidate() }

    fun setSelection(mask: Mask?) {
        selection = mask
        rebuildOverlay()
    }

    fun startCutout(bmp: Bitmap, x: Int, y: Int, scale: Float = 1f) {
        cutout = bmp; cutX = x.toFloat(); cutY = y.toFloat(); cutScale = scale
        invalidate()
    }

    /** Current cut-out placement in image coordinates (left, top, scale), or null. */
    fun cutoutPlacement(): Triple<Float, Float, Float>? = cutout?.let { Triple(cutX, cutY, cutScale) }

    fun endCutout() { cutout = null; invalidate() }

    // ---------------------------------------------------------------- overlay

    private fun rebuildOverlay() {
        val m = selection
        if (m == null) { overlay = null; invalidate(); return }
        val longSide = max(m.width, m.height)
        val ow: Int; val oh: Int
        if (longSide > OVERLAY_MAX) {
            overlayScale = longSide.toFloat() / OVERLAY_MAX
            ow = max(1, (m.width / overlayScale).toInt()); oh = max(1, (m.height / overlayScale).toInt())
        } else { overlayScale = 1f; ow = m.width; oh = m.height }
        val bmp = overlay?.takeIf { it.width == ow && it.height == oh } ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        overlay = bmp
        updateOverlayRegion(0, 0, ow, oh)
    }

    /** Refreshes overlay pixels in overlay coords [l,t,r,b). */
    private fun updateOverlayRegion(l: Int, t: Int, r: Int, b: Int) {
        val m = selection ?: return
        val bmp = overlay ?: return
        val x0 = l.coerceIn(0, bmp.width); val x1 = r.coerceIn(0, bmp.width)
        val y0 = t.coerceIn(0, bmp.height); val y1 = b.coerceIn(0, bmp.height)
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        val lime = 0x00C0FF3E
        for (y in 0 until h) {
            val my = min(m.height - 1, ((y0 + y + 0.5f) * overlayScale).toInt())
            for (x in 0 until w) {
                val mx = min(m.width - 1, ((x0 + x + 0.5f) * overlayScale).toInt())
                val a = m[mx, my]
                px[y * w + x] = if (a > 0) (a shl 24) or lime else 0
            }
        }
        bmp.setPixels(px, 0, w, x0, y0, w, h)
        invalidate()
    }

    // ---------------------------------------------------------------- geometry

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        val bmp = image
        fitMatrix.reset()
        if (bmp != null && width > 0 && height > 0) {
            fitMatrix.setRectToRect(
                RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Matrix.ScaleToFit.CENTER
            )
        }
        if (zoom <= 1.0001f) { panX = 0f; panY = 0f }
        drawMatrix.set(fitMatrix)
        drawMatrix.postScale(zoom, zoom, 0f, 0f)
        drawMatrix.postTranslate(panX, panY)
        drawMatrix.invert(inverse)
        invalidate()
    }

    private fun toImage(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        return p
    }

    /** Screen pixels per image pixel at the current zoom. */
    private fun screenScale(): Float {
        val v = floatArrayOf(0f, 0f, 1f, 0f)
        drawMatrix.mapPoints(v)
        return max(1e-4f, hypot(v[2] - v[0], v[3] - v[1]))
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = image ?: return
        canvas.save()
        canvas.concat(drawMatrix)
        val before = compare
        if (before != null) {
            // Only the original: no overlay or cut-out, so the two are easy to compare.
            canvas.save()
            canvas.scale(bmp.width.toFloat() / before.width, bmp.height.toFloat() / before.height)
            canvas.drawBitmap(before, 0f, 0f, bitmapPaint)
            canvas.restore()
            canvas.restore()
            return
        }
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        overlay?.let { ov ->
            if (cutout == null) {
                canvas.save()
                canvas.scale(overlayScale, overlayScale)
                canvas.drawBitmap(ov, 0f, 0f, overlayPaint)
                canvas.restore()
            }
        }
        cutout?.let { c ->
            canvas.save()
            canvas.translate(cutX, cutY)
            canvas.scale(cutScale, cutScale)
            canvas.drawBitmap(c, 0f, 0f, bitmapPaint)
            cutoutFrame.strokeWidth = 3f / (screenScale() * cutScale)
            canvas.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), cutoutFrame)
            canvas.restore()
        }
        canvas.restore()
        if (ringX >= 0 && (tool == Tool.BRUSH_ADD || tool == Tool.BRUSH_REMOVE)) {
            canvas.drawCircle(ringX, ringY, brushRadiusPx, brushRing)
        }
    }

    // ---------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var multiTouch = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastImgX = 0f
    private var lastImgY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (image == null) return false
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                val p = toImage(e.x, e.y); lastImgX = p[0]; lastImgY = p[1]
                if (tool == Tool.BRUSH_ADD || tool == Tool.BRUSH_REMOVE) { ringX = e.x; ringY = e.y; paintAt(p[0], p[1], p[0], p[1]) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                ringX = -1f
                lastFocusX = focusX(e); lastFocusY = focusY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch || e.pointerCount > 1) {
                    val fx = focusX(e); val fy = focusY(e)
                    val dx = fx - lastFocusX; val dy = fy - lastFocusY
                    if (tool == Tool.MOVE && cutout != null) {
                        val s = screenScale(); cutX += dx / s; cutY += dy / s
                    } else if (zoom > 1f) {
                        panX += dx; panY += dy
                    }
                    lastFocusX = fx; lastFocusY = fy
                    updateMatrix()
                } else {
                    val p = toImage(e.x, e.y)
                    when (tool) {
                        Tool.BRUSH_ADD, Tool.BRUSH_REMOVE -> {
                            ringX = e.x; ringY = e.y
                            paintAt(lastImgX, lastImgY, p[0], p[1])
                        }
                        Tool.MOVE -> if (cutout != null) {
                            cutX += p[0] - lastImgX; cutY += p[1] - lastImgY
                            invalidate()
                        }
                        else -> {}
                    }
                    lastImgX = p[0]; lastImgY = p[1]
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor focus on the remaining fingers.
                val remaining = (0 until e.pointerCount).filter { it != e.actionIndex }
                if (remaining.isNotEmpty()) {
                    lastFocusX = remaining.map { e.getX(it) }.average().toFloat()
                    lastFocusY = remaining.map { e.getY(it) }.average().toFloat()
                    if (remaining.size == 1) {
                        val p = toImage(e.getX(remaining[0]), e.getY(remaining[0]))
                        lastImgX = p[0]; lastImgY = p[1]
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                ringX = -1f
                if (!multiTouch && tool == Tool.SELECT &&
                    abs(e.x - downX) < TAP_SLOP && abs(e.y - downY) < TAP_SLOP) {
                    val p = toImage(e.x, e.y)
                    val bmp = image!!
                    if (p[0] >= 0 && p[1] >= 0 && p[0] < bmp.width && p[1] < bmp.height) listener?.onTapImage(p[0], p[1])
                }
                if (tool == Tool.BRUSH_ADD || tool == Tool.BRUSH_REMOVE) listener?.onBrushChanged()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { ringX = -1f; invalidate() }
        }
        return true
    }

    private fun focusX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun focusY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    private fun paintAt(x0: Float, y0: Float, x1: Float, y1: Float) {
        val m = selection ?: return
        val r = brushRadiusPx / screenScale()
        m.paintLine(x0, y0, x1, y1, r, tool == Tool.BRUSH_ADD)
        val pad = r + 2
        updateOverlayRegion(
            ((min(x0, x1) - pad) / overlayScale).toInt() - 1,
            ((min(y0, y1) - pad) / overlayScale).toInt() - 1,
            ((max(x0, x1) + pad) / overlayScale).toInt() + 2,
            ((max(y0, y1) + pad) / overlayScale).toInt() + 2
        )
    }

    companion object {
        private const val OVERLAY_MAX = 1600
        private const val TAP_SLOP = 24f
    }
}
