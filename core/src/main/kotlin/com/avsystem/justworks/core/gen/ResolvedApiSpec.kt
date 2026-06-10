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
 * An inline (anonymous) schema lifted out to be generated as a type nested inside its owner.
 * [id] is the reference id placed in [TypeRef.Reference] and resolved to the nested class via [Hierarchy].
 */
internal sealed interface NestedType {
    val id: String
    val simpleName: String

    data class Obj(
        override val id: String,
        override val simpleName: String,
        val schema: SchemaModel,
        val children: List<NestedType>,
    ) : NestedType

    data class Enum(
        override val id: String,
        override val simpleName: String,
        val model: EnumModel,
    ) : NestedType
}

/**
 * An [ApiSpec] whose inline schemas have been lifted: every [TypeRef.Inline]/[TypeRef.InlineEnum] is
 * rewritten to a [TypeRef.Reference], and the lifted types travel with the endpoint/schema that owns them.
 */
internal data class ResolvedApiSpec(
    val title: String,
    val version: String,
    val endpoints: List<ResolvedEndpoint>,
    val schemas: List<ResolvedSchema>,
    val enums: List<EnumModel>,
    val securitySchemes: List<SecurityScheme>,
)

internal data class ResolvedEndpoint(val endpoint: Endpoint, val inlineTypes: List<NestedType>)

internal data class ResolvedSchema(val schema: SchemaModel, val inlineTypes: List<NestedType>)

/** Lifts every inline schema (bodies and properties, recursively) into [NestedType] trees. */
internal fun ApiSpec.resolveInlines(): ResolvedApiSpec = object { // object for mutual recursion
    private var counter = 0

    private fun planProperties(properties: List<PropertyModel>, sink: MutableList<NestedType>) =
        properties.map { property ->
            property.copy(type = sink.collect(property.type, property.name.toPascalCase()))
        }

    private fun plan(type: TypeRef, hint: String): Pair<TypeRef, NestedType?> = when (type) {
        is TypeRef.Inline -> {
            val id = "inline${counter++}"
            val children = mutableListOf<NestedType>()
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
            TypeRef.Reference(id) to NestedType.Obj(id, hint, schema, children)
        }

        is TypeRef.InlineEnum -> {
            val id = "inline${counter++}"
            val model = EnumModel(
                name = hint,
                description = null,
                type = type.backingType,
                values = type.values.map { EnumModel.Value(it) },
            )
            TypeRef.Reference(id) to NestedType.Enum(id, hint, model)
        }

        is TypeRef.Array -> {
            plan(type.items, "${hint}Item").let { (item, child) -> type.copy(items = item) to child }
        }

        is TypeRef.Map -> {
            plan(type.valueType, "${hint}Value").let { (value, child) -> TypeRef.Map(value) to child }
        }

        else -> {
            type to null
        }
    }

    private fun MutableList<NestedType>.collect(type: TypeRef, hint: String): TypeRef {
        val (rewritten, nested) = plan(type, hint)
        if (nested != null) add(nested)
        return rewritten
    }

    fun run(): ResolvedApiSpec {
        val spec = this@resolveInlines.stripDiscriminatorProperties()

        val endpoints = spec.endpoints.map { endpoint ->
            val opName = endpoint.operationId.toPascalCase()
            val owned = mutableListOf<NestedType>()

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

            ResolvedEndpoint(endpoint.copy(requestBody = newRequestBody, responses = newResponses), owned)
        }

        val schemas = spec.schemas.map { schema ->
            val owned = mutableListOf<NestedType>()
            val newProperties = planProperties(schema.properties, owned)
            ResolvedSchema(schema.copy(properties = newProperties), owned)
        }

        return ResolvedApiSpec(spec.title, spec.version, endpoints, schemas, spec.enums, spec.securitySchemes)
    }
}.run()

/**
 * Drops the discriminator property from each polymorphic variant: it is emitted via `@SerialName`,
 * never as a field, so keeping it would nest an orphan single-value enum into the variant.
 */
internal fun ApiSpec.stripDiscriminatorProperties(): ApiSpec {
    val discriminatorProps = schemas
        .asSequence()
        .mapNotNull { parent -> parent.discriminator?.propertyName?.let { it to parent } }
        .flatMap { (propertyName, parent) ->
            (parent.oneOf.orEmpty() + parent.anyOf.orEmpty())
                .asSequence()
                .filterIsInstance<TypeRef.Reference>()
                .map { it.schemaName to propertyName }
        }.toMap()

    return if (discriminatorProps.isEmpty()) {
        this
    } else {
        copy(
            schemas = schemas.map { schema ->
                when (val discriminatorProp = discriminatorProps[schema.name]) {
                    null -> schema
                    else -> schema.copy(properties = schema.properties.filterNot { it.name == discriminatorProp })
                }
            },
        )
    }
}
