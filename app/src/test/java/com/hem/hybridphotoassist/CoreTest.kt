package com.hem.hybridphotoassist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskTest {
    private fun square(w: Int, h: Int, b: Box): Mask {
        val m = Mask(w, h)
        for (y in b.top until b.bottom) for (x in b.left until b.right) m[y * w + x] = 255
        return m
    }

    @Test fun boundsFindsTheSelectedArea() {
        val m = square(50, 40, Box(10, 5, 20, 15))
        assertEquals(Box(10, 5, 20, 15), m.bounds())
        assertNull(Mask(10, 10).bounds())
    }

    @Test fun dilateGrowsByRadiusExactly() {
        val m = Mask(41, 41); m[20 * 41 + 20] = 255
        val d = m.dilated(3)
        assertEquals(Box(17, 17, 24, 24), d.bounds())
        assertEquals(49, d.coverage())
    }

    @Test fun dilateAtEdgesStaysInside() {
        val m = Mask(10, 10); m[0] = 255
        assertEquals(Box(0, 0, 3, 3), m.dilated(2).bounds())
    }

    @Test fun featherKeepsCentreAndSoftensEdge() {
        val m = square(60, 60, Box(20, 20, 40, 40))
        val f = m.feathered(3)
        assertEquals(255, f[30, 30])
        assertEquals(0, f[2, 2])
        val edge = f[20, 30]
        assertTrue("edge should be partial, was $edge", edge in 1..254)
    }

    @Test fun resizeKeepsShape() {
        val m = square(100, 100, Box(0, 0, 50, 100))
        val r = m.resized(10, 10).binarized()
        assertEquals(Box(0, 0, 5, 10), r.bounds())
    }

    @Test fun brushPaintsAndErases() {
        val m = Mask(100, 100)
        m.paintLine(10f, 50f, 90f, 50f, 5f, add = true)
        assertTrue(m[50, 50] == 255 && m[12, 50] == 255 && m[88, 50] == 255)
        assertEquals(0, m[50, 40])
        m.paintCircle(50f, 50f, 8f, add = false)
        assertEquals(0, m[50, 50])
        assertEquals(255, m[20, 50])
    }

    @Test fun confidenceThreshold() {
        val m = Mask.fromConfidence(2, 2, floatArrayOf(0.1f, 0.5f, 0.9f, 0.49f))
        assertArrayEquals(intArrayOf(0, 255, 255, 0), IntArray(4) { m[it] })
    }

    @Test fun invertFlips() {
        val m = Mask(2, 1); m[0] = 255
        val i = m.inverted()
        assertEquals(0, i[0]); assertEquals(255, i[1])
    }
}

class PixelsTest {
    @Test fun compositeUsesMask() {
        val bottom = Pixels.solid(2, 1, 0x000000)
        val top = Pixels.solid(2, 1, 0xFFFFFF)
        val m = Mask(2, 1); m[1] = 255
        val c = Pixels.composite(bottom, top, m)
        assertEquals(0xFF000000.toInt(), c.data[0])
        assertEquals(0xFFFFFFFF.toInt(), c.data[1])
    }

    @Test fun lerpHalf() {
        val c = Pixels.lerp(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 128)
        val r = (c shr 16) and 0xFF
        assertTrue("half blend red was $r", r in 126..130)
    }

    @Test fun coverFitFillsFrame() {
        val src = Pixels.solid(100, 50, 0x123456)
        val out = Pixels.coverFit(src, 40, 40)
        assertEquals(40, out.width); assertEquals(40, out.height)
        assertEquals(0xFF123456.toInt(), out.data[0])
    }

    @Test fun pasteClipsAtEdges() {
        val dst = Pixels.solid(4, 4, 0x000000)
        dst.paste(Pixels.solid(3, 3, 0xFFFFFF), 2, 2)
        assertEquals(0xFFFFFFFF.toInt(), dst[3, 3])
        assertEquals(0xFF000000.toInt(), dst[1, 1])
    }

