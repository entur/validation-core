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
            outputs.file(target)
            doLast {
                val platform = Platform.current()
                val expected =
                    BUF_SHA256["$version/${platform.buf}"]
                        ?: error("No SHA-256 pinned for buf $version/${platform.buf}; add it to BufTools, or install buf (mise install).")
                val bytes =
                    URI("https://github.com/bufbuild/buf/releases/download/v$version/buf-${platform.buf}")
                        .toURL()
                        .openStream()
                        .use { it.readBytes() }
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(actual == expected) { "buf-${platform.buf} checksum mismatch: expected $expected but got $actual" }
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
            outputs.file(target)
            outputs.dir(include)
            doLast {
                // Gradle verifies the artifact against Maven Central's published checksums.
                val exe =
                    root.configurations
                        .detachedConfiguration(
                            root.dependencies.create("com.google.protobuf:protoc:$version:${Platform.current().protoc}@exe"),
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

    private enum class Platform(
        val buf: String,
        val protoc: String,
    ) {
        LINUX_X86_64("Linux-x86_64", "linux-x86_64"),
        LINUX_AARCH64("Linux-aarch64", "linux-aarch_64"),
        MAC_X86_64("Darwin-x86_64", "osx-x86_64"),
        MAC_AARCH64("Darwin-arm64", "osx-aarch_64"),
        ;

        companion object {
            fun current(): Platform {
                val os = System.getProperty("os.name").lowercase()
                val arch = System.getProperty("os.arch").lowercase()
                val arm = "aarch64" in arch || "arm64" in arch
                return when {
                    "linux" in os -> if (arm) LINUX_AARCH64 else LINUX_X86_64
                    "mac" in os -> if (arm) MAC_AARCH64 else MAC_X86_64
                    else -> error("buf and protoc are not fetched for $os/$arch; install them (mise install).")
                }
            }
        }
    }

    companion object {
        private const val FETCH_BUF = "fetchBuf"
        private const val FETCH_PROTOC = "fetchProtoc"

        // SHA-256 of buf-<platform> from each release's sha256.txt. Add the entries when bumping buf.
        private val BUF_SHA256 =
            mapOf(
                "1.73.0/Linux-x86_64" to "8f2986298ad08f0cc1bf999b9797b7c383adf32d7edf0f73d6f1e1a701baeac1",
                "1.73.0/Linux-aarch64" to "902b75267db7f4391e99b7fa0756050e5354234cc0437ef50eee9c788950c7a3",
                "1.73.0/Darwin-arm64" to "6e6df0fef4522e4e43dfe7c341873c3f2c29ceb45a9dfa5e0bad5580b8b2022f",
                "1.73.0/Darwin-x86_64" to "ff78d0ebf34180ebfa81d370275851ec630fcb088bf33e213fd723d0fd7444a6",
            )

        /** The tools for the build rooted at [project], pinned by the `.mise.toml` in [workspace]. */
        fun of(
            project: Project,
            workspace: File,
        ) = BufTools(project.rootProject, workspace)
    }
}
