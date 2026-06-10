package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef

/**
 * An inline (anonymous) object schema lifted out so it can be generated as a type nested
 * inside its owner (a client class for operation bodies, or a parent data class for
 * properties). Forms a tree: [children] are inline objects nested within this one.
 *
 * @param id stable reference id stored in [TypeRef.Reference] in place of the inline schema;
 *   the generator resolves it to the nested [com.squareup.kotlinpoet.ClassName] via [Hierarchy].
 * @param simpleName the nested type's simple name (e.g. `DomainsUpdateRequest`, `Address`).
 * @param schema the schema to generate the nested data class from, with its own inline
 *   properties already rewritten to references pointing at [children].
 */
internal data class PlannedInlineType(
    val id: String,
    val simpleName: String,
    val schema: SchemaModel,
    val children: List<PlannedInlineType>,
)

/**
 * Result of [planInlineTypes]: the spec with every [TypeRef.Inline] rewritten to a
 * [TypeRef.Reference], plus the inline types to nest, grouped by owner.
 */
internal data class InlinePlan(
    val spec: ApiSpec,
    /** Operation id -> inline body types to nest inside that operation's client class. */
    val clientInline: Map<String, List<PlannedInlineType>>,
    /** Component schema name -> inline property types to nest inside that data class. */
    val modelInline: Map<String, List<PlannedInlineType>>,
)

/**
 * Single pass over the spec that lifts every inline object schema (operation request/response
 * bodies and object-typed properties, recursively) into [PlannedInlineType] trees and rewrites
 * the spec to reference them.
 *
 * No structural deduplication: each occurrence gets its own copy, named after its position, so
 * inline types are placed (and named) relative to the type that owns them.
 */
internal fun planInlineTypes(spec: ApiSpec): InlinePlan {
    var counter = 0

    // Rewrites a type (and inline objects nested in arrays/maps) to references, returning the
    // rewritten type and, if an inline object was lifted, its PlannedInlineType.
    fun plan(type: TypeRef, hint: String): Pair<TypeRef, PlannedInlineType?> = when (type) {
        is TypeRef.Inline -> {
            val id = "inline${counter++}"
            val children = mutableListOf<PlannedInlineType>()
            val newProperties = type.properties.map { property ->
                val (newType, child) = plan(property.type, property.name.toPascalCase())
                if (child != null) children.add(child)
                property.copy(type = newType)
            }
            val schema = SchemaModel(
                name = hint,
                description = null,
                properties = newProperties,
                requiredProperties = type.requiredProperties,
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
            TypeRef.Reference(id) to PlannedInlineType(id, hint, schema, children)
        }

        is TypeRef.Array -> {
            plan(type.items, "${hint}Item").let { (item, child) -> TypeRef.Array(item) to child }
        }

        is TypeRef.Map -> {
            plan(type.valueType, "${hint}Value").let { (value, child) -> TypeRef.Map(value) to child }
        }

        else -> {
            type to null
        }
    }

    val clientInline = mutableMapOf<String, MutableList<PlannedInlineType>>()
    val modelInline = mutableMapOf<String, MutableList<PlannedInlineType>>()

    val newEndpoints = spec.endpoints.map { endpoint ->
        val opName = endpoint.operationId.toPascalCase()
        val owned = mutableListOf<PlannedInlineType>()

        val newRequestBody = endpoint.requestBody?.let { body ->
            val (newType, planned) = plan(body.schema, "${opName}Request")
            if (planned != null) owned.add(planned)
            body.copy(schema = newType)
        }

        val newResponses = endpoint.responses.mapValues { (code, response) ->
            val schema = response.schema ?: return@mapValues response
            val (newType, planned) = plan(schema, responseTypeName(opName, code))
            if (planned != null) owned.add(planned)
            response.copy(schema = newType)
        }

        if (owned.isNotEmpty()) clientInline[endpoint.operationId] = owned
        endpoint.copy(requestBody = newRequestBody, responses = newResponses)
    }

    val newSchemas = spec.schemas.map { schema ->
        val owned = mutableListOf<PlannedInlineType>()
        val newProperties = schema.properties.map { property ->
            val (newType, planned) = plan(property.type, property.name.toPascalCase())
            if (planned != null) owned.add(planned)
            property.copy(type = newType)
        }
        if (owned.isNotEmpty()) modelInline[schema.name] = owned
        schema.copy(properties = newProperties)
    }

    return InlinePlan(
        spec = spec.copy(endpoints = newEndpoints, schemas = newSchemas),
        clientInline = clientInline,
        modelInline = modelInline,
    )
}

private fun responseTypeName(opName: String, code: String): String = when {
    code.toIntOrNull()?.let { it in 200..299 } == true -> "${opName}Response"
    code == "default" -> "${opName}DefaultResponse"
    else -> "${opName}Response$code"
}
