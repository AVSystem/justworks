package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef

/**
 * An inline (anonymous) request/response body schema lifted out of an operation so it can
 * be generated as a type nested inside the owning client class.
 *
 * @param id stable reference id stored in [TypeRef.Reference] in place of the inline schema;
 *   the generator resolves it to the nested [com.squareup.kotlinpoet.ClassName] via [Hierarchy].
 * @param simpleName the nested type's simple name (e.g. `DomainsUpdateRequest`).
 * @param schema the schema to generate the nested data class from.
 */
internal data class PlannedInlineType(
    val id: String,
    val simpleName: String,
    val schema: SchemaModel,
)

/**
 * Rewrites operation request/response bodies that are [TypeRef.Inline] into
 * [TypeRef.Reference]s pointing at per-operation ids, and returns, per operationId,
 * the inline types that must be generated nested inside that operation's client class.
 *
 * No structural deduplication: each operation gets its own copy, named after the
 * operation, so identical shapes across operations do not collapse into one shared type.
 */
internal fun planOperationInlineTypes(spec: ApiSpec): Pair<ApiSpec, Map<String, List<PlannedInlineType>>> {
    val byOperation = mutableMapOf<String, MutableList<PlannedInlineType>>()

    fun lift(
        operationId: String,
        role: String,
        simpleName: String,
        inline: TypeRef.Inline
    ): TypeRef.Reference {
        val id = "$operationId#$role"
        byOperation.getOrPut(operationId) { mutableListOf() }.add(
            PlannedInlineType(
                id = id,
                simpleName = simpleName,
                schema = SchemaModel(
                    name = simpleName,
                    description = null,
                    properties = inline.properties,
                    requiredProperties = inline.requiredProperties,
                    allOf = null,
                    oneOf = null,
                    anyOf = null,
                    discriminator = null,
                ),
            ),
        )
        return TypeRef.Reference(id)
    }

    val newEndpoints = spec.endpoints.map { endpoint ->
        val opName = endpoint.operationId.toPascalCase()

        val newRequestBody = endpoint.requestBody?.let { body ->
            val schema = body.schema
            if (schema is TypeRef.Inline) {
                body.copy(schema = lift(endpoint.operationId, "request", "${opName}Request", schema))
            } else {
                body
            }
        }

        val newResponses = endpoint.responses.mapValues { (code, response) ->
            val schema = response.schema
            if (schema is TypeRef.Inline) {
                response.copy(
                    schema = lift(endpoint.operationId, "response#$code", responseTypeName(opName, code), schema),
                )
            } else {
                response
            }
        }

        endpoint.copy(requestBody = newRequestBody, responses = newResponses)
    }

    return spec.copy(endpoints = newEndpoints) to byOperation
}

private fun responseTypeName(opName: String, code: String): String = when {
    code.toIntOrNull()?.let { it in 200..299 } == true -> "${opName}Response"
    code == "default" -> "${opName}DefaultResponse"
    else -> "${opName}Response$code"
}
