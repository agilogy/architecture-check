package cat.coeli.architecturecheck.infrastructure

import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata

/** What the Kotlin compiler recorded about a class; `null` for one it didn't compile. */
internal fun JavaClass.kotlinMetadata(): KotlinClassMetadata? =
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
