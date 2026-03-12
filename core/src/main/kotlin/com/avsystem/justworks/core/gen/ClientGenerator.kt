package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.ParameterLocation
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
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
import com.squareup.kotlinpoet.asTypeName
import java.io.File

/**
 * Generates one KotlinPoet [FileSpec] per API tag, each containing a client class
 * with suspend functions for every endpoint in that tag group.
 */
@OptIn(ExperimentalKotlinPoetApi::class)
class ClientGenerator(private val apiPackage: String, private val modelPackage: String,) {
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

        val classBuilder =
            TypeSpec
                .classBuilder(className)
                .addSuperinterface(CLOSEABLE)
                .primaryConstructor(
                    FunSpec
                        .constructorBuilder()
                        .addParameter("baseUrl", STRING)
                        .addParameter("tokenProvider", STRING)
                        .build(),
                ).addProperty(
                    PropertySpec
                        .builder("baseUrl", STRING)
                        .initializer("baseUrl")
                        .addModifiers(KModifier.PRIVATE)
                        .build(),
                ).addProperty(
                    PropertySpec
                        .builder("tokenProvider", STRING)
                        .initializer("tokenProvider")
                        .addModifiers(KModifier.PRIVATE)
                        .build(),
                ).addProperty(
                    PropertySpec
                        .builder("client", HTTP_CLIENT)
                        .addModifiers(KModifier.PRIVATE)
                        .initializer(
                            buildClientInitializer(hasPolymorphicTypes),
                        ).build(),
                ).addFunction(
                    FunSpec
                        .builder("close")
                        .addModifiers(KModifier.OVERRIDE)
                        .addStatement("client.close()")
                        .build(),
                )

        for (endpoint in endpoints) {
            classBuilder.addFunction(generateEndpointFunction(endpoint))
        }

        val encodeParamFun =
            FunSpec
                .builder("encodeParam")
                .addModifiers(KModifier.PRIVATE, KModifier.INLINE)
                .addTypeVariable(
                    com.squareup.kotlinpoet
                        .TypeVariableName("T")
                        .copy(reified = true),
                ).addParameter("value", com.squareup.kotlinpoet.TypeVariableName("T"))
                .returns(STRING)
                .addStatement("return %T.%M(value).trim('\"')", JSON_CLASS, ENCODE_TO_STRING_FUN)
                .build()

        return FileSpec
            .builder(className)
            .addFunction(encodeParamFun)
            .addType(classBuilder.build())
            .build()
    }

    private fun buildClientInitializer(hasPolymorphicTypes: Boolean): CodeBlock {
        val builder = CodeBlock
            .builder()
            .add("%T·{\n", HTTP_CLIENT)
            .indent()
            .add("install(%T)·{\n", CONTENT_NEGOTIATION)
            .indent()

        if (hasPolymorphicTypes) {
            val generatedSerializersModule = MemberName(modelPackage, "generatedSerializersModule")
            builder
                .add("%M(%T·{\n", JSON_FUN, JSON_CLASS)
                .indent()
                .add("serializersModule·=·%M\n", generatedSerializersModule)
                .unindent()
                .add("})\n")
        } else {
            builder.add("%M()\n", JSON_FUN)
        }

        builder
            .unindent()
            .add("}\n")
            .add("expectSuccess·=·false\n")
            .unindent()
            .add("}")

        return builder.build()
    }

    private fun generateEndpointFunction(endpoint: Endpoint): FunSpec {
        val functionName = endpoint.operationId.toCamelCase()
        val returnBodyType = resolveReturnType(endpoint)
        val returnType = HTTP_SUCCESS.parameterizedBy(returnBodyType)

        val funBuilder =
            FunSpec
                .builder(functionName)
                .addModifiers(KModifier.SUSPEND)
                .contextReceivers(listOf(RAISE.parameterizedBy(HTTP_ERROR)))
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

        // Build function body
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

        // Build the URL string with path parameter substitution
        val urlString = buildUrlString(endpoint.path, pathParams)

        val code = CodeBlock.builder()
        code.beginControlFlow("val response = try")

        // client.METHOD(url) { ... }
        code.add("client.%M(%L)", httpMethodFun, urlString)
        code.beginControlFlow("")

        // headers block
        code.beginControlFlow("%M", HEADERS_FUN)
        code.addStatement(
            "append(%T.Authorization, %P)",
            HTTP_HEADERS,
            CodeBlock.of($$"Bearer ${'$'}{$tokenProvider}"),
        )
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
        code.endControlFlow() // headers

        // url block for query params
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
            code.endControlFlow() // url
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

        code.endControlFlow() // request lambda

        code.nextControlFlow("catch (e: %T)", Exception::class)
        code.addStatement(
            "%L(%T(0, e.message ?: \"Network error\", %T.Network))",
            RAISE_FUN,
            HTTP_ERROR,
            HTTP_ERROR_TYPE,
        )
        code.endControlFlow() // try-catch

        // when block for response mapping
        code.beginControlFlow("return when (response.status.value)")
        if (returnBodyType == UNIT) {
            code.addStatement("in 200..299 -> %T(response.status.value, Unit)", HTTP_SUCCESS)
        } else {
            code.addStatement("in 200..299 -> %T(response.status.value, response.%M())", HTTP_SUCCESS, BODY_FUN)
        }
        code.addStatement(
            "in 400..499 -> %L(%T(response.status.value, response.%M(), %T.Client))",
            RAISE_FUN,
            HTTP_ERROR,
            BODY_AS_TEXT_FUN,
            HTTP_ERROR_TYPE,
        )
        code.addStatement(
            "else -> %L(%T(response.status.value, response.%M(), %T.Server))",
            RAISE_FUN,
            HTTP_ERROR,
            BODY_AS_TEXT_FUN,
            HTTP_ERROR_TYPE,
        )
        code.endControlFlow() // when

        return code.build()
    }

    private fun buildUrlString(
        path: String,
        pathParams: List<com.avsystem.justworks.core.model.Parameter>,
    ): CodeBlock {
        // Replace {paramName} with ${paramName} for Kotlin string template
        var template = path
        for (param in pathParams) {
            val camelName = param.name.toCamelCase()
            template = template.replace("{${param.name}}", "\${encodeParam($camelName)}")
        }
        // Use %P for string template that handles $ correctly
        return CodeBlock.of("%P", CodeBlock.of("\${'$'}{baseUrl}$template"))
    }

    private fun resolveReturnType(endpoint: Endpoint): com.squareup.kotlinpoet.TypeName {
        // Find the first 2xx response with a schema
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
