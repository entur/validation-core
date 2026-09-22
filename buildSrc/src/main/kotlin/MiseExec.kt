import org.gradle.api.tasks.Exec
import java.io.File

// Gradle's Exec resolves commandLine's executable against the Gradle JVM's
// own PATH, not the PATH it hands the child process - so a bare tool name
// works in CI (where it's already on PATH) but not in a mise-based local
// setup. Resolving to the mise shim's absolute path (.mise.toml pins the
// versions) works in both, and prepending the shims dir to the child's PATH
// lets that process shell out to other mise-managed tools too.
fun Exec.miseExecutable(name: String): String {
    val miseShims = File(System.getProperty("user.home"), ".local/share/mise/shims")
    environment("PATH", "$miseShims${File.pathSeparator}${System.getenv("PATH")}")
    return File(miseShims, name).let { if (it.canExecute()) it.absolutePath else name }
}
