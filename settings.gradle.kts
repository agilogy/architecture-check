pluginManagement {
    repositories {
        gradlePluginPortal()
        // This plugin's own published versions, which check this build. GitHub Packages asks for a
        // token with read:packages even to read: GitHubPackagesUsername and GitHubPackagesPassword.
        maven("https://maven.pkg.github.com/agilogy/architecture-check") {
            name = "GitHubPackages"
            credentials(PasswordCredentials::class)
            content { includeGroupByRegex("""cat\.coeli(\..*)?""") }
        }
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "architecture-check"
