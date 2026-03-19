package com.avsystem.justworks.core.model

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

enum class HttpMethod { GET, POST, PUT, DELETE, PATCH }

data class Parameter(
    val name: String,
    val location: ParameterLocation,
    val required: Boolean,
    val schema: TypeRef,
    val description: String?,
)

enum class ParameterLocation { PATH, QUERY, HEADER }

data class RequestBody(
    val required: Boolean,
    val contentType: String,
    val schema: TypeRef
)

data class Response(
    val statusCode: String,
    val description: String?,
    val schema: TypeRef?
)

data class SchemaModel(
    val name: String,
    val description: String?,
    val properties: List<PropertyModel>,
    val requiredProperties: Set<String>,
    val isEnum: Boolean,
    val allOf: List<TypeRef>?,
    val oneOf: List<TypeRef>?,
    val anyOf: List<TypeRef>?,
    val discriminator: Discriminator?,
)

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
    val values: List<String>
)

enum class EnumBackingType { STRING, INTEGER }

data class Discriminator(val propertyName: String, val mapping: Map<String, String>)
