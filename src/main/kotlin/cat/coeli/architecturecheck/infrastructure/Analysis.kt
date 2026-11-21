package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Catalogue
import cat.coeli.architecturecheck.domain.ExcludedPackages
import cat.coeli.architecturecheck.domain.Finding
import cat.coeli.architecturecheck.domain.FindingKind
import cat.coeli.architecturecheck.domain.Layer
import cat.coeli.architecturecheck.domain.SourceLine
import cat.coeli.architecturecheck.domain.SourceSet
import cat.coeli.architecturecheck.domain.forbiddenSuppressionsOf
import cat.coeli.architecturecheck.domain.memberFindingsOf
import cat.coeli.architecturecheck.domain.stringDependenciesOf
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every finding of [modules], sorted by kind, module, subject and detail. */
internal fun analyze(
    modules: List<Module>,
    catalogue: Catalogue,
    excluded: ExcludedPackages,
): List<Finding> {
    val placements = importClasses(modules, excluded)
    val sourcePaths = sourcePathsOf(placements, modules)
    return (ClassAnalysis(
                modules = modules,
                placements = placements,
                catalogue = catalogue,
                excluded = excluded,
                sourcePaths = sourcePaths,
            )
            .findings() +
            memberFindingsOf(
                memberUses = memberUsesOf(placements, sourcePaths),
                catalogue = catalogue,
                excluded = excluded,
                apps = modules.filter { it.isApp }.map { it.path }.toSet(),
            ) +
            modules.flatMap { textFindingsOf(it, catalogue, excluded) })
        .sortedWith(compareBy({ it.kind }, { it.module }, { it.subject }, { it.detail }))
}

private fun textFindingsOf(
    module: Module,
    catalogue: Catalogue,
    excluded: ExcludedPackages,
): List<Finding> {
    val buildScript = module.buildScript
    val dependencies =
        stringDependenciesOf(buildScript.readText()).map { dependency ->
            Finding(
                kind = FindingKind.StringDependencies,
                module = module.path,
                subject = dependency.subject,
                source = SourceLine(module.pathOf(buildScript), dependency.line),
            )
        }
    val suppressions =
        SourceSet.entries
            .flatMap { sourceSet ->
                val directory = module.sourceDirectoryOf(sourceSet)
                directory.walkTopDown().filter { file ->
                    file.extension == "kt" && packageOf(file, directory) !in excluded
                }
            }
            .flatMap { file ->
                forbiddenSuppressionsOf(file.readText(), catalogue.allowedSuppressions).map {
                    suppression ->
                    Finding(
                        kind = FindingKind.ForbiddenSuppressions,
                        module = module.path,
                        subject = suppression.subject,
                        detail = module.pathOf(file),
                        source = SourceLine(module.pathOf(file), suppression.line),
                    )
                }
            }
    return dependencies + suppressions
}

// The package its directory stands for, as the Kotlin convention wants.
private fun packageOf(file: File, sourceDirectory: File): String =
    file.parentFile
        .relativeTo(sourceDirectory)
        .invariantSeparatorsPath
        .replace(oldChar = '/', newChar = '.')

@OptIn(ExperimentalSerializationApi::class) private val jsonc = Json { allowComments = true }

/**
 * JSON with comments, so the catalogue can explain itself. A layer nobody has heard of is an error,
 * not a guess.
 */
internal fun readCatalogue(text: String): Catalogue {
    val catalogue = jsonc.parseToJsonElement(text).jsonObject
    val strings = { field: String ->
        catalogue[field]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
    }
    return Catalogue(
        ownPackage =
            requireNotNull(catalogue["ownPackage"]?.run { jsonPrimitive.content }) {
                "The catalogue has no ownPackage, the package every module's root package hangs from"
            },
        libraries =
            (catalogue["libraries"] as? JsonObject).orEmpty().mapValues { (library, layer) ->
                Layer.entries.firstOrNull { it.name == layer.jsonPrimitive.content }
                    ?: throw IllegalArgumentException(
                        "Unknown layer ${layer.jsonPrimitive.content} for $library, expected one of ${Layer.entries}",
                    )
            },
        allowedSuppressions = strings("allowedSuppressions"),
        testSupportModules = strings("testSupportModules"),
        externalApi = strings("externalApi"),
    )
}
