package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Catalogue
import cat.coeli.architecturecheck.domain.ExcludedPackages
import cat.coeli.architecturecheck.domain.Finding
import cat.coeli.architecturecheck.domain.FindingKind
import cat.coeli.architecturecheck.domain.Layer
import cat.coeli.architecturecheck.domain.Placement
import cat.coeli.architecturecheck.domain.SourceLine
import cat.coeli.architecturecheck.domain.SourceSet
import cat.coeli.architecturecheck.domain.cyclesOf
import cat.coeli.architecturecheck.domain.isPlacedTooLow
import cat.coeli.architecturecheck.domain.layerOf
import cat.coeli.architecturecheck.domain.neededLayersOf
import cat.coeli.architecturecheck.domain.packagesOutsideRootOf
import cat.coeli.architecturecheck.domain.rootPackageOf
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.SourceCodeLocation
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
 * A class reached by another, and the member reached when it is reached through a call or a field
 * access.
 */
private data class Reach(
    val target: JavaClass,
    val member: String?,
    val location: SourceCodeLocation,
)

// Arrays count as their elements; primitives are nobody's.
private fun JavaClass.reaches(): List<Reach> {
    val accesses = accessesFromSelf.map {
        Reach(target = it.targetOwner, member = it.target.name, location = it.sourceCodeLocation)
    }
    val accessed = accesses.map { it.target.name to it.location.lineNumber }.toSet()
    val others =
        directDependenciesFromSelf
            .filterNot { it.targetClass.name to it.sourceCodeLocation.lineNumber in accessed }
            .map { Reach(target = it.targetClass, member = null, location = it.sourceCodeLocation) }
    return (accesses + others)
        .map { reach ->
            reach.copy(
                target = if (reach.target.isArray) reach.target.baseComponentType else reach.target,
            )
        }
        .filterNot { it.target.isPrimitive }
}

private fun JavaClass.sourceFilePath(): String =
    packageName.replace(oldChar = '.', newChar = '/') +
        "/" +
        (source.orElse(null)?.fileName?.orElse(null) ?: "${simpleName}.kt")

// Nested, local and anonymous classes, lambdas included, are judged as part of the top-level class
// around them.
private fun JavaClass.topLevelName(): String =
    generateSequence(this) { it.enclosingClass.orElse(null) }.last().name

/**
 * The analysis of the classes of `src/main` and `src/testFixtures`; tests are read only for what
 * they use.
 */
