package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.MemberUses
import cat.coeli.architecturecheck.domain.Overriding
import cat.coeli.architecturecheck.domain.Placement
import cat.coeli.architecturecheck.domain.SourceSet
import cat.coeli.architecturecheck.domain.Use
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier

/**
 * The members of `src/main` and `src/testFixtures`, their uses from any source set and what they
 * override, as the compiled classes tell. [sourcePaths] has each class's source file from the
 * repository root, when it is where the package convention puts it.
 */
internal fun memberUsesOf(
    placements: Map<JavaClass, Placement>,
    sourcePaths: Map<JavaClass, String>,
): MemberUses {
    val metadata = placements.keys.associateWith { it.kotlinMetadata() }
    val declarations =
        placements
            .filterValues { it.sourceSet != SourceSet.Test }
            .flatMap { (javaClass, placement) ->
                Declarations(
                        owner = javaClass,
                        placement = placement,
                        metadata = metadata,
                        sourcePath = sourcePaths[javaClass],
                    )
                    .all()
            }
    val byHandle =
        declarations
            .flatMap { declaration -> declaration.handles.map { it to declaration.member } }
            .toMap()
    return MemberUses(
        members = declarations.map { it.member },
        uses = declarations.flatMap { usesOf(it, placements) },
        overridings =
            declarations.flatMap { declaration ->
                declaration.overridden().map {
                    Overriding(overrider = declaration.member, overridden = byHandle[it])
                }
            },
    )
}

// Its own bytecode members calling each other, as a `$default` calls the function, don't count.
private fun usesOf(declaration: Declaration, placements: Map<JavaClass, Placement>): List<Use> =
    declaration.handles
        .asSequence()
        .flatMap { it.accessesToSelf }
        .filterNot { it.origin in declaration.handles }
        .map { it.originOwner }
        .distinct()
        .map { origin ->
            Use(
                member = declaration.member,
                origin = placements[origin],
                originPackage = origin.packageName,
                isWithinPrivateReach = declaration.site.reachesPrivately(origin),
            )
        }
        .toList()

private fun Declaration.overridden(): List<JavaMethod> =
    handles.filterIsInstance<JavaMethod>().flatMap { it.overridden() }

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
