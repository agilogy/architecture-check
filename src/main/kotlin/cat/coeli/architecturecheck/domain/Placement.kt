package cat.coeli.architecturecheck.domain

/** The source sets whose classes are read; [Test]'s only for what they use of the others. */
enum class SourceSet(internal val directoryName: String) {
    Main("main"),
    TestFixtures("testFixtures"),
    Test("test"),
}

/** Where a class was compiled from. */
internal data class Placement(val module: String, val sourceSet: SourceSet)
