package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.Visibility
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaMember
import com.tngtech.archunit.core.domain.JavaMethod
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.MemberKind
import kotlin.metadata.Modality
import kotlin.metadata.Visibility as KmVisibility
import kotlin.metadata.isConst
import kotlin.metadata.isData
import kotlin.metadata.isInline
import kotlin.metadata.isSecondary
import kotlin.metadata.isValue
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility

/**
 * A function, property or constructor as Kotlin declares it, with the bytecode members it compiled
 * to ([handles]): a use of any of them is a use of the declaration. [visibility] is the effective
 * one, narrowed by the classes around.
 */
internal data class Declaration(
    val identity: String,
    val site: DeclarationSite,
    val visibility: Visibility,
    val isFinal: Boolean,
    /** Not judged, although its uses still count for what it overrides. */
    val isExempt: Boolean,
    val handles: Set<JavaMember>,
)

/**
 * The class file a declaration is in, the visibility of its class narrowed by the classes around
 * ([classVisibility]), and which classes `private` lets use it ([reachesPrivately]).
 */
internal class DeclarationSite(
    val owner: JavaClass,
    val placement: Placement,
    private val classVisibility: Visibility,
    val reachesPrivately: (JavaClass) -> Boolean,
) {
    /** `protected` and local members are not judged. */
    fun declaration(
        identity: String,
        visibility: KmVisibility,
        isFinal: Boolean,
        isExempt: Boolean,
        handles: List<JavaMember>,
    ): Declaration? =
        visibility.toVisibility()?.let { memberVisibility ->
            Declaration(
                identity = identity,
                site = this,
                visibility = minOf(a = memberVisibility, b = classVisibility),
                isFinal = isFinal,
                isExempt = isExempt,
                handles = handles.toSet(),
            )
        }
}

/** The declarations of one class file, as its Kotlin metadata lists them. */
internal class Declarations(
    private val owner: JavaClass,
    private val placement: Placement,
    private val metadata: Map<JavaClass, KotlinClassMetadata?>,
) {
    private val methods = owner.methods.associateBy { it.name + it.descriptor }

    fun all(): List<Declaration> =
        when (val classMetadata = metadata[owner]) {
            is KotlinClassMetadata.Class -> ofClass(classMetadata.kmClass)
            is KotlinClassMetadata.FileFacade -> ofFile(classMetadata.kmPackage)
            is KotlinClassMetadata.MultiFileClassPart -> ofFile(classMetadata.kmPackage)
            else -> emptyList()
        }

    private fun ofFile(container: KmDeclarationContainer): List<Declaration> =
        membersOf(
            container = container,
            site =
                DeclarationSite(
                    owner = owner,
                    placement = placement,
                    classVisibility = Visibility.Public,
                    reachesPrivately = { origin ->
                        origin.packageName == owner.packageName &&
                            origin.sourceFileName() == owner.sourceFileName()
                    },
                ),
            kmClass = null,
            fieldOwners = listOf(owner),
        )

    // An annotation has nothing to narrow, and what the serialization plugin generates is used by
    // reflection.
    private fun ofClass(kmClass: KmClass): List<Declaration> {
        val visibility = effectiveVisibility()
        val isSkipped =
            kmClass.kind == ClassKind.ANNOTATION_CLASS ||
                kmClass.kind == ClassKind.ENUM_ENTRY ||
                owner.name.endsWith("\$\$serializer")
        return if (visibility == null || isSkipped) {
            emptyList()
        } else {
            val enclosing = owner.enclosingClass.orElse(null)
            val scope =
                if (kmClass.kind == ClassKind.COMPANION_OBJECT && enclosing != null) {
                    enclosing
                } else {
                    owner
                }
            val site =
                DeclarationSite(
                    owner = owner,
                    placement = placement,
                    classVisibility = visibility,
                    reachesPrivately = { origin ->
                        generateSequence(origin) { it.enclosingClass.orElse(null) }
                            .any { it == scope }
                    },
                )
            membersOf(
                container = kmClass,
                site = site,
                kmClass = kmClass,
                fieldOwners = listOfNotNull(owner, enclosing),
            ) + constructorsOf(kmClass, site)
        }
    }

    // The narrowest of the class's visibility and those around it; `null` for a local class or one
    // Kotlin didn't make.
    private fun effectiveVisibility(): Visibility? =
        generateSequence(owner) { it.enclosingClass.orElse(null) }
            .map { javaClass ->
                (metadata[javaClass] as? KotlinClassMetadata.Class)?.run {
                    kmClass.visibility.toVisibility()
                }
            }
            .fold<Visibility?, Visibility?>(Visibility.Public) { narrowest, visibility ->
                if (narrowest == null || visibility == null) {
                    null
                } else {
                    minOf(a = narrowest, b = visibility)
                }
            }

    private fun membersOf(
        container: KmDeclarationContainer,
        site: DeclarationSite,
        kmClass: KmClass?,
        fieldOwners: List<JavaClass>,
    ): List<Declaration> =
        container.functions
            .filter { it.kind == MemberKind.DECLARATION }
            .mapNotNull { functionOf(it, kmClass, site) } +
            container.properties
                .filter { it.kind == MemberKind.DECLARATION }
                .mapNotNull { property ->
                    propertyOf(
                        property = property,
                        kmClass = kmClass,
                        site = site,
                        fieldOwners = fieldOwners,
                    )
                }

    private fun functionOf(
        function: KmFunction,
        kmClass: KmClass?,
        site: DeclarationSite,
    ): Declaration? {
        val method = function.signature?.let { methods[it.toString()] }
        return site.declaration(
            identity = "${owner.name}.${function.name}${parametersOf(method)}",
            visibility = function.visibility,
            isFinal = function.modality == Modality.FINAL,
            isExempt =
                function.name == "main" ||
                    function.isInline ||
                    '$' in function.name ||
                    isSerializerFactory(function, kmClass),
            handles = listOfNotNull(method) + owner.defaultsOf(function.signature),
        )
    }

    // The `serializer()` the serialization plugin adds to a companion is found by reflection.
    private fun isSerializerFactory(function: KmFunction, kmClass: KmClass?): Boolean =
        kmClass?.kind == ClassKind.COMPANION_OBJECT && function.name == "serializer"

    private fun propertyOf(
        property: KmProperty,
        kmClass: KmClass?,
        site: DeclarationSite,
        fieldOwners: List<JavaClass>,
    ): Declaration? {
        val field =
            property.fieldSignature?.let { signature ->
                fieldOwners.firstNotNullOfOrNull { fieldOwner ->
                    fieldOwner.fields.firstOrNull { it.name == signature.name }
                }
            }
        val isWrappedByValueClass =
            kmClass?.isValue == true && property.name == kmClass.inlineClassUnderlyingPropertyName
        return site.declaration(
            identity = "${owner.name}.${property.name}",
            visibility = property.visibility,
            isFinal = property.modality == Modality.FINAL,
            isExempt = property.isConst || property.getter.isInline || isWrappedByValueClass,
            handles =
                listOfNotNull(
                    property.getterSignature?.let { methods[it.toString()] },
                    property.setterSignature?.let { methods[it.toString()] },
                    field,
                ) + componentsOf(property, kmClass),
        )
    }

    // A value class's constructors compile to static functions of their own, and a serializable
    // class's is called by the code the plugin generates: neither is judged.
    private fun constructorsOf(kmClass: KmClass, site: DeclarationSite): List<Declaration> =
        if (kmClass.kind != ClassKind.CLASS || kmClass.isValue || owner.isSerializable()) {
            emptyList()
        } else {
            val constructors = owner.constructors.associateBy { it.name + it.descriptor }
            kmClass.constructors.mapNotNull { constructor ->
                val jvmConstructor = constructor.signature?.let { constructors[it.toString()] }
                val copies =
                    if (kmClass.isData && !constructor.isSecondary) {
                        owner.methods.filter { it.name == "copy" || it.name == "copy\$default" }
                    } else {
                        emptyList()
                    }
                site.declaration(
                    identity = "${owner.name}${parametersOf(jvmConstructor)}",
                    visibility = constructor.visibility,
                    isFinal = true,
                    isExempt = false,
                    handles =
                        listOfNotNull(jvmConstructor) +
                            owner.constructorDefaultsOf(constructor.signature) +
                            copies,
                )
            }
        }

    // A data class's `componentN` reads the property its primary constructor declares at `N`.
    private fun componentsOf(property: KmProperty, kmClass: KmClass?): List<JavaMethod> =
        kmClass
            ?.takeIf { it.isData }
            ?.run { constructors.firstOrNull { !it.isSecondary } }
            ?.run { valueParameters.indexOfFirst { it.name == property.name } }
            ?.takeIf { it >= 0 }
            ?.let { position ->
                owner.methods.filter { method ->
                    method.name == "component${position + 1}" && method.rawParameterTypes.isEmpty()
                }
            }
            .orEmpty()
}

