package com.avsystem.justworks.core.model

sealed interface TypeRef {
    data class Primitive(val type: PrimitiveType) : TypeRef

    data class Array(val items: TypeRef, val unique: Boolean = false) : TypeRef

    data class Reference(val schemaName: String) : TypeRef

    data class Map(val valueType: TypeRef) : TypeRef

    data class Inline(
        val properties: List<PropertyModel>,
        val requiredProperties: Set<String>,
        override val contextHint: String, // "request"|"response"|property name for context-aware naming
    ) : InlineType

    data class InlineEnum(
        val values: List<String>,
        val backingType: EnumBackingType,
        override val contextHint: String, // property/item name for context-aware naming
    ) : InlineType

    data object Unknown : TypeRef

    /**
     * A schema defined inline (anonymously) that must be hoisted into a named generated
     * declaration. [contextHint] is the seed used to derive that name.
     */
    sealed interface InlineType : TypeRef {
        val contextHint: String
    }
}

enum class PrimitiveType { STRING, INT, LONG, DOUBLE, FLOAT, BOOLEAN, BYTE_ARRAY, DATE_TIME, DATE, UUID }
