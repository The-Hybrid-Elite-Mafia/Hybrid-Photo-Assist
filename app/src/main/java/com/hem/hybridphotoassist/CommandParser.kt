package com.hem.hybridphotoassist

/**
 * Turns a typed instruction into one of the edits this app can really do.
 *
 * It does not understand language in general and there is no AI behind it: it looks for plain
 * keywords. Anything it cannot map to a real edit comes back as [Command.Unsupported] with an
 * honest reason, so the app never pretends to have done something it did not do.
 * Pure Kotlin (no Android) so it is unit-tested on the JVM.
 */
object CommandParser {

    enum class BackgroundKind { BLUR, BLACK, WHITE, GRAY, LIME }

    sealed class Command {
        /** Nothing typed. */
        object Empty : Command()
        /** Remove the selected object and fill the gap. */
        object Erase : Command()
        /** Move the selected object. [dx], [dy] are -1, 0 or 1; [fraction] is how far, as a part of the photo's size. */
        data class Move(val dx: Int, val dy: Int, val fraction: Float) : Command()
        /** Brighten (positive) or darken (negative) by [percent]; [wholePhoto] = ignore the selection. */
        data class Brightness(val percent: Int, val wholePhoto: Boolean) : Command()
        /** Keep the selection and replace everything else. */
        data class Background(val kind: BackgroundKind) : Command()
        object Undo : Command()
        object Redo : Command()
        object Cancel : Command()
        object ClearSelection : Command()
        object Save : Command()
        /** Not something the app can do. [reason] is shown to the user as is. */
        data class Unsupported(val reason: String) : Command()
    }

    const val MOVE_SMALL = 0.05f
    const val MOVE_NORMAL = 0.10f
    const val MOVE_LARGE = 0.25f
    const val BRIGHT_NORMAL = 20

    /** Shown for anything the app cannot do. Lists what it can. */
    const val CAN_DO =
        "I can: remove the selected object, move it left/right/up/down, make it brighter or darker, " +
        "blur the background or make it black/white/gray/lime, undo, redo, cancel, save."

    const val FACE_REASON =
        "Changing faces (smile, eyes, expression, skin) isn't supported. This app has no face-editing AI " +
        "on the phone, so it can't do that honestly. Your photo was not changed."

    private val faceWords = listOf(
        "smile", "smiling", "eye", "eyes", "face", "faces", "facial", "expression", "mouth", "lips", "lip",
        "teeth", "tooth", "nose", "skin", "wrinkle", "wrinkles", "makeup", "make-up", "eyebrow", "eyebrows",
        "younger", "older", "frown", "blink", "pupil", "pupils", "chin", "jaw", "cheek", "cheeks", "forehead"
    )
    private val bodyWords = listOf("slimmer", "thinner", "fatter", "taller", "shorter", "muscle", "muscles", "hair", "body")
    private val eraseWords = listOf("remove", "erase", "delete", "get rid of", "take out", "take away", "fill", "clean up", "wipe")
    private val moveWords = listOf("move", "shift", "slide", "nudge", "push", "drag", "put")
    private val brighterWords = listOf("brighter", "brighten", "lighter", "lighten", "more light", "less dark")
    private val darkerWords = listOf("darker", "darken", "dimmer", "dim", "less bright", "less light")
    private val wholeWords = listOf("whole photo", "whole picture", "whole image", "entire", "everything", "all of it", "the photo", "the picture", "the image")

