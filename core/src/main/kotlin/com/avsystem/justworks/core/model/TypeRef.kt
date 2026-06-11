package com.avsystem.justworks.core.model

sealed interface TypeRef {
    data class Primitive(val type: PrimitiveType) : TypeRef

    data class Array(val items: TypeRef, val unique: Boolean = false) : TypeRef

    data class Reference(val schemaName: String) : TypeRef

    data class Map(val valueType: TypeRef) : TypeRef

    data class Inline(val properties: List<PropertyModel>, val requiredProperties: Set<String>) : TypeRef

    data class InlineEnum(val values: List<String>, val backingType: EnumBackingType) : TypeRef

    data object Unknown : TypeRef
}

enum class PrimitiveType { STRING, INT, LONG, DOUBLE, FLOAT, BOOLEAN, BYTE_ARRAY, DATE_TIME, DATE, UUID }
