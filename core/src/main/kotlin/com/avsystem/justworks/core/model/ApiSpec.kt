package com.avsystem.justworks.core.model

/**
 * Intermediate model representing a fully parsed OpenAPI specification.
 *
 * Produced by [com.avsystem.justworks.core.parser.SpecParser] and consumed by the
 * code generators. Bridges the raw Swagger Parser OAS model and the generated
 * Kotlin client/model source files.
 */
data class ApiSpec(
    val title: String,
    val version: String,
    val endpoints: List<Endpoint>,
    val schemas: List<SchemaModel>,
    val enums: List<EnumModel>,
)

data class Endpoint(
    val path: String,
    val method: HttpMethod,
    val operationId: String,
    val summary: String?,
    val tags: List<String>,
    val parameters: List<Parameter>,
    val requestBody: RequestBody?,
    val responses: Map<String, Response>,
)

enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH;

    companion object {
        fun parse(name: String): HttpMethod? = entries.find { it.name.equals(name, true) }
    }
}

data class Parameter(
    val name: String,
    val location: ParameterLocation,
    val required: Boolean,
    val schema: TypeRef,
    val description: String?,
)

// todo: add cookie
enum class ParameterLocation {
    PATH,
    QUERY,
    HEADER;

    companion object {
        fun parse(name: String): ParameterLocation? = entries.find { it.name.equals(name, true) }
    }
}

data class RequestBody(
    val required: Boolean,
    val contentType: ContentType,
    val schema: TypeRef,
)

// the order is important!!!
enum class ContentType(val value: String) {
    MULTIPART_FORM_DATA("multipart/form-data"),
    FORM_URL_ENCODED("application/x-www-form-urlencoded"),
    JSON_CONTENT_TYPE("application/json"),
}

data class Response(
    val statusCode: String,
    val description: String?,
    val schema: TypeRef?,
)

data class SchemaModel(
    val name: String,
    val description: String?,
    val properties: List<PropertyModel>,
    val requiredProperties: Set<String>,
    val allOf: List<TypeRef>?,
    val oneOf: List<TypeRef>?,
    val anyOf: List<TypeRef>?,
    val discriminator: Discriminator?,
    val underlyingType: TypeRef? = null,
) {
    val isNested get() = name.contains(".")
}

data class PropertyModel(
    val name: String,
    val type: TypeRef,
    val description: String?,
    val nullable: Boolean,
    val defaultValue: Any? = null,
)

data class EnumModel(
    val name: String,
    val description: String?,
    val type: EnumBackingType,
    val values: List<String>,
)

enum class EnumBackingType {
    STRING,
    INTEGER;

    companion object {
        fun parse(name: String): EnumBackingType? = entries.find { it.name.equals(name, true) }
    }
}

data class Discriminator(val propertyName: String, val mapping: Map<String, String>)