    @Test fun blurOfFlatImageIsFlat() {
        val p = Pixels.solid(30, 20, 0x405060).blurred(3)
        assertTrue(p.data.all { it == 0xFF405060.toInt() })
    }
}

class InpainterTest {
    /** Fake model: paints the masked area pure red, keeps the rest as given. */
    private class RedFill(override val size: Int = 64) : FillModel {
        var calls = 0
        override fun fill(rgb: FloatArray, mask: FloatArray): FloatArray {
            calls++
            val out = rgb.copyOf()
            for (i in mask.indices) if (mask[i] > 0.5f) { out[i * 3] = 1f; out[i * 3 + 1] = 0f; out[i * 3 + 2] = 0f }
            return out
        }
    }

    @Test fun fillsInsideAndLeavesFarPixelsAlone() {
        val img = Pixels.solid(400, 300, 0x00FF00)
        val sel = Mask(400, 300)
        for (y in 140 until 160) for (x in 190 until 210) sel[y * 400 + x] = 255
        val model = RedFill()
        val r = Inpainter(model).inpaint(img, sel)
        assertNotNull(r)
        r!!
        assertEquals(1, model.calls)
        val centre = r.image[200, 150]
        assertTrue("centre should be red-ish: ${Integer.toHexString(centre)}", ((centre shr 16) and 0xFF) > 200 && ((centre shr 8) and 0xFF) < 60)
        assertEquals(0xFF00FF00.toInt(), r.image[5, 5])
        // Original is not modified.
        assertEquals(0xFF00FF00.toInt(), img[200, 150])
        assertTrue(r.changed.left <= 190 && r.changed.right >= 210)
    }

    @Test fun emptySelectionDoesNothing() {
        assertNull(Inpainter(RedFill()).inpaint(Pixels.solid(10, 10, 0), Mask(10, 10)))
    }

    @Test fun cropStaysInsideAndCoversObject() {
        val b = Box(0, 0, 50, 30)
        val c = Inpainter.planCrop(1000, 800, b)
        assertTrue(c.left >= 0 && c.top >= 0 && c.right <= 1000 && c.bottom <= 800)
        assertTrue(c.left <= b.left && c.right >= b.right && c.top <= b.top && c.bottom >= b.bottom)
        val big = Inpainter.planCrop(300, 200, Box(10, 10, 290, 190))
        assertEquals(Box(0, 0, 300, 200), big)
    }

    @Test fun normalizesDifferentOutputScales() {
        assertEquals(1f, Inpainter.normalizeOutput(floatArrayOf(255f, 0f))[0], 1e-6f)
        assertEquals(0.5f, Inpainter.normalizeOutput(floatArrayOf(-1f, 0f))[1], 1e-6f)
        assertEquals(0.25f, Inpainter.normalizeOutput(floatArrayOf(0.25f, 1f))[0], 1e-6f)
    }
}

class HistoryTest {
    @Test fun undoRedoRestoresPixels() {
        val h = History()
        val before = Pixels.solid(10, 10, 0x000000)
        val after = before.copy().also { it.data[55] = 0xFFFFFFFF.toInt() }
        h.record(before, after, Box(5, 5, 6, 6))
        val img = after.copy()
        assertTrue(h.undo(img))
        assertEquals(0xFF000000.toInt(), img.data[55])
        assertTrue(h.redo(img))
        assertEquals(0xFFFFFFFF.toInt(), img.data[55])
        assertFalse(h.redo(img))
    }

    @Test fun capDropsOldestSteps() {
        val h = History(maxBytes = 10L * 10 * 4 * 2 * 2) // room for two full 10x10 steps
        val p = Pixels.solid(10, 10, 0)
        repeat(5) { h.record(p, p, Box(0, 0, 10, 10)) }
        assertEquals(2, h.undoCount())
    }
}
