package com.avsystem.justworks.core.gen

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
 * - `HttpResponse.toResult<T>()` extension for typed response mapping
 * - `HttpResponse.toEmptyResult()` extension for Unit response mapping
 * - `ApiClientBase` abstract class with common client infrastructure
 */
@OptIn(ExperimentalKotlinPoetApi::class)
object ApiClientBaseGenerator {
    private const val BASE_URL = "baseUrl"
    private const val TOKEN = "token"
    private const val CLIENT = "client"
    private const val BLOCK = "block"
    private const val ENCODE_PARAM = "encodeParam"
    private const val VALUE = "value"
    private const val TO_RESULT = "toResult"
    private const val TO_EMPTY_RESULT = "toEmptyResult"
    private const val APPLY_AUTH = "applyAuth"
    private const val SAFE_CALL = "safeCall"
    private const val CLOSE = "close"
    private const val CREATE_HTTP_CLIENT = "createHttpClient"
    private const val SERIALIZERS_MODULE_PARAM = "serializersModule"
    private const val NETWORK_ERROR = "Network error"

    fun generate(): FileSpec {
        val t = TypeVariableName("T").copy(reified = true)

        return FileSpec
            .builder(API_CLIENT_BASE)
            .addFunction(buildEncodeParam(t))
            .addFunction(buildToResult(t))
            .addFunction(buildToEmptyResult())
            .addType(buildApiClientBaseClass())
            .build()
    }

    private fun buildEncodeParam(t: TypeVariableName): FunSpec = FunSpec
        .builder(ENCODE_PARAM)
        .addModifiers(KModifier.INLINE)
        .addTypeVariable(t)
        .addParameter(VALUE, TypeVariableName("T"))
        .returns(STRING)
        .addStatement("return %T.%M(value).trim('\"')", JSON_CLASS, ENCODE_TO_STRING_FUN)
        .build()

    private fun buildToResult(t: TypeVariableName): FunSpec = FunSpec
        .builder(TO_RESULT)
        .addModifiers(KModifier.SUSPEND, KModifier.INLINE)
        .addTypeVariable(t)
        .receiver(HTTP_RESPONSE)
        .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
        .returns(HTTP_SUCCESS.parameterizedBy(TypeVariableName("T")))
        .beginControlFlow("return when (status.value)")
        .addStatement("in 200..299 -> %T(status.value, %M())", HTTP_SUCCESS, BODY_FUN)
        .addStatement(
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

    private fun buildToEmptyResult(): FunSpec = FunSpec
        .builder(TO_EMPTY_RESULT)
        .addModifiers(KModifier.SUSPEND)
        .receiver(HTTP_RESPONSE)
        .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
        .returns(HTTP_SUCCESS.parameterizedBy(UNIT))
        .beginControlFlow("return when (status.value)")
        .addStatement("in 200..299 -> %T(status.value, Unit)", HTTP_SUCCESS)
        .addStatement(
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

    private fun buildApiClientBaseClass(): TypeSpec {
        val constructor = FunSpec
            .constructorBuilder()
            .addParameter(BASE_URL, STRING)
            .addParameter(TOKEN, STRING)
            .build()

        val baseUrlProp = PropertySpec
            .builder(BASE_URL, STRING)
            .initializer(BASE_URL)
            .addModifiers(KModifier.PROTECTED)
            .build()

        val tokenProp = PropertySpec
            .builder(TOKEN, STRING)
            .initializer(TOKEN)
            .addModifiers(KModifier.PRIVATE)
            .build()

        val clientProp = PropertySpec
            .builder(CLIENT, HTTP_CLIENT)
            .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
            .build()

        val closeFun = FunSpec
            .builder(CLOSE)
            .addModifiers(KModifier.OVERRIDE)
            .addStatement("client.close()")
            .build()

        return TypeSpec
            .classBuilder(API_CLIENT_BASE)
            .addModifiers(KModifier.ABSTRACT)
            .addSuperinterface(CLOSEABLE)
            .primaryConstructor(constructor)
            .addProperty(baseUrlProp)
            .addProperty(tokenProp)
            .addProperty(clientProp)
            .addFunction(closeFun)
            .addFunction(buildApplyAuth())
            .addFunction(buildSafeCall())
            .addFunction(buildCreateHttpClient())
            .build()
    }

    private fun buildApplyAuth(): FunSpec = FunSpec
        .builder(APPLY_AUTH)
        .addModifiers(KModifier.PROTECTED)
        .receiver(HTTP_REQUEST_BUILDER)
        .beginControlFlow("%M", HEADERS_FUN)
        .addStatement(
            "append(%T.Authorization, %P)",
            HTTP_HEADERS,
            CodeBlock.of($$"Bearer ${'$'}{$token}"),
        ).endControlFlow()
        .build()

    private fun buildSafeCall(): FunSpec = FunSpec
        .builder(SAFE_CALL)
        .addModifiers(KModifier.PROTECTED, KModifier.SUSPEND)
        .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
        .addParameter(BLOCK, LambdaTypeName.get(returnType = HTTP_RESPONSE).copy(suspending = true))
        .returns(HTTP_RESPONSE)
        .beginControlFlow("return %M({ %L() })", CATCH_FUN, BLOCK)
        .addStatement(
            "%M(%T(0, it.message ?: %S, %T.Network))",
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
