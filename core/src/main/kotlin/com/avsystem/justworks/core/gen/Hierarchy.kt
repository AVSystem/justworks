package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.CacheGroup
import com.avsystem.justworks.core.memoized
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName

internal class Hierarchy(val modelPackage: ModelPackage) {
    var schemas: List<SchemaModel> = emptyList()
        private set

    /**
     * Updates the underlying schemas and invalidates all cached derived views.
     * This is necessary when schemas are updated (e.g., after inlining types).
     */
    private val cacheGroup = CacheGroup()

    fun add(newSchemas: List<SchemaModel>) {
        cacheGroup.reset()
        schemas += newSchemas
    }

    val schemasById: Map<String, SchemaModel> by memoized(cacheGroup) {
        schemas.associateBy { it.name }
    }

    private val polymorphicSchemas: List<SchemaModel> by memoized(cacheGroup) {
        schemas.filterNot { it.variants().isNullOrEmpty() }
    }

    val sealedHierarchies: Map<String, List<String>> by memoized(cacheGroup) {
        polymorphicSchemas
            .associate { schema ->
                schema.name to schema
                    .variants()
                    ?.filterIsInstance<TypeRef.Reference>()
                    ?.map { it.schemaName }
                    .orEmpty()
            }
    }

    /** Parent schema names that are anyOf without discriminator. */
    val anyOfWithoutDiscriminator: Set<String> by memoized(cacheGroup) {
        polymorphicSchemas
            .asSequence()
            .filter { !it.anyOf.isNullOrEmpty() && it.discriminator == null }
            .map { it.name }
            .toSet()
    }

    private val lookup: Map<String, ClassName> by memoized(cacheGroup) {
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

private fun SchemaModel.variants() = oneOf ?: anyOf
