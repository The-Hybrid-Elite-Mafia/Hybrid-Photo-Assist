package com.hem.hybridphotoassist

/**
 * Undo/redo that stores only the changed rectangle (before and after), capped by memory.
 * Oldest steps are dropped when the cap is reached. Pure Kotlin, unit-tested on the JVM.
 *
 * Rules (each one has a test):
 * - only real changes are recorded: an empty box or identical pixels records nothing;
 * - a new edit after Undo clears the Redo steps;
 * - Undo puts back exactly the "before" pixels, Redo exactly the "after" pixels.
 */
class History(private val maxBytes: Long = defaultCap()) {
    private class Step(val x: Int, val y: Int, val before: Pixels, val after: Pixels) {
        val bytes: Long get() = (before.data.size + after.data.size) * 4L
    }

    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun clear() { undo.clear(); redo.clear() }

    /**
     * Records that [box] changed from [before] to [after] (full images of the same size).
     * Returns false (and records nothing) when nothing actually changed.
     */
    fun record(before: Pixels, after: Pixels, box: Box): Boolean {
        require(before.width == after.width && before.height == after.height) { "history: image size changed" }
        val b = box.clampTo(before.width, before.height)
        if (b.isEmpty()) return false
        val beforeCrop = before.crop(b)
        val afterCrop = after.crop(b)
        if (beforeCrop.data.contentEquals(afterCrop.data)) return false
        redo.clear()
        undo.addLast(Step(b.left, b.top, beforeCrop, afterCrop))
        var total = undo.sumOf { it.bytes }
        while (total > maxBytes && undo.size > 1) {
            total -= undo.removeFirst().bytes
        }
        return true
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
    fun redoCount() = redo.size

    companion object {
        /** A quarter of the app's memory, at most 192 MB, so history never starves the editor itself. */
        fun defaultCap(): Long = minOf(192L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4)
    }
}
