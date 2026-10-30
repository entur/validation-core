package no.entur.http

/** Navigates a nested `Map<String, Any?>` (as loaded from YAML) by a chain of keys. */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.at(vararg keys: String): Map<String, Any?> {
    var current = this
    for (key in keys) current = current[key] as? Map<String, Any?> ?: error("Missing key '$key'")
    return current
}
