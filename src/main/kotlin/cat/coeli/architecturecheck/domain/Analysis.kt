package cat.coeli.architecturecheck.domain

/**
 * A project of the build: [path] is its directory from the repository root, `.` for the root
 * project, and [name] its Gradle name. [isApp] for an application, which no other module depends
 * on.
 */
internal data class Module(val path: String, val name: String, val isApp: Boolean)

/** The build script of the module at [module], by its [path] from the repository root. */
internal data class BuildScript(val module: String, val path: String, val text: String)

/**
 * A Kotlin source file of the module at [module], by its [path] from the repository root, in the
 * package its directory stands for.
 */
internal data class KotlinFile(
    val module: String,
    val path: String,
    val packageName: String,
    val text: String,
)

/** The code of a build, as the analysis needs it. */
internal interface CodeProvider {
    fun modules(): List<Module>

    /** The classes compiled from every module's source sets. */
    fun classes(): List<CompiledClass>

    fun memberUses(): MemberUses

    fun buildScripts(): List<BuildScript>

    fun kotlinFiles(): List<KotlinFile>
}

/** Every finding of the code, sorted by kind, module, subject and detail. */
context(code: CodeProvider)
internal fun analyze(catalogue: Catalogue, excluded: ExcludedPackages): List<Finding> {
    val modules = code.modules()
    return (ClassAnalysis(
                classes = code.classes(),
                moduleNames = modules.associate { it.path to it.name },
                catalogue = catalogue,
                excluded = excluded,
            )
            .findings() +
            memberFindingsOf(
                memberUses = code.memberUses(),
                catalogue = catalogue,
                excluded = excluded,
                apps = modules.filter { it.isApp }.map { it.path }.toSet(),
            ) +
            stringDependenciesOf(code.buildScripts()) +
            forbiddenSuppressionsOf(code.kotlinFiles(), catalogue, excluded))
        .sortedWith(compareBy({ it.kind }, { it.module }, { it.subject }, { it.detail }))
}

private fun stringDependenciesOf(buildScripts: List<BuildScript>): List<Finding> =
    buildScripts.flatMap { buildScript ->
        stringDependenciesOf(buildScript.text).map { dependency ->
            Finding(
                kind = FindingKind.StringDependencies,
                module = buildScript.module,
                subject = dependency.subject,
                source = SourceLine(buildScript.path, dependency.line),
            )
        }
    }

private fun forbiddenSuppressionsOf(
    files: List<KotlinFile>,
    catalogue: Catalogue,
    excluded: ExcludedPackages,
): List<Finding> =
    files
        .filterNot { it.packageName in excluded }
        .flatMap { file ->
            forbiddenSuppressionsOf(file.text, catalogue.allowedSuppressions).map { suppression ->
                Finding(
                    kind = FindingKind.ForbiddenSuppressions,
                    module = file.module,
                    subject = suppression.subject,
                    detail = file.path,
                    source = SourceLine(file.path, suppression.line),
                )
            }
        }
