package no.entur.api.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class ContentNegotiationSupportTest {
    private val yaml = MediaType.APPLICATION_YAML
    private val json = MediaType.APPLICATION_JSON

    @Test
    fun `defaults to the first candidate when no Accept header is present`() {
        assertEquals(yaml, ContentNegotiationSupport.bestMatch(null, yaml, json))
    }

    @Test
    fun `defaults to the first candidate for a wildcard Accept header`() {
        assertEquals(yaml, ContentNegotiationSupport.bestMatch("*/*", yaml, json))
    }

    @Test
    fun `picks the explicitly accepted candidate`() {
        assertEquals(json, ContentNegotiationSupport.bestMatch("application/json", yaml, json))
    }

    @Test
    fun `a q=0 entry is excluded rather than matched`() {
        // A client that explicitly marks JSON unacceptable must not get JSON back, even though
        // "application/json" is present in the header.
        assertEquals(yaml, ContentNegotiationSupport.bestMatch("application/json;q=0, */*;q=0.1", yaml, json))
    }

    @Test
    fun `falls back to the first candidate when every entry is q=0`() {
        assertEquals(yaml, ContentNegotiationSupport.bestMatch("application/json;q=0, application/yaml;q=0", yaml, json))
    }

    @Test
    fun `higher quality wins over declaration order`() {
        assertEquals(json, ContentNegotiationSupport.bestMatch("application/yaml;q=0.1, application/json;q=0.9", yaml, json))
        assertEquals(yaml, ContentNegotiationSupport.bestMatch("application/json;q=0.1, application/yaml;q=0.9", yaml, json))
    }

    @Test
    fun `falls back to the first candidate for an unparseable Accept header`() {
        assertEquals(yaml, ContentNegotiationSupport.bestMatch("not a media type", yaml, json))
    }

    @Test
    fun `a specific q=0 exclusion wins over a higher-quality wildcard`() {
        // A client excluding YAML specifically must not get it back just because a broader "*/*"
        // (which also matches YAML) happens to carry a higher quality value.
        assertEquals(json, ContentNegotiationSupport.bestMatch("application/yaml;q=0, */*;q=1", yaml, json))
    }
}