// What a call leaving out an argument with a default value calls instead.
private fun JavaClass.defaultsOf(signature: JvmMethodSignature?): List<JavaMethod> =
    signature
        ?.let { function ->
            methods.filter { method ->
                method.name == "${function.name}\$default" &&
                    function.parameters() in method.descriptor
            }
        }
        .orEmpty()

private fun JavaClass.constructorDefaultsOf(signature: JvmMethodSignature?): List<JavaMember> =
    signature
        ?.let { constructor ->
            constructors.filter { candidate ->
                constructor.parameters() in candidate.descriptor &&
                    candidate.descriptor.endsWith("DefaultConstructorMarker;)V")
            }
        }
        .orEmpty()

private fun JavaClass.isSerializable(): Boolean =
    isAnnotatedWith("kotlinx.serialization.Serializable")

// Its parameter types, receiver first, to tell overloads apart; `(?)` when the bytecode has no
// member for it.
private fun parametersOf(codeUnit: JavaCodeUnit?): String =
    codeUnit?.rawParameterTypes?.joinToString(prefix = "(", postfix = ")") { it.simpleName }
        ?: "(?)"

private fun JvmMethodSignature.parameters(): String =
    descriptor.substringAfter('(').substringBefore(')')

// `null` for what isn't judged: `protected` and local declarations.
private fun KmVisibility.toVisibility(): Visibility? =
    when (this) {
        KmVisibility.PRIVATE,
        KmVisibility.PRIVATE_TO_THIS -> Visibility.Private
        KmVisibility.INTERNAL -> Visibility.Internal
        KmVisibility.PUBLIC -> Visibility.Public
        KmVisibility.PROTECTED,
        KmVisibility.LOCAL -> null
    }

private fun JavaClass.sourceFileName(): String? = source.orElse(null)?.fileName?.orElse(null)
