package com.avsystem.justworks.core.gen.client

import com.avsystem.justworks.core.gen.API_CLIENT_BASE
import com.avsystem.justworks.core.gen.APPLY_AUTH
import com.avsystem.justworks.core.gen.ApiPackage
import com.avsystem.justworks.core.gen.BASE64_CLASS
import com.avsystem.justworks.core.gen.BASE_URL
import com.avsystem.justworks.core.gen.CLIENT
import com.avsystem.justworks.core.gen.CREATE_HTTP_CLIENT
import com.avsystem.justworks.core.gen.GENERATED_SERIALIZERS_MODULE
import com.avsystem.justworks.core.gen.HEADERS_FUN
import com.avsystem.justworks.core.gen.HTTP_CLIENT
import com.avsystem.justworks.core.gen.HTTP_HEADERS
import com.avsystem.justworks.core.gen.HTTP_REQUEST_BUILDER
import com.avsystem.justworks.core.gen.HTTP_SUCCESS
import com.avsystem.justworks.core.gen.Hierarchy
import com.avsystem.justworks.core.gen.NameRegistry
import com.avsystem.justworks.core.gen.client.BodyGenerator.buildFunctionBody
import com.avsystem.justworks.core.gen.client.ParametersGenerator.buildBodyParams
import com.avsystem.justworks.core.gen.client.ParametersGenerator.buildNullableParameter
import com.avsystem.justworks.core.gen.invoke
import com.avsystem.justworks.core.gen.sanitizeKdoc
import com.avsystem.justworks.core.gen.shared.AuthParam
import com.avsystem.justworks.core.gen.shared.toAuthParam
import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toPascalCase
import com.avsystem.justworks.core.gen.toTypeName
import com.avsystem.justworks.core.model.ApiKeyLocation
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.SecurityScheme
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
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

/**
 * Generates one KotlinPoet [FileSpec] per API tag, each containing a client class
 * that extends `ApiClientBase` with suspend functions for every endpoint in that tag group.
 */

internal object ClientGenerator {
    private const val DEFAULT_TAG = "Default"
    private const val API_SUFFIX = "Api"

    context(_: Hierarchy, _: ApiPackage, _: NameRegistry)
    fun generate(spec: ApiSpec, hasPolymorphicTypes: Boolean): List<FileSpec> {
        val grouped = spec.endpoints.groupBy { it.tags.firstOrNull() ?: DEFAULT_TAG }
        return grouped.map { (tag, endpoints) ->
            generateClientFile(tag, endpoints, hasPolymorphicTypes, spec.securitySchemes, spec.title)
        }
    }

    context(hierarchy: Hierarchy, apiPackage: ApiPackage, nameRegistry: NameRegistry)
    private fun generateClientFile(
        tag: String,
        endpoints: List<Endpoint>,
        hasPolymorphicTypes: Boolean,
        securitySchemes: List<SecurityScheme>,
        specTitle: String,
    ): FileSpec {
        val className = ClassName(apiPackage, nameRegistry.register("${tag.toPascalCase()}$API_SUFFIX"))

        val clientInitializer = if (hasPolymorphicTypes) {
            val generatedSerializersModule = MemberName(hierarchy.modelPackage, GENERATED_SERIALIZERS_MODULE)
            CodeBlock.of("${CREATE_HTTP_CLIENT}(%M)", generatedSerializersModule)
        } else {
            CodeBlock.of("${CREATE_HTTP_CLIENT}()")
        }

        val tokenType = LambdaTypeName.get(returnType = STRING)
        val authParamNames = securitySchemes.flatMap { scheme ->
            when (scheme) {
                is SecurityScheme.Bearer -> listOf(scheme.toAuthParam(specTitle).name)
                is SecurityScheme.ApiKey -> listOf(scheme.toAuthParam(specTitle).name)
                is SecurityScheme.Basic -> scheme.toAuthParam(specTitle).let { listOf(it.username, it.password) }
            }
        }

        val constructorBuilder = FunSpec
            .constructorBuilder()
            .addParameter(BASE_URL, STRING)

        val classBuilder = TypeSpec
            .classBuilder(className)
            .superclass(API_CLIENT_BASE)
            .addSuperclassConstructorParameter(BASE_URL)

        for (paramName in authParamNames) {
            constructorBuilder.addParameter(paramName, tokenType)
            classBuilder.addProperty(
                PropertySpec
                    .builder(paramName, tokenType)
                    .initializer(paramName)
                    .addModifiers(KModifier.PRIVATE)
                    .build(),
            )
        }

        val httpClientProperty = PropertySpec
            .builder(CLIENT, HTTP_CLIENT)
            .addModifiers(KModifier.OVERRIDE, KModifier.PROTECTED)
            .initializer(clientInitializer)
            .build()

        classBuilder
            .primaryConstructor(constructorBuilder.build())
            .addProperty(httpClientProperty)

        if (securitySchemes.isNotEmpty()) {
            classBuilder.addFunction(buildApplyAuth(securitySchemes, specTitle))
        }

        context(NameRegistry()) {
            classBuilder.addFunctions(endpoints.map { generateEndpointFunction(it) })
        }

        return FileSpec
            .builder(className)
            .addType(classBuilder.build())
            .build()
    }

