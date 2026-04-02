package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.MemoScope
import com.avsystem.justworks.core.memoized
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName

internal class Hierarchy(val modelPackage: ModelPackage) {
    private val schemas = mutableSetOf<SchemaModel>()

    /**
     * Updates the underlying schemas and invalidates all cached derived views.
     * This is necessary when schemas are updated (e.g., after inlining types).
     */
    private val memoScope = MemoScope()

    fun addSchemas(newSchemas: List<SchemaModel>) {
        memoScope.reset()
        schemas += newSchemas
    }

    val schemasById: Map<String, SchemaModel> by memoized(memoScope) {
        schemas.associateBy { it.name }
    }

    private val polymorphicSchemas: List<SchemaModel> by memoized(memoScope) {
        schemas.filterNot { it.variants().isNullOrEmpty() }
    }

    val sealedHierarchies: Map<String, List<String>> by memoized(memoScope) {
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
    val anyOfWithoutDiscriminator: Set<String> by memoized(memoScope) {
        polymorphicSchemas
            .asSequence()
            .filter { !it.anyOf.isNullOrEmpty() && it.discriminator == null }
            .map { it.name }
            .toSet()
    }

    private val lookup: Map<String, ClassName> by memoized(memoScope) {
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
