package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogueFileTest {
    @Test
    fun theCatalogueIsJsonWithComments() {
        val catalogue =
            readCatalogue(
                """
                {
                    "ownPackage": "com.example",
                    // Layers of libraries
                    "libraries": { "io.ktor": "Infrastructure" },
                    "allowedSuppressions": ["JUnitMalformedDeclaration"],
                    "testSupportModules": ["modules/test-utils"]
                }
                """
                    .trimIndent(),
            )

        assertEquals(
            Layer.Infrastructure,
            catalogue.libraryLayerOf("io.ktor.client.HttpClient", "io.ktor.client"),
        )
        assertEquals(setOf("JUnitMalformedDeclaration"), catalogue.allowedSuppressions)
        assertEquals(setOf("modules/test-utils"), catalogue.testSupportModules)
        assertEquals("com.example", catalogue.ownPackage)
    }

    @Test
    fun aCatalogueWithoutOwnPackageIsAnError() {
        assertFailsWith<IllegalArgumentException> { readCatalogue("""{ "libraries": {} }""") }
    }

    @Test
    fun aLayerNobodyHasHeardOfIsAnError() {
        assertFailsWith<IllegalArgumentException> {
            readCatalogue("""{ "ownPackage": "a", "libraries": { "io.ktor": "Adapter" } }""")
        }
    }
}