    private fun buildApplyAuth(securitySchemes: List<SecurityScheme>, specTitle: String): FunSpec {
        val builder = FunSpec
            .builder(APPLY_AUTH)
            .addModifiers(KModifier.OVERRIDE, KModifier.PROTECTED)
            .receiver(HTTP_REQUEST_BUILDER)

        val headerSchemes = securitySchemes.filter { scheme ->
            scheme is SecurityScheme.Bearer ||
                scheme is SecurityScheme.Basic ||
                (scheme is SecurityScheme.ApiKey && scheme.location == ApiKeyLocation.HEADER)
        }
        val querySchemes = securitySchemes
            .filterIsInstance<SecurityScheme.ApiKey>()
            .filter { scheme -> scheme.location == ApiKeyLocation.QUERY }

        if (headerSchemes.isNotEmpty()) {
            builder.beginControlFlow("%M", HEADERS_FUN)
            for (scheme in headerSchemes) {
                when (scheme) {
                    is SecurityScheme.Bearer -> {
                        val authParam = scheme.toAuthParam(specTitle)
                        builder.addStatement(
                            "append(%T.Authorization, %P)",
                            HTTP_HEADERS,
                            CodeBlock.of($$"Bearer ${$${authParam.name}()}"),
                        )
                    }

                    is SecurityScheme.Basic -> {
                        val authParam = scheme.toAuthParam(specTitle)
                        builder.addStatement(
                            "append(%T.Authorization, %P)",
                            HTTP_HEADERS,
                            CodeBlock.of(
                                $$"Basic ${%T.getEncoder().encodeToString(\"${$${authParam.username}()}:${$${authParam.password}()}\".toByteArray(Charsets.UTF_8))}",
                                BASE64_CLASS,
                            ),
                        )
                    }

                    is SecurityScheme.ApiKey -> {
                        val authParam = scheme.toAuthParam(specTitle)
                        builder.addStatement(
                            "append(%S, ${authParam.name}())",
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
                val authParam = scheme.toAuthParam(specTitle)
                builder.addStatement(
                    "parameters.append(%S, ${authParam.name}())",
                    scheme.parameterName,
                )
            }
            builder.endControlFlow()
        }

        return builder.build()
    }

    context(_: Hierarchy, methodRegistry: NameRegistry)
    private fun generateEndpointFunction(endpoint: Endpoint): FunSpec {
        val functionName = methodRegistry.register(endpoint.operationId.toCamelCase())
        val returnBodyType = resolveReturnType(endpoint)
        val returnType = HTTP_SUCCESS.parameterizedBy(returnBodyType)

        val funBuilder = FunSpec
            .builder(functionName)
            .addModifiers(KModifier.SUSPEND)
            .returns(returnType)

        val params = endpoint.parameters.groupBy { it.location }

        val pathParams = params[ParameterLocation.PATH].orEmpty().map { param ->
            ParameterSpec(param.name.toCamelCase(), param.schema.toTypeName())
        }

        val queryParams = params[ParameterLocation.QUERY].orEmpty().map { param ->
            buildNullableParameter(param.schema, param.name, param.required)
        }

        val headerParams = params[ParameterLocation.HEADER].orEmpty().map { param ->
            buildNullableParameter(param.schema, param.name, param.required)
        }

        funBuilder.addParameters(pathParams + queryParams + headerParams)

        if (endpoint.requestBody != null) {
            funBuilder.addParameters(buildBodyParams(endpoint.requestBody))
        }

        val kdocParts = mutableListOf<String>()
        endpoint.summary?.let { kdocParts.add(it.sanitizeKdoc()) }
        endpoint.description?.let {
            if (kdocParts.isNotEmpty()) kdocParts.add("")
            kdocParts.add(it.sanitizeKdoc())
        }
        val paramDocs = endpoint.parameters.filter { it.description != null }
        if (paramDocs.isNotEmpty() && kdocParts.isNotEmpty()) kdocParts.add("")
        paramDocs.forEach { param ->
            kdocParts.add("@param ${param.name.toCamelCase()} ${param.description?.sanitizeKdoc()}")
        }
        if (kdocParts.isNotEmpty()) {
            funBuilder.addKdoc("%L", kdocParts.joinToString("\n"))
        }
        if (returnBodyType != UNIT) {
            if (kdocParts.isNotEmpty()) funBuilder.addKdoc("\n\n")
            funBuilder.addKdoc("@return [%T] containing [%T] on success", HTTP_SUCCESS, returnBodyType)
        }

        funBuilder.addCode(buildFunctionBody(endpoint, params, returnBodyType))

        return funBuilder.build()
    }

    context(_: Hierarchy)
    private fun resolveReturnType(endpoint: Endpoint): TypeName = endpoint.responses.entries
        .asSequence()
        .filter { it.key.startsWith("2") }
        .firstNotNullOfOrNull { it.value.schema }
        ?.toTypeName()
        ?: UNIT
}
