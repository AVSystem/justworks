package com.avsystem.justworks.core.gen.client

import com.avsystem.justworks.core.gen.API_CLIENT_BASE
import com.avsystem.justworks.core.gen.ApiPackage
import com.avsystem.justworks.core.gen.BASE_URL
import com.avsystem.justworks.core.gen.CLIENT
import com.avsystem.justworks.core.gen.CREATE_HTTP_CLIENT
import com.avsystem.justworks.core.gen.GENERATED_SERIALIZERS_MODULE
import com.avsystem.justworks.core.gen.HTTP_CLIENT
import com.avsystem.justworks.core.gen.HTTP_ERROR
import com.avsystem.justworks.core.gen.HTTP_SUCCESS
import com.avsystem.justworks.core.gen.ModelPackage
import com.avsystem.justworks.core.gen.RAISE
import com.avsystem.justworks.core.gen.TOKEN
import com.avsystem.justworks.core.gen.client.BodyGenerator.buildFunctionBody
import com.avsystem.justworks.core.gen.client.ParametersGenerator.buildBodyParams
import com.avsystem.justworks.core.gen.client.ParametersGenerator.buildNullableParameter
import com.avsystem.justworks.core.gen.invoke
import com.avsystem.justworks.core.gen.sanitizeKdoc
import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toPascalCase
import com.avsystem.justworks.core.gen.toTypeName
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.ParameterLocation
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

/**
 * Generates one KotlinPoet [FileSpec] per API tag, each containing a client class
 * that extends `ApiClientBase` with suspend functions for every endpoint in that tag group.
 */

@OptIn(ExperimentalKotlinPoetApi::class)
internal object ClientGenerator {
    private const val DEFAULT_TAG = "Default"
    private const val API_SUFFIX = "Api"

    context(_: ModelPackage, _: ApiPackage)
    fun generate(spec: ApiSpec, hasPolymorphicTypes: Boolean = false): List<FileSpec> {
        val grouped = spec.endpoints.groupBy { it.tags.firstOrNull() ?: DEFAULT_TAG }
        return grouped.map { (tag, endpoints) -> generateClientFile(tag, endpoints, hasPolymorphicTypes) }
    }

    context(modelPackage: ModelPackage, apiPackage: ApiPackage)
    private fun generateClientFile(
        tag: String,
        endpoints: List<Endpoint>,
        hasPolymorphicTypes: Boolean = false,
    ): FileSpec {
        val className = ClassName(apiPackage, "${tag.toPascalCase()}$API_SUFFIX")

        val clientInitializer = if (hasPolymorphicTypes) {
            val generatedSerializersModule = MemberName(modelPackage, GENERATED_SERIALIZERS_MODULE)
            CodeBlock.of("${CREATE_HTTP_CLIENT}(%M)", generatedSerializersModule)
        } else {
            CodeBlock.of("${CREATE_HTTP_CLIENT}()")
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

        classBuilder.addFunctions(endpoints.map { generateEndpointFunction(it) })

        return FileSpec
            .builder(className)
            .addType(classBuilder.build())
            .build()
    }

    context(_: ModelPackage)
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

    context(_: ModelPackage)
    private fun resolveReturnType(endpoint: Endpoint): TypeName = endpoint.responses.entries
        .asSequence()
        .filter { it.key.startsWith("2") }
        .firstNotNullOfOrNull { it.value.schema }
        ?.toTypeName()
        ?: UNIT
}
