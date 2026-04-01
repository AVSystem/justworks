package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.filter
import kotlin.to

private fun SchemaModel.variants() = oneOf ?: anyOf ?: emptyList()

internal class Hierarchy private constructor(
    val schemas: List<SchemaModel>,
    val modelPackage: ModelPackage,
    val variantParents: Map<String, Map<ClassName, String>>,
    val anyOfWithoutDiscriminator: Set<String>,
    val sealedHierarchies: Map<String, List<String>>,
    val anyOfWithoutDiscriminatorVariants: Set<String>,
    private val lookup: Map<String, ClassName>,
) {
    operator fun get(name: String): ClassName = lookup[name] ?: ClassName(modelPackage, name)

    companion object {
        operator fun invoke(schemas: List<SchemaModel>, modelPackage: ModelPackage): Hierarchy {
            val polymorphicSchemas = schemas.filter { it.variants().isNotEmpty() }

            val variantParents: Map<String, Map<ClassName, String>> = polymorphicSchemas
                .asSequence()
                .flatMap { schema ->
                    val parentClass = ClassName(modelPackage, schema.name)
                    schema.variants().filterIsInstance<TypeRef.Reference>().map { ref ->
                        ref.schemaName to (parentClass to resolveSerialName(schema, ref.schemaName))
                    }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, entries) -> entries.toMap() }

            val anyOfWithoutDiscriminator = polymorphicSchemas
                .asSequence()
                .filter { !it.anyOf.isNullOrEmpty() && it.discriminator == null }
                .map { it.name }
                .toSet()

            val sealedHierarchies = polymorphicSchemas.associate { schema ->
                schema.name to schema
                    .variants()
                    .asSequence()
                    .filterIsInstance<TypeRef.Reference>()
                    .map { it.schemaName }
                    .toList()
            }

            val anyOfWithoutDiscriminatorVariants = sealedHierarchies
                .asSequence()
                .filter { (key, _) -> key in anyOfWithoutDiscriminator }
                .flatMap { (_, value) -> value }
                .toSet()

            val lookup = sealedHierarchies
                .asSequence()
                .filterNot { (parent, _) -> parent in anyOfWithoutDiscriminator }
                .flatMap { (parent, variants) ->
                    val parentClass = ClassName(modelPackage, parent)
                    variants.map { variant -> variant to parentClass.nestedClass(variant) } +
                        (parent to parentClass)
                }.toMap()

            return Hierarchy(
                schemas = schemas,
                modelPackage = modelPackage,
                variantParents = variantParents,
                anyOfWithoutDiscriminator = anyOfWithoutDiscriminatorVariants,
                sealedHierarchies = sealedHierarchies,
                anyOfWithoutDiscriminatorVariants = anyOfWithoutDiscriminatorVariants,
                lookup = lookup,
            )
        }
    }
}
