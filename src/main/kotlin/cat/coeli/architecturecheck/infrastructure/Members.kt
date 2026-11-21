package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Catalogue
import cat.coeli.architecturecheck.domain.ExcludedPackages
import cat.coeli.architecturecheck.domain.Finding
import cat.coeli.architecturecheck.domain.FindingKind
import cat.coeli.architecturecheck.domain.SourceLine
import cat.coeli.architecturecheck.domain.UseScope
import cat.coeli.architecturecheck.domain.Verdict
import cat.coeli.architecturecheck.domain.Visibility
import cat.coeli.architecturecheck.domain.verdictOf
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaMember
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.SourceCodeLocation

/**
 * The members of `src/main` and `src/testFixtures` whose uses, from any source set, need less
 * visibility or none.
 */
internal fun memberFindingsOf(
    placements: Map<JavaClass, Placement>,
    modules: List<Module>,
    catalogue: Catalogue,
    excluded: ExcludedPackages,
    sourceLineOf: (JavaClass, Placement, SourceCodeLocation) -> SourceLine?,
): List<Finding> {
    val metadata = placements.keys.associateWith { it.kotlinMetadata() }
    val declarations =
        placements
            .filterValues { it.sourceSet != SourceSet.Test }
            .flatMap { (javaClass, placement) ->
                Declarations(javaClass, placement, metadata).all()
            }
    val byHandle =
        declarations
            .flatMap { declaration -> declaration.handles.map { it to declaration } }
            .toMap()
    val apps = modules.filter { it.isApp }.map { it.path }.toSet()
    val usesByRoot =
        usesByRootOf(
            declarations = declarations,
            byHandle = byHandle,
            placements = placements,
            catalogue = catalogue,
            excluded = excluded,
        )
    val overridersByRoot = overridersByRootOf(declarations, byHandle)
    return declarations
        .filter { !it.isExempt && it.overridden().isEmpty() }
        .mapNotNull { declaration ->
            verdictOf(
                    current = declaration.visibility,
                    uses = usesByRoot[declaration].orEmpty(),
                    overriders = overridersByRoot[declaration].orEmpty(),
                    canBePrivate = declaration.isFinal,
                    isApp = declaration.site.placement.module in apps,
                )
                ?.let { verdict -> findingOf(declaration, verdict, sourceLineOf) }
        }
}

private fun usesByRootOf(
    declarations: List<Declaration>,
    byHandle: Map<JavaMember, Declaration>,
    placements: Map<JavaClass, Placement>,
    catalogue: Catalogue,
    excluded: ExcludedPackages,
): Map<Declaration, Set<UseScope>> =
    declarations
        .flatMap { declaration ->
            val uses =
                usesOf(
                    declaration = declaration,
                    placements = placements,
                    catalogue = catalogue,
                    excluded = excluded,
                )
            rootsOf(declaration, byHandle).map { it to uses }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, uses) -> uses.flatten().toSet() }

private fun overridersByRootOf(
    declarations: List<Declaration>,
    byHandle: Map<JavaMember, Declaration>,
): Map<Declaration, Set<UseScope>> =
    declarations
        .filter { it.overridden().isNotEmpty() }
        .flatMap { override ->
            rootsOf(override, byHandle).map { root ->
                root to
                    if (override.site.placement.module == root.site.placement.module) {
                        UseScope.Module
                    } else {
                        UseScope.OtherModule
                    }
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, scopes) -> scopes.toSet() }

private fun findingOf(
    declaration: Declaration,
    verdict: Verdict,
    sourceLineOf: (JavaClass, Placement, SourceCodeLocation) -> SourceLine?,
): Finding {
    val source =
        declaration.handles
            .filterIsInstance<JavaCodeUnit>()
            .firstOrNull { it.sourceCodeLocation.lineNumber > 0 }
            ?.let { codeUnit ->
                sourceLineOf(
                    declaration.site.owner,
                    declaration.site.placement,
                    codeUnit.sourceCodeLocation,
                )
            }
    return when (verdict) {
        is Verdict.TooVisible ->
            Finding(
                kind = FindingKind.MembersTooVisible,
                module = declaration.site.placement.module,
                subject = declaration.identity,
                detail = "${verdict.current.keyword} → ${verdict.needed.keyword}",
                source = source,
            )
        is Verdict.Unused ->
            Finding(
                kind = FindingKind.UnusedMembers,
                module = declaration.site.placement.module,
                subject = declaration.identity,
                detail =
                    if (verdict.isUsedOnlyByTestsOfOtherModules) {
                        "used only by tests of other modules"
                    } else {
                        null
                    },
                source = source,
            )
    }
}

// Its own bytecode members calling each other, as a `$default` calls the function, don't count.
private fun usesOf(
    declaration: Declaration,
    placements: Map<JavaClass, Placement>,
    catalogue: Catalogue,
    excluded: ExcludedPackages,
): Set<UseScope> =
    declaration.handles
        .asSequence()
        .flatMap { it.accessesToSelf }
        .filterNot { it.origin in declaration.handles || it.originOwner.packageName in excluded }
        .map { access ->
            val origin = access.originOwner
            val placement = placements[origin]
            val declared = declaration.site.placement
            when {
                placement == null -> UseScope.OtherModule
                placement.module != declared.module ->
                    if (
                        placement.sourceSet == SourceSet.Test &&
                            declared.sourceSet == SourceSet.Main &&
                            declared.module !in catalogue.testSupportModules
                    ) {
                        UseScope.OtherModuleTest
                    } else {
                        UseScope.OtherModule
                    }
                placement == declared && declaration.site.reachesPrivately(origin) ->
                    UseScope.Declaration
                else -> UseScope.Module
            }
        }
        .toSet() +
        setOfNotNull(
            UseScope.OtherModule.takeIf {
                declaration.visibility == Visibility.Public &&
                    declaration.site.owner.name in catalogue.externalApi
            },
        )

private fun Declaration.overridden(): List<JavaMethod> =
    handles.filterIsInstance<JavaMethod>().flatMap { it.overridden() }

// The declarations an override stands for: those at the top of what it overrides. One overriding a
// library's member stands for none, so its uses count for nothing we judge.
private fun rootsOf(
    declaration: Declaration,
    byHandle: Map<JavaMember, Declaration>,
): Set<Declaration> =
    declaration
        .overridden()
        .map { byHandle[it] }
        .let { overridden ->
            when {
                overridden.isEmpty() -> setOf(declaration)
                overridden.any { it == null } -> emptySet()
                else -> overridden.filterNotNull().flatMap { rootsOf(it, byHandle) }.toSet()
            }
        }

// By name and number of parameters, as generics erase differently on each side: a false match only
// spares a member.
private fun JavaMethod.overridden(): List<JavaMethod> =
    if (JavaModifier.STATIC in modifiers || JavaModifier.PRIVATE in modifiers) {
        emptyList()
    } else {
        (owner.allRawSuperclasses + owner.allRawInterfaces).flatMap { supertype ->
            supertype.methods.filter { candidate ->
                candidate.name == name &&
                    candidate.rawParameterTypes.size == rawParameterTypes.size &&
                    JavaModifier.PRIVATE !in candidate.modifiers &&
                    JavaModifier.STATIC !in candidate.modifiers
            }
        }
    }
