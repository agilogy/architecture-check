package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Catalogue
import cat.coeli.architecturecheck.domain.Finding
import cat.coeli.architecturecheck.domain.FindingKind
import cat.coeli.architecturecheck.domain.Layer
import cat.coeli.architecturecheck.domain.SourceLine
import cat.coeli.architecturecheck.domain.forbiddenSuppressionsOf
import cat.coeli.architecturecheck.domain.stringDependenciesOf
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every finding of the repository at [root], sorted by kind, module and subject. */
internal fun analyze(root: File, catalogue: Catalogue): List<Finding> {
    val modules = discoverModules(root)
    val placements = importClasses(modules)
    val classes = ClassAnalysis(modules, placements, catalogue)
    return (classes.findings() +
            memberFindingsOf(
                placements = placements,
                modules = modules,
                catalogue = catalogue,
                sourceLineOf = classes::sourceLineOf,
            ) +
            modules.flatMap { textFindingsOf(it, catalogue) })
        .sortedWith(compareBy({ it.kind }, { it.module }, { it.subject }))
}

private fun textFindingsOf(module: Module, catalogue: Catalogue): List<Finding> {
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
        if (module.path == ".") {
            emptyList()
        } else {
            SourceSet.entries
                .flatMap { sourceSet ->
                    module.sourceDirectoryOf(sourceSet).walkTopDown().filter {
                        it.extension == "kt"
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
        }
    return dependencies + suppressions
}

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
    )
}
