package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.SourceSet
import java.io.File

/**
 * A project of the build: [path] is its directory from the repository root, `.` for the root
 * project, and [name] its Gradle name. [isApp] for an application, which no other module depends
 * on.
 */
internal class Module(
    val path: String,
    private val directory: File,
    internal val name: String,
    internal val isApp: Boolean,
) {
    internal val buildScript: File
        get() = File(directory, BUILD_SCRIPT)

    internal fun sourceDirectoryOf(sourceSet: SourceSet): File =
        File(directory, "src/${sourceSet.directoryName}/kotlin")

    internal fun classDirectoryOf(sourceSet: SourceSet): File =
        File(directory, "build/classes/kotlin/${sourceSet.directoryName}")

    /** A file of the module as a path from the repository root. */
    internal fun pathOf(file: File): String =
        file.relativeTo(directory).invariantSeparatorsPath.let {
            if (path == ".") it else "$path/$it"
        }
}

private const val BUILD_SCRIPT = "build.gradle.kts"
