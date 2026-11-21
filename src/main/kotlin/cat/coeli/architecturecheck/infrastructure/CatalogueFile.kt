package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Catalogue
import cat.coeli.architecturecheck.domain.Layer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
