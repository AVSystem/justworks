package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.ContentType
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.SecurityScheme
import com.avsystem.justworks.core.model.TypeRef

/**
 * An inline object schema lifted out to be generated as a type nested inside its owner.
 * [children] are inline objects nested within this one. [id] is the reference id placed in
 * [TypeRef.Reference] and resolved to the nested class via [Hierarchy].
 */
internal data class PlannedInlineType(
    val id: String,
    val simpleName: String,
    val schema: SchemaModel,
    val children: List<PlannedInlineType>,
)

/**
 * An [ApiSpec] whose inline schemas have been lifted: every [TypeRef.Inline] is rewritten to a
 * [TypeRef.Reference], and the lifted types travel with the endpoint/schema that owns them.
 */
internal data class TransformedApiSpec(
    val title: String,
    val version: String,
    val endpoints: List<TransformedEndpoint>,
    val schemas: List<TransformedSchema>,
    val enums: List<EnumModel>,
    val securitySchemes: List<SecurityScheme>,
)

/** An endpoint plus the inline request/response body types to nest inside its client class. */
internal data class TransformedEndpoint(val endpoint: Endpoint, val inlineTypes: List<PlannedInlineType>)

/** A component schema plus the inline property types to nest inside its data class. */
internal data class TransformedSchema(val schema: SchemaModel, val inlineTypes: List<PlannedInlineType>)

/** Lifts every inline object schema (bodies and properties, recursively) into [PlannedInlineType] trees. */
internal fun ApiSpec.transform(): TransformedApiSpec = object { // object for mutual recursion
    private val spec = this@transform
    private var counter = 0

    private fun planProperties(properties: List<PropertyModel>, sink: MutableList<PlannedInlineType>) =
        properties.map { property ->
            property.copy(type = sink.collect(property.type, property.name.toPascalCase()))
        }

    private fun plan(type: TypeRef, hint: String): Pair<TypeRef, PlannedInlineType?> = when (type) {
        is TypeRef.Inline -> {
            val id = "inline${counter++}"
            val children = mutableListOf<PlannedInlineType>()
            val schema = SchemaModel(
                name = hint,
                description = null,
                properties = planProperties(type.properties, children),
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

    private fun MutableList<PlannedInlineType>.collect(type: TypeRef, hint: String): TypeRef {
        val (rewritten, planned) = plan(type, hint)
        if (planned != null) this.add(planned)
        return rewritten
    }

    fun run(): TransformedApiSpec {
        val endpoints = spec.endpoints.map { endpoint ->
            val opName = endpoint.operationId.toPascalCase()
            val owned = mutableListOf<PlannedInlineType>()

            // Only JSON bodies become a nested body type; form/multipart bodies stay inline so their
            // properties expand into individual function parameters.
            val newRequestBody = endpoint.requestBody?.let {
                if (it.contentType == ContentType.JSON_CONTENT_TYPE) {
                    it.copy(schema = owned.collect(it.schema, "${opName}Request"))
                } else {
                    it
                }
            }
            val newResponses = endpoint.responses.mapValues { (code, response) ->
                response.schema?.let {
                    response.copy(
                        schema = owned.collect(
                            it,
                            when {
                                code.toIntOrNull() in 200..299 -> "${opName}Response"
                                code == "default" -> "${opName}DefaultResponse"
                                else -> "${opName}Response$code"
                            },
                        ),
                    )
                }
                    ?: response
            }

            TransformedEndpoint(endpoint.copy(requestBody = newRequestBody, responses = newResponses), owned)
        }

        val schemas = spec.schemas.map { schema ->
            val owned = mutableListOf<PlannedInlineType>()
            val newProperties = planProperties(schema.properties, owned)
            TransformedSchema(schema.copy(properties = newProperties), owned)
        }

        return TransformedApiSpec(spec.title, spec.version, endpoints, schemas, spec.enums, spec.securitySchemes)
    }
}.run()
