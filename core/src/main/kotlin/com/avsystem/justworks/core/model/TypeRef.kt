package com.avsystem.justworks.core.model

sealed interface TypeRef {
    data class Primitive(val type: PrimitiveType) : TypeRef

    data class Array(val items: TypeRef) : TypeRef

    data class Reference(val schemaName: String) : TypeRef

    data class Map(val valueType: TypeRef) : TypeRef

    data class Inline(
        val properties: List<PropertyModel>,
        val requiredProperties: Set<String>,
        val contextHint: String, // "request"|"response"|property name for context-aware naming
    ) : TypeRef

    data object Unknown : TypeRef
}

enum class PrimitiveType { STRING, INT, LONG, DOUBLE, FLOAT, BOOLEAN, BYTE_ARRAY, DATE_TIME, DATE }
