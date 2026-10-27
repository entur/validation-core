package no.entur.api.security

import org.entur.auth.spring.common.server.DefaultAuthProviders
import org.entur.auth.spring.common.server.EnturAuthProperties
import org.springframework.context.annotation.Configuration

/**
 * Fails startup unless every trusted tenant's issuer has at least one accepted audience in
 * `entur.auth.apis`.
 *
 * Entur's resource-server library only checks a token's `aud` claim for issuers with an
 * `entur.auth.apis` entry whose `issuer-url` matches the issuer *exactly*, and accepts any
 * audience otherwise. A typo or a missing trailing slash in one environment's issuer URL would
 * therefore switch off audience checking for that tenant without any error, so this turns that
 * silent misconfiguration into a failed deploy.
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
