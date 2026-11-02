package no.entur.api.security

import org.entur.auth.spring.common.server.DefaultAuthProviders
import org.entur.auth.spring.common.server.EnturAuthProperties
import org.springframework.context.annotation.Configuration

/**
 * Fails startup unless every trusted issuer has an accepted audience in `entur.auth.apis`. Entur's
 * resource-server library accepts any `aud` for an issuer without an exactly matching `issuer-url`, so a
 * typo would silently switch off audience checking.
 */
@Configuration
class AudienceValidationGuard(
    properties: EnturAuthProperties,
) {
    init {
        val audiencesByIssuer =
            properties.apis
                .groupBy({ it.issuerUrl }, { it.audiences })
                .mapValues { (_, audiences) -> audiences.flatten().filter { it.isNotBlank() } }
        val trustedIssuers =
            DefaultAuthProviders()
                .get(properties.tenants.environment, properties.tenants.include)
                .map { it.issuerUrl }
        val unchecked = trustedIssuers.filter { audiencesByIssuer[it].isNullOrEmpty() }
        check(unchecked.isEmpty()) {
            "No accepted audience configured in entur.auth.apis for trusted issuer(s) $unchecked; " +
                "tokens from them would be accepted for any audience. Configured issuers: " +
                "${audiencesByIssuer.keys}"
        }
    }
}