    fun parse(input: String): Command {
        val t = " " + input.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9%' -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim() + " "
        if (t.isBlank()) return Command.Empty

        // Faces and bodies first: the fill model must never be passed off as a face editor.
        if (faceWords.any { has(t, it) }) return Command.Unsupported(FACE_REASON)
        if (bodyWords.any { has(t, it) } && !eraseWords.any { has(t, it) } && !moveWords.any { has(t, it) }) {
            return Command.Unsupported("Reshaping people (body, hair) isn't supported. Your photo was not changed.")
        }

        // One-word controls.
        val words = t.trim().split(' ')
        if (has(t, "redo") || has(t, "do it again")) return Command.Redo
        if (has(t, "undo") || has(t, "go back") || has(t, "take that back")) return Command.Undo
        if (has(t, "cancel") || has(t, "stop") || has(t, "never mind") || has(t, "nevermind")) return Command.Cancel
        if (has(t, "deselect") || has(t, "unselect") || has(t, "clear selection") || has(t, "clear the selection")) return Command.ClearSelection
        if (words.first() == "save" || has(t, "save it") || has(t, "save the photo") || has(t, "save photo")) return Command.Save

        // Background: "blur the background", "make the background white". Checked before erase so
        // "remove the background" does not erase the selected subject.
        if (has(t, "background") || has(t, "backdrop") || has(t, "behind")) {
            val kind = backgroundKind(t)
            val targetsBackground = Regex("\\b(remove|erase|delete|change|replace|swap|blur|make|turn|paint|set)\\b( the| my)? (background|backdrop)\\b").containsMatchIn(t) ||
                Regex("\\b(background|backdrop) (to|into|white|black|gray|grey|blurry|blurred|lime|green)\\b").containsMatchIn(t) ||
                (kind == BackgroundKind.BLUR && has(t, "blur"))
            if (targetsBackground) {
                return if (kind != null) Command.Background(kind)
                else Command.Unsupported(
                    "Say what the new background should be: blur, black, white, gray or lime. " +
                    "(Select the person or thing to keep first.) Your photo was not changed."
                )
            }
            // Otherwise "background" just describes where the object is ("remove the man in the background").
        }

        // An exact amount, e.g. "move left 7%" or "brighter 35 percent".
        val pct = Regex("\\b(\\d{1,3}) ?(%|percent|per cent)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100)

        // Brightness.
        val brighter = brighterWords.any { has(t, it) }
        val darker = darkerWords.any { has(t, it) }
        if (brighter || darker) {
            if (brighter && darker) return Command.Unsupported("Brighter or darker? Say just one. Your photo was not changed.")
            val amount = pct ?: (BRIGHT_NORMAL * strength(t)).toInt().coerceIn(1, 80)
            val whole = wholeWords.any { has(t, it) } && !has(t, "selected") && !has(t, "selection")
            return Command.Brightness(if (brighter) amount else -amount, wholePhoto = whole)
        }

        // Move.
        if (moveWords.any { has(t, it) }) {
            var dx = 0; var dy = 0
            if (has(t, "left")) dx -= 1
            if (has(t, "right")) dx += 1
            if (has(t, "up") || has(t, "higher") || has(t, "upward") || has(t, "upwards")) dy -= 1
            if (has(t, "down") || has(t, "lower") || has(t, "downward") || has(t, "downwards")) dy += 1
            if (dx == 0 && dy == 0) {
                return Command.Unsupported(
                    "Say which way to move it: left, right, up or down. Or tap Move and drag it with your finger. " +
                    "Your photo was not changed."
                )
            }
            val s = strength(t)
            val fraction = when {
                pct != null -> pct / 100f
                s < 1f -> MOVE_SMALL
                s > 1f -> MOVE_LARGE
                else -> MOVE_NORMAL
            }
            return Command.Move(dx, dy, fraction)
        }

        // Erase / fill.
        if (eraseWords.any { has(t, it) }) return Command.Erase

        return Command.Unsupported("I don't know how to do that. $CAN_DO Your photo was not changed.")
    }

    /** Plain-words description of what a command will do (shown when you edit a phrase). */
    fun describe(c: Command): String = when (c) {
        Command.Empty -> "Nothing yet."
        Command.Erase -> "Removes what you selected and fills the gap."
        is Command.Move -> {
            val dir = listOfNotNull(if (c.dy < 0) "up" else if (c.dy > 0) "down" else null, if (c.dx < 0) "left" else if (c.dx > 0) "right" else null).joinToString(" and ")
            "Moves what you selected $dir by ${(c.fraction * 100).toInt()}% of the photo."
        }
        is Command.Brightness -> "Makes ${if (c.wholePhoto) "the whole photo" else "what you selected"} ${if (c.percent > 0) "brighter" else "darker"} by ${kotlin.math.abs(c.percent)}%."
        is Command.Background -> "Keeps what you selected and makes the background " + when (c.kind) {
            BackgroundKind.BLUR -> "blurry."; BackgroundKind.BLACK -> "black."; BackgroundKind.WHITE -> "white."
            BackgroundKind.GRAY -> "gray."; BackgroundKind.LIME -> "lime."
        }
        Command.Undo -> "Undo."
        Command.Redo -> "Redo."
        Command.Cancel -> "Cancels a move, or clears the selection."
        Command.ClearSelection -> "Clears the selection."
        Command.Save -> "Saves the photo."
        is Command.Unsupported -> c.reason
    }

    private fun backgroundKind(t: String): BackgroundKind? = when {
        has(t, "blur") || has(t, "blurry") || has(t, "blurred") || has(t, "out of focus") || has(t, "bokeh") -> BackgroundKind.BLUR
        has(t, "black") -> BackgroundKind.BLACK
        has(t, "white") -> BackgroundKind.WHITE
        has(t, "gray") || has(t, "grey") -> BackgroundKind.GRAY
        has(t, "lime") || has(t, "green") || has(t, "hem") -> BackgroundKind.LIME
        else -> null
    }

    /** 0.5 for "a little", 2 for "a lot", 1 otherwise. */
    private fun strength(t: String): Float = when {
        listOf("a little", "a bit", "slightly", "a touch", "tiny", "little bit", "just a").any { has(t, it) } -> 0.5f
        listOf("a lot", "much", "way", "really", "very", "far", "lots").any { has(t, it) } -> 2f
        else -> 1f
    }

    /** Whole-word / whole-phrase match on the normalised, space-padded text. */
    private fun has(t: String, phrase: String) = t.contains(" $phrase ")
}
