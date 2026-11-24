package no.entur.http

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Makes `buf` and `protoc` available to the plugin's [Exec] tasks.
 *
 * Both are used from the PATH or mise's shims when present (the developer setup), and otherwise fetched
 * into `<root>/build/tools` at the versions pinned in the workspace's `.mise.toml` - so a bare runner, such
 * as CodeQL's autobuild, can build without a setup step. buf's `protoc_builtin` plugins look `protoc` up on
 * the PATH, so [configure] puts the tools directory there.
 */
class BufTools private constructor(
    private val root: Project,
    private val workspace: File,
) {
    private val miseShims = File(System.getProperty("user.home"), ".local/share/mise/shims")
    private val toolsDir = root.layout.buildDirectory.dir("tools").get().asFile
    private val bin = File(toolsDir, "bin")
    private val include = File(toolsDir, "include")

    private val installedBuf = findExecutable("buf")
    private val installedProtoc = findExecutable("protoc")

    /** The `buf` to run: an installed one, else the one [fetchTasks] downloads. */
    val bufExecutable: String = installedBuf ?: File(bin, "buf").absolutePath

    private val fetchTasks: List<Task> =
        listOfNotNull(
            if (installedBuf == null) root.tasks.findByName(FETCH_BUF) ?: registerFetchBuf() else null,
            if (installedProtoc == null) root.tasks.findByName(FETCH_PROTOC) ?: registerFetchProtoc() else null,
        )

    /** Makes [exec] run after the tools exist, with them on its PATH. */
    fun configure(exec: Exec) {
        exec.dependsOn(fetchTasks)
        exec.environment(
            "PATH",
            listOf(bin, miseShims).joinToString(File.pathSeparator) + File.pathSeparator + System.getenv("PATH").orEmpty(),
        )
    }

    private fun findExecutable(name: String): String? =
        (listOf(miseShims) + System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map(::File))
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    private fun pinnedVersion(tool: String): String =
        Regex("""^$tool\s*=\s*['"]([^'"]+)['"]""", RegexOption.MULTILINE)
            .find(File(workspace, ".mise.toml").readText())
            ?.groupValues
            ?.get(1) ?: error("$tool is not pinned in ${File(workspace, ".mise.toml")}")

    private fun registerFetchBuf() =
        root.tasks.register(FETCH_BUF) {
            description = "Downloads the buf release pinned in .mise.toml, since none is installed."
            group = "build"
            val version = pinnedVersion("buf")
            val target = File(bin, "buf")
            inputs.property("version", version)
            outputs.file(target)
            doLast {
                checkLinuxX86()
                // SHA-256 of buf-Linux-x86_64 from the release's sha256.txt. Update it when bumping buf.
                check(version == BUF_VERSION) { "Update BUF_VERSION and BUF_SHA256 in BufTools for buf $version." }
                val bytes = URI("https://github.com/bufbuild/buf/releases/download/v$version/buf-Linux-x86_64").toURL().readBytes()
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(actual == BUF_SHA256) { "buf checksum mismatch: expected $BUF_SHA256 but got $actual" }
                target.parentFile.mkdirs()
                target.writeBytes(bytes)
                target.setExecutable(true)
            }
        }.get()

    private fun registerFetchProtoc() =
        root.tasks.register(FETCH_PROTOC) {
            description = "Fetches the protoc release pinned in .mise.toml from Maven Central, since none is installed."
            group = "build"
            // protoc 36.1 is published to Maven Central as com.google.protobuf:protoc:4.36.1.
            val version = "4.${pinnedVersion("protoc")}"
            val target = File(bin, "protoc")
            inputs.property("version", version)
            outputs.file(target)
            outputs.dir(include)
            doLast {
                // Gradle verifies the artifact against Maven Central's published checksums.
                checkLinuxX86()
                val exe =
                    root.configurations
                        .detachedConfiguration(
                            root.dependencies.create("com.google.protobuf:protoc:$version:linux-x86_64@exe"),
                        ).singleFile
                target.parentFile.mkdirs()
                exe.copyTo(target, overwrite = true)
                target.setExecutable(true)
                // The bare executable ships without google/protobuf/*.proto, which protoc looks for in
                // ../include next to itself. protobuf-java's jar carries the same files.
                val javaJar =
                    root.configurations
                        .detachedConfiguration(root.dependencies.create("com.google.protobuf:protobuf-java:$version"))
                        .apply { isTransitive = false }
                        .singleFile
                ZipFile(javaJar).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { it.name.startsWith("google/protobuf/") && it.name.endsWith(".proto") }
                        .forEach { entry ->
                            File(include, entry.name).apply { parentFile.mkdirs() }.writeBytes(zip.getInputStream(entry).readBytes())
                        }
                }
            }
        }.get()

    companion object {
        private const val FETCH_BUF = "fetchBuf"
        private const val FETCH_PROTOC = "fetchProtoc"

        private const val BUF_VERSION = "1.73.0"
        private const val BUF_SHA256 = "8f2986298ad08f0cc1bf999b9797b7c383adf32d7edf0f73d6f1e1a701baeac1"

        private fun checkLinuxX86() =
            check("linux" in System.getProperty("os.name").lowercase() && System.getProperty("os.arch") in listOf("amd64", "x86_64")) {
                "buf and protoc are only fetched for Linux x86_64; install them (mise install)."
            }

        /** The tools for the build rooted at [project], pinned by the `.mise.toml` in [workspace]. */
        fun of(
            project: Project,
            workspace: File,
        ) = BufTools(project.rootProject, workspace)
    }
}
