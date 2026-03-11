package com.avsystem.justworks.core.gen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

// Centralized repository of type and function references used across code generators.
// Organized by domain (HTTP client, serialization, dates, error handling).

// ============================================================================
// Ktor HTTP Client
// ============================================================================

val HTTP_CLIENT = ClassName("io.ktor.client", "HttpClient")
val CONTENT_NEGOTIATION = ClassName("io.ktor.client.plugins.contentnegotiation", "ContentNegotiation")
val HTTP_HEADERS = ClassName("io.ktor.http", "HttpHeaders")

val JSON_FUN = MemberName("io.ktor.serialization.kotlinx.json", "json")
val BODY_FUN = MemberName("io.ktor.client.call", "body")
val BODY_AS_TEXT_FUN = MemberName("io.ktor.client.statement", "bodyAsText")
val SET_BODY_FUN = MemberName("io.ktor.client.request", "setBody")
val CONTENT_TYPE_FUN = MemberName("io.ktor.http", "contentType")
val CONTENT_TYPE_APP_JSON = MemberName("io.ktor.http", "ContentType")
val HEADERS_FUN = MemberName("io.ktor.client.request", "headers")

val GET_FUN = MemberName("io.ktor.client.request", "get")
val POST_FUN = MemberName("io.ktor.client.request", "post")
val PUT_FUN = MemberName("io.ktor.client.request", "put")
val DELETE_FUN = MemberName("io.ktor.client.request", "delete")
val PATCH_FUN = MemberName("io.ktor.client.request", "patch")

// ============================================================================
// kotlinx.serialization
// ============================================================================

val SERIALIZABLE = ClassName("kotlinx.serialization", "Serializable")
val SERIAL_NAME = ClassName("kotlinx.serialization", "SerialName")
val EXPERIMENTAL_SERIALIZATION_API = ClassName("kotlinx.serialization", "ExperimentalSerializationApi")
val SERIALIZATION_EXCEPTION = ClassName("kotlinx.serialization", "SerializationException")
val JSON_CLASS_DISCRIMINATOR = ClassName("kotlinx.serialization.json", "JsonClassDiscriminator")
val JSON_CLASS = ClassName("kotlinx.serialization.json", "Json")
val JSON_CONTENT_POLYMORPHIC_SERIALIZER = ClassName("kotlinx.serialization.json", "JsonContentPolymorphicSerializer")
val JSON_ELEMENT = ClassName("kotlinx.serialization.json", "JsonElement")
val SERIALIZERS_MODULE = ClassName("kotlinx.serialization.modules", "SerializersModule")

val JSON_OBJECT_EXT = MemberName("kotlinx.serialization.json", "jsonObject")

val ENCODE_TO_STRING_FUN = MemberName("kotlinx.serialization", "encodeToString")
val POLYMORPHIC_FUN = MemberName("kotlinx.serialization.modules", "polymorphic")
val SUBCLASS_FUN = MemberName("kotlinx.serialization.modules", "subclass")

// ============================================================================
// Date/Time
// ============================================================================

val INSTANT = ClassName("kotlin.time", "Instant")
val LOCAL_DATE = ClassName("kotlinx.datetime", "LocalDate")

// ============================================================================
// Error Handling (Arrow + Kotlin stdlib)
// ============================================================================

val RAISE = ClassName("arrow.core.raise", "Raise")
val RAISE_FUN = MemberName("arrow.core.raise.context", "raise")
val HTTP_ERROR = ClassName("com.avsystem.justworks", "HttpError")
val HTTP_ERROR_TYPE = ClassName("com.avsystem.justworks", "HttpErrorType")
val HTTP_SUCCESS = ClassName("com.avsystem.justworks", "HttpSuccess")

// ============================================================================
// Kotlin stdlib
// ============================================================================

val CLOSEABLE = ClassName("java.io", "Closeable")
val OPT_IN = ClassName("kotlin", "OptIn")
