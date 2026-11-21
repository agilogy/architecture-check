import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
}

// Gradle 9 runs on Java 17 or later: the plugin loads in any of them.
private val gradleJavaVersion = 17

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(gradleJavaVersion.toString())
        freeCompilerArgs.add("-Xjdk-release=$gradleJavaVersion")
        allWarningsAsErrors = true
    }
}

tasks.withType<JavaCompile>().configureEach { options.release = gradleJavaVersion }

ktfmt { kotlinLangStyle() }

detekt {
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom(file("config/detekt/detekt.yml"))
}

// The detekt tasks with type resolution (detektMain, detektTest…) apply more rules than the plain
// `detekt` task, which they replace in `check`.
// The fixtures AnalysisTest analyses are written to break the rules.
tasks.withType<Detekt>().configureEach { exclude("cat/coeli/alerts/**") }

tasks.named("check") {
    setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name == "detekt" })
    dependsOn(
        tasks.withType<Detekt>().matching { it.name != "detekt" && !it.name.endsWith("SourceSet") },
    )
}

dependencies {
    implementation(libs.archunit)
    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

gradlePlugin {
    plugins {
        create("architectureCheck") {
            id = "cat.coeli.architecture-check"
            implementationClass =
                "cat.coeli.architecturecheck.infrastructure.ArchitectureCheckPlugin"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/agilogy/architecture-check")
            credentials(PasswordCredentials::class)
        }
    }
}
