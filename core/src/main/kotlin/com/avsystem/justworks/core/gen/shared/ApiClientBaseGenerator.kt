package com.avsystem.justworks.core.gen.shared

import com.avsystem.justworks.core.gen.API_CLIENT_BASE
import com.avsystem.justworks.core.gen.APPLY_AUTH
import com.avsystem.justworks.core.gen.BASE64_CLASS
import com.avsystem.justworks.core.gen.BASE_URL
import com.avsystem.justworks.core.gen.BODY_AS_TEXT_FUN
import com.avsystem.justworks.core.gen.BODY_FUN
import com.avsystem.justworks.core.gen.CLIENT
import com.avsystem.justworks.core.gen.CLOSEABLE
import com.avsystem.justworks.core.gen.CONTENT_NEGOTIATION
import com.avsystem.justworks.core.gen.CREATE_HTTP_CLIENT
import com.avsystem.justworks.core.gen.ENCODE_PARAM_FUN
import com.avsystem.justworks.core.gen.ENCODE_TO_STRING_FUN
import com.avsystem.justworks.core.gen.HEADERS_FUN
import com.avsystem.justworks.core.gen.HTTP_CLIENT
import com.avsystem.justworks.core.gen.HTTP_ERROR
import com.avsystem.justworks.core.gen.HTTP_ERROR_TYPE
import com.avsystem.justworks.core.gen.HTTP_HEADERS
import com.avsystem.justworks.core.gen.HTTP_REQUEST_BUILDER
import com.avsystem.justworks.core.gen.HTTP_REQUEST_TIMEOUT_EXCEPTION
import com.avsystem.justworks.core.gen.HTTP_RESPONSE
import com.avsystem.justworks.core.gen.HTTP_SUCCESS
import com.avsystem.justworks.core.gen.IO_EXCEPTION
import com.avsystem.justworks.core.gen.JSON_CLASS
import com.avsystem.justworks.core.gen.JSON_FUN
import com.avsystem.justworks.core.gen.RAISE
import com.avsystem.justworks.core.gen.RAISE_FUN
import com.avsystem.justworks.core.gen.SAFE_CALL
import com.avsystem.justworks.core.gen.SERIALIZERS_MODULE
import com.avsystem.justworks.core.gen.TOKEN
import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.model.ApiKeyLocation
import com.avsystem.justworks.core.model.SecurityScheme
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ContextParameter
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
 * - `HttpResponse.mapToResult<T>()` private extension with response mapping logic
 * - `HttpResponse.toResult<T>()` extension for typed response mapping
 * - `HttpResponse.toEmptyResult()` extension for Unit response mapping
 * - `ApiClientBase` abstract class with common client infrastructure
 */
@OptIn(ExperimentalKotlinPoetApi::class)
internal object ApiClientBaseGenerator {
    private const val SERIALIZERS_MODULE_PARAM = "serializersModule"
    private const val SUCCESS_BODY = "successBody"
    private const val MAP_TO_RESULT = "mapToResult"
    private const val BLOCK = "block"
    private const val NETWORK_ERROR = "Network error"

    fun generate(securitySchemes: List<SecurityScheme>): FileSpec {
        val t = TypeVariableName("T").copy(reified = true)

        return FileSpec
            .builder(API_CLIENT_BASE)
            .addFunction(buildEncodeParam(t))
            .addFunction(buildMapToResult(t))
            .addFunction(buildToResult(t))
            .addFunction(buildToEmptyResult())
            .addType(buildApiClientBaseClass(securitySchemes))
            .build()
    }

    private fun buildEncodeParam(t: TypeVariableName): FunSpec = FunSpec
        .builder(ENCODE_PARAM_FUN.simpleName)
        .addModifiers(KModifier.INLINE)
        .addTypeVariable(t)
        .addParameter("value", TypeVariableName("T"))
        .returns(STRING)
        .addStatement("return %T.%M(value).trim('\"')", JSON_CLASS, ENCODE_TO_STRING_FUN)
        .build()

