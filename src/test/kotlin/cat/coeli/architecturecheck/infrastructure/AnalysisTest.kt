package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Catalogue
import cat.coeli.architecturecheck.domain.ExcludedPackages
import cat.coeli.architecturecheck.domain.Finding
import cat.coeli.architecturecheck.domain.FindingKind
import cat.coeli.architecturecheck.domain.Layer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Analyzes a made-up repository whose classes are the fixtures under `com.example.sample`, `.app`
 * and `.elsewhere` of this test source set, copied to where Gradle would have compiled them.
 */
class AnalysisTest {
    private val root = createTempDirectory("architecture-check").toFile()

    private val modules: List<Module> by lazy {
        compiled("com/example/sample/domain", "modules/sample", SourceSet.Main)
        compiled("com/example/sample/infrastructure", "modules/sample", SourceSet.Main)
        compiled("com/example/sample/cycle", "modules/sample", SourceSet.Main)
        compiled("com/example/elsewhere", "modules/sample", SourceSet.Main)
        compiled("com/example/sample/tests", "modules/sample", SourceSet.Test)
        compiled("com/example/app/infrastructure", "apps/app", SourceSet.Main)
        compiled("com/example/app/domain", "apps/app", SourceSet.Main)
        compiled("com/example/app/tests", "apps/app", SourceSet.Test)
        file("build.gradle.kts", "plugins { base }")
        file(
            "modules/sample/build.gradle.kts",
            """
            dependencies {
                implementation("com.example:library:1.0")
                // implementation("com.example:commented-out:1.0")
                testImplementation(project(":app"))
            }
            """
                .trimIndent(),
        )
        file("apps/app/build.gradle.kts", "dependencies { implementation(projects.sample) }")
        file(
            "modules/sample/src/main/kotlin/com/example/sample/domain/Suppressed.kt",
            """
            @Suppress("MagicNumber", "Allowed")
            val x = 42
            """
                .trimIndent(),
        )
        file(
            "modules/sample/src/main/kotlin/com/example/elsewhere/Suppressed.kt",
            "@Suppress(\"MagicNumber\") val y = 42",
        )
        listOf(
            Module(path = ".", directory = root, isApp = false),
            Module(
                path = "modules/sample",
                directory = File(root, "modules/sample"),
                isApp = false,
            ),
            Module(path = "apps/app", directory = File(root, "apps/app"), isApp = true),
        )
    }

    private val findings: List<Finding> by lazy { analyzed() }

    private fun catalogue(externalApi: Set<String>) =
        Catalogue(
            ownPackage = "com.example",
            libraries =
                mapOf(
                    "kotlin" to Layer.Helper,
                    "kotlin.io.ConsoleKt" to Layer.Infrastructure,
                    "java.lang" to Layer.Helper,
                    "java.io" to Layer.Infrastructure,
                    "org.jetbrains.annotations" to Layer.Helper,
                ),
            allowedSuppressions = setOf("Allowed"),
            externalApi = externalApi,
        )

    private fun analyzed(
        excluded: Set<String> = emptySet(),
        externalApi: Set<String> = emptySet(),
    ): List<Finding> =
        analyze(
            modules = modules,
            catalogue = catalogue(externalApi),
            excluded = ExcludedPackages(excluded),
        )

    @AfterTest
    fun deleteRepository() {
        root.deleteRecursively()
    }

    private fun compiled(packagePath: String, module: String, sourceSet: SourceSet) {
        val classes = File(javaClass.protectionDomain.codeSource.location.toURI())
        File(classes, packagePath)
            .copyRecursively(
                File(root, "$module/build/classes/kotlin/${sourceSet.directoryName}/$packagePath"),
            )
    }

