package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PropertyModel

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
