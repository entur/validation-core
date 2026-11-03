package no.entur.http

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Represent
import org.yaml.snakeyaml.representer.Representer

/** A [Yaml] that writes OpenAPI specs in the format this repo commits. */
fun newOpenApiYaml(): Yaml {
    // Literal blocks (`|`) for multi-line strings, so descriptions in the committed specs diff line by line.
    val options =
        DumperOptions().apply {
            indent = 4
            indicatorIndent = 2
            indentWithIndicator = false
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        }
    val representer =
        object : Representer(options) {
            init {
                representers[String::class.java] =
                    Represent { data ->
                        val s = data as String
                        if (s.contains('\n')) {
                            representScalar(Tag.STR, s, DumperOptions.ScalarStyle.LITERAL)
                        } else {
                            representScalar(Tag.STR, s)
                        }
                    }
            }
        }
    return Yaml(representer, options)
}

@Suppress("UNCHECKED_CAST")
fun yamlMap(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>

@Suppress("UNCHECKED_CAST")
fun yamlMapOrNull(value: Any?): MutableMap<String, Any?>? = value as? MutableMap<String, Any?>

/**
 * A copy of [value]'s Map/List structure with no shared instances, which SnakeYAML would render as YAML
 * anchors/aliases.
 */
fun deepCopy(value: Any?): Any? =
    when (value) {
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (k, v) -> k to deepCopy(v) }
        is List<*> -> value.map { deepCopy(it) }
        else -> value
    }