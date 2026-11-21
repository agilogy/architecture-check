package cat.coeli.architecturecheck.domain

/**
 * What the check can't tell from the code. [ownPackage] is the one every module's root package
 * hangs from. [libraries] gives each library its layer by package prefix, class
 * (`java.lang.System`, `kotlin.random.Random$Default`) or member without parameters
 * (`java.lang.System#getenv`); the most precise entry wins. [testSupportModules] exist for other
 * modules' tests, whose uses of them count. The public members of the [externalApi] classes count
 * as used from another module, such as a Gradle plugin's, which Gradle and the builds applying it
 * use.
 */
class Catalogue(
    internal val ownPackage: String,
    private val libraries: Map<String, Layer>,
    internal val allowedSuppressions: Set<String> = emptySet(),
    internal val testSupportModules: Set<String> = emptySet(),
    internal val externalApi: Set<String> = emptySet(),
) {
    internal fun libraryLayerOf(
        className: String,
        packageName: String,
        member: String? = null,
    ): Layer? =
        (listOfNotNull(member?.let { "$className#$it" }) +
                classNestingFromInnermost(className) +
                packagePrefixesFromLongest(packageName))
            .firstNotNullOfOrNull { libraries[it] }
}

private fun classNestingFromInnermost(className: String): List<String> =
    className.split('$').let { parts ->
        (parts.size downTo 1).map { parts.take(it).joinToString("$") }
    }

private fun packagePrefixesFromLongest(packageName: String): List<String> =
    packageName.split('.').let { segments ->
        (segments.size downTo 1).map { segments.take(it).joinToString(".") }
    }
