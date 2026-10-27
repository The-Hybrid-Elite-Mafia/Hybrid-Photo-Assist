package com.hem.hybridphotoassist

import com.hem.hybridphotoassist.CommandParser.BackgroundKind
import com.hem.hybridphotoassist.CommandParser.Command

/**
 * Steven's own phrases for the instruction box ("scoot it left", "kill it", …).
 * Each phrase is tied to one real action from [Action], so a custom phrase can never claim an edit
 * the app can't do. Typed text that matches a phrase (ignoring case, punctuation and extra spaces)
 * runs that action; anything else goes to [CommandParser]. Pure Kotlin, unit-tested.
 */
class PhraseBook(entries: List<Entry> = defaults()) {

    enum class Action(val label: String) {
        ERASE("Remove it (erase + fill)"),
        MOVE_LEFT("Move left"), MOVE_RIGHT("Move right"), MOVE_UP("Move up"), MOVE_DOWN("Move down"),
        MOVE_LEFT_LITTLE("Move left a little"), MOVE_RIGHT_LITTLE("Move right a little"),
        MOVE_UP_LITTLE("Move up a little"), MOVE_DOWN_LITTLE("Move down a little"),
        MOVE_LEFT_FAR("Move left a lot"), MOVE_RIGHT_FAR("Move right a lot"),
        MOVE_UP_FAR("Move up a lot"), MOVE_DOWN_FAR("Move down a lot"),
        BRIGHTER("Selection brighter"), DARKER("Selection darker"),
        BRIGHTER_LITTLE("Selection a little brighter"), DARKER_LITTLE("Selection a little darker"),
        PHOTO_BRIGHTER("Whole photo brighter"), PHOTO_DARKER("Whole photo darker"),
        BG_BLUR("Blur the background"), BG_BLACK("Background black"), BG_WHITE("Background white"),
        BG_GRAY("Background gray"), BG_LIME("Background lime"),
        UNDO("Undo"), REDO("Redo"), CANCEL("Cancel"), DESELECT("Clear the selection"), SAVE("Save");

        fun toCommand(): Command = when (this) {
            ERASE -> Command.Erase
            MOVE_LEFT -> Command.Move(-1, 0, CommandParser.MOVE_NORMAL)
            MOVE_RIGHT -> Command.Move(1, 0, CommandParser.MOVE_NORMAL)
            MOVE_UP -> Command.Move(0, -1, CommandParser.MOVE_NORMAL)
            MOVE_DOWN -> Command.Move(0, 1, CommandParser.MOVE_NORMAL)
            MOVE_LEFT_LITTLE -> Command.Move(-1, 0, CommandParser.MOVE_SMALL)
            MOVE_RIGHT_LITTLE -> Command.Move(1, 0, CommandParser.MOVE_SMALL)
            MOVE_UP_LITTLE -> Command.Move(0, -1, CommandParser.MOVE_SMALL)
            MOVE_DOWN_LITTLE -> Command.Move(0, 1, CommandParser.MOVE_SMALL)
            MOVE_LEFT_FAR -> Command.Move(-1, 0, CommandParser.MOVE_LARGE)
            MOVE_RIGHT_FAR -> Command.Move(1, 0, CommandParser.MOVE_LARGE)
            MOVE_UP_FAR -> Command.Move(0, -1, CommandParser.MOVE_LARGE)
            MOVE_DOWN_FAR -> Command.Move(0, 1, CommandParser.MOVE_LARGE)
            BRIGHTER -> Command.Brightness(CommandParser.BRIGHT_NORMAL, wholePhoto = false)
            DARKER -> Command.Brightness(-CommandParser.BRIGHT_NORMAL, wholePhoto = false)
            BRIGHTER_LITTLE -> Command.Brightness(CommandParser.BRIGHT_NORMAL / 2, wholePhoto = false)
            DARKER_LITTLE -> Command.Brightness(-CommandParser.BRIGHT_NORMAL / 2, wholePhoto = false)
            PHOTO_BRIGHTER -> Command.Brightness(CommandParser.BRIGHT_NORMAL, wholePhoto = true)
            PHOTO_DARKER -> Command.Brightness(-CommandParser.BRIGHT_NORMAL, wholePhoto = true)
            BG_BLUR -> Command.Background(BackgroundKind.BLUR)
            BG_BLACK -> Command.Background(BackgroundKind.BLACK)
            BG_WHITE -> Command.Background(BackgroundKind.WHITE)
            BG_GRAY -> Command.Background(BackgroundKind.GRAY)
            BG_LIME -> Command.Background(BackgroundKind.LIME)
            UNDO -> Command.Undo
            REDO -> Command.Redo
            CANCEL -> Command.Cancel
            DESELECT -> Command.ClearSelection
            SAVE -> Command.Save
        }
    }

    data class Entry(val phrase: String, val action: Action)

    private val list = entries.toMutableList()

    val entries: List<Entry> get() = list.toList()

    /** The action for [text] if it matches one of the phrases, else null. */
    fun match(text: String): Command? {
        val key = normalize(text)
        if (key.isEmpty()) return null
        return list.firstOrNull { normalize(it.phrase) == key }?.action?.toCommand()
    }

    /**
     * Adds a phrase, or replaces the one at [replaceIndex]. Returns an error message for the user,
     * or null when it was saved.
     */
    fun put(phrase: String, action: Action, replaceIndex: Int? = null): String? {
        val clean = phrase.replace(Regex("[\\t\\r\\n]+"), " ").trim()
        if (normalize(clean).isEmpty()) return "Type a phrase first."
        if (clean.length > MAX_LEN) return "Keep it under $MAX_LEN letters."
        val clash = list.indexOfFirst { normalize(it.phrase) == normalize(clean) }
        if (clash >= 0 && clash != replaceIndex) return "\"${list[clash].phrase}\" is already in your list."
        val e = Entry(clean, action)
        if (replaceIndex != null && replaceIndex in list.indices) list[replaceIndex] = e else list.add(e)
        return null
    }

    fun remove(index: Int) { if (index in list.indices) list.removeAt(index) }

    fun resetToDefaults() { list.clear(); list.addAll(defaults()) }

    /** One phrase per line: ACTION<TAB>phrase. */
    fun serialize(): String = list.joinToString("\n") { "${it.action.name}\t${it.phrase}" }

    companion object {
        const val MAX_LEN = 80

        fun normalize(s: String): String = s.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun defaults(): List<Entry> = listOf(
            Entry("Move left", Action.MOVE_LEFT),
            Entry("Move right", Action.MOVE_RIGHT),
            Entry("Move up", Action.MOVE_UP),
            Entry("Move down", Action.MOVE_DOWN),
            Entry("Remove it", Action.ERASE),
            Entry("Brighter", Action.BRIGHTER),
            Entry("Darker", Action.DARKER),
            Entry("Blur the background", Action.BG_BLUR),
            Entry("Undo", Action.UNDO),
            Entry("Redo", Action.REDO)
        )

        /** Reads [serialize] output; unknown or broken lines are skipped. Null/blank = defaults. */
        fun parse(text: String?): PhraseBook {
            if (text == null) return PhraseBook()
            val out = mutableListOf<Entry>()
            for (line in text.split('\n')) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val action = runCatching { Action.valueOf(line.substring(0, tab)) }.getOrNull() ?: continue
                val phrase = line.substring(tab + 1).trim()
                if (normalize(phrase).isEmpty()) continue
                if (out.any { normalize(it.phrase) == normalize(phrase) }) continue
                out.add(Entry(phrase, action))
            }
            return PhraseBook(out)
        }
    }
}
