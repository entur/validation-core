package no.entur.api.security

import org.entur.auth.spring.common.server.ApiProperties
import org.entur.auth.spring.common.server.EnturAuthProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains

/**
 * The guard must refuse to start whenever a trusted tenant's issuer would get no audience check:
 * no `entur.auth.apis` entry, an entry with no audiences, or an issuer URL that differs from the
 * library's by so much as a trailing slash.
 */
internal class AudienceValidationGuardTest {
    @Test
    fun startsWhenEveryTrustedIssuerHasAnAudience() {
        assertDoesNotThrow {
            AudienceValidationGuard(
                properties(
                    api("https://partner.dev.entur.org/", "https://api.dev.entur.io"),
                    api("https://internal.dev.entur.org/", "https://api.dev.entur.io"),
                ),
            )
        }
    }

    @Test
    fun failsWhenATrustedIssuerHasNoEntry() {
        val error =
            assertThrows<IllegalStateException> {
                AudienceValidationGuard(properties(api("https://internal.dev.entur.org/", "https://api.dev.entur.io")))
            }
        assertContains(error.message!!, "https://partner.dev.entur.org/")
    }

    @Test
    fun failsWhenAnIssuerUrlDoesNotMatchExactly() {
        val error =
            assertThrows<IllegalStateException> {
                AudienceValidationGuard(
                    properties(
                        api("https://partner.dev.entur.org", "https://api.dev.entur.io"),
                        api("https://internal.dev.entur.org/", "https://api.dev.entur.io"),
                    ),
                )
            }
        assertContains(error.message!!, "https://partner.dev.entur.org/")
    }

    @Test
    fun failsWhenATrustedIssuerHasOnlyABlankAudience() {
        assertThrows<IllegalStateException> {
            AudienceValidationGuard(
                properties(
                    api("https://partner.dev.entur.org/", ""),
                    api("https://internal.dev.entur.org/", "https://api.dev.entur.io"),
                ),
            )
        }
    }

    private fun properties(vararg apis: ApiProperties) =
        EnturAuthProperties().apply {
            tenants.environment = "dev"
            tenants.include = listOf("partner", "internal")
            this.apis = apis.toMutableList()
        }

    private fun api(
        issuerUrl: String,
        audience: String,
    ) = ApiProperties().apply {
        this.issuerUrl = issuerUrl
        audiences = mutableListOf(audience)
    }
}
