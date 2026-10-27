package sefirah.domain.util

import java.text.Normalizer
import java.util.Locale

/** Conservative, local OTP extraction. No Android API, logging or retained message content. */
object OtpDetector {
    private val accents = Regex("\\p{M}+")
    private val candidates = Regex("(?<![\\p{L}\\p{N}_])(?:[0-9]{2,4}[ -][0-9]{2,4}|[A-Za-z0-9]{4,10})(?![\\p{L}\\p{N}_])")
    private val strongContext = Regex("\\b(?:otp|pin|passcode|(?:verification|confirmation|security|authentication|login|access) code|code (?:de |d['’])?(?:verification|validation|confirmation|securite|authentification|connexion|acces)|one[ -]time (?:password|code)|mot de passe a usage unique|verif(?:y|ier|ication)|authentification|authentication)\\b")
    private val codeContext = Regex("\\bcode\\b")
    private val negativeContext = Regex("\\b(?:commande|order|colis|parcel|tracking|livraison|delivery|reference|ref|identifiant|identifier|invoice|facture|carte|card|telephone|phone|appelez|call|prix|price|montant|amount|paiement|payment|annee|year|date|heure|time|postal|zip|promo|discount|reduction)\\b")
    private val structuredNumber = Regex("(?:[0-9]+[/:.,][0-9]+(?:[/:.,][0-9]+)?|\\+?[0-9][0-9 ()-]{8,}[0-9])")
    private val numericLengths = setOf(4, 6, 8)
    private val amountOrDuration = Regex("^(?:eur|usd|gbp|minutes?|seconds?|jours?|days?|heures?|hours?)\\b")

    fun detect(notificationText: String): String? {
        if (notificationText.isBlank()) return null
        // Bound work even for unusually large email notifications.
        val text = accents.replace(Normalizer.normalize(notificationText.take(16_384), Normalizer.Form.NFD), "")
        val lower = text.lowercase(Locale.ROOT)
        val strong = strongContext.findAll(lower).map { it.range }.toList()
        val generic = codeContext.findAll(lower).map { it.range }.toList()
        if (strong.isEmpty() && generic.isEmpty()) return null
        val negatives = negativeContext.findAll(lower).map { it.range }.toList()
        val structured = structuredNumber.findAll(lower).map { it.range }.toList()
        val scores = mutableMapOf<String, Int>()
        for (match in candidates.findAll(text)) {
            val code = match.value.replace(" ", "").replace("-", "")
            if (code.none { it in '0'..'9' } || code.length !in 4..10) continue
            val numeric = code.all { it in '0'..'9' }
            if (numeric && code.length !in numericLengths) continue
            if (structured.any { it.first <= match.range.last && it.last >= match.range.first }) continue
            val after = lower.substring(match.range.last + 1, minOf(lower.length, match.range.last + 12)).trimStart()
            val before = lower.substring(maxOf(0, match.range.first - 4), match.range.first).trimEnd()
            if (after.startsWith("€") || after.startsWith("$") || after.startsWith("£") ||
                amountOrDuration.containsMatchIn(after) ||
                before.endsWith("€") || before.endsWith("$") || before.endsWith("£")) continue
            val strongDistance = strong.minOfOrNull { distance(match.range, it) } ?: Int.MAX_VALUE
            val genericDistance = generic.minOfOrNull { distance(match.range, it) } ?: Int.MAX_VALUE
            val contextDistance = minOf(strongDistance, genericDistance)
            if (contextDistance > 64 || (!numeric && strongDistance > 64)) continue
            val negativeDistance = negatives.minOfOrNull { distance(match.range, it) } ?: Int.MAX_VALUE
            // A number belonging to an order/card/etc. remains invalid in an OTP notification.
            if (negativeDistance <= contextDistance && negativeDistance < 24) continue
            val score = (if (strongDistance <= 64) 120 - strongDistance else 80 - genericDistance) +
                (if (numeric && code.length == 6) 4 else 0)
            scores[code] = maxOf(scores[code] ?: Int.MIN_VALUE, score)
        }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        // Ambiguous notifications must not overwrite the user's clipboard.
        if (ranked.size > 1 && best.value - ranked[1].value < 20) return null
        return best.key
    }

    private fun distance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last - 1
        b.last < a.first -> a.first - b.last - 1
        else -> 0
    }
}
