# architecture-check

Gradle plugin `cat.coeli.architecture-check`. It reads the compiled classes of every Kotlin JVM project of a
build and fails `check` on any finding: there is no baseline, every finding is to be fixed.

It finds:

- dependencies the layering forbids, and libraries without a layer
- `infrastructure` files that need nothing of infrastructure, and package cycles
- packages outside their module's root package
- members more visible than their uses need, and members nobody uses
- dependencies declared with strings in build scripts, and `@Suppress` the catalogue doesn't allow

## Conventions

- **Layers**, from abstract to concrete: Helper → Domain → Infrastructure. Code may depend on its own layer and
  on the layers above it. A package's layer is its deepest `domain` or `infrastructure` segment
  (`x.domain.alerts` is Domain, `x.infrastructure.postgres` Infrastructure); any other package is Helper.
- **Modules** are the build's projects with the Kotlin JVM plugin, the root one included. A module's root
  package is the catalogue's `ownPackage` followed by its Gradle name without dashes: in
  `cat.coeli.alerts`, the project `test-utils` hangs from `cat.coeli.alerts.testutils`.
- **Apps** are the projects with the `application` plugin: nobody depends on them, so `public` is as good as
  `internal` there.
- **Tests of other modules don't count** as uses of a module's `src/main`: they reach it through its
  `testFixtures`.

## Setup

The build needs Gradle 9 (tried with 9.9.2), the Kotlin DSL and a version catalog.

### Credentials

The plugin is published to GitHub Packages, which asks for a token even to read. Add a classic token with
`read:packages` to `~/.gradle/gradle.properties`:

```properties
GitHubPackagesUsername=<your GitHub user>
GitHubPackagesPassword=<token with read:packages>
```

In GitHub Actions, give the job the workflow's token:

```yaml
env:
  ORG_GRADLE_PROJECT_GitHubPackagesUsername: ${{ github.actor }}
  ORG_GRADLE_PROJECT_GitHubPackagesPassword: ${{ secrets.GITHUB_TOKEN }}
```

If the workflow sets `permissions`, add `packages: read`. Workflows of other repositories read the package
that way too (tried with a public repository of the same organisation).

### `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        // The plugin's dependencies (ArchUnit, kotlin-metadata-jvm…).
        mavenCentral()
        maven("https://maven.pkg.github.com/agilogy/architecture-check") {
            name = "GitHubPackages"
            credentials(PasswordCredentials::class)
            content { includeGroupByRegex("""cat\.coeli(\..*)?""") }
        }
    }
}
```

### `gradle/libs.versions.toml`

```toml
[versions]
architecture-check = "0.3.0"

[plugins]
architecture-check = { id = "cat.coeli.architecture-check", version.ref = "architecture-check" }
```

The published versions are listed in the
[package](https://github.com/agilogy/architecture-check/packages/3409651).

### Root `build.gradle.kts`

Apply it to the root project only: it finds the modules itself.

```kotlin
plugins {
    alias(libs.plugins.architecture.check)
}

// Optional: packages the check doesn't see, with their subpackages, such as fixtures written to break the rules.
architectureCheck { excludedPackages = setOf("com.example") }
```

### `config/architecture/catalogue.jsonc`

JSON with comments: what the check can't tell from the code.

```jsonc
{
    // Every module's root package hangs from it.
    "ownPackage": "cat.coeli.alerts",
    // The layer each library plays for the code that uses it, by package prefix, class or `class#member`;
    // the most precise entry wins.
    "libraries": {
        "kotlin": "Helper",
        "java": "Helper",
        "java.io": "Infrastructure",
        "java.lang.System": "Infrastructure",
        "io.ktor": "Infrastructure"
    },
    // Rules a @Suppress may name.
    "allowedSuppressions": [],
    // Modules made for other modules' tests: their uses from those tests count.
    "testSupportModules": ["modules/test-utils"],
    // Classes whose public members count as used from outside the build, such as a Gradle plugin's.
    "externalApi": []
}
```

Only `ownPackage` is required. A library missing from `libraries` is a finding.

## Running

`./gradlew check` runs `architectureCheck`; `./gradlew architectureCheck` runs it alone. It writes
`build/reports/architecture/report.txt`, `report.md` and `report.html`. On GitHub Actions each finding links
to its line in the commit. To show the report in the job's summary:

```yaml
- name: Architecture report
  if: ${{ !cancelled() && hashFiles('build/reports/architecture/report.md') != '' }}
  run: cat build/reports/architecture/report.md >> "$GITHUB_STEP_SUMMARY"
```
