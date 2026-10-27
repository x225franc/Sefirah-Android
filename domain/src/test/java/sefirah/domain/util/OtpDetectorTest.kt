package sefirah.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OtpDetectorTest(private val text: String, private val expected: String?) {
    @Test fun detectsOnlyContextualCodes() {
        assertEquals(expected, OtpDetector.detect(text))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "case {index}")
        fun cases(): List<Array<Any?>> = listOf(
            "Votre code est 482913" to "482913",
            "Votre code de vérification : 1937" to "1937",
            "482913 est votre code de vérification" to "482913",
            "Your verification code is 736291" to "736291",
            "736291 is your verification code" to "736291",
            "OTP: 839201" to "839201",
            "Use QBX13VHS to verify your account" to "QBX13VHS",
            "Code de sécurité : 12345678" to "12345678",
            "Votre code est 482913. Il expire dans 10 minutes." to "482913",
            "Votre commande 482913 a été expédiée" to null,
            "Paiement de 1299 € accepté" to null,
            "Nous sommes en 2026" to null,
            "Appelez le 0123456789" to null,
            "Votre colis 839201 sera livré demain" to null,
            "Code : 482 913" to "482913",
            "Code : 482-913" to "482913",
            "VOTRE CODE DE VÉRIFICATION : 482913" to "482913",
            "Votre code de ve\u0301rification : 482913" to "482913",
            "Security code: AB12CD" to "AB12CD",
            "OTP: 7GH2K9" to "7GH2K9",
            "Your authentication code: G482913" to "G482913",
            "OTP: AB1D" to "AB1D",
            "OTP: A12345678Z" to "A12345678Z",
            "Votre code de connexion : BONJOUR" to null,
            "OTP: 123" to null,
            "OTP: 0123456789" to null,
            "OTP: A123456789Z" to null,
            "Verification code: 1299 €" to null,
            "Code de sécurité pour votre carte 1937" to null,
            "Code postal : 1937" to null,
            "Code promo : AB12CD" to null,
            "Code de vérification : 01/02/2026" to null,
            "Code de vérification : 12:1937" to null,
            "Verification: call +33 6 48 29 13 00" to null,
            "Votre commande 123456. Votre code de confirmation est 482913." to "482913",
            "Code: 482913 ou 839201" to null,
            "Code: 482913\nCode: 482913" to "482913",
            "Code: _482913_" to null,
            "" to null,
            "Code de vérification : " + "x".repeat(100) + " 482913" to null,
        ).map { arrayOf(it.first, it.second) }
    }
}
