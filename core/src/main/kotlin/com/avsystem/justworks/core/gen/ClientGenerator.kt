package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.Parameter
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.RequestBody
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ContextParameter
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

private fun TypeRef.isBinaryUpload(): Boolean = this is TypeRef.Primitive && this.type == PrimitiveType.BYTE_ARRAY

private const val DEFAULT_TAG = "Default"
private const val API_SUFFIX = "Api"
private const val MULTIPART_FORM_DATA = "multipart/form-data"
private const val FORM_URL_ENCODED = "application/x-www-form-urlencoded"

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
            val generatedSerializersModule = MemberName(modelPackage, GENERATED_SERIALIZERS_MODULE)
            CodeBlock.of("$CREATE_HTTP_CLIENT(%M)", generatedSerializersModule)
        } else {
            CodeBlock.of("$CREATE_HTTP_CLIENT()")
        }

        val tokenType = LambdaTypeName.get(returnType = STRING)

        val primaryConstructor = FunSpec
            .constructorBuilder()
            .addParameter(BASE_URL, STRING)
            .addParameter(TOKEN, tokenType)
            .build()

        val httpClientProperty = PropertySpec
            .builder(CLIENT, HTTP_CLIENT)
            .addModifiers(KModifier.OVERRIDE, KModifier.PROTECTED)
            .initializer(clientInitializer)
            .build()

        val classBuilder = TypeSpec
            .classBuilder(className)
            .superclass(API_CLIENT_BASE)
            .addSuperclassConstructorParameter(BASE_URL)
            .addSuperclassConstructorParameter(TOKEN)
            .primaryConstructor(primaryConstructor)
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
        val returnType = HTTP_SUCCESS.parameterizedBy(returnBodyType)

        val funBuilder = FunSpec
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
            val bodyParams = when (endpoint.requestBody.contentType) {
                MULTIPART_FORM_DATA -> buildMultipartParameters(endpoint.requestBody)
                FORM_URL_ENCODED -> buildFormParameters(endpoint.requestBody)
                else -> listOf(buildNullableParameter(endpoint.requestBody.schema, BODY, endpoint.requestBody.required))
            }
            funBuilder.addParameters(bodyParams)
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
    ): CodeBlock = when (endpoint.requestBody?.contentType) {
        MULTIPART_FORM_DATA -> buildMultipartBody(endpoint, params, returnBodyType)
        FORM_URL_ENCODED -> buildFormUrlEncodedBody(endpoint, params, returnBodyType)
        else -> buildJsonBody(endpoint, params, returnBodyType)
    }

    private fun buildJsonBody(
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

        val urlString = buildUrlString(endpoint, params)
        val resultFun = if (returnBodyType == UNIT) TO_EMPTY_RESULT_FUN else TO_RESULT_FUN

        val code = CodeBlock.builder()

        code.beginControlFlow("return $SAFE_CALL")

        code.beginControlFlow("$CLIENT.%M(%L)", httpMethodFun, urlString)
        code.addStatement("$APPLY_AUTH()")

        addHeaderParams(code, params)
        addQueryParams(code, params)

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

    private fun buildMultipartBody(
        endpoint: Endpoint,
        params: Map<ParameterLocation, List<Parameter>>,
        returnBodyType: TypeName,
    ): CodeBlock {
        val urlString = buildUrlString(endpoint, params)
        val resultFun = if (returnBodyType == UNIT) TO_EMPTY_RESULT_FUN else TO_RESULT_FUN
        val properties = endpoint.requestBody!!.schema.properties

        val code = CodeBlock.builder()
        code.beginControlFlow("return $SAFE_CALL")
        code.beginControlFlow(
            "$CLIENT.%M(\nurl = %L,\nformData = %M",
            SUBMIT_FORM_WITH_BINARY_DATA_FUN,
            urlString,
            FORM_DATA_FUN,
        )

        for (prop in properties) {
            val paramName = prop.name.toCamelCase()
            if (prop.type.isBinaryUpload()) {
                code.beginControlFlow(
                    "append(%S, %L, %T.build",
                    prop.name,
                    paramName,
                    HEADERS_CLASS,
                )
                code.addStatement(
                    "append(%T.ContentType, %L.toString())",
                    HTTP_HEADERS,
                    "${paramName}ContentType",
                )
                code.addStatement(
                    "append(%T.ContentDisposition, %P)",
                    HTTP_HEADERS,
                    CodeBlock.of("filename=\"\${%L}\"", "${paramName}Name"),
                )
                code.endControlFlow()
                code.add(")\n")
            } else {
                code.addStatement("append(%S, %L)", prop.name, paramName)
            }
        }

        code.endControlFlow() // formData
        code.beginControlFlow(")")
        code.addStatement("$APPLY_AUTH()")
        addHeaderParams(code, params)
        addQueryParams(code, params)

        if (endpoint.method != HttpMethod.POST) {
            code.addStatement("method = %T.%L", HTTP_METHOD_CLASS, endpoint.method.name.toPascalCase())
        }

        code.endControlFlow()
        code.unindent()
        code.add("}.%M()\n", resultFun)

        return code.build()
    }

    private fun buildFormUrlEncodedBody(
        endpoint: Endpoint,
        params: Map<ParameterLocation, List<Parameter>>,
        returnBodyType: TypeName,
    ): CodeBlock {
        val urlString = buildUrlString(endpoint, params)
        val resultFun = if (returnBodyType == UNIT) TO_EMPTY_RESULT_FUN else TO_RESULT_FUN
        val properties = endpoint.requestBody!!.schema.properties
        val requiredProperties = endpoint.requestBody.schema.requiredProperties

        val code = CodeBlock.builder()
        code.beginControlFlow("return $SAFE_CALL")
        code.beginControlFlow(
            "$CLIENT.%M(\nurl = %L,\nformParameters = %M",
            SUBMIT_FORM_FUN,
            urlString,
            PARAMETERS_FUN,
        )

        for (prop in properties) {
            val paramName = prop.name.toCamelCase()
            val isRequired = prop.name in requiredProperties
            val isString = prop.type == TypeRef.Primitive(PrimitiveType.STRING)
            val valueExpr = if (isString) paramName else "$paramName.toString()"

            code.optionalGuard(isRequired, paramName) {
                addStatement("append(%S, %L)", prop.name, valueExpr)
            }
        }

        code.endControlFlow() // parameters
        code.beginControlFlow(")")
        code.addStatement("$APPLY_AUTH()")
        addHeaderParams(code, params)
        addQueryParams(code, params)

        if (endpoint.method != HttpMethod.POST) {
            code.addStatement("method = %T.%L", HTTP_METHOD_CLASS, endpoint.method.name.toPascalCase())
        }

        code.endControlFlow()
        code.unindent()
        code.add("}.%M()\n", resultFun)

        return code.build()
    }

    private fun buildUrlString(endpoint: Endpoint, params: Map<ParameterLocation, List<Parameter>>): CodeBlock {
        val (format, args) = params[ParameterLocation.PATH]
            .orEmpty()
            .fold($$"${'$'}{$$BASE_URL}" + endpoint.path to emptyList<Any>()) { (format, args), param ->
                format.replace("{${param.name}}", $$"${%M(%L)}") to args + ENCODE_PARAM_FUN + param.name.toCamelCase()
            }
        return CodeBlock.of("%P", CodeBlock.of(format, *args.toTypedArray<Any>()))
    }

    private fun addHeaderParams(code: CodeBlock.Builder, params: Map<ParameterLocation, List<Parameter>>) {
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
    }

    private fun addQueryParams(code: CodeBlock.Builder, params: Map<ParameterLocation, List<Parameter>>) {
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
    }

    private fun buildMultipartParameters(requestBody: RequestBody): List<ParameterSpec> =
        requestBody.schema.properties.flatMap { prop ->
            val name = prop.name.toCamelCase()
            if (prop.type.isBinaryUpload()) {
                listOf(
                    ParameterSpec(name, CHANNEL_PROVIDER),
                    ParameterSpec("${name}Name", STRING),
                    ParameterSpec("${name}ContentType", CONTENT_TYPE_CLASS),
                )
            } else {
                listOf(
                    ParameterSpec(name, TypeMapping.toTypeName(prop.type, modelPackage)),
                )
            }
        }

    private fun buildFormParameters(requestBody: RequestBody): List<ParameterSpec> =
        requestBody.schema.properties.map { prop ->
            val isRequired = requestBody.required && prop.name in requestBody.schema.requiredProperties
            buildNullableParameter(prop.type, prop.name, isRequired)
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