internal class ClassAnalysis(
    modules: List<Module>,
    private val placements: Map<JavaClass, Placement>,
    private val catalogue: Catalogue,
    private val excluded: ExcludedPackages,
) {
    private val modulesByPath = modules.associateBy { it.path }
    private val ownClassNames = placements.keys.map { it.name }.toSet()
    private val ownPackages = placements.keys.map { it.packageName }.toSet()
    private val production = placements.filterValues { it.sourceSet != SourceSet.Test }

    private val neededLayers: Map<String, Layer> by lazy {
        val reachesByUnit =
            production.keys
                .groupBy { it.topLevelName() }
                .mapValues { (_, classes) -> classes.flatMap { it.reaches() } }
        neededLayersOf(
            libraryLayers =
                reachesByUnit
                    .mapNotNull { (unit, reaches) ->
                        reaches
                            .filter { isLibrary(it.target) }
                            .mapNotNull { libraryLayerOf(it) }
                            .maxOrNull()
                            ?.let { unit to it }
                    }
                    .toMap(),
            dependencies =
                reachesByUnit.mapValues { (unit, reaches) ->
                    reaches.filter { isOwn(it.target) }.map { it.target.topLevelName() }.toSet() -
                        unit
                },
        )
    }

    // A source file moves as a whole, so it needs what the most demanding of its classes needs.
    private val neededLayersByFile: Map<String, Layer> by lazy {
        production.keys
            .groupBy { it.sourceFilePath() }
            .mapValues { (_, classes) ->
                classes.maxOf { neededLayers[it.topLevelName()] ?: Layer.Helper }
            }
    }

    private fun isOwn(target: JavaClass) = target.name in ownClassNames

    // A class of ours the import left out, such as one Kotlin inlined away, is nobody's.
    private fun isLibrary(target: JavaClass) =
        target.packageName.isNotEmpty() &&
            target.packageName !in ownPackages &&
            target.packageName !in excluded

    private fun libraryLayerOf(reach: Reach): Layer? =
        catalogue.libraryLayerOf(
            className = reach.target.name,
            packageName = reach.target.packageName,
            member = reach.member,
        )

    // The layer a class of ours counts at: the one it needs when it is placed too low, as that is
    // reported apart.
    private fun ownLayerOf(target: JavaClass): Layer {
        val declared = layerOf(target.packageName)
        val needed = neededLayersByFile[target.sourceFilePath()] ?: declared
        return if (isPlacedTooLow(declared, needed)) needed else declared
    }

    fun findings(): List<Finding> =
        forbiddenDependencies() +
            classesPlacedTooLow() +
            librariesWithoutRole() +
            packageCycles() +
            packagesOutsideModuleRoot()

    private fun forbiddenDependencies(): List<Finding> =
        production.entries
            .asSequence()
            .flatMap { (origin, placement) ->
                val originLayer = layerOf(origin.packageName)
                origin.reaches().mapNotNull { reach ->
                    val targetLayer =
                        when {
                            isOwn(reach.target) -> ownLayerOf(reach.target)
                            isLibrary(reach.target) -> libraryLayerOf(reach)
                            else -> null
                        }
                    targetLayer
                        ?.takeUnless { originLayer.mayDependOn(it) }
                        ?.let { forbiddenLayer ->
                            Finding(
                                kind = FindingKind.ForbiddenDependencies,
                                module = placement.module,
                                subject = "${origin.topLevelName()} → ${reach.target.packageName}",
                                detail = "$originLayer → $forbiddenLayer: ${reach.target.name}",
                                source = sourceLineOf(origin, placement, reach.location),
                            )
                        }
                }
            }
            .sortedWith(compareBy({ it.source?.line ?: 0 }, { it.detail }))
            .distinctBy { it.module to it.subject }
            .toList()

    // One finding per source file, which is what moves.
    private fun classesPlacedTooLow(): List<Finding> =
        production.entries
            .distinctBy { (javaClass, _) -> javaClass.sourceFilePath() }
            .mapNotNull { (javaClass, placement) ->
                val file = javaClass.sourceFilePath()
                Finding(
                        kind = FindingKind.ClassesPlacedTooLow,
                        module = placement.module,
                        subject = file,
                        detail = "needs nothing of ${Layer.Infrastructure}",
                        source =
                            sourceLineOf(javaClass, placement, javaClass.sourceCodeLocation)
                                ?.copy(line = 1),
                    )
                    .takeIf {
                        isPlacedTooLow(
                            declared = layerOf(javaClass.packageName),
                            needed = neededLayersByFile.getValue(file),
                        )
                    }
            }

    // Once for the whole repository, wherever the library is used from.
    private fun librariesWithoutRole(): List<Finding> =
        production.entries
            .asSequence()
            .flatMap { (origin, placement) ->
                origin
                    .reaches()
                    .filter { isLibrary(it.target) && libraryLayerOf(it) == null }
                    .map { reach ->
                        Finding(
                            kind = FindingKind.LibrariesWithoutRole,
                            module = placement.module,
                            subject = reach.target.packageName,
                            detail = "used by ${origin.topLevelName()}",
                            source = sourceLineOf(origin, placement, reach.location),
                        )
                    }
            }
            .sortedWith(compareBy({ it.module }, { it.source?.line ?: 0 }, { it.detail }))
            .distinctBy { it.subject }
            .toList()

    private fun packageCycles(): List<Finding> {
        val edges =
            production.keys
                .flatMap { origin ->
                    origin.directDependenciesFromSelf
                        .filter { dependency ->
                            isOwn(dependency.targetClass) &&
                                dependency.targetClass.packageName != origin.packageName
                        }
                        .map { origin.packageName to it.targetClass.packageName }
                }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { it.value.toSet() }
        val moduleOfPackage =
            production.entries.associate { (javaClass, placement) ->
                javaClass.packageName to placement.module
            }
        return cyclesOf(edges).map { cycle ->
            Finding(
                kind = FindingKind.PackageCycles,
                module = moduleOfPackage.getValue(cycle.first()),
                subject = cycle.joinToString(" ↔ "),
            )
        }
    }

    private fun packagesOutsideModuleRoot(): List<Finding> =
        placements.entries
            .groupBy(keySelector = { it.value.module }, valueTransform = { it.key.packageName })
            .flatMap { (module, packages) ->
                val name = modulesByPath.getValue(module).name
                val root = rootPackageOf(ownPackage = catalogue.ownPackage, moduleName = name)
                packagesOutsideRootOf(
                        ownPackage = catalogue.ownPackage,
                        moduleName = name,
                        packageNames = packages.toSet(),
                    )
                    .map { outside ->
                        Finding(
                            kind = FindingKind.PackagesOutsideModuleRoot,
                            module = module,
                            subject = outside,
                            detail = "expected under $root",
                        )
                    }
            }

    /** The source file where the package convention puts it, when it is there. */
    fun sourceLineOf(
        javaClass: JavaClass,
        placement: Placement,
        location: SourceCodeLocation,
    ): SourceLine? =
        modulesByPath[placement.module]?.sourceLineOf(javaClass, placement.sourceSet, location)
}

private fun Module.sourceLineOf(
    javaClass: JavaClass,
    sourceSet: SourceSet,
    location: SourceCodeLocation,
): SourceLine? =
    location.sourceFileName
        .takeIf { it.isNotEmpty() }
        ?.let { fileName ->
            File(
                sourceDirectoryOf(sourceSet),
                javaClass.packageName.replace(oldChar = '.', newChar = '/') + "/$fileName",
            )
        }
        ?.takeIf { it.isFile }
        ?.let { SourceLine(pathOf(it), location.lineNumber) }
