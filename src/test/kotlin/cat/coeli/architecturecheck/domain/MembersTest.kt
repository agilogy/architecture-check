package cat.coeli.architecturecheck.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MembersTest {
    private val library = Placement("library", SourceSet.Main)
    private val libraryTests = Placement("library", SourceSet.Test)
    private val libraryFixtures = Placement("library", SourceSet.TestFixtures)
    private val app = Placement("app", SourceSet.Main)
    private val appTests = Placement("app", SourceSet.Test)

    private fun member(
        identity: String,
        placement: Placement = library,
        visibility: Visibility = Visibility.Public,
        isFinal: Boolean = true,
        isExempt: Boolean = false,
    ) =
        Member(
            identity = identity,
            owner = "com.example.library.Thing",
            placement = placement,
            visibility = visibility,
            isFinal = isFinal,
            isExempt = isExempt,
            source = null,
        )

    private fun use(
        member: Member,
        origin: Placement?,
        originPackage: String = "com.example",
        isWithinPrivateReach: Boolean = false,
    ) =
        Use(
            member = member,
            origin = origin,
            originPackage = originPackage,
            isWithinPrivateReach = isWithinPrivateReach,
        )

    private fun catalogue(
        testSupportModules: Set<String> = emptySet(),
        externalApi: Set<String> = emptySet(),
    ) =
        Catalogue(
            ownPackage = "com.example",
            libraries = emptyMap(),
            testSupportModules = testSupportModules,
            externalApi = externalApi,
        )

    private fun found(
        members: List<Member>,
        uses: List<Use> = emptyList(),
        overridings: List<Overriding> = emptyList(),
        catalogue: Catalogue = catalogue(),
        excluded: Set<String> = emptySet(),
    ): List<String> =
        memberFindingsOf(
                memberUses = MemberUses(members, uses, overridings),
                catalogue = catalogue,
                excluded = ExcludedPackages(excluded),
                apps = setOf("app"),
            )
            .map { listOfNotNull(it.subject, it.detail).joinToString(" ") }

    @Test
    fun onlyAUseFromTheSameSourceSetCanBeWithinPrivateReach() {
        val private = member("private")
        val internal = member("internal")

        assertEquals(
            listOf("private public → private", "internal public → internal"),
            found(
                members = listOf(private, internal),
                uses =
                    listOf(
                        use(private, origin = library, isWithinPrivateReach = true),
                        use(internal, origin = libraryTests, isWithinPrivateReach = true),
                    ),
            ),
        )
    }

    @Test
    fun aClassOutsideTheModulesIsAnotherModule() {
        val used = member("used")

        assertEquals(emptyList(), found(members = listOf(used), uses = listOf(use(used, null))))
    }

    @Test
    fun otherModulesTestsUseOnlyTestFixturesAndTestSupportModules() {
        val main = member("main")
        val fixture = member("fixture", placement = libraryFixtures)
        val uses = listOf(use(main, origin = appTests), use(fixture, origin = appTests))

        assertEquals(
            listOf("main used only by tests of other modules"),
            found(members = listOf(main, fixture), uses = uses),
        )
        assertEquals(
            emptyList(),
            found(
                members = listOf(main, fixture),
                uses = uses,
                catalogue = catalogue(testSupportModules = setOf("library")),
            ),
        )
    }

    @Test
    fun usesFromExcludedPackagesDoNotCount() {
        val used = member("used")

        assertEquals(
            listOf("used"),
            found(
                members = listOf(used),
                uses = listOf(use(used, origin = null, originPackage = "com.example.fixtures")),
                excluded = setOf("com.example.fixtures"),
            ),
        )
    }

    @Test
    fun thePublicMembersOfTheExternalApiAreUsedFromOtherModules() {
        assertEquals(
            listOf("internal"),
            found(
                members =
                    listOf(member("public"), member("internal", visibility = Visibility.Internal)),
                catalogue = catalogue(externalApi = setOf("com.example.library.Thing")),
            ),
        )
    }

    @Test
    fun inAnAppPublicIsAsGoodAsInternal() {
        val inApp = member("inApp", placement = app)
        val inLibrary = member("inLibrary")

        assertEquals(
            listOf("inLibrary public → internal"),
            found(
                members = listOf(inApp, inLibrary),
                uses = listOf(use(inApp, origin = app), use(inLibrary, origin = library)),
            ),
        )
    }

    @Test
    fun exemptMembersAreNotJudged() {
        assertEquals(emptyList(), found(members = listOf(member("exempt", isExempt = true))))
    }

    @Test
    fun overridesAreNotJudgedAndTheirUsesCountForTheTopOfWhatTheyOverride() {
        val top = member("top", isFinal = false)
        val middle = member("middle", isFinal = false)
        val bottom = member("bottom")

        assertEquals(
            listOf("top public → internal"),
            found(
                members = listOf(top, middle, bottom),
                uses = listOf(use(bottom, origin = libraryTests)),
                overridings = listOf(Overriding(middle, top), Overriding(bottom, middle)),
            ),
        )
    }

    @Test
    fun aMemberOverriddenInAnotherModuleStaysPublic() {
        val open = member("open", isFinal = false)
        val override = member("override", placement = app)

        assertEquals(
            emptyList(),
            found(
                members = listOf(open, override),
                uses = listOf(use(open, origin = library, isWithinPrivateReach = true)),
                overridings = listOf(Overriding(override, open)),
            ),
        )
    }

    @Test
    fun anOverrideOfAMemberNotJudgedCountsForNothing() {
        val open = member("open", isFinal = false)
        val override = member("override", placement = app)

        assertEquals(
            listOf("open"),
            found(
                members = listOf(open, override),
                uses = listOf(use(override, origin = null)),
                overridings = listOf(Overriding(override, open), Overriding(override, null)),
            ),
        )
    }
}
