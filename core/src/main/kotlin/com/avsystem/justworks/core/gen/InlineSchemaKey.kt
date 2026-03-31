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
    @ConsistentCopyVisibility
    data class PropertyKey private constructor(
        val name: String,
        val type: TypeRef,
        val required: Boolean,
        val nullable: Boolean,
    ) {
        // do not rely on default value for equality
        var defaultValue: Any? = null
            private set

        constructor(name: String, type: TypeRef, required: Boolean, nullable: Boolean, defaultValue: Any?) :
            this(name, type, required, nullable) {
            this.defaultValue = defaultValue
        }
    }

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
