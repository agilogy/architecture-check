package cat.coeli.architecturecheck.infrastructure

import cat.coeli.architecturecheck.domain.BuildScript
import cat.coeli.architecturecheck.domain.CodeProvider
import cat.coeli.architecturecheck.domain.CompiledClass
import cat.coeli.architecturecheck.domain.KotlinFile
import cat.coeli.architecturecheck.domain.Member
import cat.coeli.architecturecheck.domain.MemberUses
import cat.coeli.architecturecheck.domain.Module
import cat.coeli.architecturecheck.domain.Overriding
import cat.coeli.architecturecheck.domain.Placement
import cat.coeli.architecturecheck.domain.Reach
import cat.coeli.architecturecheck.domain.SourceLine
import cat.coeli.architecturecheck.domain.SourceSet
import cat.coeli.architecturecheck.domain.Use
import cat.coeli.architecturecheck.domain.Visibility
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaMember
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import java.io.File
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
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility

/**
 * The code of [modules] as their directories hold it: the classes Gradle compiled, read with
 * ArchUnit and their Kotlin metadata once for both classes and members, and the build scripts and
 * Kotlin sources.
 */
internal class CompiledCode(private val modules: List<ModuleFiles>) : CodeProvider {
    // Every class compiled from the modules' source sets, with where it was compiled from.
    private val placements: Map<JavaClass, Placement> by lazy {
        val directories = modules.flatMap { files ->
            SourceSet.entries
                .map { files.classDirectoryOf(it) to Placement(files.module.path, it) }
                .filter { (directory, _) -> directory.isDirectory }
        }
        val prefixes = directories.map { (directory, placement) ->
            directory.absolutePath + File.separator to placement
        }
        if (directories.isEmpty()) {
            emptyMap()
        } else {
            ClassFileImporter()
                .importPaths(directories.map { it.first.toPath() })
                .mapNotNull { javaClass ->
                    placementOf(javaClass, prefixes)?.let { javaClass to it }
                }
                .toMap()
        }
    }

    // Each class's source file from the repository root, when it is where the package convention
    // puts it.
    private val sourcePaths: Map<JavaClass, String> by lazy {
        val modulesByPath = modules.associateBy { it.module.path }
        placements
            .mapNotNull { (javaClass, placement) ->
                modulesByPath[placement.module]?.sourcePathOf(javaClass, placement.sourceSet)?.let {
                    javaClass to it
                }
            }
            .toMap()
    }

    override fun modules(): List<Module> = modules.map { it.module }

    override fun classes(): List<CompiledClass> = placements.map { (javaClass, placement) ->
        CompiledClass(
            name = javaClass.name,
            packageName = javaClass.packageName,
            unit = javaClass.topLevelName(),
            sourceFile = javaClass.sourceFilePath(),
            sourcePath = sourcePaths[javaClass],
            placement = placement,
            reaches = javaClass.reaches(),
            dependencies = javaClass.directDependenciesFromSelf.map { it.targetClass.name }.toSet(),
        )
    }

