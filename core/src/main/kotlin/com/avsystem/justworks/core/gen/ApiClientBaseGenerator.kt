package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiKeyLocation
import com.avsystem.justworks.core.model.SecurityScheme
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT

/**
 * Generates the shared `ApiClientBase.kt` file containing:
 * - `encodeParam<T>()` top-level utility function
 * - `HttpResponse.deserializeErrorBody<E>()` internal helper for error body deserialization
 * - `HttpResponse.mapToResult<E, T>()` private extension with response mapping logic
 * - `HttpResponse.toResult<E, T>()` extension for typed response mapping
 * - `HttpResponse.toEmptyResult<E>()` extension for Unit response mapping
 * - `ApiClientBase` abstract class with common client infrastructure
 */
@OptIn(ExperimentalKotlinPoetApi::class)
object ApiClientBaseGenerator {
    private const val BLOCK = "block"
    private const val MAP_TO_RESULT = "mapToResult"
    private const val SUCCESS_BODY = "successBody"
    private const val SERIALIZERS_MODULE_PARAM = "serializersModule"

    fun generate(securitySchemes: List<SecurityScheme>? = null): FileSpec {
        val t = TypeVariableName("T").copy(reified = true)
        val e = TypeVariableName("E").copy(reified = true)

        return FileSpec
            .builder(API_CLIENT_BASE)
            .addFunction(buildEncodeParam(t))
            .addFunction(buildDeserializeErrorBody(e))
            .addFunction(buildMapToResult(e, t))
            .addFunction(buildToResult(e, t))
            .addFunction(buildToEmptyResult(e))
            .addType(buildApiClientBaseClass(securitySchemes ?: emptyList(), isExplicit = securitySchemes != null))
            .build()
    }

    private fun buildEncodeParam(t: TypeVariableName): FunSpec = FunSpec
        .builder("encodeParam")
        .addModifiers(KModifier.INLINE)
        .addTypeVariable(t)
        .addParameter("value", TypeVariableName("T"))
        .returns(STRING)
        .addStatement("return %T.%M(value).trim('\"')", JSON_CLASS, ENCODE_TO_STRING_FUN)
        .build()

    private fun buildDeserializeErrorBody(e: TypeVariableName): FunSpec = FunSpec
        .builder("deserializeErrorBody")
        .addAnnotation(PublishedApi::class)
        .addModifiers(KModifier.INTERNAL, KModifier.SUSPEND, KModifier.INLINE)
        .addTypeVariable(e)
        .receiver(HTTP_RESPONSE)
        .returns(TypeVariableName("E").copy(nullable = true))
        .beginControlFlow("return try")
        .addStatement("%M()", BODY_FUN)
        .nextControlFlow("catch (_: %T)", Exception::class)
        .addStatement("null")
        .endControlFlow()
        .build()

