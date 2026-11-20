package no.entur.http

import org.gradle.api.tasks.Exec
import java.io.File

/**
 * Resolves [name] to its mise shim and puts the shims directory on the child's PATH.
 *
 * Exec resolves the executable against the Gradle JVM's PATH, not the child's, so a bare name
 * fails under mise locally. The shims dir also goes on the child's PATH for tools it shells out to.
 */
internal fun Exec.miseExecutable(name: String): String {
    val miseShims = File(System.getProperty("user.home"), ".local/share/mise/shims")
    environment("PATH", "$miseShims${File.pathSeparator}${System.getenv("PATH")}")
    return File(miseShims, name).let { if (it.canExecute()) it.absolutePath else name }
}
