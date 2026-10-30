package no.entur.api.openapi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(OpenApiController::class)
@TestPropertySource(properties = ["entur.api.openapi.spec-location=openapi/test-spec.yaml"])
class OpenApiControllerTest {
    @Autowired
    private lateinit var client: WebTestClient

    @Test
    fun `getOpenApiSpec returns yaml by default`() {
        client
            .get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_YAML)
            .expectBody(String::class.java)
            .consumeWith { assertTrue(it.responseBody!!.contains("openapi:")) }
    }

    @Test
    fun `getOpenApiSpec returns json when json is accepted`() {
        client
            .get()
            .uri("/v3/api-docs")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(String::class.java)
            .consumeWith { assertTrue(it.responseBody!!.trimStart().startsWith("{")) }
    }

    @Test
    fun `getOpenApiSpec returns yaml when json is explicitly marked unacceptable`() {
        client
            .get()
            .uri("/v3/api-docs")
            .header("Accept", "application/json;q=0, */*;q=0.1")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_YAML)
            .expectBody(String::class.java)
            .consumeWith { assertTrue(it.responseBody!!.contains("openapi:")) }
    }
}
