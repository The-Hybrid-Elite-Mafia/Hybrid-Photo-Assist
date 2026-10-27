package com.hem.hybridphotoassist

import com.hem.hybridphotoassist.CommandParser.BackgroundKind
import com.hem.hybridphotoassist.CommandParser.Command
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the 1.1.0 repair: history rules, typed instructions, brightness and placing a moved object. */
class HistoryRulesTest {
    private fun img(seed: Int) = Pixels(8, 8, IntArray(64) { (0xFF shl 24) or (seed * 1000 + it) })

    @Test fun unchangedEditIsNotRecorded() {
        val h = History()
        val a = img(1)
        assertFalse(h.record(a, a.copy(), Box(0, 0, 8, 8)))
        assertFalse(h.canUndo)
    }

    @Test fun emptyBoxIsNotRecorded() {
        val h = History()
        assertFalse(h.record(img(1), img(2), Box(3, 3, 3, 3)))
        assertFalse(h.canUndo)
    }

    @Test fun severalUndoAndRedoGiveExactPixels() {
        val h = History()
        val s0 = img(0); val s1 = img(1); val s2 = img(2); val s3 = img(3)
        assertTrue(h.record(s0, s1, Box(0, 0, 8, 8)))
        assertTrue(h.record(s1, s2, Box(0, 0, 8, 4)))
        val s2full = s1.copy().also { it.paste(s2.crop(Box(0, 0, 8, 4)), 0, 0) }
        assertTrue(h.record(s2full, s3, Box(0, 0, 8, 8)))
        val cur = s3.copy()
        assertTrue(h.undo(cur)); assertArrayEquals(s2full.data, cur.data)
        assertTrue(h.undo(cur)); assertArrayEquals(s1.data, cur.data)
        assertTrue(h.undo(cur)); assertArrayEquals(s0.data, cur.data)
        assertFalse(h.undo(cur)); assertArrayEquals(s0.data, cur.data)
        assertTrue(h.redo(cur)); assertArrayEquals(s1.data, cur.data)
        assertTrue(h.redo(cur)); assertArrayEquals(s2full.data, cur.data)
        assertTrue(h.redo(cur)); assertArrayEquals(s3.data, cur.data)
        assertFalse(h.redo(cur))
    }

