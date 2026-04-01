package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.SCHEMA_PREFIX
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName

internal val TypeRef.properties: List<PropertyModel>
    get() = when (this) {
        is TypeRef.Inline -> properties
        is TypeRef.Array, is TypeRef.Map, is TypeRef.Primitive, is TypeRef.Reference, TypeRef.Unknown -> emptyList()
    }

internal val TypeRef.requiredProperties: Set<String>
    get() = when (this) {
        is TypeRef.Inline -> requiredProperties
        is TypeRef.Array, is TypeRef.Map, is TypeRef.Primitive, is TypeRef.Reference, TypeRef.Unknown -> emptySet()
    }

context(hierarchy: Hierarchy)
internal fun TypeRef.toTypeName(): TypeName = when (this) {
    is TypeRef.Primitive -> {
        when (type) {
            PrimitiveType.STRING -> STRING
            PrimitiveType.INT -> INT
            PrimitiveType.LONG -> LONG
            PrimitiveType.DOUBLE -> DOUBLE
            PrimitiveType.FLOAT -> FLOAT
            PrimitiveType.BOOLEAN -> BOOLEAN
            PrimitiveType.BYTE_ARRAY -> BYTE_ARRAY
            PrimitiveType.DATE_TIME -> INSTANT
            PrimitiveType.DATE -> LOCAL_DATE
            PrimitiveType.UUID -> UUID_TYPE
        }
    }

    is TypeRef.Array -> {
        LIST.parameterizedBy(items.toTypeName())
    }

    is TypeRef.Map -> {
        MAP.parameterizedBy(STRING, valueType.toTypeName())
    }

    is TypeRef.Reference -> {
        hierarchy[schemaName]
    }

    is TypeRef.Inline -> {
        error("TypeRef.Inline should have been resolved by InlineTypeResolver (contextHint=$contextHint)")
    }

    is TypeRef.Unknown -> {
        JSON_ELEMENT
    }
}

internal fun TypeRef.isBinaryUpload(): Boolean = this is TypeRef.Primitive && this.type == PrimitiveType.BYTE_ARRAY

/**
 * Resolves the @SerialName value for a variant within a oneOf schema.
 */
internal fun SchemaModel.resolveSerialName(variantSchemaName: String): String = discriminator
    ?.mapping
    ?.firstNotNullOfOrNull { (serialName, refPath) ->
        serialName.takeIf { refPath.removePrefix(SCHEMA_PREFIX) == variantSchemaName }
    }
    ?: variantSchemaName
