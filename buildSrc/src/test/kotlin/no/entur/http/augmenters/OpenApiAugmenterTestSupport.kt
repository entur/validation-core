package no.entur.http.augmenters

import java.io.File

/**
 * The `protoc` to compile fixtures with: mise's shim, else the one BufTools fetched into the root
 * project's `build/tools/bin` (a bare runner), else whatever is on the PATH.
 */
internal fun protocExecutable(): String =
    listOf(
        File(System.getProperty("user.home"), ".local/share/mise/shims/protoc"),
        File(System.getProperty("fetchedProtoc")),
    ).firstOrNull { it.canExecute() }?.absolutePath ?: "protoc"

/** Navigates a nested `Map<String, Any?>` (as loaded from YAML) by a chain of keys. */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.at(vararg keys: String): Map<String, Any?> {
    var current = this
    for (key in keys) current = current[key] as? Map<String, Any?> ?: error("Missing key '$key'")
    return current
}
