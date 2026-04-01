package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName

private fun SchemaModel.variants() = oneOf ?: anyOf ?: emptyList()

internal class Hierarchy(
    val schemas: List<SchemaModel>,
    val modelPackage: ModelPackage,
) {
    val schemasById: Map<String, SchemaModel> by lazy { schemas.associateBy { it.name } }

    private val polymorphicSchemas by lazy { schemas.filter { it.variants().isNotEmpty() } }

    val sealedHierarchies: Map<String, List<String>> by lazy {
        polymorphicSchemas.associate { schema ->
            schema.name to schema
                .variants()
                .filterIsInstance<TypeRef.Reference>()
                .map { it.schemaName }
        }
    }

    /** Parent schema names that are anyOf without discriminator. */
    val anyOfWithoutDiscriminator: Set<String> by lazy {
        polymorphicSchemas
            .asSequence()
            .filter { !it.anyOf.isNullOrEmpty() && it.discriminator == null }
            .map { it.name }
            .toSet()
    }

    private val lookup: Map<String, ClassName> by lazy {
        sealedHierarchies
            .asSequence()
            .filterNot { (parent, _) -> parent in anyOfWithoutDiscriminator }
            .flatMap { (parent, variants) ->
                val parentClass = ClassName(modelPackage, parent)
                variants.map { variant -> variant to parentClass.nestedClass(variant) } +
                    (parent to parentClass)
            }.toMap()
    }

    operator fun get(name: String): ClassName = lookup[name] ?: ClassName(modelPackage, name)
}
