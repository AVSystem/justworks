package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.Parameter
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.SecurityScheme
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT

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
        return grouped.map { (tag, endpoints) ->
            generateClientFile(tag, endpoints, hasPolymorphicTypes, spec.securitySchemes)
        }
    }

    private fun generateClientFile(
        tag: String,
        endpoints: List<Endpoint>,
        hasPolymorphicTypes: Boolean = false,
        securitySchemes: List<SecurityScheme> = emptyList(),
    ): FileSpec {
        val className = ClassName(apiPackage, "${tag.toPascalCase()}$API_SUFFIX")

        val clientInitializer = if (hasPolymorphicTypes) {
            val generatedSerializersModule = MemberName(modelPackage, GENERATED_SERIALIZERS_MODULE)
            CodeBlock.of("$CREATE_HTTP_CLIENT(%M)", generatedSerializersModule)
        } else {
            CodeBlock.of("$CREATE_HTTP_CLIENT()")
        }

        val tokenType = LambdaTypeName.get(returnType = STRING)
        val authParams = ApiClientBaseGenerator.buildAuthConstructorParams(securitySchemes)

        val constructorBuilder = FunSpec
            .constructorBuilder()
            .addParameter(BASE_URL, STRING)

        val classBuilder = TypeSpec
            .classBuilder(className)
            .superclass(API_CLIENT_BASE)
            .addSuperclassConstructorParameter(BASE_URL)

        if (authParams.isEmpty() && securitySchemes.isEmpty()) {
            // Backward compat: no security info -> default token param
            constructorBuilder.addParameter(TOKEN, tokenType)
            classBuilder.addSuperclassConstructorParameter(TOKEN)
        } else {
            for ((paramName, _) in authParams) {
                constructorBuilder.addParameter(paramName, tokenType)
                classBuilder.addSuperclassConstructorParameter(paramName)
            }
        }

        val httpClientProperty = PropertySpec
            .builder(CLIENT, HTTP_CLIENT)
            .addModifiers(KModifier.OVERRIDE, KModifier.PROTECTED)
            .initializer(clientInitializer)
            .build()

        classBuilder
            .primaryConstructor(constructorBuilder.build())
            .addProperty(httpClientProperty)

        classBuilder.addFunctions(endpoints.map(::generateEndpointFunction))

        return FileSpec
            .builder(className)
            .addType(classBuilder.build())
            .build()
    }

    private fun generateEndpointFunction(endpoint: Endpoint): FunSpec {
        val functionName = endpoint.operationId.toCamelCase()
        val returnBodyType = resolveReturnType(endpoint)
        val errorType = resolveErrorType(endpoint)
        val returnType = HTTP_RESULT.parameterizedBy(errorType, returnBodyType)

        val funBuilder = FunSpec
            .builder(functionName)
            .addModifiers(KModifier.SUSPEND)
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
        val httpMethodFun = when (endpoint.method) {
            HttpMethod.GET -> GET_FUN
            HttpMethod.POST -> POST_FUN
            HttpMethod.PUT -> PUT_FUN
            HttpMethod.DELETE -> DELETE_FUN
            HttpMethod.PATCH -> PATCH_FUN
        }

        val (format, args) = params[ParameterLocation.PATH]
            .orEmpty()
            .fold($$"${'$'}{$$BASE_URL}" + endpoint.path to emptyList<Any>()) { (format, args), param ->
                format.replace("{${param.name}}", $$"${%M(%L)}") to args + ENCODE_PARAM_FUN + param.name.toCamelCase()
            }

        val urlString = CodeBlock.of("%P", CodeBlock.of(format, *args.toTypedArray<Any>()))
        val resultFun = if (returnBodyType == UNIT) TO_EMPTY_RESULT_FUN else TO_RESULT_FUN

        val code = CodeBlock.builder()

        code.beginControlFlow("return $SAFE_CALL")

        code.beginControlFlow("$CLIENT.%M(%L)", httpMethodFun, urlString)
        code.addStatement("$APPLY_AUTH()")

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

        val queryParams = params[ParameterLocation.QUERY]
        if (!queryParams.isNullOrEmpty()) {
            code.beginControlFlow("url")
            for (param in queryParams) {
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

        // Close client.METHOD block and chain .toResult() / .toEmptyResult()
        code.unindent()
        code.add("}.%M()\n", resultFun)
        code.endControlFlow() // safeCall

        return code.build()
    }

    private fun resolveErrorType(endpoint: Endpoint): TypeName {
        val errorSchemas = endpoint.responses.entries
            .asSequence()
            .filter { !it.key.startsWith("2") }
            .mapNotNull { it.value.schema }
            .map { TypeMapping.toTypeName(it, modelPackage) }
            .distinct()
            .toList()

        return when {
            errorSchemas.size == 1 -> errorSchemas.single()
            else -> JSON_ELEMENT
        }
    }

    private fun resolveReturnType(endpoint: Endpoint): TypeName = endpoint.responses.entries
        .asSequence()
        .filter { it.key.startsWith("2") }
        .firstNotNullOfOrNull { it.value.schema }
        ?.let { successResponse -> TypeMapping.toTypeName(successResponse, modelPackage) }
        ?: UNIT

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
