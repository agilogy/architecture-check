package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.CATALOGUE_FILE
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin

private val compilations = setOf("classes", "testFixturesClasses", "testClasses")

/**
 * Applied to the root project: registers `architectureCheck`, which `check` runs, over every
 * subproject with the Kotlin JVM plugin.
 */
class ArchitectureCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(BasePlugin::class.java)
        val architectureCheck =
            project.tasks.register("architectureCheck", ArchitectureCheck::class.java) {
                it.configureFor(project)
            }
        project.subprojects.forEach { module ->
            module.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                architectureCheck.configure { task -> task.analyse(module) }
            }
        }
        project.tasks.named("check") { it.dependsOn(architectureCheck) }
    }
}

private fun ArchitectureCheck.configureFor(project: Project) {
    val providers = project.providers
    group = "verification"
    description =
        "Checks the layering, package names, member visibility and build scripts of every module"
    repositoryRoot.set(project.layout.projectDirectory)
    catalogue.set(project.layout.projectDirectory.file(CATALOGUE_FILE))
    analysedFiles.from(project.layout.projectDirectory.file("build.gradle.kts"))
    reportDirectory.set(project.layout.buildDirectory.dir("reports/architecture"))
    sourceLinkBase.set(
        providers
            .environmentVariable("GITHUB_SERVER_URL")
            .zip(providers.environmentVariable("GITHUB_REPOSITORY")) { server, repository ->
                "$server/$repository"
            }
            .zip(providers.environmentVariable("GITHUB_SHA")) { repository, commit ->
                "$repository/blob/$commit"
            },
    )
}

private fun ArchitectureCheck.analyse(module: Project) {
    dependsOn(module.tasks.matching { it.name in compilations })
    analysedFiles.from(
        module.layout.buildDirectory.dir("classes"),
        module.layout.projectDirectory.dir("src"),
        module.layout.projectDirectory.file("build.gradle.kts"),
    )
}