    private fun buildMapToResult(t: TypeVariableName): FunSpec = FunSpec
        .builder(MAP_TO_RESULT)
        .addAnnotation(PublishedApi::class)
        .addModifiers(KModifier.INTERNAL, KModifier.SUSPEND, KModifier.INLINE)
        .addTypeVariable(t)
        .receiver(HTTP_RESPONSE)
        .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
        .addParameter(SUCCESS_BODY, LambdaTypeName.get(returnType = TypeVariableName("T")))
        .returns(HTTP_SUCCESS.parameterizedBy(TypeVariableName("T")))
        .beginControlFlow("return when (status.value)")
        .addStatement("in 200..299 -> %T(status.value, %L())", HTTP_SUCCESS, SUCCESS_BODY)
        .addStatement(
            "in 300..399 -> %M(%T(status.value, %M(), %T.Redirect))",
            RAISE_FUN,
            HTTP_ERROR,
            BODY_AS_TEXT_FUN,
            HTTP_ERROR_TYPE,
        ).addStatement(
            "in 400..499 -> %M(%T(status.value, %M(), %T.Client))",
            RAISE_FUN,
            HTTP_ERROR,
            BODY_AS_TEXT_FUN,
            HTTP_ERROR_TYPE,
        ).addStatement(
            "else -> %M(%T(status.value, %M(), %T.Server))",
            RAISE_FUN,
            HTTP_ERROR,
            BODY_AS_TEXT_FUN,
            HTTP_ERROR_TYPE,
        ).endControlFlow()
        .build()

    private fun buildToResult(t: TypeVariableName): FunSpec = FunSpec
        .builder("toResult")
        .addModifiers(KModifier.SUSPEND, KModifier.INLINE)
        .addTypeVariable(t)
        .receiver(HTTP_RESPONSE)
        .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
        .returns(HTTP_SUCCESS.parameterizedBy(TypeVariableName("T")))
        .addStatement("return %L { %M() }", MAP_TO_RESULT, BODY_FUN)
        .build()

    private fun buildToEmptyResult(): FunSpec = FunSpec
        .builder("toEmptyResult")
        .addModifiers(KModifier.SUSPEND)
        .receiver(HTTP_RESPONSE)
        .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
        .returns(HTTP_SUCCESS.parameterizedBy(UNIT))
        .addStatement("return %L { Unit }", MAP_TO_RESULT)
        .build()

