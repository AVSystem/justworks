package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.ParameterLocation
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
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import java.io.File

/**
 * Generates one KotlinPoet [FileSpec] per API tag, each containing a client class
 * that extends `ApiClientBase` with suspend functions for every endpoint in that tag group.
 */
@OptIn(ExperimentalKotlinPoetApi::class)
class ClientGenerator(private val apiPackage: String, private val modelPackage: String) {
    fun generate(spec: ApiSpec, hasPolymorphicTypes: Boolean = false): List<FileSpec> {
        val grouped = spec.endpoints.groupBy { it.tags.firstOrNull() ?: "Default" }
        return grouped.map { (tag, endpoints) ->
            generateClientFile(tag, endpoints, hasPolymorphicTypes)
        }
    }

    /**
     * Generates client files from [spec] and writes them to [outputDir].
     * Returns the number of files written.
     */
    fun generateTo(
        spec: ApiSpec,
        outputDir: File,
        hasPolymorphicTypes: Boolean = false,
    ): Int {
        val files = generate(spec, hasPolymorphicTypes)
        for (fileSpec in files) {
            fileSpec.writeTo(outputDir)
        }
        return files.size
    }

    private fun generateClientFile(
        tag: String,
        endpoints: List<Endpoint>,
        hasPolymorphicTypes: Boolean = false,
    ): FileSpec {
        val className = ClassName(apiPackage, "${tag.toPascalCase()}Api")

        val clientInitializer = if (hasPolymorphicTypes) {
            val generatedSerializersModule = MemberName(modelPackage, "generatedSerializersModule")
            CodeBlock.of("createHttpClient(%M)", generatedSerializersModule)
        } else {
            CodeBlock.of("createHttpClient()")
        }

        val classBuilder =
            TypeSpec
                .classBuilder(className)
                .superclass(API_CLIENT_BASE)
                .addSuperclassConstructorParameter("baseUrl")
                .addSuperclassConstructorParameter("tokenProvider")
                .primaryConstructor(
                    FunSpec
                        .constructorBuilder()
                        .addParameter("baseUrl", STRING)
                        .addParameter("tokenProvider", STRING)
                        .build(),
                ).addProperty(
                    PropertySpec
                        .builder("client", HTTP_CLIENT)
                        .addModifiers(KModifier.OVERRIDE, KModifier.PROTECTED)
                        .initializer(clientInitializer)
                        .build(),
                )

        for (endpoint in endpoints) {
            classBuilder.addFunction(generateEndpointFunction(endpoint))
        }

        return FileSpec
            .builder(className)
            .addImport("com.avsystem.justworks", "encodeParam")
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

        // Add parameters: PATH first, then QUERY, then HEADER, then body
        val pathParams = endpoint.parameters.filter { it.location == ParameterLocation.PATH }
        val queryParams = endpoint.parameters.filter { it.location == ParameterLocation.QUERY }
        val headerParams = endpoint.parameters.filter { it.location == ParameterLocation.HEADER }

        for (param in pathParams) {
            val type = TypeMapping.toTypeName(param.schema, modelPackage)
            funBuilder.addParameter(param.name.toCamelCase(), type)
        }

        for (param in queryParams) {
            val baseType = TypeMapping.toTypeName(param.schema, modelPackage)
            if (param.required) {
                funBuilder.addParameter(param.name.toCamelCase(), baseType)
            } else {
                funBuilder.addParameter(
                    ParameterSpec
                        .builder(param.name.toCamelCase(), baseType.copy(nullable = true))
                        .defaultValue("null")
                        .build(),
                )
            }
        }

        for (param in headerParams) {
            val baseType = TypeMapping.toTypeName(param.schema, modelPackage)
            if (param.required) {
                funBuilder.addParameter(param.name.toCamelCase(), baseType)
            } else {
                funBuilder.addParameter(
                    ParameterSpec
                        .builder(param.name.toCamelCase(), baseType.copy(nullable = true))
                        .defaultValue("null")
                        .build(),
                )
            }
        }

        if (endpoint.requestBody != null) {
            val bodyType = TypeMapping.toTypeName(endpoint.requestBody.schema, modelPackage)
            if (endpoint.requestBody.required) {
                funBuilder.addParameter("body", bodyType)
            } else {
                funBuilder.addParameter(
                    ParameterSpec
                        .builder("body", bodyType.copy(nullable = true))
                        .defaultValue("null")
                        .build(),
                )
            }
        }

        funBuilder.addCode(buildFunctionBody(endpoint, pathParams, queryParams, headerParams, returnBodyType))

        return funBuilder.build()
    }

