package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.EnumBackingType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.RequestBody
import com.avsystem.justworks.core.model.Response
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef

/**
 * Structural key for an inline enum, ignoring [TypeRef.InlineEnum.contextHint].
 * Two inline enums are considered the same generated type if they share the same
 * ordered values and backing type.
 */
internal data class InlineEnumKey(val values: List<String>, val backingType: EnumBackingType) {
    companion object {
        fun from(type: TypeRef.InlineEnum) = InlineEnumKey(type.values, type.backingType)
    }
}

/**
 * Resolves a single [TypeRef.Inline]/[TypeRef.InlineEnum] to [TypeRef.Reference] using the
 * provided maps. Non-inline types are returned as-is; containers ([TypeRef.Array], [TypeRef.Map])
 * are resolved recursively.
 */
internal fun ApiSpec.resolveTypeRef(
    type: TypeRef,
    nameMap: Map<InlineSchemaKey, String>,
    enumNameMap: Map<InlineEnumKey, String> = emptyMap(),
): TypeRef = when (type) {
    is TypeRef.Inline -> {
        val key = InlineSchemaKey.from(type.properties, type.requiredProperties)
        val className = nameMap[key]
            ?: error(
                "Missing inline schema mapping for key (contextHint=${type.contextHint}). " +
                    "This indicates a mismatch between inline schema collection and resolution.",
            )
        TypeRef.Reference(className)
    }

    is TypeRef.InlineEnum -> {
        val className = enumNameMap[InlineEnumKey.from(type)]
            ?: error(
                "Missing inline enum mapping for key (contextHint=${type.contextHint}). " +
                    "This indicates a mismatch between inline enum collection and resolution.",
            )
        TypeRef.Reference(className)
    }

    is TypeRef.Array -> {
        TypeRef.Array(resolveTypeRef(type.items, nameMap, enumNameMap), type.unique)
    }

    is TypeRef.Map -> {
        TypeRef.Map(resolveTypeRef(type.valueType, nameMap, enumNameMap))
    }

    else -> {
        type
    }
}

/**
 * Rewrites all [TypeRef.Inline]/[TypeRef.InlineEnum] references in an [ApiSpec] to
 * [TypeRef.Reference], using the provided maps from structural keys to generated names.
 *
 * This is applied once after inline collection, so downstream generators
 * never encounter inline types and need no special handling.
 */
internal fun ApiSpec.resolveInlineTypes(
    nameMap: Map<InlineSchemaKey, String>,
    enumNameMap: Map<InlineEnumKey, String> = emptyMap(),
): ApiSpec {
    if (nameMap.isEmpty() && enumNameMap.isEmpty()) return this

    fun TypeRef.resolve(): TypeRef = resolveTypeRef(this, nameMap, enumNameMap)

    fun PropertyModel.resolve() = copy(type = type.resolve())

    fun SchemaModel.resolve() = copy(
        properties = properties.map { it.resolve() },
        allOf = allOf?.map { it.resolve() },
        oneOf = oneOf?.map { it.resolve() },
        anyOf = anyOf?.map { it.resolve() },
        underlyingType = underlyingType?.resolve(),
    )

    fun Response.resolve() = copy(schema = schema?.resolve())

    fun RequestBody.resolve() = copy(schema = schema.resolve())

    fun Endpoint.resolve() = copy(
        parameters = parameters.map { it.copy(schema = it.schema.resolve()) },
        requestBody = requestBody?.resolve(),
        responses = responses.mapValues { (_, v) -> v.resolve() },
    )

    return copy(
        schemas = schemas.map { it.resolve() },
        endpoints = endpoints.map { it.resolve() },
    )
}
