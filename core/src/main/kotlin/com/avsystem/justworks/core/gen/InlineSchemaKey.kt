package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.TypeRef

/**
 * Key for structural equality of inline schemas.
 * Two inline schemas are considered equal if they have the same properties
 * (name, type, required status, nullable status) regardless of property order.
 * Nested [TypeRef.Inline] types are normalized to ignore [TypeRef.Inline.contextHint],
 * ensuring purely structural comparison.
 */
internal data class InlineSchemaKey(val properties: Set<PropertyKey>) {
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
                    type = it.type.normalized(),
                    required = it.name in required,
                    nullable = it.nullable,
                    defaultValue = it.defaultValue,
                )
            }
            return InlineSchemaKey(keys.toSet())
        }

        private fun TypeRef.normalized(): TypeRef = when (this) {
            is TypeRef.Inline -> TypeRef.Inline(
                properties = properties
                    .map { it.copy(type = it.type.normalized()) }
                    .sortedBy { it.name },
                requiredProperties = this.requiredProperties,
                contextHint = "",
            )

            is TypeRef.Array -> TypeRef.Array(items.normalized(), unique)

            is TypeRef.Map -> TypeRef.Map(valueType.normalized())

            is TypeRef.InlineEnum -> copy(contextHint = "")

            else -> this
        }
    }
}
