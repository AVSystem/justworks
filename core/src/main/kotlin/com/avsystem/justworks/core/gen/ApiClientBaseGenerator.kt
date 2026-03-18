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
import java.io.File

/**
 * Generates the shared `ApiClientBase.kt` file containing:
 * - `encodeParam<T>()` top-level utility function
 * - `HttpResponse.toResult<T>()` extension for typed response mapping
 * - `HttpResponse.toEmptyResult()` extension for Unit response mapping
 * - `ApiClientBase` abstract class with common client infrastructure
 */
@OptIn(ExperimentalKotlinPoetApi::class)
object ApiClientBaseGenerator {
    /**
     * Generates files and writes them to [outputDir].
     * Returns the number of files written.
     */
    fun generateTo(outputDir: File): Int {
        val file = generate()
        file.writeTo(outputDir)
        return 1
    }

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
        .builder("encodeParam")
        .addModifiers(KModifier.INLINE)
        .addTypeVariable(t)
        .addParameter("value", TypeVariableName("T"))
        .returns(STRING)
        .addStatement("return %T.%M(value).trim('\"')", JSON_CLASS, ENCODE_TO_STRING_FUN)
        .build()

    private fun buildToResult(t: TypeVariableName): FunSpec = FunSpec
        .builder("toResult")
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
        .builder("toEmptyResult")
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
        val constructor =
            FunSpec
                .constructorBuilder()
                .addParameter("baseUrl", STRING)
                .addParameter("token", STRING)
                .build()

        val baseUrlProp =
            PropertySpec
                .builder("baseUrl", STRING)
                .initializer("baseUrl")
                .addModifiers(KModifier.PROTECTED)
                .build()

        val tokenProp =
            PropertySpec
                .builder("token", STRING)
                .initializer("token")
                .addModifiers(KModifier.PRIVATE)
                .build()

        val clientProp =
            PropertySpec
                .builder("client", HTTP_CLIENT)
                .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
                .build()

        val closeFun =
            FunSpec
                .builder("close")
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
        .builder("applyAuth")
        .addModifiers(KModifier.PROTECTED)
        .receiver(HTTP_REQUEST_BUILDER)
        .beginControlFlow("%M", HEADERS_FUN)
        .addStatement(
            "append(%T.Authorization, %P)",
            HTTP_HEADERS,
            CodeBlock.of($$"Bearer ${'$'}{$token}"),
        ).endControlFlow()
        .build()

    private fun buildSafeCall(): FunSpec {
        val lambdaType = LambdaTypeName.get(returnType = HTTP_RESPONSE).copy(suspending = true)

        return FunSpec
            .builder("safeCall")
            .addModifiers(KModifier.PROTECTED, KModifier.SUSPEND)
            .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
            .addParameter("block", lambdaType)
            .returns(HTTP_RESPONSE)
            .addCode(
                CodeBlock
                    .builder()
                    .add("return %M({ block() }) { e ->\n", CATCH_FUN)
                    .indent()
                    .addStatement(
                        "%M(%T(0, e.message ?: %S, %T.Network))",
                        RAISE_FUN,
                        HTTP_ERROR,
                        "Network error",
                        HTTP_ERROR_TYPE,
                    ).unindent()
                    .add("}\n")
                    .build(),
            ).build()
    }

    private fun buildCreateHttpClient(): FunSpec {
        val code =
            CodeBlock
                .builder()
                .add("return %T {\n", HTTP_CLIENT)
                .indent()
                .beginControlFlow("install(%T)", CONTENT_NEGOTIATION)
                .beginControlFlow("if (serializersModule != null)")
                .addStatement(
                    "%M(%T { this.serializersModule = serializersModule })",
                    JSON_FUN,
                    JSON_CLASS,
                ).nextControlFlow("else")
                .addStatement("%M()", JSON_FUN)
                .endControlFlow()
                .endControlFlow()
                .addStatement("expectSuccess = false")
                .unindent()
                .add("}\n")
                .build()

        return FunSpec
            .builder("createHttpClient")
            .addModifiers(KModifier.PROTECTED)
            .addParameter(
                ParameterSpec
                    .builder("serializersModule", SERIALIZERS_MODULE.copy(nullable = true))
                    .defaultValue("null")
                    .build(),
            ).returns(HTTP_CLIENT)
            .addCode(code)
            .build()
    }
}
