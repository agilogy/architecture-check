package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Module
import cat.coeli.architecturecheck.domain.SourceSet
import java.io.File

/** Where the files of [module] are: its project [directory]. */
internal class ModuleFiles(internal val module: Module, private val directory: File) {
    internal val buildScript: File
        get() = File(directory, BUILD_SCRIPT)

    internal fun sourceDirectoryOf(sourceSet: SourceSet): File =
        File(directory, "src/${sourceSet.directoryName}/kotlin")

    internal fun classDirectoryOf(sourceSet: SourceSet): File =
        File(directory, "build/classes/kotlin/${sourceSet.directoryName}")

    /** A file of the module as a path from the repository root. */
    internal fun pathOf(file: File): String =
        file.relativeTo(directory).invariantSeparatorsPath.let {
            if (module.path == ".") it else "${module.path}/$it"
        }
}

private const val BUILD_SCRIPT = "build.gradle.kts"
