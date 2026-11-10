package sefirah.domain.util

/** Labels of clipboard actions, never the selected text itself. */
object ClipboardActionLabels {
    fun isCopyOrCut(label: CharSequence?, copyWord: String, cutWord: String): Boolean {
        val normalized = label?.toString()?.trim()?.lowercase(java.util.Locale.ROOT) ?: return false
        if (normalized.isEmpty() || normalized.length >= 80) return false
        val actions = setOf(copyWord, cutWord, "Copy", "Cut").map { it.lowercase(java.util.Locale.ROOT) }
        return actions.any { action ->
            normalized == action || normalized.startsWith("$action ") || normalized.startsWith("$action:")
        }
    }
}
