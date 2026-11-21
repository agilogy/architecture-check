package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.CATALOGUE_FILE
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.SetProperty

private val compilations = setOf("classes", "testFixturesClasses", "testClasses")

/** `architectureCheck { excludedPackages = setOf("com.example") }` in the root build script. */
interface ArchitectureCheckExtension {
    /** Packages, with their subpackages, the check doesn't see, such as fixtures of tests. */
    val excludedPackages: SetProperty<String>
}

/**
 * Applied to the root project: registers `architectureCheck`, which `check` runs, over the root
 * build script and every project of the build with the Kotlin JVM plugin, the root one included. A
 * project with the `application` plugin is an app.
 */
class ArchitectureCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(BasePlugin::class.java)
        val extension =
            project.extensions.create("architectureCheck", ArchitectureCheckExtension::class.java)
        val architectureCheck =
            project.tasks.register("architectureCheck", ArchitectureCheck::class.java) { task ->
                task.configureFor(project)
                task.excludedPackages.set(extension.excludedPackages)
                task.modules.put(".", project.isApp())
            }
        project.allprojects.forEach { module ->
            module.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                architectureCheck.configure { task -> task.analyse(module, project) }
            }
        }
        project.tasks.named("check") { it.dependsOn(architectureCheck) }
    }
}

// A provider, as the application plugin may come after the Kotlin one.
private fun Project.isApp() = provider { pluginManager.hasPlugin("application") }

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

private fun ArchitectureCheck.analyse(module: Project, root: Project) {
    modules.put(
        module.projectDir.relativeTo(root.projectDir).invariantSeparatorsPath.ifEmpty { "." },
        module.isApp(),
    )
    dependsOn(module.tasks.matching { it.name in compilations })
    analysedFiles.from(
        module.layout.buildDirectory.dir("classes"),
        module.layout.projectDirectory.dir("src"),
        module.layout.projectDirectory.file("build.gradle.kts"),
    )
}