    private fun buildApiClientBaseClass(securitySchemes: List<SecurityScheme>): TypeSpec {
        val tokenType = LambdaTypeName.get(returnType = STRING)
        val authParams = buildAuthConstructorParams(securitySchemes)

        val constructorBuilder = FunSpec
            .constructorBuilder()
            .addParameter(BASE_URL, STRING)

        val classBuilder = TypeSpec
            .classBuilder(API_CLIENT_BASE)
            .addModifiers(KModifier.ABSTRACT)
            .addSuperinterface(CLOSEABLE)

        val baseUrlProp = PropertySpec
            .builder(BASE_URL, STRING)
            .initializer(BASE_URL)
            .addModifiers(KModifier.PROTECTED)
            .build()

        classBuilder.addProperty(baseUrlProp)

        for ((paramName, _) in authParams) {
            constructorBuilder.addParameter(paramName, tokenType)
            classBuilder.addProperty(
                PropertySpec
                    .builder(paramName, tokenType)
                    .initializer(paramName)
                    .addModifiers(KModifier.PRIVATE)
                    .build(),
            )
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

        return classBuilder
            .primaryConstructor(constructorBuilder.build())
            .addProperty(clientProp)
            .addFunction(closeFun)
            .addFunction(buildApplyAuth(securitySchemes))
            .addFunction(buildSafeCall())
            .addFunction(buildCreateHttpClient())
            .build()
    }

    /**
     * Builds the list of auth-related constructor parameter names based on security schemes.
     * Returns pairs of (paramName, schemeType) for each scheme.
     */
    internal fun buildAuthConstructorParams(securitySchemes: List<SecurityScheme>): List<Pair<String, SecurityScheme>> {
        val isSingleBearer = securitySchemes.size == 1 && securitySchemes.first() is SecurityScheme.Bearer

        return securitySchemes.flatMap { scheme ->
            when (scheme) {
                is SecurityScheme.Bearer -> listOf(
                    (if (isSingleBearer) TOKEN else "${scheme.name.toCamelCase()}Token") to scheme,
                )

                is SecurityScheme.ApiKey -> listOf(
                    "${scheme.name.toCamelCase()}Key" to scheme,
                )

                is SecurityScheme.Basic -> listOf(
                    "${scheme.name.toCamelCase()}Username" to scheme,
                    "${scheme.name.toCamelCase()}Password" to scheme,
                )
            }
        }
    }

    private fun buildApplyAuth(securitySchemes: List<SecurityScheme>): FunSpec {
        val builder = FunSpec
            .builder(APPLY_AUTH)
            .addModifiers(KModifier.PROTECTED)
            .receiver(HTTP_REQUEST_BUILDER)

        if (securitySchemes.isEmpty()) return builder.build()

        val headerSchemes = securitySchemes.filter { scheme ->
            scheme is SecurityScheme.Bearer ||
                scheme is SecurityScheme.Basic ||
                (scheme is SecurityScheme.ApiKey && scheme.location == ApiKeyLocation.HEADER)
        }
        val querySchemes = securitySchemes
            .filterIsInstance<SecurityScheme.ApiKey>()
            .filter { it.location == ApiKeyLocation.QUERY }

        if (headerSchemes.isNotEmpty()) {
            val isSingleBearer = securitySchemes.size == 1 && securitySchemes.first() is SecurityScheme.Bearer

            builder.beginControlFlow("%M", HEADERS_FUN)
            for (scheme in headerSchemes) {
                when (scheme) {
                    is SecurityScheme.Bearer -> {
                        val paramName = if (isSingleBearer) TOKEN else "${scheme.name.toCamelCase()}Token"
                        builder.addStatement(
                            "append(%T.Authorization, %P)",
                            HTTP_HEADERS,
                            CodeBlock.of($$"Bearer ${$$paramName()}"),
                        )
                    }

                    is SecurityScheme.Basic -> {
                        val usernameParam = "${scheme.name.toCamelCase()}Username"
                        val passwordParam = "${scheme.name.toCamelCase()}Password"
                        builder.addStatement(
                            "append(%T.Authorization, %P)",
                            HTTP_HEADERS,
                            CodeBlock.of(
                                $$"Basic ${%T.getEncoder().encodeToString(\"${$$usernameParam()}:${$$passwordParam()}\".toByteArray())}",
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

    private fun buildSafeCall(): FunSpec = FunSpec
        .builder(SAFE_CALL)
        .addModifiers(KModifier.PROTECTED, KModifier.SUSPEND)
        .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
        .addParameter(BLOCK, LambdaTypeName.get(returnType = HTTP_RESPONSE).copy(suspending = true))
        .returns(HTTP_RESPONSE)
        .beginControlFlow("return try")
        .addStatement("%L()", BLOCK)
        .nextControlFlow("catch (e: %T)", IO_EXCEPTION)
        .addStatement(
            "%M(%T(0, e.message ?: %S, %T.Network))",
            RAISE_FUN,
            HTTP_ERROR,
            NETWORK_ERROR,
            HTTP_ERROR_TYPE,
        ).nextControlFlow("catch (e: %T)", HTTP_REQUEST_TIMEOUT_EXCEPTION)
        .addStatement(
            "%M(%T(0, e.message ?: %S, %T.Network))",
            RAISE_FUN,
            HTTP_ERROR,
            NETWORK_ERROR,
            HTTP_ERROR_TYPE,
        ).endControlFlow()
        .build()

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
