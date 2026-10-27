package com.hem.hybridphotoassist

import com.hem.hybridphotoassist.CommandParser.Command

/**
 * Steven's own phrases for the instruction box. Both sides are his to edit:
 * - [Entry.phrase]: what he types ("scoot it left");
 * - [Entry.does]: what it does, in plain words ("move left 7%"), typed freely or picked from suggestions.
 *
 * "Does" is checked by [CommandParser] when it is saved, so a phrase can only be tied to something the
 * app really can do; anything else is refused with the reason. Typed text that matches a phrase
 * (ignoring case, punctuation and extra spaces) runs its "does"; anything else goes to [CommandParser].
 * Pure Kotlin, unit-tested.
 */
class PhraseBook(entries: List<Entry> = emptyList()) {

    /** Ready-made "does" choices for the drop-down (all of them can be changed or replaced). */
    enum class Action(val label: String) {
        ERASE("Remove it"),
        MOVE_LEFT("Move left"), MOVE_RIGHT("Move right"), MOVE_UP("Move up"), MOVE_DOWN("Move down"),
        MOVE_LEFT_LITTLE("Move left a little"), MOVE_RIGHT_LITTLE("Move right a little"),
        MOVE_UP_LITTLE("Move up a little"), MOVE_DOWN_LITTLE("Move down a little"),
        MOVE_LEFT_FAR("Move left a lot"), MOVE_RIGHT_FAR("Move right a lot"),
        MOVE_UP_FAR("Move up a lot"), MOVE_DOWN_FAR("Move down a lot"),
        BRIGHTER("Brighter"), DARKER("Darker"),
        BRIGHTER_LITTLE("A little brighter"), DARKER_LITTLE("A little darker"),
        PHOTO_BRIGHTER("Whole photo brighter"), PHOTO_DARKER("Whole photo darker"),
        BG_BLUR("Blur the background"), BG_BLACK("Background black"), BG_WHITE("Background white"),
        BG_GRAY("Background gray"), BG_LIME("Background lime"),
        UNDO("Undo"), REDO("Redo"), CANCEL("Cancel"), DESELECT("Clear the selection"), SAVE("Save")
    }

    data class Entry(val phrase: String, val does: String)

    private val list = entries.toMutableList()

    val entries: List<Entry> get() = list.toList()

    /** The command for [text] if it matches one of the phrases, else null. */
    fun match(text: String): Command? {
        val key = normalize(text)
        if (key.isEmpty()) return null
        return list.firstOrNull { normalize(it.phrase) == key }?.let { CommandParser.parse(it.does) }
    }

    /** Drop-down suggestions for "does": the ready-made ones, exact-amount examples, then ones already used. */
    fun suggestions(): List<String> =
        (Action.values().map { it.label } + EXAMPLES + list.map { it.does })
            .distinctBy { normalize(it) }

    /**
     * Adds a phrase, or replaces the one at [replaceIndex]. Returns an error message for the user,
     * or null when it was saved.
     */
    fun put(phrase: String, does: String, replaceIndex: Int? = null): String? {
        val p = clean(phrase); val d = clean(does)
        if (normalize(p).isEmpty()) return "Type what you'll say first."
        if (p.length > MAX_LEN || d.length > MAX_LEN) return "Keep it under $MAX_LEN letters."
        when (val c = CommandParser.parse(d)) {
            Command.Empty -> return "Type or pick what it does."
            is Command.Unsupported -> return "The app can't do \"$d\". ${c.reason}"
            else -> {}
        }
        val clash = list.indexOfFirst { normalize(it.phrase) == normalize(p) }
        if (clash >= 0 && clash != replaceIndex) return "\"${list[clash].phrase}\" is already in your list."
        val e = Entry(p, d)
        if (replaceIndex != null && replaceIndex in list.indices) list[replaceIndex] = e else list.add(e)
        return null
    }

    fun remove(index: Int) { if (index in list.indices) list.removeAt(index) }

    fun clear() = list.clear()

    /** One phrase per line: P<TAB>phrase<TAB>does. */
    fun serialize(): String = list.joinToString("\n") { "P\t${it.phrase}\t${it.does}" }

    companion object {
        const val MAX_LEN = 80
        val EXAMPLES = listOf("Move left 5%", "Move right 20%", "Brighter 35%", "Darker 10%", "Whole photo brighter 15%")

        private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()

        fun normalize(s: String): String = s.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9%' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** The starting phrases 1.2.0 shipped with. They're dropped when an old list is read: the box understands them anyway. */
        private val OLD_DEFAULTS = setOf("move left", "move right", "move up", "move down", "remove it", "brighter", "darker",
            "blur the background", "undo", "redo")

        /**
         * Reads [serialize] output. Also reads the 1.2.0 format (ACTION<TAB>phrase), leaving out its
         * starting phrases. Lines that are broken or no longer valid are skipped. Null = empty list.
         */
        fun parse(text: String?): PhraseBook {
            if (text == null) return PhraseBook()
            val book = PhraseBook(emptyList())
            for (line in text.split('\n')) {
                val f = line.split('\t')
                when {
                    f.size == 3 && f[0] == "P" -> book.put(f[1], f[2])
                    f.size == 2 -> runCatching { Action.valueOf(f[0]) }.getOrNull()?.let { a ->
                        if (normalize(f[1]) !in OLD_DEFAULTS) book.put(f[1], a.label)
                    }
                }
            }
            return book
        }
    }
}
