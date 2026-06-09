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
import com.avsystem.justworks.core.gen.HTTP_RESULT
import com.avsystem.justworks.core.gen.HTTP_SUCCESS
import com.avsystem.justworks.core.gen.Hierarchy
import com.avsystem.justworks.core.gen.JSON_ELEMENT
import com.avsystem.justworks.core.gen.NameRegistry
import com.avsystem.justworks.core.gen.OutputOptions
import com.avsystem.justworks.core.gen.TOKEN
import com.avsystem.justworks.core.gen.client.BodyGenerator.buildFunctionBody
import com.avsystem.justworks.core.gen.client.ParametersGenerator.buildBodyParams
import com.avsystem.justworks.core.gen.client.ParametersGenerator.buildNullableParameter
import com.avsystem.justworks.core.gen.invoke
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

    context(_: Hierarchy, _: OutputOptions, _: ApiPackage, _: NameRegistry)
    fun generate(spec: ApiSpec, hasPolymorphicTypes: Boolean): List<FileSpec> {
        val grouped = spec.endpoints.groupBy { it.tags.firstOrNull() ?: DEFAULT_TAG }
        return grouped.flatMap { (tag, endpoints) ->
            generateClientFiles(tag, endpoints, hasPolymorphicTypes, spec.securitySchemes, spec.title)
        }
    }

    context(hierarchy: Hierarchy, options: OutputOptions, apiPackage: ApiPackage, nameRegistry: NameRegistry)
    private fun generateClientFiles(
        tag: String,
        endpoints: List<Endpoint>,
        hasPolymorphicTypes: Boolean,
        securitySchemes: List<SecurityScheme>,
        specTitle: String,
    ): List<FileSpec> {
        val baseName = "${options.apiClassPrefix}${tag.toPascalCase()}${options.apiClassSuffix}"

        // When interfaces are enabled the public type keeps the base name and the
        // concrete client gets an `Impl` suffix; otherwise the client uses the base name.
        val interfaceClass = if (options.generateInterfaces) {
            ClassName(apiPackage, nameRegistry.register(baseName))
        } else {
            null
        }
        val classSimpleName = if (interfaceClass != null) "${baseName}Impl" else baseName
        val className = ClassName(apiPackage, nameRegistry.register(classSimpleName))

        val clientInitializer = if (hasPolymorphicTypes) {
            val generatedSerializersModule = MemberName(hierarchy.modelPackage, GENERATED_SERIALIZERS_MODULE)
            CodeBlock.of("${CREATE_HTTP_CLIENT}(%M)", generatedSerializersModule)
        } else {
            CodeBlock.of("${CREATE_HTTP_CLIENT}()")
        }

        val tokenType = LambdaTypeName.get(returnType = STRING)
        val isSingleBearer = securitySchemes.singleOrNull() is SecurityScheme.Bearer

        val constructorBuilder = FunSpec
            .constructorBuilder()
            .addParameter(BASE_URL, STRING)

        val classBuilder = TypeSpec
            .classBuilder(className)
            .superclass(API_CLIENT_BASE)
            .addSuperclassConstructorParameter(BASE_URL)

        if (interfaceClass != null) {
            classBuilder.addSuperinterface(interfaceClass)
        }

        if (isSingleBearer) {
            // Single Bearer: use plain "token" param name for ergonomics
            constructorBuilder.addParameter(TOKEN, tokenType)
            classBuilder.addProperty(
                PropertySpec
                    .builder(TOKEN, tokenType)
                    .initializer(TOKEN)
                    .addModifiers(KModifier.PRIVATE)
                    .build(),
            )
        } else if (securitySchemes.isNotEmpty()) {
            // Multiple or non-Bearer schemes: generate named auth params
            val authParamNames = securitySchemes.flatMap { scheme ->
                when (scheme) {
                    is SecurityScheme.Bearer -> listOf(scheme.toAuthParam(specTitle).name)
                    is SecurityScheme.ApiKey -> listOf(scheme.toAuthParam(specTitle).name)
                    is SecurityScheme.Basic -> scheme.toAuthParam(specTitle).let { listOf(it.username, it.password) }
                }
            }

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
            classBuilder.addFunction(buildApplyAuth(securitySchemes, isSingleBearer, specTitle))
        }

        val implementsInterface = interfaceClass != null
        context(NameRegistry()) {
            classBuilder.addFunctions(
                endpoints.map {
                    generateEndpointFunction(
                        it,
                        override = implementsInterface,
                        includeBody = true,
                        // KDoc lives on the interface when one is generated.
                        includeKdoc = options.generateKdoc && !implementsInterface,
                    )
                },
            )
        }

        val classFile = FileSpec
            .builder(className)
            .addType(classBuilder.build())
            .build()

        if (interfaceClass == null) return listOf(classFile)

        val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceClass)
        context(NameRegistry()) {
            interfaceBuilder.addFunctions(
                endpoints.map {
                    generateEndpointFunction(
                        it,
                        override = false,
                        includeBody = false,
                        includeKdoc = options.generateKdoc,
                    )
                },
            )
        }
        val interfaceFile = FileSpec
            .builder(interfaceClass)
            .addType(interfaceBuilder.build())
            .build()

        return listOf(interfaceFile, classFile)
    }

    private fun buildApplyAuth(
        securitySchemes: List<SecurityScheme>,
        isSingleBearer: Boolean,
        specTitle: String,
    ): FunSpec {
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
                        val tokenRef = if (isSingleBearer) TOKEN else scheme.toAuthParam(specTitle).name
                        builder.addStatement(
                            "append(%T.Authorization, %P)",
                            HTTP_HEADERS,
                            CodeBlock.of($$"Bearer ${$$tokenRef()}"),
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

    /**
     * Builds a suspend function for an endpoint.
     *
     * @param override emit an `override` modifier (the client implements an interface)
     * @param includeBody emit the request-building body; when false the function is abstract
     *   (used for interface members)
     * @param includeKdoc emit KDoc on the function
     */
    context(_: Hierarchy, _: OutputOptions, methodRegistry: NameRegistry)
    private fun generateEndpointFunction(
        endpoint: Endpoint,
        override: Boolean,
        includeBody: Boolean,
        includeKdoc: Boolean,
    ): FunSpec {
        val functionName = methodRegistry.register(endpoint.operationId.toCamelCase())
        val returnBodyType = resolveReturnType(endpoint)
        val errorType = resolveErrorType(endpoint)
        val returnType = HTTP_RESULT.parameterizedBy(errorType, returnBodyType)

        val funBuilder = FunSpec
            .builder(functionName)
            .addModifiers(KModifier.SUSPEND)
            .apply { if (override) addModifiers(KModifier.OVERRIDE) }
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

        val bodyParams = endpoint.requestBody?.let { buildBodyParams(it) }.orEmpty()
        val allParams = pathParams + queryParams + headerParams + bodyParams

        // An overriding function may not repeat default values; defaults live on the interface.
        funBuilder.addParameters(
            if (override) allParams.map { it.toBuilder().defaultValue(null as CodeBlock?).build() } else allParams,
        )

        if (includeKdoc) {
            val kdocParts = mutableListOf<String>()
            endpoint.summary?.let { kdocParts.add(it) }
            endpoint.description?.let {
                if (kdocParts.isNotEmpty()) kdocParts.add("")
                kdocParts.add(it)
            }
            val paramDocs = endpoint.parameters.filter { it.description != null }
            if (paramDocs.isNotEmpty() && kdocParts.isNotEmpty()) kdocParts.add("")
            paramDocs.forEach { param ->
                kdocParts.add("@param ${param.name.toCamelCase()} ${param.description}")
            }
            if (kdocParts.isNotEmpty()) {
                funBuilder.addKdoc("%L", kdocParts.joinToString("\n"))
            }
            if (returnBodyType != UNIT) {
                if (kdocParts.isNotEmpty()) funBuilder.addKdoc("\n\n")
                funBuilder.addKdoc("@return [%T] containing [%T] on success", HTTP_SUCCESS, returnBodyType)
            }
        }

        if (includeBody) {
            funBuilder.addCode(buildFunctionBody(endpoint, params, returnBodyType))
        } else {
            // Interface member: abstract so KotlinPoet omits the body.
            funBuilder.addModifiers(KModifier.ABSTRACT)
        }

        return funBuilder.build()
    }

    context(_: Hierarchy)
    private fun resolveErrorType(endpoint: Endpoint): TypeName {
        val errorSchemas = endpoint.responses.entries
            .asSequence()
            .filter { !it.key.startsWith("2") && it.key != "default" }
            .mapNotNull { it.value.schema }
            .map { it.toTypeName() }
            .distinct()
            .toList()

        return when {
            errorSchemas.size == 1 -> errorSchemas.single()
            else -> JSON_ELEMENT
        }
    }

    context(_: Hierarchy)
    private fun resolveReturnType(endpoint: Endpoint): TypeName {
        val twoXxSchema = endpoint.responses
            .asSequence()
            .filter { it.key.startsWith("2") }
            .firstNotNullOfOrNull { it.value.schema }

        val schema = twoXxSchema ?: endpoint.responses["default"]?.schema.takeIf {
            endpoint.responses.none { it.key.startsWith("2") }
        }

        return schema?.toTypeName() ?: UNIT
    }
}
