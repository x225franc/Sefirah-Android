package sefirah.domain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardActionLabelsTest {
    @Test fun localizedCopyAndCut() {
        for (label in listOf("Copier", "Couper", " COUPER ", "Copy", "Cut"))
            assertTrue(label, ClipboardActionLabels.isCopyOrCut(label, "Copier", "Couper"))
    }

    @Test fun appCopyButtonsWithDescription() {
        for (label in listOf("Copier le lien", "Copier: adresse", "Copy code", "Couper le texte"))
            assertTrue(label, ClipboardActionLabels.isCopyOrCut(label, "Copier", "Couper"))
    }

    @Test fun unrelatedTextDoesNotTriggerClipboardRead() {
        for (label in listOf(null, "", "Copyright", "Recopier", "Découper", "Ne pas copier", "a".repeat(100)))
            assertFalse(ClipboardActionLabels.isCopyOrCut(label, "Copier", "Couper"))
    }
}
