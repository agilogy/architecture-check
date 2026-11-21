package cat.coeli.architecturecheck.infrastructure

import java.io.File

/** The source sets whose classes are read; [Test]'s only for what they use of the others. */
enum class SourceSet(internal val directoryName: String) {
    Main("main"),
    TestFixtures("testFixtures"),
    Test("test"),
}

/** Where a class was compiled from. */
data class Placement(val module: String, val sourceSet: SourceSet)

/**
 * A project of the build: [path] is its directory from the repository root, `.` for the root
 * project. [isApp] for an application, which no other module depends on.
 */
class Module(val path: String, private val directory: File, internal val isApp: Boolean) {
    internal val directoryName: String
        get() = directory.name

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
