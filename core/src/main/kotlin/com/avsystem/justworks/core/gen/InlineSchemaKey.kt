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