    @Test fun newEditAfterUndoClearsRedo() {
        val h = History()
        h.record(img(0), img(1), Box(0, 0, 8, 8))
        h.record(img(1), img(2), Box(0, 0, 8, 8))
        val cur = img(2)
        h.undo(cur)
        assertTrue(h.canRedo)
        assertTrue(h.record(cur.copy(), img(5), Box(0, 0, 8, 8)))
        assertFalse(h.canRedo)
        assertEquals(2, h.undoCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun differentSizesAreRejected() {
        History().record(Pixels.solid(4, 4, 0), Pixels.solid(5, 4, 0), Box(0, 0, 4, 4))
    }
}

class CommandParserTest {
    private fun p(s: String) = CommandParser.parse(s)

    @Test fun emptyText() { assertEquals(Command.Empty, p("   ")) }

    @Test fun removeWords() {
        assertEquals(Command.Erase, p("Remove the selected object and fill the gap."))
        assertEquals(Command.Erase, p("erase it"))
        assertEquals(Command.Erase, p("Get rid of that sign"))
        assertEquals(Command.Erase, p("Fill the selected region"))
        // "background" here only says where the person is.
        assertEquals(Command.Erase, p("Remove the person in the background."))
    }

    @Test fun moveDirections() {
        assertEquals(Command.Move(-1, 0, CommandParser.MOVE_NORMAL), p("Move the selected object to the left."))
        assertEquals(Command.Move(1, -1, CommandParser.MOVE_NORMAL), p("move it up and right"))
        assertEquals(Command.Move(0, 1, CommandParser.MOVE_SMALL), p("nudge it down a little"))
        assertEquals(Command.Move(1, 0, CommandParser.MOVE_LARGE), p("move it way right"))
        assertTrue(p("move it") is Command.Unsupported)
    }

    @Test fun brightness() {
        assertEquals(Command.Brightness(20, wholePhoto = false), p("Make the selected area brighter."))
        assertEquals(Command.Brightness(-10, wholePhoto = false), p("a little darker"))
        assertEquals(Command.Brightness(40, wholePhoto = true), p("make the whole photo a lot brighter"))
    }

    @Test fun backgrounds() {
        assertEquals(Command.Background(BackgroundKind.BLUR), p("Blur the background"))
        assertEquals(Command.Background(BackgroundKind.WHITE), p("make the background white"))
        assertEquals(Command.Background(BackgroundKind.GRAY), p("change the background to grey"))
        assertTrue(p("remove the background") is Command.Unsupported)
    }

    @Test fun controls() {
        assertEquals(Command.Undo, p("undo"))
        assertEquals(Command.Redo, p("Redo"))
        assertEquals(Command.Cancel, p("cancel"))
        assertEquals(Command.ClearSelection, p("deselect"))
        assertEquals(Command.Save, p("save"))
    }

    @Test fun facesAreRefusedHonestly() {
        for (s in listOf("Widen her smile.", "Make her eyes look normal.", "Make the expression look more natural.",
                         "Explain how to make this face look more natural.")) {
            val c = p(s)
            assertTrue(s, c is Command.Unsupported)
            assertEquals(CommandParser.FACE_REASON, (c as Command.Unsupported).reason)
        }
    }

    @Test fun unknownIsUnsupported() {
        val c = p("turn this into a painting")
        assertTrue(c is Command.Unsupported)
        assertTrue((c as Command.Unsupported).reason.contains("not changed"))
    }
}

class BrightnessAndOverlayTest {
    @Test fun brightnessOnlyInsideMask() {
        val img = Pixels.solid(4, 1, 0x808080)
        val m = Mask(4, 1).also { it[0] = 255; it[1] = 128 }
        val out = img.brightness(50, m)
        assertEquals(0xFFC0C0C0.toInt(), out.data[0]) // 128 + 127*0.5 = 191.5 -> 192 = 0xC0
        assertTrue((out.data[1] and 0xFF) in 0x90..0xA8) // half strength
        assertEquals(img.data[2], out.data[2])
        assertEquals(0xFF808080.toInt(), img.data[0]) // original untouched
    }

    @Test fun darkenWholeImage() {
        val out = Pixels.solid(2, 2, 0x646464).brightness(-50, null)
        assertEquals(0xFF323232.toInt(), out.data[3])
    }

    @Test fun overlayPlacesAndClips() {
        val base = Pixels.solid(6, 6, 0x000000)
        val top = Pixels.solid(3, 3, 0xFFFFFF)
        val a = Mask(3, 3).also { it.data.fill(255.toByte()) }
        val out = Pixels.overlay(base, top, a, 4, -1) // partly outside on the right and top
        assertEquals(0xFFFFFFFF.toInt(), out[4, 0])
        assertEquals(0xFFFFFFFF.toInt(), out[5, 1])
        assertEquals(0xFF000000.toInt(), out[4, 2])
        assertEquals(0xFF000000.toInt(), out[3, 0])
        assertEquals(0xFF000000.toInt(), base[4, 0]) // base untouched
    }
}

class PhraseBookTest {
    @Test fun startsEmpty() {
        assertTrue(PhraseBook().entries.isEmpty())
        assertTrue(PhraseBook.parse(null).entries.isEmpty())
        assertEquals(null, PhraseBook().match("move left"))
    }

    @Test fun bothSidesAreEditable() {
        val b = PhraseBook()
        assertEquals(null, b.put("Scoot it over", "move left 7%"))
        assertEquals(Command.Move(-1, 0, 0.07f), b.match("scoot it over!"))
        val i = b.entries.indexOfFirst { it.phrase == "Scoot it over" }
        assertEquals(null, b.put("Slide it", "Move right a little", i))       // change what I say AND what it does
        assertEquals(null, b.match("scoot it over"))
        assertEquals(Command.Move(1, 0, CommandParser.MOVE_SMALL), b.match("slide it"))
        assertEquals(null, b.put("Pop it", "Brighter 35%"))
        assertEquals(Command.Brightness(35, wholePhoto = false), b.match("pop it"))
        val again = PhraseBook.parse(b.serialize())
        assertEquals(b.entries, again.entries)
        again.remove(0)
        assertEquals(null, again.match("slide it"))
    }

    @Test fun refusesWhatTheAppCantDo() {
        val b = PhraseBook()
        assertTrue(b.put("fix her", "widen her smile")!!.contains("can't do"))
        assertTrue(b.put("x", "") != null)
        assertTrue(b.put("   ", "undo") != null)
        assertEquals(null, b.put("back", "undo"))
        assertTrue(b.put("BACK!", "redo") != null) // same phrase twice
        assertTrue(b.put("y".repeat(PhraseBook.MAX_LEN + 1), "undo") != null)
        assertEquals(1, b.entries.size)
    }

    @Test fun readsOldListWithoutItsStartingPhrases() {
        val b = PhraseBook.parse("MOVE_LEFT\tMove left\nMOVE_LEFT_LITTLE\tscoot\nNOT_AN_ACTION\tfoo\nbroken line\n")
        assertEquals(listOf(PhraseBook.Entry("scoot", "Move left a little")), b.entries)
        assertEquals(Command.Move(-1, 0, CommandParser.MOVE_SMALL), b.match("scoot"))
    }

    @Test fun everyDropDownChoiceIsSomethingTheAppCanDo() {
        for (s in PhraseBook().suggestions()) {
            val c = CommandParser.parse(s)
            assertTrue(s, c !is Command.Unsupported && c != Command.Empty)
        }
    }

    @Test fun exactAmounts() {
        assertEquals(Command.Move(0, -1, 0.12f), CommandParser.parse("move up 12%"))
        assertEquals(Command.Brightness(-8, wholePhoto = true), CommandParser.parse("whole photo darker 8 percent"))
    }
}

class LookMatchTest {
    private fun noisy(w: Int, h: Int, base: Int, spread: Int, seed: Int): Pixels {
        val r = java.util.Random(seed.toLong())
        return Pixels(w, h, IntArray(w * h) {
            val v = (base + r.nextInt(spread * 2 + 1) - spread).coerceIn(0, 255)
            Pixels.argb(255, v, (v * 0.9).toInt(), (v * 0.7).toInt())
        })
    }
    private fun meanLuma(p: Pixels): Double = p.data.map { c -> 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255) }.average()

    @Test fun fullStrengthMovesBrightnessToReference() {
        val src = noisy(64, 48, 60, 20, 1); val ref = noisy(40, 40, 190, 20, 2)
        for (mode in LookMatch.Mode.values()) {
            val out = LookMatch.apply(src, ref, mode, 1f)
            assertEquals(mode.name, meanLuma(ref), meanLuma(out), 6.0)
        }
    }

    @Test fun zeroStrengthChangesNothing() {
        val src = noisy(32, 32, 100, 30, 3)
        assertArrayEquals(src.data, LookMatch.apply(src, noisy(32, 32, 200, 10, 4), LookMatch.Mode.VIBE, 0f).data)
    }

    @Test fun onlyInsideMask() {
        val src = noisy(20, 10, 80, 10, 5)
        val m = Mask(20, 10).also { for (y in 0 until 10) for (x in 0 until 10) it[y * 20 + x] = 255 }
        val out = LookMatch.apply(src, noisy(20, 20, 220, 10, 6), LookMatch.Mode.LOOK, 1f, m)
        assertEquals(src[15, 5], out[15, 5])
        assertTrue(meanLuma(Pixels(1, 1, intArrayOf(out[3, 3]))) > 150)
    }

    @Test fun bigImageUsesSampledStats() {
        val src = noisy(1200, 700, 70, 25, 7)
        val ref = noisy(300, 300, 170, 25, 8)
        val out = LookMatch.apply(src, ref, LookMatch.Mode.LOOK, 1f)
        assertEquals(1200, out.width)
        assertEquals(meanLuma(ref), meanLuma(out), 8.0)
    }
}
