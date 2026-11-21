package cat.coeli.architecturecheck.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyzeTest {
    private val sample = Module(path = "modules/sample", name = "sample", isApp = false)

    private class Code(
        private val module: Module,
        private val members: List<Member> = emptyList(),
        private val scripts: List<BuildScript> = emptyList(),
        private val sources: List<KotlinFile> = emptyList(),
    ) : CodeProvider {
        override fun modules(): List<Module> = listOf(module)

        override fun classes(): List<CompiledClass> = emptyList()

        override fun memberUses(): MemberUses = MemberUses(members, emptyList(), emptyList())

        override fun buildScripts(): List<BuildScript> = scripts

        override fun kotlinFiles(): List<KotlinFile> = sources
    }

    private fun analyzed(code: CodeProvider): List<Finding> =
        with(code) {
            analyze(
                catalogue =
                    Catalogue(
                        ownPackage = "com.example",
                        libraries = emptyMap(),
                        allowedSuppressions = setOf("Allowed"),
                    ),
                excluded = ExcludedPackages(setOf("com.example.fixtures")),
            )
        }

    private fun source(path: String, packageName: String, text: String) =
        KotlinFile(
            module = sample.path,
            path = "modules/sample/src/main/kotlin/$path",
            packageName = packageName,
            text = text,
        )

    @Test
    fun everyFindingOfTheCodeSortedByKindModuleSubjectAndDetail() {
        val findings =
            analyzed(
                Code(
                    module = sample,
                    members =
                        listOf(
                            Member(
                                identity = "com.example.sample.Thing.unused()",
                                owner = "com.example.sample.Thing",
                                placement = Placement(sample.path, SourceSet.Main),
                                visibility = Visibility.Public,
                                isFinal = true,
                                isExempt = false,
                                source = null,
                            ),
                        ),
                    scripts =
                        listOf(
                            BuildScript(
                                module = sample.path,
                                path = "modules/sample/build.gradle.kts",
                                text = """dependencies { implementation("com.example:lib:1.0") }""",
                            ),
                        ),
                    sources =
                        listOf(
                            source(
                                "com/example/sample/B.kt",
                                "com.example.sample",
                                """@Suppress("Zeta", "Allowed") val b = 1""",
                            ),
                            source(
                                "com/example/sample/A.kt",
                                "com.example.sample",
                                """@Suppress("Zeta") val a = 1""",
                            ),
                            source(
                                "com/example/fixtures/F.kt",
                                "com.example.fixtures",
                                """@Suppress("Zeta") val f = 1""",
                            ),
                        ),
                ),
            )

        assertEquals(
            listOf(
                "UnusedMembers com.example.sample.Thing.unused()",
                "StringDependencies implementation com.example:lib",
                "ForbiddenSuppressions Zeta modules/sample/src/main/kotlin/com/example/sample/A.kt",
                "ForbiddenSuppressions Zeta modules/sample/src/main/kotlin/com/example/sample/B.kt",
            ),
            findings.map { listOfNotNull(it.kind.name, it.subject, it.detail).joinToString(" ") },
        )
    }
}
