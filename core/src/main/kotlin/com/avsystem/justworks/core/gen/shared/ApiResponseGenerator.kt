package com.avsystem.justworks.core.gen.shared

import com.avsystem.justworks.core.gen.BODY
import com.avsystem.justworks.core.gen.EITHER
import com.avsystem.justworks.core.gen.HTTP_ERROR
import com.avsystem.justworks.core.gen.HTTP_RESULT
import com.avsystem.justworks.core.gen.HTTP_SUCCESS
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.NOTHING
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.THROWABLE
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

/**
 * Generates [FileSpec]s containing:
 * - `HttpError<out B>` sealed class hierarchy with predefined HTTP error subtypes
 * - `HttpResult<E, T>` typealias as `Either<HttpError<E>, HttpSuccess<T>>`
 * - `HttpSuccess<T>` data class wrapping successful responses
 */
internal object ApiResponseGenerator {
    private const val CODE = "code"

    private val HTTP_ERROR_SUBTYPES = listOf(
        "BadRequest" to 400,
        "Unauthorized" to 401,
        "Forbidden" to 403,
        "NotFound" to 404,
        "MethodNotAllowed" to 405,
        "Conflict" to 409,
        "Gone" to 410,
        "UnprocessableEntity" to 422,
        "TooManyRequests" to 429,
        "InternalServerError" to 500,
        "BadGateway" to 502,
        "ServiceUnavailable" to 503,
    )

    fun generate(): List<FileSpec> = listOf(generateHttpError(), generateHttpSuccess())

    fun generateHttpError(): FileSpec {
        val b = TypeVariableName("B", variance = KModifier.OUT)

        val sealedClass = TypeSpec
            .classBuilder(HTTP_ERROR)
            .addModifiers(KModifier.SEALED)
            .addTypeVariable(b)
            .addProperty(
                PropertySpec
                    .builder(CODE, INT)
                    .addModifiers(KModifier.ABSTRACT)
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder(BODY, b.copy(nullable = true))
                    .addModifiers(KModifier.ABSTRACT)
                    .build(),
            )

        // Predefined HTTP error subtypes with body
        for ((name, statusCode) in HTTP_ERROR_SUBTYPES) {
            sealedClass.addType(buildBodySubtype(name, statusCode))
        }

        // Other: both code and body in constructor
        sealedClass.addType(buildOtherSubtype())

        // Network: no type variable, extends HttpError<Nothing>
        sealedClass.addType(buildNetworkSubtype())

        // HttpResult typealias
        val e = TypeVariableName("E")
        val t = TypeVariableName("T")
        val httpResultAlias = TypeAliasSpec
            .builder(
                "HttpResult",
                EITHER.parameterizedBy(
                    HTTP_ERROR.parameterizedBy(e),
                    HTTP_SUCCESS.parameterizedBy(t),
                ),
            ).addTypeVariable(e)
            .addTypeVariable(t)
            .build()

        return FileSpec
            .builder(HTTP_ERROR)
            .addType(sealedClass.build())
            .addTypeAlias(httpResultAlias)
            .build()
    }

    private fun buildBodySubtype(name: String, statusCode: Int): TypeSpec {
        val b = TypeVariableName("B", variance = KModifier.OUT)
        return TypeSpec
            .classBuilder(name)
            .addModifiers(KModifier.DATA)
            .addTypeVariable(b)
            .superclass(HTTP_ERROR.parameterizedBy(b))
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter(BODY, b)
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder(BODY, b)
                    .initializer(BODY)
                    .addModifiers(KModifier.OVERRIDE)
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder(CODE, INT)
                    .addModifiers(KModifier.OVERRIDE)
                    .getter(
                        FunSpec
                            .getterBuilder()
                            .addStatement("return %L", statusCode)
                            .build(),
                    ).build(),
            ).build()
    }

    private fun buildOtherSubtype(): TypeSpec {
        val b = TypeVariableName("B", variance = KModifier.OUT)
        return TypeSpec
            .classBuilder("Other")
            .addModifiers(KModifier.DATA)
            .addTypeVariable(b)
            .superclass(HTTP_ERROR.parameterizedBy(b))
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter(CODE, INT)
                    .addParameter(BODY, b)
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder(CODE, INT)
                    .initializer(CODE)
                    .addModifiers(KModifier.OVERRIDE)
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder(BODY, b)
                    .initializer(BODY)
                    .addModifiers(KModifier.OVERRIDE)
                    .build(),
            ).build()
    }

    private fun buildNetworkSubtype(): TypeSpec = TypeSpec
        .classBuilder("Network")
        .addModifiers(KModifier.DATA)
        .superclass(HTTP_ERROR.parameterizedBy(NOTHING))
        .primaryConstructor(
            FunSpec
                .constructorBuilder()
                .addParameter(
                    ParameterSpec
                        .builder("cause", THROWABLE.copy(nullable = true))
                        .defaultValue("null")
                        .build(),
                ).build(),
        ).addProperty(
            PropertySpec
                .builder("cause", THROWABLE.copy(nullable = true))
                .initializer("cause")
                .build(),
        ).addProperty(
            PropertySpec
                .builder(CODE, INT)
                .addModifiers(KModifier.OVERRIDE)
                .getter(
                    FunSpec
                        .getterBuilder()
                        .addStatement("return 0")
                        .build(),
                ).build(),
        ).addProperty(
            PropertySpec
                .builder(BODY, NOTHING.copy(nullable = true))
                .addModifiers(KModifier.OVERRIDE)
                .getter(
                    FunSpec
                        .getterBuilder()
                        .addStatement("return null")
                        .build(),
                ).build(),
        ).build()

    fun generateHttpSuccess(): FileSpec {
        val t = TypeVariableName("T")

        val primaryConstructor = FunSpec
            .constructorBuilder()
            .addParameter(CODE, INT)
            .addParameter(BODY, t)
            .build()

        val successType = TypeSpec
            .classBuilder(HTTP_SUCCESS)
            .addModifiers(KModifier.DATA)
            .addTypeVariable(t)
            .primaryConstructor(primaryConstructor)
            .addProperty(PropertySpec.builder(CODE, INT).initializer(CODE).build())
            .addProperty(PropertySpec.builder(BODY, t).initializer(BODY).build())
            .build()

        return FileSpec
            .builder(HTTP_SUCCESS)
            .addType(successType)
            .build()
    }
}
