package cat.coeli.architecturecheck.domain

/**
 * A function, property or constructor of `src/main` or `src/testFixtures`, as Kotlin declares it.
 * [owner] is the class it compiles into. [visibility] is the effective one, narrowed by the classes
 * around.
 */
internal data class Member(
    val identity: String,
    val owner: String,
    val placement: Placement,
    val visibility: Visibility,
    val isFinal: Boolean,
    /** Not judged, although its uses still count for what it overrides. */
    val isExempt: Boolean,
    val source: SourceLine?,
)

/**
 * A use of [member] by a class of [originPackage] compiled from [origin], `null` when it is none of
 * the modules' source sets. [isWithinPrivateReach] when `private` lets that class use it.
 */
internal data class Use(
    val member: Member,
    val origin: Placement?,
    val originPackage: String,
    val isWithinPrivateReach: Boolean,
)

/** [overrider] overrides [overridden], `null` when that is a member not judged, as a library's. */
internal data class Overriding(val overrider: Member, val overridden: Member?)

/** The [members] of a repository, their [uses] and their [overridings]. */
internal data class MemberUses(
    val members: List<Member>,
    val uses: List<Use>,
    val overridings: List<Overriding>,
)

/**
 * The members whose uses, from any source set, need less visibility or none. [apps] are the paths
 * of the modules that are apps.
 */
internal fun memberFindingsOf(
    memberUses: MemberUses,
    catalogue: Catalogue,
    excluded: ExcludedPackages,
    apps: Set<String>,
): List<Finding> {
    val overridden =
        memberUses.overridings.groupBy(
            keySelector = { it.overrider },
            valueTransform = { it.overridden },
        )
    val usesByRoot =
        usesByRootOf(
            memberUses = memberUses,
            overridden = overridden,
            catalogue = catalogue,
            excluded = excluded,
        )
    val overridersByRoot = overridersByRootOf(overridden)
    return memberUses.members
        .filter { !it.isExempt && it !in overridden }
        .mapNotNull { member ->
            verdictOf(
                    current = member.visibility,
                    uses = usesByRoot[member].orEmpty(),
                    overriders = overridersByRoot[member].orEmpty(),
                    canBePrivate = member.isFinal,
                    isApp = member.placement.module in apps,
                )
                ?.let { verdict -> findingOf(member, verdict) }
        }
}

// The public members of the external API are used from other modules, whether we see it or not.
private fun usesByRootOf(
    memberUses: MemberUses,
    overridden: Map<Member, List<Member?>>,
    catalogue: Catalogue,
    excluded: ExcludedPackages,
): Map<Member, Set<UseScope>> {
    val scopes =
        memberUses.uses
            .filterNot { it.originPackage in excluded }
            .groupBy(keySelector = { it.member }, valueTransform = { scopeOf(it, catalogue) })
    return memberUses.members
        .flatMap { member ->
            val memberScopes =
                scopes[member].orEmpty().toSet() +
                    setOfNotNull(
                        UseScope.OtherModule.takeIf {
                            member.visibility == Visibility.Public &&
                                member.owner in catalogue.externalApi
                        },
                    )
            rootsOf(member, overridden).map { it to memberScopes }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, scopes) -> scopes.flatten().toSet() }
}

private fun scopeOf(use: Use, catalogue: Catalogue): UseScope {
    val declared = use.member.placement
    return when {
        use.origin == null -> UseScope.OtherModule
        use.origin.module != declared.module ->
            if (
                use.origin.sourceSet == SourceSet.Test &&
                    declared.sourceSet == SourceSet.Main &&
                    declared.module !in catalogue.testSupportModules
            ) {
                UseScope.OtherModuleTest
            } else {
                UseScope.OtherModule
            }
        use.origin == declared && use.isWithinPrivateReach -> UseScope.Declaration
        else -> UseScope.Module
    }
}

private fun overridersByRootOf(overridden: Map<Member, List<Member?>>): Map<Member, Set<UseScope>> =
    overridden.keys
        .flatMap { overrider ->
            rootsOf(overrider, overridden).map { root ->
                root to
                    if (overrider.placement.module == root.placement.module) {
                        UseScope.Module
                    } else {
                        UseScope.OtherModule
                    }
            }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, scopes) -> scopes.toSet() }

// The members an override stands for: those at the top of what it overrides. One overriding a
// member not judged stands for none, so its uses count for nothing we judge.
private fun rootsOf(member: Member, overridden: Map<Member, List<Member?>>): Set<Member> =
    overridden[member].orEmpty().let { targets ->
        when {
            targets.isEmpty() -> setOf(member)
            targets.any { it == null } -> emptySet()
            else -> targets.filterNotNull().flatMap { rootsOf(it, overridden) }.toSet()
        }
    }

private fun findingOf(member: Member, verdict: Verdict): Finding =
    when (verdict) {
        is Verdict.TooVisible ->
            Finding(
                kind = FindingKind.MembersTooVisible,
                module = member.placement.module,
                subject = member.identity,
                detail = "${verdict.current.keyword} → ${verdict.needed.keyword}",
                source = member.source,
            )
        is Verdict.Unused ->
            Finding(
                kind = FindingKind.UnusedMembers,
                module = member.placement.module,
                subject = member.identity,
                detail =
                    if (verdict.isUsedOnlyByTestsOfOtherModules) {
                        "used only by tests of other modules"
                    } else {
                        null
                    },
                source = member.source,
            )
    }
