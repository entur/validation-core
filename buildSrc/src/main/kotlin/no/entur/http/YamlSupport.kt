package no.entur.http

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Represent
import org.yaml.snakeyaml.representer.Representer

/**
 * Creates a new Open API YAML specification template with formatting specifications.
 */
fun newOpenApiYaml(): Yaml {
    // Block style throughout, and multi-line strings as literal blocks (`|`) rather than one line with
    // escaped `\n`s.
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

// SnakeYAML's object graph is untyped (Object/LinkedHashMap), so recovering the OpenAPI-shaped
// Map<String, Any?> it's known to contain needs a cast that erasure can't verify.
@Suppress("UNCHECKED_CAST")
fun yamlMap(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>

@Suppress("UNCHECKED_CAST")
fun yamlMapOrNull(value: Any?): MutableMap<String, Any?>? = value as? MutableMap<String, Any?>

/**
 * A fresh copy of [value]'s Map/List structure, so no two places in it end up sharing the same Map/List
 * instance - which SnakeYAML would otherwise render as a YAML anchor/alias pair.
 */
fun deepCopy(value: Any?): Any? =
    when (value) {
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (k, v) -> k to deepCopy(v) }
        is List<*> -> value.map { deepCopy(it) }
        else -> value
    }