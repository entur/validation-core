package no.entur.api.http

import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory

/**
 * Converts an arbitrary YAML document to its equivalent JSON - no knowledge of what the document
 * contains, so any endpoint that stores or serves YAML can reuse it to also offer JSON. The mappers
 * are reused across calls: `ObjectMapper` is thread-safe once configured, and construction is the
 * expensive part.
 */
object YamlJsonConverter {
    private val yamlReader = ObjectMapper(YAMLFactory())
    private val jsonWriter = ObjectMapper()

    fun yamlToJson(yaml: String): String = jsonWriter.writeValueAsString(yamlReader.readTree(yaml))
}
