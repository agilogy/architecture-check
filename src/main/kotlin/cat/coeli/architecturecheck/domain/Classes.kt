package cat.coeli.architecturecheck.domain

/**
 * A class compiled from one of the modules' source sets. Nested, local and anonymous classes,
 * lambdas included, are judged as part of the top-level class around them, their [unit].
 * [sourceFile] is where the package convention puts its source; [sourcePath] the same from the
 * repository root, when it is there. [dependencies] are the names of the classes it depends on.
 */
internal data class CompiledClass(
    val name: String,
    val packageName: String,
    val unit: String,
    val sourceFile: String,
    val sourcePath: String?,
    val placement: Placement,
    val reaches: List<Reach>,
    val dependencies: Set<String>,
)

/**
 * A class reached at [line] of the source of the class reaching it, and the [member] reached when
 * it is reached through a call or a field access.
 */
internal data class Reach(
    val target: String,
    val targetPackage: String,
    val member: String?,
    val line: Int,
)

/**
 * The analysis of the classes of `src/main` and `src/testFixtures`; tests are read only for what
 * they use. [moduleNames] has each module's Gradle name by its path.
 */
internal class ClassAnalysis(
    classes: List<CompiledClass>,
    private val moduleNames: Map<String, String>,
    private val catalogue: Catalogue,
    private val excluded: ExcludedPackages,
) {
    private val seen = classes.filterNot { it.packageName in excluded }
    private val classesByName = seen.associateBy { it.name }
    private val ownPackages = seen.map { it.packageName }.toSet()
    private val production = seen.filter { it.placement.sourceSet != SourceSet.Test }

    private val neededLayers: Map<String, Layer> by lazy {
        val reachesByUnit =
            production
                .groupBy { it.unit }
                .mapValues { (_, unitClasses) -> unitClasses.flatMap { it.reaches } }
        neededLayersOf(
            libraryLayers =
                reachesByUnit
                    .mapNotNull { (unit, reaches) ->
                        reaches
                            .filter { isLibrary(it) }
                            .mapNotNull { libraryLayerOf(it) }
                            .maxOrNull()
                            ?.let { unit to it }
                    }
                    .toMap(),
            dependencies =
                reachesByUnit.mapValues { (unit, reaches) ->
                    reaches.mapNotNull { classesByName[it.target]?.unit }.toSet() - unit
                },
        )
    }

    // A source file moves as a whole, so it needs what the most demanding of its classes needs.
    private val neededLayersByFile: Map<String, Layer> by lazy {
        production
            .groupBy { it.sourceFile }
            .mapValues { (_, fileClasses) ->
                fileClasses.maxOf { neededLayers[it.unit] ?: Layer.Helper }
            }
    }

    // A class of ours the import left out, such as one Kotlin inlined away, is nobody's.
    private fun isLibrary(reach: Reach) =
        reach.targetPackage.isNotEmpty() &&
            reach.targetPackage !in ownPackages &&
            reach.targetPackage !in excluded

    private fun libraryLayerOf(reach: Reach): Layer? =
        catalogue.libraryLayerOf(
            className = reach.target,
            packageName = reach.targetPackage,
            member = reach.member,
        )

    // The layer a class of ours counts at: the one it needs when it is placed too low, as that is
    // reported apart.
    private fun ownLayerOf(target: CompiledClass): Layer {
        val declared = layerOf(target.packageName)
        val needed = neededLayersByFile[target.sourceFile] ?: declared
        return if (isPlacedTooLow(declared, needed)) needed else declared
    }

    fun findings(): List<Finding> =
        forbiddenDependencies() +
            classesPlacedTooLow() +
            librariesWithoutRole() +
            packageCycles() +
            packagesOutsideModuleRoot()

    private fun forbiddenDependencies(): List<Finding> =
        production
            .asSequence()
            .flatMap { origin ->
                val originLayer = layerOf(origin.packageName)
                origin.reaches.mapNotNull { reach ->
                    val own = classesByName[reach.target]
                    val targetLayer =
                        when {
                            own != null -> ownLayerOf(own)
                            isLibrary(reach) -> libraryLayerOf(reach)
                            else -> null
                        }
                    targetLayer
                        ?.takeUnless { originLayer.mayDependOn(it) }
                        ?.let { forbiddenLayer ->
                            Finding(
                                kind = FindingKind.ForbiddenDependencies,
                                module = origin.placement.module,
                                subject = "${origin.unit} → ${reach.targetPackage}",
                                detail = "$originLayer → $forbiddenLayer: ${reach.target}",
                                source = sourceLineOf(origin, reach.line),
                            )
                        }
                }
            }
            .sortedWith(compareBy({ it.source?.line ?: 0 }, { it.detail }))
            .distinctBy { it.module to it.subject }
            .toList()

    // One finding per source file, which is what moves.
    private fun classesPlacedTooLow(): List<Finding> =
        production
            .distinctBy { it.sourceFile }
            .mapNotNull { compiled ->
                Finding(
                        kind = FindingKind.ClassesPlacedTooLow,
                        module = compiled.placement.module,
                        subject = compiled.sourceFile,
                        detail = "needs nothing of ${Layer.Infrastructure}",
                        source = sourceLineOf(compiled, 1),
                    )
                    .takeIf {
                        isPlacedTooLow(
                            declared = layerOf(compiled.packageName),
                            needed = neededLayersByFile.getValue(compiled.sourceFile),
                        )
                    }
            }

    // Once for the whole repository, wherever the library is used from.
    private fun librariesWithoutRole(): List<Finding> =
        production
            .asSequence()
            .flatMap { origin ->
                origin.reaches
                    .filter { isLibrary(it) && libraryLayerOf(it) == null }
                    .map { reach ->
                        Finding(
                            kind = FindingKind.LibrariesWithoutRole,
                            module = origin.placement.module,
                            subject = reach.targetPackage,
                            detail = "used by ${origin.unit}",
                            source = sourceLineOf(origin, reach.line),
                        )
                    }
            }
            .sortedWith(compareBy({ it.module }, { it.source?.line ?: 0 }, { it.detail }))
            .distinctBy { it.subject }
            .toList()

    private fun packageCycles(): List<Finding> {
        val edges =
            production
                .flatMap { origin ->
                    origin.dependencies
                        .asSequence()
                        .mapNotNull { classesByName[it]?.packageName }
                        .filter { it != origin.packageName }
                        .map { origin.packageName to it }
                        .toList()
                }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { it.value.toSet() }
        val moduleOfPackage = production.associate { it.packageName to it.placement.module }
        return cyclesOf(edges).map { cycle ->
            Finding(
                kind = FindingKind.PackageCycles,
                module = moduleOfPackage.getValue(cycle.first()),
                subject = cycle.joinToString(" ↔ "),
            )
        }
    }

    private fun packagesOutsideModuleRoot(): List<Finding> =
        seen
            .groupBy(
                keySelector = { it.placement.module },
                valueTransform = { it.packageName },
            )
            .flatMap { (module, packages) ->
                val name = moduleNames.getValue(module)
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

    private fun sourceLineOf(compiled: CompiledClass, line: Int): SourceLine? =
        compiled.sourcePath?.let { SourceLine(it, line) }
}
