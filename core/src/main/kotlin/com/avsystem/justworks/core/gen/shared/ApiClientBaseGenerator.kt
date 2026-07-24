package com.avsystem.justworks.core.gen.shared

import com.avsystem.justworks.core.gen.API_CLIENT_BASE
import com.avsystem.justworks.core.gen.APPLY_AUTH
import com.avsystem.justworks.core.gen.BASE_URL
import com.avsystem.justworks.core.gen.BODY_FUN
import com.avsystem.justworks.core.gen.CLIENT
import com.avsystem.justworks.core.gen.CLOSEABLE
import com.avsystem.justworks.core.gen.CONTENT_NEGOTIATION
import com.avsystem.justworks.core.gen.CREATE_HTTP_CLIENT
import com.avsystem.justworks.core.gen.DESERIALIZE_ERROR_BODY_FUN
import com.avsystem.justworks.core.gen.ENCODE_PARAM_FUN
import com.avsystem.justworks.core.gen.ENCODE_PATH_PARAM_FUN
import com.avsystem.justworks.core.gen.ENCODE_TO_JSON_ELEMENT_FUN
import com.avsystem.justworks.core.gen.ENCODE_URL_PATH_PART_FUN
import com.avsystem.justworks.core.gen.ENUM_CLASS
import com.avsystem.justworks.core.gen.HTTP_CLIENT
import com.avsystem.justworks.core.gen.HTTP_ERROR
import com.avsystem.justworks.core.gen.HTTP_REQUEST_BUILDER
import com.avsystem.justworks.core.gen.HTTP_REQUEST_TIMEOUT_EXCEPTION
import com.avsystem.justworks.core.gen.HTTP_RESPONSE
import com.avsystem.justworks.core.gen.HTTP_RESULT
import com.avsystem.justworks.core.gen.HTTP_SUCCESS
import com.avsystem.justworks.core.gen.IO_EXCEPTION
import com.avsystem.justworks.core.gen.JSON_CLASS
import com.avsystem.justworks.core.gen.JSON_FUN
import com.avsystem.justworks.core.gen.JSON_PRIMITIVE_EXT
import com.avsystem.justworks.core.gen.SAFE_CALL
import com.avsystem.justworks.core.gen.SERIALIZERS_MODULE
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.NUMBER
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT

/**
 * Generates the shared `ApiClientBase.kt` file containing:
 * - `encodeParam()` / `encodePathParam()` top-level overload sets — String/Number/Boolean plus a
 *   reified `Enum<T>` overload — no generic passthrough, so a value that can't serialize to a JSON
 *   primitive (an object, a list, a map, a raw byte array) fails to compile instead of throwing at
 *   runtime. Int/Long/Double/Float are covered by the Number overload via ordinary subtyping.
 *   Uuid/Instant/LocalDate are deliberately NOT overloads here: since this file is generated once,
 *   independent of any spec, an unconditional Uuid/LocalDate reference would force every consumer
 *   to opt into ExperimentalUuidApi / depend on kotlinx-datetime even if their spec never uses
 *   them. BodyGenerator instead renders those three as a direct `.toString()` call at the call
 *   site, in the per-spec client file that already imports the type only when it's actually used.
 * - `HttpResponse.deserializeErrorBody<E>()` internal helper for error body deserialization
 * - `HttpResponse.mapToResult<E, T>()` private extension with response mapping logic
 * - `HttpResponse.toResult<E, T>()` extension for typed response mapping
 * - `HttpResponse.toEmptyResult<E>()` extension for Unit response mapping
 * - `ApiClientBase` abstract class with common client infrastructure
 */
internal object ApiClientBaseGenerator {
    private const val SERIALIZERS_MODULE_PARAM = "serializersModule"
    private const val SUCCESS_BODY = "successBody"
    private const val MAP_TO_RESULT = "mapToResult"
    private const val BLOCK = "block"

    // Kotlin types that are always JSON primitives, mapped to how to render them as a String.
    // `null` conversion means the value already is a String (returned as-is).
    private val PRIMITIVE_SAFE_TYPES: List<Pair<TypeName, String?>> = listOf(
        STRING to null,
        NUMBER to "toString",
        BOOLEAN to "toString",
    )

    fun generate(): FileSpec {
        val e = TypeVariableName("E").copy(reified = true)

        return FileSpec
            .builder(API_CLIENT_BASE)
            .addFunctions(buildEncodeParamOverloads())
            .addFunctions(buildEncodePathParamOverloads())
            .addFunction(buildDeserializeErrorBody(e))
            .addFunction(buildMapToResult(e, TypeVariableName("T").copy(reified = true)))
            .addFunction(buildToResult(e, TypeVariableName("T").copy(reified = true)))
            .addFunction(buildToEmptyResult(e))
            .addType(buildApiClientBaseClass())
            .build()
    }

    private fun enumTypeVariable(): TypeVariableName = TypeVariableName(
        "T",
        ENUM_CLASS.parameterizedBy(TypeVariableName("T")),
    ).copy(reified = true)

