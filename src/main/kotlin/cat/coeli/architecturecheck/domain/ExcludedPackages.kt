package cat.coeli.architecturecheck.domain

/**
 * Packages, each with its subpackages, whose classes the check doesn't see: they get no findings,
 * their uses of other classes don't count and nothing reaching them counts as a dependency.
 */
class ExcludedPackages(private val packageNames: Set<String>) {
    internal operator fun contains(packageName: String): Boolean = packageNames.any {
        packageName == it || packageName.startsWith("$it.")
    }
}
