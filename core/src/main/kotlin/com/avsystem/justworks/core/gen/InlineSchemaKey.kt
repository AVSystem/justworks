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
        val nullable: Boolean,
        val defaultValue: Any?,
    )

    companion object {
        fun from(properties: List<PropertyModel>, required: Set<String>): InlineSchemaKey {
            val keys = properties.map {
                PropertyKey(
                    name = it.name,
                    type = normalizeType(it.type),
                    required = it.name in required,
                    nullable = it.nullable,
                    defaultValue = it.defaultValue,
                )
            }
            return InlineSchemaKey(keys.toSet())
        }

        private fun normalizeType(type: TypeRef): TypeRef = when (type) {
            is TypeRef.Inline -> TypeRef.Inline(
                properties = type.properties
                    .map { it.copy(type = normalizeType(it.type)) }
                    .sortedBy { it.name },
                requiredProperties = type.requiredProperties,
                contextHint = "",
            )

            is TypeRef.Array -> TypeRef.Array(normalizeType(type.items))

            is TypeRef.Map -> TypeRef.Map(normalizeType(type.valueType))

            else -> type
        }
    }
}
