package no.entur.http

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Represent
import org.yaml.snakeyaml.representer.Representer

fun newOpenApiYaml(): Yaml {
    // Multi-line strings as literal blocks (`|`) rather than escaped `\n`s.
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

/** Shared Map/List instances would be rendered by SnakeYAML as YAML anchors/aliases. */
fun deepCopy(value: Any?): Any? =
    when (value) {
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (k, v) -> k to deepCopy(v) }
        is List<*> -> value.map { deepCopy(it) }
        else -> value
    }