    private fun file(path: String, text: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun found(kind: FindingKind, among: List<Finding> = findings): List<String> =
        among
            .filter { it.kind == kind }
            .map { listOfNotNull(it.subject, it.detail).joinToString(" ") }

    @Test
    fun domainCodeMayNotReachInfrastructure() {
        assertEquals(
            listOf(
                "com.example.sample.domain.ReportKt → java.io Domain → Infrastructure: java.io.File",
            ),
            found(FindingKind.ForbiddenDependencies),
        )
    }

    @Test
    fun infrastructureFilesThatNeedNothingOfItArePlacedTooLow() {
        assertEquals(
            listOf(
                "com/example/sample/infrastructure/Pure.kt needs nothing of Infrastructure",
            ),
            found(FindingKind.ClassesPlacedTooLow),
        )
    }

    @Test
    fun librariesTheCatalogueDoesNotListHaveNoLayer() {
        assertEquals(
            listOf(
                "java.util.zip used by com.example.sample.infrastructure.UsesAnUncataloguedLibrary",
            ),
            found(FindingKind.LibrariesWithoutRole),
        )
    }

    @Test
    fun packagesThatDependOnEachOtherFormACycle() {
        assertEquals(
            listOf("com.example.sample.cycle.a ↔ com.example.sample.cycle.b"),
            found(FindingKind.PackageCycles),
        )
    }

    @Test
    fun packagesHangFromTheirModulesRootPackage() {
        assertEquals(
            listOf("com.example.elsewhere expected under com.example.sample"),
            found(FindingKind.PackagesOutsideModuleRoot),
        )
    }

    @Test
    fun membersNeedOnlyTheVisibilityTheirUsesNeed() {
        assertEquals(
            listOf(
                "com.example.sample.cycle.b.B() public → internal",
                "com.example.sample.domain.Report.decorated() public → private",
                "com.example.sample.domain.Report.title public → private",
                "com.example.sample.domain.ReportKt.readsAFile(String) public → internal",
                "com.example.sample.domain.ReportKt.usedByOwnTests() public → internal",
                "com.example.sample.domain.ReportKt.usedInModule() public → internal",
            ),
            found(FindingKind.MembersTooVisible),
        )
    }

    @Test
    fun membersNothingUsesButOtherModulesTestsAreUnused() {
        assertEquals(
            listOf(
                "com.example.elsewhere.Stray()",
                "com.example.sample.domain.ReportKt.neverUsed()",
                "com.example.sample.domain.ReportKt.usedByOtherModulesTests() used only by tests of other modules",
            ),
            found(FindingKind.UnusedMembers),
        )
    }

    @Test
    fun dependenciesAreDeclaredWithoutStrings() {
        assertEquals(
            listOf("implementation com.example:library", "project :app"),
            found(FindingKind.StringDependencies),
        )
    }

    @Test
    fun onlyTheCatalogueAllowsSuppressions() {
        assertEquals(
            listOf(
                "MagicNumber modules/sample/src/main/kotlin/com/example/elsewhere/Suppressed.kt",
                "MagicNumber modules/sample/src/main/kotlin/com/example/sample/domain/Suppressed.kt",
            ),
            found(FindingKind.ForbiddenSuppressions),
        )
    }

    @Test
    fun thePublicMembersOfTheExternalApiAreUsedFromOtherModules() {
        val findings = analyzed(externalApi = setOf("com.example.sample.domain.Report"))

        assertEquals(
            listOf(
                "com.example.sample.cycle.b.B() public → internal",
                "com.example.sample.domain.ReportKt.readsAFile(String) public → internal",
                "com.example.sample.domain.ReportKt.usedByOwnTests() public → internal",
                "com.example.sample.domain.ReportKt.usedInModule() public → internal",
            ),
            found(FindingKind.MembersTooVisible, findings),
        )
    }

    @Test
    fun excludedPackagesAreNotSeen() {
        val findings =
            analyzed(excluded = setOf("com.example.elsewhere", "com.example.sample.tests"))

        assertEquals(emptyList(), found(FindingKind.PackagesOutsideModuleRoot, findings))
        assertEquals(
            listOf(
                "com.example.sample.domain.ReportKt.neverUsed()",
                "com.example.sample.domain.ReportKt.usedByOtherModulesTests() used only by tests of other modules",
                "com.example.sample.domain.ReportKt.usedByOwnTests()",
            ),
            found(FindingKind.UnusedMembers, findings),
        )
        assertEquals(
            listOf(
                "MagicNumber modules/sample/src/main/kotlin/com/example/sample/domain/Suppressed.kt",
            ),
            found(FindingKind.ForbiddenSuppressions, findings),
        )
    }
}
