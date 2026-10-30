package no.entur.api.http

import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory

/**
 * Converts an arbitrary YAML document to its equivalent JSON. This class is thread-safe.
 */
object YamlJsonConverter {
    // ObjectMapper is thread-safe. Construct them once because it is somewhat expensive.
    private val yamlReader = ObjectMapper(YAMLFactory())
    private val jsonWriter = ObjectMapper()

    fun yamlToJson(yaml: String): String = jsonWriter.writeValueAsString(yamlReader.readTree(yaml))
}