    private fun buildFunctionBody(
        endpoint: Endpoint,
        pathParams: List<com.avsystem.justworks.core.model.Parameter>,
        queryParams: List<com.avsystem.justworks.core.model.Parameter>,
        headerParams: List<com.avsystem.justworks.core.model.Parameter>,
        returnBodyType: com.squareup.kotlinpoet.TypeName,
    ): CodeBlock {
        val httpMethodFun =
            when (endpoint.method) {
                HttpMethod.GET -> GET_FUN
                HttpMethod.POST -> POST_FUN
                HttpMethod.PUT -> PUT_FUN
                HttpMethod.DELETE -> DELETE_FUN
                HttpMethod.PATCH -> PATCH_FUN
            }

        val urlString = buildUrlString(endpoint.path, pathParams)
        val resultFun = if (returnBodyType == UNIT) TO_EMPTY_RESULT_FUN else TO_RESULT_FUN

        val code = CodeBlock.builder()

        // safeCall {
        code.add("return safeCall {\n")
        code.indent()

        // client.METHOD(url) {
        code.add("client.%M(%L) {\n", httpMethodFun, urlString)
        code.indent()

        // applyAuth()
        code.addStatement("applyAuth()")

        // custom headers (if any)
        if (headerParams.isNotEmpty()) {
            code.beginControlFlow("%M", HEADERS_FUN)
            for (param in headerParams) {
                val paramName = param.name.toCamelCase()
                if (param.required) {
                    code.addStatement("append(%S, encodeParam(%L))", param.name, paramName)
                } else {
                    code.beginControlFlow("if (%L != null)", paramName)
                    code.addStatement("append(%S, encodeParam(%L))", param.name, paramName)
                    code.endControlFlow()
                }
            }
            code.endControlFlow()
        }

        // query params (if any)
        if (queryParams.isNotEmpty()) {
            code.beginControlFlow("url")
            for (param in queryParams) {
                val paramName = param.name.toCamelCase()
                if (param.required) {
                    code.addStatement("this.parameters.append(%S, encodeParam(%L))", param.name, paramName)
                } else {
                    code.beginControlFlow("if (%L != null)", paramName)
                    code.addStatement("this.parameters.append(%S, encodeParam(%L))", param.name, paramName)
                    code.endControlFlow()
                }
            }
            code.endControlFlow()
        }

        // request body
        if (endpoint.requestBody != null) {
            if (endpoint.requestBody.required) {
                code.addStatement("%M(%M.Application.Json)", CONTENT_TYPE_FUN, CONTENT_TYPE_APP_JSON)
                code.addStatement("%M(body)", SET_BODY_FUN)
            } else {
                code.beginControlFlow("if (body != null)")
                code.addStatement("%M(%M.Application.Json)", CONTENT_TYPE_FUN, CONTENT_TYPE_APP_JSON)
                code.addStatement("%M(body)", SET_BODY_FUN)
                code.endControlFlow()
            }
        }

        code.unindent()
        code.add("}\n") // close request lambda

        code.unindent()
        code.add("}.%M()\n", resultFun) // close safeCall + chain toResult

        return code.build()
    }

    private fun buildUrlString(
        path: String,
        pathParams: List<com.avsystem.justworks.core.model.Parameter>,
    ): CodeBlock {
        var template = path
        for (param in pathParams) {
            val camelName = param.name.toCamelCase()
            template = template.replace("{${param.name}}", "\${encodeParam($camelName)}")
        }
        return CodeBlock.of("%P", CodeBlock.of("\${'$'}{baseUrl}$template"))
    }

    private fun resolveReturnType(endpoint: Endpoint): com.squareup.kotlinpoet.TypeName {
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
}
