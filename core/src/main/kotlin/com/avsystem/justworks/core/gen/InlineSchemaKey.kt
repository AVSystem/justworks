package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.TypeRef

/**
 * Key for structural equality of inline schemas.
 * Two inline schemas are considered equal if they have the same properties
 * (name, type, required status) regardless of property order.
 * Nested [TypeRef.Inline] types are normalized to ignore [TypeRef.Inline.contextHint],
 * ensuring purely structural comparison.
 */
data class InlineSchemaKey(val properties: Set<PropertyKey>) {
    data class PropertyKey(
        val name: String,
        val type: TypeRef,
        val required: Boolean,
    )

    companion object {
        fun from(properties: List<PropertyModel>, required: Set<String>) = InlineSchemaKey(
            properties = properties.map { PropertyKey(it.name, normalizeType(it.type), it.name in required) }.toSet(),
        )

        private fun normalizeType(type: TypeRef): TypeRef = when (type) {
            is TypeRef.Inline -> TypeRef.Inline(
                properties = type.properties,
                requiredProperties = type.requiredProperties,
                contextHint = "",
            )

            is TypeRef.Array -> TypeRef.Array(normalizeType(type.items))

            is TypeRef.Map -> TypeRef.Map(normalizeType(type.valueType))

            else -> type
        }
    }
}
