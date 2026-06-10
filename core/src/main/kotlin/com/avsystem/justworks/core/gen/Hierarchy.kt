package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.MemoScope
import com.avsystem.justworks.core.memoized
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName

internal class Hierarchy(val modelPackage: ModelPackage) {
    private val schemaModels = mutableSetOf<SchemaModel>()
    private val memoScope = MemoScope()

    /**
     * Resolution overrides for inline body types nested inside client/model classes,
     * keyed by the reference id assigned by [planInlineTypes].
     */
    private val inlineRefs = mutableMapOf<String, ClassName>()

    /** Inline object types to nest inside a component data class, keyed by the component name. */
    var modelInline: Map<String, List<PlannedInlineType>> = emptyMap()

    /** Registers the nested [ClassName] an inline reference id resolves to. */
    fun registerInlineRef(id: String, className: ClassName) {
        inlineRefs[id] = className
    }

    /**
     * Updates the underlying schemas and invalidates all cached derived views.
     * This is necessary when schemas are updated (e.g., after inlining types).
     */
    fun addSchemas(newSchemas: List<SchemaModel>) {
        schemaModels += newSchemas
        memoScope.reset()
    }

    /** All schemas indexed by name for quick lookup. */
    val schemasById: Map<String, SchemaModel> by memoized(memoScope) {
        schemaModels.associateBy { it.name }
    }

    /** Schemas that define polymorphic variants via oneOf or anyOf. */
    private val polymorphicSchemas: List<SchemaModel> by memoized(memoScope) {
        schemaModels.filterNot { it.variants().isNullOrEmpty() }
    }

    /** Maps parent schema name to its variant schema names (for both oneOf and anyOf). */
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

    /** Parent schema names that use anyOf without a discriminator (JsonContentPolymorphicSerializer pattern). */
    val anyOfWithoutDiscriminator: Set<String> by memoized(memoScope) {
        polymorphicSchemas
            .asSequence()
            .filter { !it.anyOf.isNullOrEmpty() && it.discriminator == null }
            .map { it.name }
            .toSet()
    }

    /** Inverse of [sealedHierarchies] for anyOf-without-discriminator: variant name to its parent names. */
    val anyOfParents: Map<String, Set<String>> by memoized(memoScope) {
        sealedHierarchies
            .asSequence()
            .filter { (parent, _) -> parent in anyOfWithoutDiscriminator }
            .flatMap { (parent, variants) -> variants.map { it to parent } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, parents) -> parents.toSet() }
    }

    /** Top-level [ClassName] for a component schema/enum name, normalized to a PascalCase identifier. */
    fun classNameFor(schemaName: String): ClassName = ClassName(modelPackage, schemaName.toPascalCase())

    /** Maps schema name to its [ClassName], using nested class for discriminated hierarchy variants. */
    private val lookup: Map<String, ClassName> by memoized(memoScope) {
        sealedHierarchies
            .asSequence()
            .filterNot { (parent, _) -> parent in anyOfWithoutDiscriminator }
            .flatMap { (parent, variants) ->
                val parentClass = classNameFor(parent)
                variants.map { variant -> variant to parentClass.nestedClass(variant.toPascalCase()) } +
                    (parent to parentClass)
            }.toMap()
    }

    /** Resolves a schema name (or inline-ref id) to its [ClassName], falling back to a flat top-level class. */
    operator fun get(name: String): ClassName = inlineRefs[name] ?: lookup[name] ?: classNameFor(name)
}

private fun SchemaModel.variants() = oneOf ?: anyOf
