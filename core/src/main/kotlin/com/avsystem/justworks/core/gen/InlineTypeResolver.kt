package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.RequestBody
import com.avsystem.justworks.core.model.Response
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef

/**
 * Resolves a single [TypeRef.Inline] to [TypeRef.Reference] using the provided [nameMap].
 * Non-inline types are returned as-is; containers ([TypeRef.Array], [TypeRef.Map]) are resolved recursively.
 */
fun ApiSpec.resolveTypeRef(type: TypeRef, nameMap: Map<InlineSchemaKey, String>): TypeRef = when (type) {
    is TypeRef.Inline -> {
        val key = InlineSchemaKey.from(type.properties, type.requiredProperties)
        val className = nameMap[key]
            ?: error(
                "Missing inline schema mapping for key (contextHint=${type.contextHint}). " +
                    "This indicates a mismatch between inline schema collection and resolution.",
            )
        TypeRef.Reference(className)
    }

    is TypeRef.Array -> {
        TypeRef.Array(resolveTypeRef(type.items, nameMap))
    }

    is TypeRef.Map -> {
        TypeRef.Map(resolveTypeRef(type.valueType, nameMap))
    }

    else -> {
        type
    }
}

/**
 * Rewrites all [TypeRef.Inline] references in an [ApiSpec] to [TypeRef.Reference],
 * using the provided [nameMap] that maps structural keys to generated class names.
 *
 * This is applied once after inline schema collection, so downstream generators
 * never encounter [TypeRef.Inline] and need no special handling.
 */
fun ApiSpec.resolveInlineTypes(nameMap: Map<InlineSchemaKey, String>): ApiSpec {
    if (nameMap.isEmpty()) return this

    fun TypeRef.resolve(): TypeRef = resolveTypeRef(this, nameMap)

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
