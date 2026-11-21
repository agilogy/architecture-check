package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.CompiledClass
import cat.coeli.architecturecheck.domain.ExcludedPackages
import cat.coeli.architecturecheck.domain.Placement
import cat.coeli.architecturecheck.domain.Reach
import cat.coeli.architecturecheck.domain.SourceSet
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import java.io.File

/** Every class compiled from the modules' source sets, with where it was compiled from. */
internal fun importClasses(
    modules: List<Module>,
    excluded: ExcludedPackages,
): Map<JavaClass, Placement> {
    val directories = modules.flatMap { module ->
        SourceSet.entries
            .map { module.classDirectoryOf(it) to Placement(module.path, it) }
            .filter { (directory, _) -> directory.isDirectory }
    }
    val prefixes = directories.map { (directory, placement) ->
        directory.absolutePath + File.separator to placement
    }
    return if (directories.isEmpty()) {
        emptyMap()
    } else {
        ClassFileImporter()
            .importPaths(directories.map { it.first.toPath() })
            .filterNot { it.packageName in excluded }
            .mapNotNull { javaClass -> placementOf(javaClass, prefixes)?.let { javaClass to it } }
            .toMap()
    }
}

private fun placementOf(javaClass: JavaClass, prefixes: List<Pair<String, Placement>>): Placement? =
    javaClass.source
        .orElse(null)
        ?.uri
        ?.takeIf { it.scheme == "file" }
        ?.let { File(it).absolutePath }
        ?.let { path -> prefixes.firstOrNull { (prefix, _) -> path.startsWith(prefix) }?.second }

/**
 * Each class with what it reaches and depends on. [sourcePaths] has each class's source file from
 * the repository root, when it is where the package convention puts it.
 */
internal fun compiledClassesOf(
    placements: Map<JavaClass, Placement>,
    sourcePaths: Map<JavaClass, String>,
): List<CompiledClass> = placements.map { (javaClass, placement) ->
    CompiledClass(
        name = javaClass.name,
        packageName = javaClass.packageName,
        unit = javaClass.topLevelName(),
        sourceFile = javaClass.sourceFilePath(),
        sourcePath = sourcePaths[javaClass],
        placement = placement,
        reaches = javaClass.reaches(),
        dependencies = javaClass.directDependenciesFromSelf.map { it.targetClass.name }.toSet(),
    )
}

private fun JavaClass.reaches(): List<Reach> {
    val accessed =
        accessesFromSelf.map { it.targetOwner.name to it.sourceCodeLocation.lineNumber }.toSet()
    return accessesFromSelf.mapNotNull { access ->
        reachOf(
            target = access.targetOwner,
            member = access.target.name,
            line = access.sourceCodeLocation.lineNumber,
        )
    } +
        directDependenciesFromSelf
            .filterNot { it.targetClass.name to it.sourceCodeLocation.lineNumber in accessed }
            .mapNotNull { dependency ->
                reachOf(
                    target = dependency.targetClass,
                    member = null,
                    line = dependency.sourceCodeLocation.lineNumber,
                )
            }
}

// Arrays count as their elements; primitives are nobody's.
private fun reachOf(target: JavaClass, member: String?, line: Int): Reach? =
    (if (target.isArray) target.baseComponentType else target)
        .takeUnless { it.isPrimitive }
        ?.let {
            Reach(target = it.name, targetPackage = it.packageName, member = member, line = line)
        }

private fun JavaClass.sourceFilePath(): String =
    packageName.replace(oldChar = '.', newChar = '/') +
        "/" +
        (source.orElse(null)?.fileName?.orElse(null) ?: "${simpleName}.kt")

private fun JavaClass.topLevelName(): String =
    generateSequence(this) { it.enclosingClass.orElse(null) }.last().name

/**
 * Each class's source file from the repository root, when it is where the package convention puts
 * it.
 */
internal fun sourcePathsOf(
    placements: Map<JavaClass, Placement>,
    modules: List<Module>,
): Map<JavaClass, String> {
    val modulesByPath = modules.associateBy { it.path }
    return placements
        .mapNotNull { (javaClass, placement) ->
            modulesByPath[placement.module]?.sourcePathOf(javaClass, placement.sourceSet)?.let {
                javaClass to it
            }
        }
        .toMap()
}

private fun Module.sourcePathOf(javaClass: JavaClass, sourceSet: SourceSet): String? =
    javaClass.sourceCodeLocation.sourceFileName
        .takeIf { it.isNotEmpty() }
        ?.let { fileName ->
            File(
                sourceDirectoryOf(sourceSet),
                javaClass.packageName.replace(oldChar = '.', newChar = '/') + "/$fileName",
            )
        }
        ?.takeIf { it.isFile }
        ?.let { pathOf(it) }
