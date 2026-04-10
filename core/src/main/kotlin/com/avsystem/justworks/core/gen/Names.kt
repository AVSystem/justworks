package com.avsystem.justworks.core.gen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

// ============================================================================
// Ktor HTTP Client
// ============================================================================

val HTTP_CLIENT = ClassName("io.ktor.client", "HttpClient")
val CONTENT_NEGOTIATION = ClassName("io.ktor.client.plugins.contentnegotiation", "ContentNegotiation")
val HTTP_HEADERS = ClassName("io.ktor.http", "HttpHeaders")

val JSON_FUN = MemberName("io.ktor.serialization.kotlinx.json", "json")
val BODY_FUN = MemberName("io.ktor.client.call", "body")
val SET_BODY_FUN = MemberName("io.ktor.client.request", "setBody")
val CONTENT_TYPE_FUN = MemberName("io.ktor.http", "contentType")
val CONTENT_TYPE_APPLICATION = ClassName("io.ktor.http", "ContentType", "Application")
val HEADERS_FUN = MemberName("io.ktor.client.request", "headers")

val GET_FUN = MemberName("io.ktor.client.request", "get")
val POST_FUN = MemberName("io.ktor.client.request", "post")
val PUT_FUN = MemberName("io.ktor.client.request", "put")
val DELETE_FUN = MemberName("io.ktor.client.request", "delete")
val PATCH_FUN = MemberName("io.ktor.client.request", "patch")

// ============================================================================
// Ktor Forms & Multipart
// ============================================================================

val SUBMIT_FORM_FUN = MemberName("io.ktor.client.request.forms", "submitForm")
val SUBMIT_FORM_WITH_BINARY_DATA_FUN = MemberName("io.ktor.client.request.forms", "submitFormWithBinaryData")
val FORM_DATA_FUN = MemberName("io.ktor.client.request.forms", "formData")
val CHANNEL_PROVIDER = ClassName("io.ktor.client.request.forms", "ChannelProvider")
val PARAMETERS_FUN = MemberName("io.ktor.http", "parameters")
val CONTENT_TYPE_CLASS = ClassName("io.ktor.http", "ContentType")
val HEADERS_CLASS = ClassName("io.ktor.http", "Headers")
val HTTP_METHOD_CLASS = ClassName("io.ktor.http", "HttpMethod")

// ============================================================================
// kotlinx.serialization
// ============================================================================

val SERIALIZABLE = ClassName("kotlinx.serialization", "Serializable")
val USE_SERIALIZERS = ClassName("kotlinx.serialization", "UseSerializers")
val SERIAL_NAME = ClassName("kotlinx.serialization", "SerialName")
val EXPERIMENTAL_SERIALIZATION_API = ClassName("kotlinx.serialization", "ExperimentalSerializationApi")
val SERIALIZATION_EXCEPTION = ClassName("kotlinx.serialization", "SerializationException")
val JSON_CLASS_DISCRIMINATOR = ClassName("kotlinx.serialization.json", "JsonClassDiscriminator")
val JSON_CLASS = ClassName("kotlinx.serialization.json", "Json")
val JSON_CONTENT_POLYMORPHIC_SERIALIZER = ClassName("kotlinx.serialization.json", "JsonContentPolymorphicSerializer")
val JSON_ELEMENT = ClassName("kotlinx.serialization.json", "JsonElement")
val SERIALIZERS_MODULE = ClassName("kotlinx.serialization.modules", "SerializersModule")

val JSON_OBJECT_EXT = MemberName("kotlinx.serialization.json", "jsonObject")

val K_SERIALIZER = ClassName("kotlinx.serialization", "KSerializer")
val SERIAL_DESCRIPTOR = ClassName("kotlinx.serialization.descriptors", "SerialDescriptor")
val PRIMITIVE_SERIAL_DESCRIPTOR_FUN = MemberName("kotlinx.serialization.descriptors", "PrimitiveSerialDescriptor")
val PRIMITIVE_KIND = ClassName("kotlinx.serialization.descriptors", "PrimitiveKind")
val DECODER = ClassName("kotlinx.serialization.encoding", "Decoder")
val ENCODER = ClassName("kotlinx.serialization.encoding", "Encoder")

val ENCODE_TO_STRING_FUN = MemberName("kotlinx.serialization", "encodeToString")
val POLYMORPHIC_FUN = MemberName("kotlinx.serialization.modules", "polymorphic")
val SUBCLASS_FUN = MemberName("kotlinx.serialization.modules", "subclass")

// ============================================================================
// Date/Time (kotlin.time / kotlinx.datetime)
// ============================================================================

val INSTANT = ClassName("kotlin.time", "Instant")
val LOCAL_DATE = ClassName("kotlinx.datetime", "LocalDate")

// ============================================================================
// UUID (kotlin.uuid)
// ============================================================================

val UUID_TYPE = ClassName("kotlin.uuid", "Uuid")
val EXPERIMENTAL_UUID_API = ClassName("kotlin.uuid", "ExperimentalUuidApi")

// ============================================================================
// Error Handling
// ============================================================================

val HTTP_ERROR = ClassName("com.avsystem.justworks", "HttpError")
val HTTP_SUCCESS = ClassName("com.avsystem.justworks", "HttpSuccess")
val HTTP_RESULT = ClassName("com.avsystem.justworks", "HttpResult")
val DESERIALIZE_ERROR_BODY_FUN = MemberName("com.avsystem.justworks", "deserializeErrorBody")

// ============================================================================
// Kotlin stdlib
// ============================================================================

val BASE64_CLASS = ClassName("java.util", "Base64")
val CLOSEABLE = ClassName("java.io", "Closeable")
val IO_EXCEPTION = ClassName("java.io", "IOException")
val HTTP_REQUEST_TIMEOUT_EXCEPTION = ClassName("io.ktor.client.plugins", "HttpRequestTimeoutException")
val OPT_IN = ClassName("kotlin", "OptIn")

// ============================================================================
// Shared client base (generated)
// ============================================================================

val API_CLIENT_BASE = ClassName("com.avsystem.justworks", "ApiClientBase")
val HTTP_RESPONSE = ClassName("io.ktor.client.statement", "HttpResponse")
val HTTP_REQUEST_BUILDER = ClassName("io.ktor.client.request", "HttpRequestBuilder")
val TO_RESULT_FUN = MemberName("com.avsystem.justworks", "toResult")
val TO_EMPTY_RESULT_FUN = MemberName("com.avsystem.justworks", "toEmptyResult")
val ENCODE_PARAM_FUN = MemberName("com.avsystem.justworks", "encodeParam")
val UUID_SERIALIZER = ClassName("com.avsystem.justworks", "UuidSerializer")

// ============================================================================
// Shared property / parameter names (used by multiple generators)
// ============================================================================

const val BASE_URL = "baseUrl"
const val TOKEN = "token"
const val CLIENT = "client"
const val BODY = "body"
const val APPLY_AUTH = "applyAuth"
const val SAFE_CALL = "safeCall"
const val CREATE_HTTP_CLIENT = "createHttpClient"
const val GENERATED_SERIALIZERS_MODULE = "generatedSerializersModule"