    private fun buildEncodeParamOverloads(): List<FunSpec> {
        val simpleOverloads = PRIMITIVE_SAFE_TYPES.map { (type, conversion) ->
            FunSpec
                .builder(ENCODE_PARAM_FUN.simpleName)
                .addParameter("value", type)
                .returns(STRING)
                .addStatement(if (conversion == null) "return value" else "return value.$conversion()")
                .build()
        }

        val enumOverload = FunSpec
            .builder(ENCODE_PARAM_FUN.simpleName)
            .addModifiers(KModifier.INLINE)
            .addTypeVariable(enumTypeVariable())
            .addParameter("value", TypeVariableName("T"))
            .returns(STRING)
            .addStatement(
                "return %T.%M(value).%M.content",
                JSON_CLASS,
                ENCODE_TO_JSON_ELEMENT_FUN,
                JSON_PRIMITIVE_EXT,
            ).build()

        return simpleOverloads + enumOverload
    }

    private fun buildEncodePathParamOverloads(): List<FunSpec> {
        val simpleOverloads = PRIMITIVE_SAFE_TYPES.map { (type, _) ->
            FunSpec
                .builder(ENCODE_PATH_PARAM_FUN.simpleName)
                .addParameter("value", type)
                .returns(STRING)
                .addStatement("return %M(value).%M()", ENCODE_PARAM_FUN, ENCODE_URL_PATH_PART_FUN)
                .build()
        }

        val enumOverload = FunSpec
            .builder(ENCODE_PATH_PARAM_FUN.simpleName)
            .addModifiers(KModifier.INLINE)
            .addTypeVariable(enumTypeVariable())
            .addParameter("value", TypeVariableName("T"))
            .returns(STRING)
            .addStatement("return %M(value).%M()", ENCODE_PARAM_FUN, ENCODE_URL_PATH_PART_FUN)
            .build()

        return simpleOverloads + enumOverload
    }

    private fun buildDeserializeErrorBody(e: TypeVariableName): FunSpec = FunSpec
        .builder("deserializeErrorBody")
        .addAnnotation(PublishedApi::class)
        .addModifiers(KModifier.INTERNAL, KModifier.SUSPEND, KModifier.INLINE)
        .addTypeVariable(e)
        .receiver(HTTP_RESPONSE)
        .returns(TypeVariableName("E").copy(nullable = true))
        .beginControlFlow("return try")
        .addStatement("%M()", BODY_FUN)
        .nextControlFlow("catch (e: %T)", Exception::class)
        .addStatement("if (e is %T) throw e", ClassName("kotlinx.coroutines", "CancellationException"))
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
            "in 200..299 -> %T(status.value, %L())",
            HTTP_SUCCESS,
            SUCCESS_BODY,
        ).addStatement(
            "in 300..399 -> %T.Redirect(status.value, %M())",
            HTTP_ERROR,
            DESERIALIZE_ERROR_BODY_FUN,
        ).apply {
            for ((name, code) in ApiResponseGenerator.HTTP_ERROR_SUBTYPES) {
                addStatement(
                    "$code -> %T.$name(%M())",
                    HTTP_ERROR,
                    DESERIALIZE_ERROR_BODY_FUN,
                )
            }
        }.addStatement(
            "else -> %T.Other(status.value, %M())",
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

    private fun buildApiClientBaseClass(): TypeSpec {
        val constructor = FunSpec
            .constructorBuilder()
            .addParameter(BASE_URL, STRING)
            .build()

        val baseUrlProp = PropertySpec
            .builder(BASE_URL, STRING)
            .initializer(BASE_URL)
            .addModifiers(KModifier.PROTECTED)
            .build()

        val clientProp = PropertySpec
            .builder(CLIENT, HTTP_CLIENT)
            .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
            .build()

        val closeFun = FunSpec
            .builder("close")
            .addModifiers(KModifier.OVERRIDE)
            .addStatement("$CLIENT.close()")
            .build()

        return TypeSpec
            .classBuilder(API_CLIENT_BASE)
            .addModifiers(KModifier.ABSTRACT)
            .addSuperinterface(CLOSEABLE)
            .primaryConstructor(constructor)
            .addProperty(baseUrlProp)
            .addProperty(clientProp)
            .addFunction(closeFun)
            .addFunction(buildApplyAuth())
            .addFunction(buildSafeCall())
            .addFunction(buildCreateHttpClient())
            .build()
    }

    private fun buildApplyAuth(): FunSpec = FunSpec
        .builder(APPLY_AUTH)
        .addModifiers(KModifier.PROTECTED, KModifier.OPEN)
        .receiver(HTTP_REQUEST_BUILDER)
        .build()

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
            .addStatement("%T.Network(e)", HTTP_ERROR)
            .nextControlFlow("catch (e: %T)", HTTP_REQUEST_TIMEOUT_EXCEPTION)
            .addStatement("%T.Network(e)", HTTP_ERROR)
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