    private fun buildMapToResult(e: TypeVariableName, t: TypeVariableName): FunSpec = FunSpec
        .builder(MAP_TO_RESULT)
        .addAnnotation(PublishedApi::class)
        .addModifiers(KModifier.INTERNAL, KModifier.SUSPEND, KModifier.INLINE)
        .addTypeVariable(e)
        .addTypeVariable(t)
        .receiver(HTTP_RESPONSE)
        .addParameter(SUCCESS_BODY, LambdaTypeName.get(returnType = TypeVariableName("T")))
        .returns(HTTP_RESULT.parameterizedBy(TypeVariableName("E"), TypeVariableName("T")))
        .beginControlFlow("return when (status.value)")
        .addStatement(
            "in 200..299 -> %T.Right(%T(status.value, %L()))",
            EITHER,
            HTTP_SUCCESS,
            SUCCESS_BODY,
        ).addStatement(
            "400 -> %T.Left(%T.BadRequest(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "401 -> %T.Left(%T.Unauthorized(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "403 -> %T.Left(%T.Forbidden(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "404 -> %T.Left(%T.NotFound(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "405 -> %T.Left(%T.MethodNotAllowed(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "409 -> %T.Left(%T.Conflict(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "410 -> %T.Left(%T.Gone(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "422 -> %T.Left(%T.UnprocessableEntity(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "429 -> %T.Left(%T.TooManyRequests(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "500 -> %T.Left(%T.InternalServerError(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "502 -> %T.Left(%T.BadGateway(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "503 -> %T.Left(%T.ServiceUnavailable(%M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).addStatement(
            "else -> %T.Left(%T.Other(status.value, %M()))",
            EITHER,
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).endControlFlow()
        .build()

    private fun buildToResult(e: TypeVariableName, t: TypeVariableName): FunSpec = FunSpec
        .builder("toResult")
        .addModifiers(KModifier.SUSPEND, KModifier.INLINE)
        .addTypeVariable(e)
        .addTypeVariable(t)
        .receiver(HTTP_RESPONSE)
        .returns(HTTP_RESULT.parameterizedBy(TypeVariableName("E"), TypeVariableName("T")))
        .addStatement("return %L { %M() }", MAP_TO_RESULT, BODY_FUN)
        .build()

    private fun buildToEmptyResult(e: TypeVariableName): FunSpec = FunSpec
        .builder("toEmptyResult")
        .addModifiers(KModifier.SUSPEND, KModifier.INLINE)
        .addTypeVariable(e)
        .receiver(HTTP_RESPONSE)
        .returns(HTTP_RESULT.parameterizedBy(TypeVariableName("E"), UNIT))
        .addStatement("return %L { Unit }", MAP_TO_RESULT)
        .build()

    private fun buildApiClientBaseClass(securitySchemes: List<SecurityScheme>, isExplicit: Boolean): TypeSpec {
        val tokenType = LambdaTypeName.get(returnType = STRING)
        val authParams = buildAuthConstructorParams(securitySchemes)

        val constructorBuilder = FunSpec
            .constructorBuilder()
            .addParameter(BASE_URL, STRING)

        val propertySpecs = mutableListOf<PropertySpec>()

        val baseUrlProp = PropertySpec
            .builder(BASE_URL, STRING)
            .initializer(BASE_URL)
            .addModifiers(KModifier.PROTECTED)
            .build()
        propertySpecs.add(baseUrlProp)

        if (authParams.isEmpty() && !isExplicit) {
            // Backward compat: no securitySchemes info -> default token param
            constructorBuilder.addParameter(TOKEN, tokenType)
            propertySpecs.add(
                PropertySpec
                    .builder(TOKEN, tokenType)
                    .initializer(TOKEN)
                    .addModifiers(KModifier.PRIVATE)
                    .build(),
            )
        } else {
            for ((paramName, _) in authParams) {
                constructorBuilder.addParameter(paramName, tokenType)
                propertySpecs.add(
                    PropertySpec
                        .builder(paramName, tokenType)
                        .initializer(paramName)
                        .addModifiers(KModifier.PRIVATE)
                        .build(),
                )
            }
        }

        val clientProp = PropertySpec
            .builder(CLIENT, HTTP_CLIENT)
            .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
            .build()

        val closeFun = FunSpec
            .builder("close")
            .addModifiers(KModifier.OVERRIDE)
            .addStatement("$CLIENT.close()")
            .build()

        val classBuilder = TypeSpec
            .classBuilder(API_CLIENT_BASE)
            .addModifiers(KModifier.ABSTRACT)
            .addSuperinterface(CLOSEABLE)
            .primaryConstructor(constructorBuilder.build())

        for (prop in propertySpecs) {
            classBuilder.addProperty(prop)
        }

        return classBuilder
            .addProperty(clientProp)
            .addFunction(closeFun)
            .addFunction(buildApplyAuth(securitySchemes, isExplicit))
            .addFunction(buildSafeCall())
            .addFunction(buildCreateHttpClient())
            .build()
    }

    /**
     * Builds the list of auth-related constructor parameter names based on security schemes.
     * Returns pairs of (paramName, schemeType) for each scheme.
     */
    internal fun buildAuthConstructorParams(
        securitySchemes: List<SecurityScheme>,
    ): List<Pair<String, SecurityScheme>> {
        if (securitySchemes.isEmpty()) return emptyList()

        val isSingleBearer = securitySchemes.size == 1 && securitySchemes.first() is SecurityScheme.Bearer

        return securitySchemes.flatMap { scheme ->
            when (scheme) {
                is SecurityScheme.Bearer -> {
                    val paramName = if (isSingleBearer) TOKEN else "${scheme.name.toCamelCase()}Token"
                    listOf(paramName to scheme)
                }

                is SecurityScheme.ApiKey -> {
                    listOf("${scheme.name.toCamelCase()}Key" to scheme)
                }

                is SecurityScheme.Basic -> {
                    listOf(
                        "${scheme.name.toCamelCase()}Username" to scheme,
                        "${scheme.name.toCamelCase()}Password" to scheme,
                    )
                }
            }
        }
    }

    private fun buildApplyAuth(securitySchemes: List<SecurityScheme>, isExplicit: Boolean): FunSpec {
        val builder = FunSpec
            .builder(APPLY_AUTH)
            .addModifiers(KModifier.PROTECTED)
            .receiver(HTTP_REQUEST_BUILDER)

        // Explicitly empty schemes = no security at all -> empty applyAuth
        if (isExplicit && securitySchemes.isEmpty()) {
            return builder.build()
        }

        // Backward compat: no schemes info means hardcoded Bearer with token param
        if (securitySchemes.isEmpty()) {
            builder.beginControlFlow("%M", HEADERS_FUN)
            builder.addStatement(
                "append(%T.Authorization, %P)",
                HTTP_HEADERS,
                CodeBlock.of($$"Bearer ${'$'}{$$TOKEN()}"),
            )
            builder.endControlFlow()
            return builder.build()
        }

        val isSingleBearer = securitySchemes.size == 1 && securitySchemes.first() is SecurityScheme.Bearer

        val headerSchemes = securitySchemes.filter {
            it is SecurityScheme.Bearer ||
                it is SecurityScheme.Basic ||
                (it is SecurityScheme.ApiKey && it.location == ApiKeyLocation.HEADER)
        }
        val querySchemes = securitySchemes
            .filterIsInstance<SecurityScheme.ApiKey>()
            .filter { it.location == ApiKeyLocation.QUERY }

        if (headerSchemes.isNotEmpty()) {
            builder.beginControlFlow("%M", HEADERS_FUN)
            for (scheme in headerSchemes) {
                when (scheme) {
                    is SecurityScheme.Bearer -> {
                        val paramName = if (isSingleBearer) TOKEN else "${scheme.name.toCamelCase()}Token"
                        builder.addStatement(
                            "append(%T.Authorization, %P)",
                            HTTP_HEADERS,
                            CodeBlock.of("Bearer \${$paramName()}"),
                        )
                    }

                    is SecurityScheme.Basic -> {
                        val usernameParam = "${scheme.name.toCamelCase()}Username"
                        val passwordParam = "${scheme.name.toCamelCase()}Password"
                        builder.addStatement(
                            "append(%T.Authorization, %P)",
                            HTTP_HEADERS,
                            CodeBlock.of(
                                "Basic \${%T.getEncoder().encodeToString(\"${'$'}{$usernameParam()}:${'$'}{$passwordParam()}\".toByteArray())}",
                                BASE64_CLASS,
                            ),
                        )
                    }

                    is SecurityScheme.ApiKey -> {
                        val paramName = "${scheme.name.toCamelCase()}Key"
                        builder.addStatement(
                            "append(%S, $paramName())",
                            scheme.parameterName,
                        )
                    }
                }
            }
            builder.endControlFlow()
        }

        if (querySchemes.isNotEmpty()) {
            builder.beginControlFlow("url")
            for (scheme in querySchemes) {
                val paramName = "${scheme.name.toCamelCase()}Key"
                builder.addStatement(
                    "parameters.append(%S, $paramName())",
                    scheme.parameterName,
                )
            }
            builder.endControlFlow()
        }

        return builder.build()
    }

    private fun buildSafeCall(): FunSpec {
        val e = TypeVariableName("E").copy(reified = true)
        val t = TypeVariableName("T").copy(reified = true)
        val resultType = HTTP_RESULT.parameterizedBy(TypeVariableName("E"), TypeVariableName("T"))
        val blockType = LambdaTypeName.get(returnType = resultType).copy(suspending = true)

        return FunSpec
            .builder(SAFE_CALL)
            .addModifiers(KModifier.PROTECTED, KModifier.SUSPEND, KModifier.INLINE)
            .addTypeVariable(e)
            .addTypeVariable(t)
            .addParameter(BLOCK, blockType)
            .returns(resultType)
            .beginControlFlow("return try")
            .addStatement("%L()", BLOCK)
            .nextControlFlow("catch (e: %T)", IO_EXCEPTION)
            .addStatement("%T.Left(%T.Network(e))", EITHER, HTTP_ERROR)
            .nextControlFlow("catch (e: %T)", HTTP_REQUEST_TIMEOUT_EXCEPTION)
            .addStatement("%T.Left(%T.Network(e))", EITHER, HTTP_ERROR)
            .endControlFlow()
            .build()
    }

    private fun buildCreateHttpClient(): FunSpec = FunSpec
        .builder(CREATE_HTTP_CLIENT)
        .addModifiers(KModifier.PROTECTED)
        .addParameter(
            ParameterSpec
                .builder(SERIALIZERS_MODULE_PARAM, SERIALIZERS_MODULE.copy(nullable = true))
                .defaultValue("null")
                .build(),
        ).returns(HTTP_CLIENT)
        .beginControlFlow("return %T", HTTP_CLIENT)
        .beginControlFlow("install(%T)", CONTENT_NEGOTIATION)
        .beginControlFlow("if ($SERIALIZERS_MODULE_PARAM != null)")
        .addStatement(
            "%M(%T { this.$SERIALIZERS_MODULE_PARAM = $SERIALIZERS_MODULE_PARAM })",
            JSON_FUN,
            JSON_CLASS,
        ).nextControlFlow("else")
        .addStatement("%M()", JSON_FUN)
        .endControlFlow()
        .endControlFlow()
        .addStatement("expectSuccess = false")
        .endControlFlow()
        .build()
}
