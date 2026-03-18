package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.Parameter
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ContextParameter
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT

private const val BASE_URL = "baseUrl"
private const val TOKEN = "token"
private const val CLIENT = "client"
private const val BODY = "body"
private const val DEFAULT_TAG = "Default"
private const val API_SUFFIX = "Api"

/**
 * Generates one KotlinPoet [FileSpec] per API tag, each containing a client class
 * that extends `ApiClientBase` with suspend functions for every endpoint in that tag group.
 */
@OptIn(ExperimentalKotlinPoetApi::class)
class ClientGenerator(private val apiPackage: String, private val modelPackage: String) {
    fun generate(spec: ApiSpec, hasPolymorphicTypes: Boolean = false): List<FileSpec> {
        val grouped = spec.endpoints.groupBy { it.tags.firstOrNull() ?: DEFAULT_TAG }
        return grouped.map { (tag, endpoints) -> generateClientFile(tag, endpoints, hasPolymorphicTypes) }
    }

    private fun generateClientFile(
        tag: String,
        endpoints: List<Endpoint>,
        hasPolymorphicTypes: Boolean = false,
    ): FileSpec {
        val className = ClassName(apiPackage, "${tag.toPascalCase()}$API_SUFFIX")

        val clientInitializer = if (hasPolymorphicTypes) {
            val generatedSerializersModule = MemberName(modelPackage, "generatedSerializersModule")
            CodeBlock.of("createHttpClient(%M)", generatedSerializersModule)
        } else {
            CodeBlock.of("createHttpClient()")
        }

        val classBuilder = TypeSpec
            .classBuilder(className)
            .superclass(API_CLIENT_BASE)
            .addSuperclassConstructorParameter(BASE_URL)
            .addSuperclassConstructorParameter(TOKEN)
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter(BASE_URL, STRING)
                    .addParameter(TOKEN, STRING)
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder(CLIENT, HTTP_CLIENT)
                    .addModifiers(KModifier.OVERRIDE, KModifier.PROTECTED)
                    .initializer(clientInitializer)
                    .build(),
            )

        classBuilder.addFunctions(endpoints.map(::generateEndpointFunction))

        return FileSpec
            .builder(className)
            .addType(classBuilder.build())
            .build()
    }

    private fun generateEndpointFunction(endpoint: Endpoint): FunSpec {
        val functionName = endpoint.operationId.toCamelCase()
        val returnBodyType = resolveReturnType(endpoint)
        val returnType = HTTP_SUCCESS.parameterizedBy(returnBodyType)

        val funBuilder =
            FunSpec
                .builder(functionName)
                .addModifiers(KModifier.SUSPEND)
                .contextParameters(listOf(ContextParameter(RAISE.parameterizedBy(HTTP_ERROR))))
                .returns(returnType)

        val params = endpoint.parameters.groupBy { it.location }

        val pathParams = params[ParameterLocation.PATH].orEmpty().map { param ->
            ParameterSpec(param.name.toCamelCase(), TypeMapping.toTypeName(param.schema, modelPackage))
        }

        val queryParams = params[ParameterLocation.QUERY].orEmpty().map { param ->
            buildNullableParameter(param.schema, param.name, param.required)
        }

        val headerParams = params[ParameterLocation.HEADER].orEmpty().map { param ->
            buildNullableParameter(param.schema, param.name, param.required)
        }

        funBuilder.addParameters(pathParams + queryParams + headerParams)

        if (endpoint.requestBody != null) {
            funBuilder.addParameter(
                buildNullableParameter(endpoint.requestBody.schema, BODY, endpoint.requestBody.required),
            )
        }

        funBuilder.addCode(buildFunctionBody(endpoint, params, returnBodyType))

        return funBuilder.build()
    }

    private fun buildNullableParameter(
        typeRef: TypeRef,
        name: String,
        required: Boolean,
    ): ParameterSpec {
        val baseType = TypeMapping.toTypeName(typeRef, modelPackage)

        val builder = ParameterSpec.builder(name.toCamelCase(), baseType.copy(nullable = !required))
        if (!required) builder.defaultValue("null")
        return builder.build()
    }

    private fun buildFunctionBody(
        endpoint: Endpoint,
        params: Map<ParameterLocation, List<Parameter>>,
        returnBodyType: TypeName,
    ): CodeBlock {
        val httpMethodFun =
            when (endpoint.method) {
                HttpMethod.GET -> GET_FUN
                HttpMethod.POST -> POST_FUN
                HttpMethod.PUT -> PUT_FUN
                HttpMethod.DELETE -> DELETE_FUN
                HttpMethod.PATCH -> PATCH_FUN
            }

        val (format, args) = params[ParameterLocation.PATH]
            .orEmpty()
            .fold($$"${'$'}{baseUrl}" + endpoint.path to emptyList<Any>()) { (format, args), param ->
                format.replace("{${param.name}}", $$"${%M(%L)}") to args + ENCODE_PARAM_FUN + param.name.toCamelCase()
            }

        val urlString = CodeBlock.of("%P", CodeBlock.of(format, *args.toTypedArray<Any>()))
        val resultFun = if (returnBodyType == UNIT) TO_EMPTY_RESULT_FUN else TO_RESULT_FUN

        val code = CodeBlock.builder()

        code.beginControlFlow("return safeCall")

        code.beginControlFlow("client.%M(%L)", httpMethodFun, urlString)
        code.addStatement("applyAuth()")

        val headerParams = params[ParameterLocation.HEADER]
        if (!headerParams.isNullOrEmpty()) {
            code.beginControlFlow("%M", HEADERS_FUN)
            for (param in headerParams) {
                val paramName = param.name.toCamelCase()
                code.optionalGuard(param.required, paramName) {
                    addStatement("append(%S, %M(%L))", param.name, ENCODE_PARAM_FUN, paramName)
                }
            }
            code.endControlFlow()
        }

        if (!params[ParameterLocation.QUERY].isNullOrEmpty()) {
            code.beginControlFlow("url")
            for (param in params[ParameterLocation.QUERY]!!) {
                val paramName = param.name.toCamelCase()
                code.optionalGuard(param.required, paramName) {
                    addStatement("this.parameters.append(%S, %M(%L))", param.name, ENCODE_PARAM_FUN, paramName)
                }
            }
            code.endControlFlow()
        }

        if (endpoint.requestBody != null) {
            code.optionalGuard(endpoint.requestBody.required, BODY) {
                addStatement("%M(%T.Json)", CONTENT_TYPE_FUN, CONTENT_TYPE_APPLICATION)
                addStatement("%M(%L)", SET_BODY_FUN, BODY)
            }
        }

        code.endControlFlow() // client.METHOD
        code.unindent()
        code.add("}.%M()\n", resultFun)

        return code.build()
    }

    private fun resolveReturnType(endpoint: Endpoint): TypeName {
        val successResponse =
            endpoint.responses.entries
                .filter { it.key.startsWith("2") }
                .firstNotNullOfOrNull { it.value.schema }

        return if (successResponse != null) {
            TypeMapping.toTypeName(successResponse, modelPackage)
        } else {
            UNIT
        }
    }

    /**
     * If [required], emits [block] directly. Otherwise wraps it in `if (name != null) { ... }`.
     */
    private inline fun CodeBlock.Builder.optionalGuard(
        required: Boolean,
        name: String,
        block: CodeBlock.Builder.() -> Unit,
    ) {
        if (!required) beginControlFlow("if (%L != null)", name)
        block()
        if (!required) endControlFlow()
    }
}
