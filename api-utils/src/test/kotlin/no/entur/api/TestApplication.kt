package no.entur.api

import org.springframework.boot.autoconfigure.SpringBootApplication

/** Lets `@WebFluxTest` (which searches upwards from the test's own package) find a configuration. */
@SpringBootApplication
class TestApplication
