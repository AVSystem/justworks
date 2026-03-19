package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.TypeRef

/**
 * Key for structural equality of inline schemas.
 * Two inline schemas are considered equal if they have the same properties
 * (name, type, required status) regardless of property order.
 */
data class InlineSchemaKey(val properties: Set<PropertyKey>) {
    data class PropertyKey(
        val name: String,
        val type: TypeRef,
        val required: Boolean,
    )

    companion object {
        fun from(properties: List<PropertyModel>, required: Set<String>) = InlineSchemaKey(
            properties = properties.map { PropertyKey(it.name, it.type, it.name in required) }.toSet(),
        )
    }
}

/**
 * Deduplicates inline schemas based on structural equality.
 * Ensures that structurally identical inline schemas generate only one class,
 * and handles name collisions with component schemas.
 */
class InlineSchemaDeduplicator(private val componentSchemaNames: Set<String>) {
    private val namesByKey = mutableMapOf<InlineSchemaKey, String>()

    fun getOrGenerateName(
        properties: List<PropertyModel>,
        requiredProps: Set<String>,
        contextName: String,
    ): String = namesByKey.getOrPut(InlineSchemaKey.from(properties, requiredProps)) {
        val inlineName = contextName.toInlinedName()
        val candidates = sequence {
            yield(inlineName)
            yield("${inlineName}Inline")
            generateSequence(2) { it + 1 }.forEach {
                yield("${inlineName}${it}Inline")
            }
        }

        val existingNames = (componentSchemaNames + namesByKey.values).toSet()
        candidates.first { it !in existingNames }
    }
}
