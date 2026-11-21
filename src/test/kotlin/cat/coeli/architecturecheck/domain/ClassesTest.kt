package cat.coeli.architecturecheck.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ClassesTest {
    private val main = Placement("modules/sample", SourceSet.Main)

    private fun compiled(
        name: String,
        reaches: List<Reach> = emptyList(),
        dependencies: Set<String> = emptySet(),
        sourceSet: SourceSet = SourceSet.Main,
        fileName: String = name.substringAfterLast('.').substringBefore('$') + ".kt",
    ): CompiledClass {
        val packageName = name.substringBeforeLast('.')
        val sourceFile = packageName.replace('.', '/') + "/" + fileName
        return CompiledClass(
            name = name,
            packageName = packageName,
            unit = name.substringBefore('$'),
            sourceFile = sourceFile,
            sourcePath = "modules/sample/src/${sourceSet.directoryName}/kotlin/$sourceFile",
            placement = main.copy(sourceSet = sourceSet),
            reaches = reaches,
            dependencies = dependencies,
        )
    }

    private fun reach(target: String, line: Int = 1) =
        Reach(
            target = target,
            targetPackage = target.substringBeforeLast('.'),
            member = null,
            line = line,
        )

    private fun findings(vararg classes: CompiledClass): List<Finding> =
        ClassAnalysis(
                classes = classes.toList(),
                moduleNames = mapOf("modules/sample" to "sample"),
                catalogue =
                    Catalogue(
                        ownPackage = "com.example",
                        libraries =
                            mapOf(
                                "java" to Layer.Helper,
                                "java.io" to Layer.Infrastructure,
                                "kotlin" to Layer.Helper,
                            ),
                    ),
                excluded = ExcludedPackages(setOf("com.example.fixtures")),
            )
            .findings()

    private fun found(kind: FindingKind, vararg classes: CompiledClass): List<String> =
        findings(*classes)
            .filter { it.kind == kind }
            .map { listOfNotNull(it.subject, it.detail).joinToString(" ") }

    @Test
    fun aClassMayNotReachALayerBelowItsOwnOursOrALibrarys() {
        val findings =
            findings(
                compiled(
                    "com.example.sample.domain.Report",
                    reaches =
                        listOf(
                            reach("com.example.sample.infrastructure.Files", line = 3),
                            reach("java.io.File", line = 7),
                        ),
                ),
                compiled(
                    "com.example.sample.infrastructure.Files",
                    reaches = listOf(reach("java.io.File")),
                ),
            )

        assertEquals(
            listOf(
                Finding(
                    kind = FindingKind.ForbiddenDependencies,
                    module = "modules/sample",
                    subject =
                        "com.example.sample.domain.Report → com.example.sample.infrastructure",
                    detail = "Domain → Infrastructure: com.example.sample.infrastructure.Files",
                    source =
                        SourceLine(
                            "modules/sample/src/main/kotlin/com/example/sample/domain/Report.kt",
                            3,
                        ),
                ),
                Finding(
                    kind = FindingKind.ForbiddenDependencies,
                    module = "modules/sample",
                    subject = "com.example.sample.domain.Report → java.io",
                    detail = "Domain → Infrastructure: java.io.File",
                    source =
                        SourceLine(
                            "modules/sample/src/main/kotlin/com/example/sample/domain/Report.kt",
                            7,
                        ),
                ),
            ),
            findings.filter { it.kind == FindingKind.ForbiddenDependencies },
        )
    }

    @Test
    fun aClassPlacedTooLowCountsAtTheLayerItNeeds() {
        assertEquals(
            emptyList(),
            found(
                FindingKind.ForbiddenDependencies,
                compiled(
                    "com.example.sample.domain.Report",
                    reaches = listOf(reach("com.example.sample.infrastructure.Pure")),
                ),
                compiled("com.example.sample.infrastructure.Pure"),
            ),
        )
    }

    @Test
    fun testsAreReadOnlyForWhatTheyUse() {
        assertEquals(
            emptyList(),
            found(
                FindingKind.ForbiddenDependencies,
                compiled(
                    "com.example.sample.domain.ReportTest",
                    reaches = listOf(reach("java.io.File")),
                    sourceSet = SourceSet.Test,
                ),
            ),
        )
    }

    @Test
    fun anInfrastructureFileIsPlacedTooLowWhenNoneOfItsClassesNeedsInfrastructure() {
        val findings =
            findings(
                compiled(
                    "com.example.sample.infrastructure.Pure",
                    reaches = listOf(reach("kotlin.String")),
                ),
                compiled("com.example.sample.infrastructure.Pure\$Nested"),
                compiled("com.example.sample.infrastructure.Mixed"),
                compiled(
                    "com.example.sample.infrastructure.MixedKt",
                    reaches = listOf(reach("java.io.File")),
                    fileName = "Mixed.kt",
                ),
            )

        assertEquals(
            listOf(
                Finding(
                    kind = FindingKind.ClassesPlacedTooLow,
                    module = "modules/sample",
                    subject = "com/example/sample/infrastructure/Pure.kt",
                    detail = "needs nothing of Infrastructure",
                    source =
                        SourceLine(
                            "modules/sample/src/main/kotlin/com/example/sample/infrastructure/Pure.kt",
                            1,
                        ),
                ),
            ),
            findings.filter { it.kind == FindingKind.ClassesPlacedTooLow },
        )
    }

    @Test
    fun aLibraryTheCatalogueDoesNotListIsReportedOnce() {
        assertEquals(
            listOf("org.example used by com.example.sample.domain.Report"),
            found(
                FindingKind.LibrariesWithoutRole,
                compiled(
                    "com.example.sample.domain.Report",
                    reaches =
                        listOf(
                            reach("org.example.Thing"),
                            reach("com.example.fixtures.Fixture"),
                        ),
                ),
                compiled(
                    "com.example.sample.domain.Summary",
                    reaches = listOf(reach("org.example.Other")),
                ),
            ),
        )
    }

    @Test
    fun packagesOfOursThatDependOnEachOtherFormACycle() {
        assertEquals(
            listOf("com.example.sample.a ↔ com.example.sample.b"),
            found(
                FindingKind.PackageCycles,
                compiled(
                    "com.example.sample.a.A",
                    dependencies = setOf("com.example.sample.b.B", "java.lang.String"),
                ),
                compiled("com.example.sample.b.B", dependencies = setOf("com.example.sample.a.A")),
            ),
        )
    }

    @Test
    fun packagesHangFromTheirModulesRootPackage() {
        assertEquals(
            listOf("com.example.elsewhere expected under com.example.sample"),
            found(
                FindingKind.PackagesOutsideModuleRoot,
                compiled("com.example.sample.domain.Report"),
                compiled("com.example.elsewhere.Stray", sourceSet = SourceSet.Test),
            ),
        )
    }
}
