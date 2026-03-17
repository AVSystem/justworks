package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef

/**
 * Key for structural equality of inline schemas.
 * Two inline schemas are considered equal if they have the same properties
 * (name, type, required status) regardless of property order.
 */
data class InlineSchemaKey(val properties: List<PropertyKey>, val requiredProperties: Set<String>) {
    data class PropertyKey(
        val name: String,
        val type: TypeRef,
        val required: Boolean,
    )

    companion object {
        /**
         * Creates an InlineSchemaKey from properties and required set.
         * Properties are sorted by name for deterministic equality.
         */
        fun from(properties: List<PropertyModel>, required: Set<String>): InlineSchemaKey {
            val propKeys = properties
                .map { PropertyKey(it.name, it.type, it.name in required) }
                .sortedBy { it.name } // Deterministic ordering
            return InlineSchemaKey(properties = propKeys, requiredProperties = required)
        }
    }
}

/**
 * Deduplicates inline schemas based on structural equality.
 * Ensures that structurally identical inline schemas generate only one class,
 * and handles name collisions with component schemas.
 */
class InlineSchemaDeduplicator(
    // Names of all component schemas (from components/schemas in OpenAPI spec)
    val componentSchemaNames: Set<String>,
) {
    // Maps structural key to the first generated name for that structure
    private val namesByKey = mutableMapOf<InlineSchemaKey, String>()

    /**
     * Gets or generates a name for an inline schema.
     * If a structurally identical schema was already seen, returns its name (deduplication).
     * If the context name collides with a component schema or another inline schema,
     * appends "Inline" suffix.
     *
     * @param properties The properties of the inline schema
     * @param requiredProps The set of required property names
     * @param contextName The base name derived from context (e.g., "PostPetRequest")
     * @return The final name to use for this inline schema
     */
    fun getOrGenerateName(
        properties: List<PropertyModel>,
        requiredProps: Set<String>,
        contextName: String,
    ): String {
        val key = InlineSchemaKey.from(properties, requiredProps)

        return namesByKey[key] ?: run {
            // Generate new name, handling collisions
            val finalName = contextName
                .takeUnless { it in componentSchemaNames || it in namesByKey.values }
                ?: "${contextName}Inline"

            finalName.also { namesByKey[key] = it }
        }
    }
}