    /**
     * The members of `src/main` and `src/testFixtures`, their uses from any source set and what
     * they override.
     */
    override fun memberUses(): MemberUses {
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
            uses = declarations.flatMap { usesOf(it) },
            overridings =
                declarations.flatMap { declaration ->
                    declaration.overridden().map {
                        Overriding(overrider = declaration.member, overridden = byHandle[it])
                    }
                },
        )
    }

    override fun buildScripts(): List<BuildScript> = modules.map { files ->
        BuildScript(
            module = files.module.path,
            path = files.pathOf(files.buildScript),
            text = files.buildScript.readText(),
        )
    }

    override fun kotlinFiles(): List<KotlinFile> = modules.flatMap { files ->
        SourceSet.entries.flatMap { sourceSet ->
            val directory = files.sourceDirectoryOf(sourceSet)
            directory
                .walkTopDown()
                .filter { it.extension == "kt" }
                .map { file ->
                    KotlinFile(
                        module = files.module.path,
                        path = files.pathOf(file),
                        packageName = packageOf(file, directory),
                        text = file.readText(),
                    )
                }
                .toList()
        }
    }

    // Its own bytecode members calling each other, as a `$default` calls the function, don't count.
    private fun usesOf(declaration: Declaration): List<Use> =
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

    /**
     * A [member] with the bytecode members it compiled to ([handles]): a use of any of them is a
     * use of the member.
     */
    private data class Declaration(
        val member: Member,
        val site: DeclarationSite,
        val handles: Set<JavaMember>,
    )

    /**
     * The class file a declaration is in, the visibility of its class narrowed by the classes
     * around ([classVisibility]), which classes `private` lets use it ([reachesPrivately]) and its
     * source file from the repository root ([sourcePath]), when it is where the package convention
     * puts it.
     */
    private class DeclarationSite(
        private val owner: JavaClass,
        private val placement: Placement,
        private val classVisibility: Visibility,
        private val sourcePath: String?,
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
                    member =
                        Member(
                            identity = identity,
                            owner = owner.name,
                            placement = placement,
                            visibility = minOf(a = memberVisibility, b = classVisibility),
                            isFinal = isFinal,
                            isExempt = isExempt,
                            source = sourceLineOf(handles),
                        ),
                    site = this,
                    handles = handles.toSet(),
                )
            }

        private fun sourceLineOf(handles: List<JavaMember>): SourceLine? {
            val line =
                handles
                    .filterIsInstance<JavaCodeUnit>()
                    .map { it.sourceCodeLocation.lineNumber }
                    .firstOrNull { it > 0 }
            return if (sourcePath == null || line == null) null else SourceLine(sourcePath, line)
        }
    }

    /** The declarations of one class file, as its Kotlin metadata lists them. */
    private class Declarations(
        private val owner: JavaClass,
        private val placement: Placement,
        private val metadata: Map<JavaClass, KotlinClassMetadata?>,
        private val sourcePath: String?,
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
                        sourcePath = sourcePath,
                        reachesPrivately = { origin ->
                            origin.packageName == owner.packageName &&
                                origin.sourceFileName() == owner.sourceFileName()
                        },
                    ),
                kmClass = null,
                fieldOwners = listOf(owner),
            )

        // An annotation has nothing to narrow, and what the serialization plugin generates is used
        // by reflection.
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
                        sourcePath = sourcePath,
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

        // The narrowest of the class's visibility and those around it; `null` for a local class or
        // one Kotlin didn't make.
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
                kmClass?.isValue == true &&
                    property.name == kmClass.inlineClassUnderlyingPropertyName
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

        // A value class's constructors compile to static functions of their own, and a
        // serializable class's is called by the code the plugin generates: neither is judged.
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
                        method.name == "component${position + 1}" &&
                            method.rawParameterTypes.isEmpty()
                    }
                }
                .orEmpty()
    }

    private companion object {
        private fun placementOf(
            javaClass: JavaClass,
            prefixes: List<Pair<String, Placement>>,
        ): Placement? =
            javaClass.source
                .orElse(null)
                ?.uri
                ?.takeIf { it.scheme == "file" }
                ?.let { File(it).absolutePath }
                ?.let { path ->
                    prefixes.firstOrNull { (prefix, _) -> path.startsWith(prefix) }?.second
                }

        private fun ModuleFiles.sourcePathOf(javaClass: JavaClass, sourceSet: SourceSet): String? =
            javaClass.sourceCodeLocation.sourceFileName
                .takeIf { it.isNotEmpty() }
                ?.let { fileName ->
                    File(
                        sourceDirectoryOf(sourceSet),
                        javaClass.packageName.replace(oldChar = '.', newChar = '/') + "/$fileName",
                    )
                }
                ?.takeIf { it.isFile }
                ?.let { pathOf(it) }

        // The package its directory stands for, as the Kotlin convention wants.
        private fun packageOf(file: File, sourceDirectory: File): String =
            file.parentFile
                .relativeTo(sourceDirectory)
                .invariantSeparatorsPath
                .replace(oldChar = '/', newChar = '.')

        private fun JavaClass.reaches(): List<Reach> {
            val accessed =
                accessesFromSelf
                    .map { it.targetOwner.name to it.sourceCodeLocation.lineNumber }
                    .toSet()
            return accessesFromSelf.mapNotNull { access ->
                reachOf(
                    target = access.targetOwner,
                    member = access.target.name,
                    line = access.sourceCodeLocation.lineNumber,
                )
            } +
                directDependenciesFromSelf
                    .filterNot {
                        it.targetClass.name to it.sourceCodeLocation.lineNumber in accessed
                    }
                    .mapNotNull { dependency ->
                        reachOf(
                            target = dependency.targetClass,
                            member = null,
                            line = dependency.sourceCodeLocation.lineNumber,
                        )
                    }
        }

        // Arrays count as their elements; primitives are nobody's.
        private fun reachOf(target: JavaClass, member: String?, line: Int): Reach? =
            (if (target.isArray) target.baseComponentType else target)
                .takeUnless { it.isPrimitive }
                ?.let { reached ->
                    Reach(
                        target = reached.name,
                        targetPackage = reached.packageName,
                        member = member,
                        line = line,
                    )
                }

        private fun JavaClass.sourceFilePath(): String =
            packageName.replace(oldChar = '.', newChar = '/') +
                "/" +
                (sourceFileName() ?: "${simpleName}.kt")

        private fun JavaClass.topLevelName(): String =
            generateSequence(this) { it.enclosingClass.orElse(null) }.last().name

        private fun JavaClass.sourceFileName(): String? =
            source.orElse(null)?.fileName?.orElse(null)

        // What the Kotlin compiler recorded about a class; `null` for one it didn't compile.
        private fun JavaClass.kotlinMetadata(): KotlinClassMetadata? =
            tryGetAnnotationOfType("kotlin.Metadata").orElse(null)?.let { annotation ->
                KotlinClassMetadata.readLenient(
                    Metadata(
                        kind = annotation.value("k") as? Int,
                        metadataVersion = annotation.ints("mv"),
                        data1 = annotation.strings("d1"),
                        data2 = annotation.strings("d2"),
                        extraString = annotation.value("xs") as? String,
                        packageName = annotation.value("pn") as? String,
                        extraInt = annotation.value("xi") as? Int,
                    ),
                )
            }

        private fun JavaAnnotation<*>.value(property: String): Any? = get(property).orElse(null)

        private fun JavaAnnotation<*>.strings(property: String): Array<String>? =
            (value(property) as? Array<*>)?.run { filterIsInstance<String>().toTypedArray() }

        private fun JavaAnnotation<*>.ints(property: String): IntArray? =
            when (val value = value(property)) {
                is IntArray -> value
                is Array<*> -> value.filterIsInstance<Int>().toIntArray()
                else -> null
            }

        private fun Declaration.overridden(): List<JavaMethod> =
            handles.filterIsInstance<JavaMethod>().flatMap { it.overridden() }

        // By name and number of parameters, as generics erase differently on each side: a false
        // match only spares a member.
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

        private fun JavaClass.constructorDefaultsOf(
            signature: JvmMethodSignature?,
        ): List<JavaMember> =
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

        // Its parameter types, receiver first, to tell overloads apart; `(?)` when the bytecode
        // has no member for it.
        private fun parametersOf(codeUnit: JavaCodeUnit?): String =
            codeUnit?.rawParameterTypes?.joinToString(prefix = "(", postfix = ")") { type ->
                type.simpleName
            } ?: "(?)"

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
    }
}
