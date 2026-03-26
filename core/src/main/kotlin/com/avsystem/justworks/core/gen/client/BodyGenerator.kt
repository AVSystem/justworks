package com.avsystem.justworks.core.gen.client

import com.avsystem.justworks.core.gen.APPLY_AUTH
import com.avsystem.justworks.core.gen.BASE_URL
import com.avsystem.justworks.core.gen.BODY
import com.avsystem.justworks.core.gen.CLIENT
import com.avsystem.justworks.core.gen.CONTENT_TYPE_APPLICATION
import com.avsystem.justworks.core.gen.CONTENT_TYPE_FUN
import com.avsystem.justworks.core.gen.DELETE_FUN
import com.avsystem.justworks.core.gen.ENCODE_PARAM_FUN
import com.avsystem.justworks.core.gen.FORM_DATA_FUN
import com.avsystem.justworks.core.gen.GET_FUN
import com.avsystem.justworks.core.gen.HEADERS_CLASS
import com.avsystem.justworks.core.gen.HEADERS_FUN
import com.avsystem.justworks.core.gen.HTTP_HEADERS
import com.avsystem.justworks.core.gen.HTTP_METHOD_CLASS
import com.avsystem.justworks.core.gen.PARAMETERS_FUN
import com.avsystem.justworks.core.gen.PATCH_FUN
import com.avsystem.justworks.core.gen.POST_FUN
import com.avsystem.justworks.core.gen.PUT_FUN
import com.avsystem.justworks.core.gen.SAFE_CALL
import com.avsystem.justworks.core.gen.SET_BODY_FUN
import com.avsystem.justworks.core.gen.SUBMIT_FORM_FUN
import com.avsystem.justworks.core.gen.SUBMIT_FORM_WITH_BINARY_DATA_FUN
import com.avsystem.justworks.core.gen.TO_EMPTY_RESULT_FUN
import com.avsystem.justworks.core.gen.TO_RESULT_FUN
import com.avsystem.justworks.core.gen.optionalGuard
import com.avsystem.justworks.core.gen.properties
import com.avsystem.justworks.core.gen.requiredProperties
import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toPascalCase
import com.avsystem.justworks.core.model.ContentType
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.Parameter
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import kotlin.collections.orEmpty
import kotlin.collections.plus

internal object BodyGenerator {
    fun buildFunctionBody(
        endpoint: Endpoint,
        params: Map<ParameterLocation, List<Parameter>>,
        returnBodyType: TypeName,
    ): CodeBlock = when (endpoint.requestBody?.contentType) {
        ContentType.MULTIPART_FORM_DATA -> buildMultipartBody(endpoint, params, returnBodyType)
        ContentType.FORM_URL_ENCODED -> buildFormUrlEncodedBody(endpoint, params, returnBodyType)
        ContentType.JSON_CONTENT_TYPE, null -> buildJsonBody(endpoint, params, returnBodyType)
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
        code.addStatement("${APPLY_AUTH}()")

        code.addHeaderParams(params)
        code.addQueryParams(params)

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
        val properties = endpoint.requestBody
            ?.schema
            ?.properties
            .orEmpty()

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
                    CodeBlock.of($$"filename=\"${%L}\"", "${paramName}Name"),
                )
                code.endControlFlow()
                code.add(")\n")
            } else {
                code.addStatement("append(%S, %L)", prop.name, paramName)
            }
        }

        code.endControlFlow() // formData
        code.beginControlFlow(")")
        code.addStatement("${APPLY_AUTH}()")
        code.addHeaderParams(params)
        code.addQueryParams(params)

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

        val properties = endpoint.requestBody
            ?.schema
            ?.properties
            .orEmpty()

        val requiredProperties = endpoint.requestBody
            ?.schema
            ?.requiredProperties
            .orEmpty()

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
        code.addStatement("${APPLY_AUTH}()")
        code.addHeaderParams(params)
        code.addQueryParams(params)

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
            .fold($$"${'$'}{${BASE_URL}}" + endpoint.path to emptyList<Any>()) { (format, args), param ->
                format.replace("{${param.name}}", $$"${%M(%L)}") to args + ENCODE_PARAM_FUN + param.name.toCamelCase()
            }
        return CodeBlock.of("%P", CodeBlock.of(format, *args.toTypedArray<Any>()))
    }

    private fun CodeBlock.Builder.addHeaderParams(params: Map<ParameterLocation, List<Parameter>>) {
        val headerParams = params[ParameterLocation.HEADER]
        if (!headerParams.isNullOrEmpty()) {
            beginControlFlow("%M", HEADERS_FUN)
            for (param in headerParams) {
                val paramName = param.name.toCamelCase()
                optionalGuard(param.required, paramName) {
                    addStatement("append(%S, %M(%L))", param.name, ENCODE_PARAM_FUN, paramName)
                }
            }
            endControlFlow()
        }
    }

    private fun CodeBlock.Builder.addQueryParams(params: Map<ParameterLocation, List<Parameter>>) {
        val queryParams = params[ParameterLocation.QUERY]
        if (!queryParams.isNullOrEmpty()) {
            beginControlFlow("url")
            for (param in queryParams) {
                val paramName = param.name.toCamelCase()
                optionalGuard(param.required, paramName) {
                    addStatement("this.parameters.append(%S, %M(%L))", param.name, ENCODE_PARAM_FUN, paramName)
                }
            }
            endControlFlow()
        }
    }
}
