package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName

private fun SchemaModel.variants() = oneOf ?: anyOf ?: emptyList()

internal data class Hierarchy(val schemas: List<SchemaModel>, val modelPackage: ModelPackage) {
    private val polymorphicSchemas by lazy { schemas.filter { it.variants().isNotEmpty() } }

    val variantParents by lazy {
        polymorphicSchemas
            .asSequence()
            .flatMap { schema ->
                val parentClass = ClassName(modelPackage, schema.name)
                schema.variants().filterIsInstance<TypeRef.Reference>().map { ref ->
                    ref.schemaName to (parentClass to resolveSerialName(schema, ref.schemaName))
                }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.toMap() }
    }

    val anyOfWithoutDiscriminator by lazy {
        polymorphicSchemas
            .asSequence()
            .filter { !it.anyOf.isNullOrEmpty() && it.discriminator == null }
            .map { it.name }
            .toSet()
    }

    val sealedHierarchies by lazy {
        polymorphicSchemas.associate { schema ->
            schema.name to schema
                .variants()
                .asSequence()
                .filterIsInstance<TypeRef.Reference>()
                .map { it.schemaName }
                .toList()
        }
    }

    /** Variant names that belong to anyOf-without-discriminator hierarchies (still use interface pattern). */
    val anyOfWithoutDiscriminatorVariants: Set<String> by lazy {
        sealedHierarchies
            .asSequence()
            .filter { (key, _) -> key in anyOfWithoutDiscriminator }
            .flatMap { (_, value) -> value }
            .toSet()
    }

    val lookup: Map<String, ClassName> by lazy {
        sealedHierarchies
            .asSequence()
            .filterNot { (parent, _) -> parent in anyOfWithoutDiscriminator }
            .flatMap { (parent, variants) ->
                val parentClass = ClassName(modelPackage, parent)
                variants.map { variant -> variant to parentClass.nestedClass(variant) } + (parent to parentClass)
            }.toMap()
    }
}
