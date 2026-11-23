import no.entur.http.BufTools
import org.gradle.api.tasks.Exec

/**
 * The `buf` for this [Exec] to run: the one on the PATH or in mise's shims, else a copy fetched at the
 * version pinned in `.mise.toml` (see [BufTools]), so a bare runner such as CodeQL's autobuild can build.
 * Also puts buf, protoc and the mise shims on the child's PATH.
 */
fun Exec.miseExecutable(name: String): String {
    require(name == "buf") { "Only buf is resolved here, not $name" }
    val tools = BufTools.of(project, project.rootProject.projectDir)
    tools.configure(this)
    return tools.bufExecutable
}
