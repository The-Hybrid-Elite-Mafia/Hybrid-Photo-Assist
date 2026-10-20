package com.hem.hybridphotoassist

/**
 * Undo/redo that stores only the changed rectangle (before and after), capped by memory.
 * Oldest steps are dropped when the cap is reached.
 */
class History(private val maxBytes: Long = 192L * 1024 * 1024) {
    private class Step(val x: Int, val y: Int, val before: Pixels, val after: Pixels) {
        val bytes: Long get() = (before.data.size + after.data.size) * 4L
    }

    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun clear() { undo.clear(); redo.clear() }

    /** Records that [box] changed from [before] to [after] (full images of the same size). */
    fun record(before: Pixels, after: Pixels, box: Box) {
        val b = box.clampTo(before.width, before.height)
        if (b.isEmpty()) return
        redo.clear()
        undo.addLast(Step(b.left, b.top, before.crop(b), after.crop(b)))
        var total = undo.sumOf { it.bytes }
        while (total > maxBytes && undo.size > 1) {
            total -= undo.removeFirst().bytes
        }
    }

    /** Applies undo to [image] in place. */
    fun undo(image: Pixels): Boolean {
        val s = undo.removeLastOrNull() ?: return false
        image.paste(s.before, s.x, s.y)
        redo.addLast(s)
        return true
    }

    fun redo(image: Pixels): Boolean {
        val s = redo.removeLastOrNull() ?: return false
        image.paste(s.after, s.x, s.y)
        undo.addLast(s)
        return true
    }

    fun undoCount() = undo.size
}
