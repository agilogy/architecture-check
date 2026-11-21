# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Gradle plugin `cat.coeli.architecture-check`: applied to a build's root project, it registers
`architectureCheck` (run by `check`), which reads the compiled classes of every Kotlin JVM subproject and fails
on any finding. Its configuration is the consuming repository's `config/architecture/catalogue.jsonc`
(`ownPackage`, `libraries`, `allowedSuppressions`, `testSupportModules`). The rules are in `FindingKind`.

## Commands

```bash
./gradlew build             # compile, tests, ktfmt and detekt (all rules, config/detekt/detekt.yml)
./gradlew ktfmtFormat
./gradlew publishToMavenLocal   # try a change in a consuming build through mavenLocal()
```

Every pull request bumps `version` in `gradle.properties` (major.minor.patch): its build fails while that
version is already published. Every push to `main` publishes it to GitHub Packages
(`.github/workflows/build.yml`).

## Code

- `domain`: the rules, on plain data. `infrastructure`: reading classes (ArchUnit, Kotlin metadata), files,
  the catalogue, the reports and the Gradle plugin and task.
- `AnalysisTest` runs the whole analysis over a made-up repository whose classes are the fixtures under
  `src/test/kotlin/com/example/{sample,app,elsewhere}`. Those fixtures are written to produce findings.
