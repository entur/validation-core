package no.entur.api.security

import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity

/** Turns on the `@PreAuthorize("hasPermission(...)")` checks the controllers use. */
@Configuration
@EnableReactiveMethodSecurity
class MethodSecurityConfig
