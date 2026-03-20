package com.avsystem.justworks.core.parser

import arrow.core.fold
import arrow.core.merge
import arrow.core.raise.context.Raise
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.either
import arrow.core.raise.nullable
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Discriminator
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.EnumBackingType
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.Parameter
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.RequestBody
import com.avsystem.justworks.core.model.Response
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.core.models.ParseOptions
import java.io.File
import java.util.IdentityHashMap
import io.swagger.v3.oas.models.parameters.Parameter as SwaggerParameter

/**
 * Result of parsing an OpenAPI specification file.
 *
 * Use pattern matching to handle both outcomes:
 * ```kotlin
 * when (val result = SpecParser.parse(file)) {
 *     is ParseResult.Success -> result.apiSpec
 *     is ParseResult.Failure -> handleErrors(result.errors)
 * }
 * ```
 *
 * Both [Success] and [Failure] may carry [warnings] about non-fatal issues
 * encountered during parsing or validation.
 */
sealed interface ParseResult {
    data class Success(val apiSpec: ApiSpec, val warnings: List<String> = emptyList()) : ParseResult

    data class Failure(val errors: List<String>, val warnings: List<String> = emptyList()) : ParseResult
}

object SpecParser {
    /**
     * Parses an OpenAPI 3.0 specification file into an [ApiSpec] intermediate model.
     *
     * Accepts YAML or JSON files. Swagger 2.0 specs are automatically converted to
     * OpenAPI 3.0 by the underlying Swagger Parser before model extraction.
     *
     * Uses Arrow [either] for the internal parse pipeline; the result is collapsed
     * to [ParseResult] via [arrow.core.merge] so callers always receive a [ParseResult]
     * and never an [arrow.core.Either].
     *
     * @param specFile path to the OpenAPI or Swagger 2.0 specification file
     * @return [ParseResult.Success] with the parsed model and any warnings, or
     *         [ParseResult.Failure] with a non-empty list of error messages
     */
    fun parse(specFile: File): ParseResult = either {
        val parseOptions = ParseOptions().apply {
            isResolve = true
            isResolveFully = true
            isResolveCombinators = false
        }

        val swaggerResult = OpenAPIParser().readLocation(specFile.absolutePath, null, parseOptions)
        val openApi = swaggerResult.openAPI
        val swaggerMessages = swaggerResult.messages.orEmpty()

        ensureNotNull(openApi) {
            ParseResult.Failure(swaggerMessages.ifEmpty { listOf("Failed to parse spec: ${specFile.name}") })
        }

        val validationIssues = SpecValidator.validate(openApi)
        val (errors, warnings) = validationIssues.partition { it is SpecValidator.ValidationIssue.Error }
        val allWarnings = warnings.map { it.message } + swaggerMessages

        ensure(errors.isEmpty()) {
            ParseResult.Failure(errors.map { it.message }, allWarnings)
        }

        ParseResult.Success(openApi.toApiSpec(), warnings = allWarnings)
    }.merge()

    private typealias ComponentSchemaIdentity = IdentityHashMap<Schema<*>, String>
    private typealias ComponentSchemas = MutableMap<String, Schema<*>>

    context(_: Raise<ParseResult.Failure>)
    private fun OpenAPI.toApiSpec(): ApiSpec {
        val allSchemas = components?.schemas.orEmpty()

        val componentSchemaIdentity = ComponentSchemaIdentity(allSchemas.size).apply {
            allSchemas.forEach { (name, schema) -> this[schema] = name }
        }

        val componentSchemas: ComponentSchemas = allSchemas.toMutableMap()

        context(componentSchemaIdentity, componentSchemas) {
            val endpoints = extractEndpoints(paths.orEmpty())

            val (enumModels, schemaModels) = allSchemas.fold(
                emptyList<EnumModel>() to emptyList<SchemaModel>(),
            ) { (accEnum, accModels), (name, schema) ->
                if (schema.isEnumSchema) {
                    accEnum + extractEnumModel(name, schema) to accModels
                } else {
                    accEnum to accModels + extractSchemaModel(name, schema)
                }
            }

            return ApiSpec(
                title = info?.title ?: "Untitled",
                version = info?.version ?: "0.0.0",
                endpoints = endpoints,
                schemas = schemaModels,
                enums = enumModels,
            )
        }
    }

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun extractEndpoints(paths: Map<String, PathItem>): List<Endpoint> = paths
        .asSequence()
        .flatMap { (path, pathItem) ->
            pathItem
                .readOperationsMap()
                .asSequence()
                .mapNotNull { (method, value) -> HttpMethod.parse(method.name)?.let { it to value } }
                .map { (method, operation) ->
                    val operationId = operation.operationId ?: generateOperationId(method, path)

                    val mergedParams = (operation.parameters.orEmpty() + pathItem.parameters.orEmpty())
                        .distinctBy { "${it.name}:${it.`in`}" }
                        .map { it.toParameter() }

                    val requestBody = nullable {
                        val body = operation.requestBody.bind()
                        val content = body.content.bind()
                        val schema = content[JSON_CONTENT_TYPE]?.schema.bind()
                        RequestBody(
                            required = body.required ?: false,
                            contentType = JSON_CONTENT_TYPE,
                            schema = schema.toTypeRef("${operationId.replaceFirstChar { it.uppercase() }}Request"),
                        )
                    }

                    val responses = operation.responses
                        .orEmpty()
                        .mapValues { (code, resp) ->
                            Response(
                                statusCode = code,
                                description = resp.description,
                                schema = resp.content
                                    ?.get(JSON_CONTENT_TYPE)
                                    ?.schema
                                    ?.toTypeRef("${operationId.replaceFirstChar { it.uppercase() }}Response"),
                            )
                        }

                    Endpoint(
                        path = path,
                        method = method,
                        operationId = operationId,
                        summary = operation.summary,
                        tags = operation.tags.orEmpty(),
                        parameters = mergedParams,
                        requestBody = requestBody,
                        responses = responses,
                    )
                }
        }.toList()

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun SwaggerParameter.toParameter(): Parameter = Parameter(
        name = name ?: "",
        location = ParameterLocation.parse(`in`) ?: ParameterLocation.QUERY,
        required = required ?: false,
        schema = schema?.toTypeRef() ?: TypeRef.Primitive(PrimitiveType.STRING),
        description = description,
    )

    // --- Schema extraction ---

    context(_: Raise<ParseResult.Failure>, _: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun extractSchemaModel(name: String, schema: Schema<*>): SchemaModel {
        val allOf = schema.allOf?.mapNotNull { it.resolveName() }

        val (oneOf, discriminatorFromWrapper) = detectAndUnwrapOneOfWrappers(schema) // may register new schemas
            ?: (schema.oneOf?.mapNotNull { it.resolveName() } to null)

        val anyOf = schema.anyOf?.mapNotNull { it.resolveName() }

        ensure(oneOf.isNullOrEmpty() || anyOf.isNullOrEmpty()) {
            ParseResult.Failure(listOf("Schema '$name' has both oneOf and anyOf. Use one combinator only."))
        }

        val (properties, requiredProps) =
            if (!schema.allOf.isNullOrEmpty()) {
                extractAllOfProperties(name, schema)
            } else {
                val requiredProps = schema.required.orEmpty().toSet()
                val props = schema
                    .propertyModels(requiredProps) { propName -> "$name.${propName.toPascalCase()}" }
                    .values
                    .toList()
                props to requiredProps
            }

        val discriminator = discriminatorFromWrapper ?: nullable {
            val disc = schema.discriminator.bind()
            val propertyName = disc.propertyName.bind()
            Discriminator(propertyName = propertyName, mapping = disc.mapping.orEmpty())
        }

        // Resolve underlying type for primitive-only / $ref-wrapper schemas.
        // Uses $ref for wrapper schemas, otherwise resolves structurally
        // from type/format to bypass componentSchemaIdentity (which would self-reference).
        val underlyingType = schema
            .takeIf { properties.isEmpty() && oneOf.isNullOrEmpty() && anyOf.isNullOrEmpty() }
            ?.let { s -> s.`$ref`?.removePrefix(SCHEMA_PREFIX)?.let(TypeRef::Reference) ?: s.resolveByType() }
            ?.takeUnless { it is TypeRef.Unknown }

        return SchemaModel(
            name = name,
            description = schema.description,
            properties = properties,
            requiredProperties = requiredProps,
            allOf = allOf?.let { it.map(TypeRef::Reference).ifEmpty { null } },
            oneOf = oneOf?.let { it.map(TypeRef::Reference).ifEmpty { null } },
            anyOf = anyOf?.let { it.map(TypeRef::Reference).ifEmpty { null } },
            discriminator = discriminator,
            underlyingType = underlyingType,
        )
    }

    private fun extractEnumModel(name: String, schema: Schema<*>): EnumModel = EnumModel(
        name = name,
        description = schema.description,
        type = EnumBackingType.parse(schema.type) ?: EnumBackingType.STRING,
        values = schema.enum.map { it.toString() },
    )

    // --- allOf property merging ---

    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun extractAllOfProperties(parentName: String, schema: Schema<*>): Pair<List<PropertyModel>, Set<String>> {
        val topRequired = schema.required.orEmpty().toSet()
        val contextCreator: (String) -> String? = { propName -> "$parentName.${propName.toPascalCase()}" }

        val (required, properties) = schema.allOf
            .orEmpty()
            .fold(topRequired to emptyMap<String, PropertyModel>()) { (accRequired, accProperties), subSchema ->
                val resolvedSchema = subSchema.resolveSubSchema()
                val mergedRequired = accRequired + resolvedSchema.required.orEmpty().toSet()
                mergedRequired to accProperties + resolvedSchema.propertyModels(mergedRequired, contextCreator)
            }

        val topLevelProperties = schema.propertyModels(required, contextCreator)
        val finalProperties =
            properties.plus(topLevelProperties).values.map { prop -> prop.copy(nullable = prop.name !in required) }

        return finalProperties to required
    }

    context(_: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun Schema<*>.resolveSubSchema(): Schema<*> = resolveName()?.let { componentSchemas[it] } ?: this

    /**
     * Detects and unwraps the oneOf wrapper pattern where each variant is a single-property
     * object schema with the property name serving as the discriminator.
     *
     * Detection criteria (all must be true):
     * - Schema has oneOf list
     * - No explicit discriminator is defined
     * - Every oneOf variant is an object schema (not a $ref)
     * - Every variant has exactly one property
     * - The property value is either a $ref or an inline object
     *
     * Returns: Pair of (unwrapped oneOf refs, synthetic discriminator) or null if pattern not matched.
     */
    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun detectAndUnwrapOneOfWrappers(schema: Schema<*>): Pair<List<String>, Discriminator>? = nullable {
        ensure(!schema.oneOf.isNullOrEmpty() && schema.discriminator == null)

        val variants = schema.oneOf.orEmpty()
        ensure(variants.all { it.isInlineObject })

        val unwrapped = variants
            .associate {
                val (propertyName, propertySchema) = ensureNotNull(
                    it.properties?.entries?.singleOrNull(),
                )

                val schemaName = ensureNotNull(
                    propertySchema.resolveName() ?: propertyName
                        .takeIf { propertySchema.isInlineObject }
                        ?.also { name ->
                            componentSchemas[name] = propertySchema
                            componentSchemaIdentity[propertySchema] = name
                        },
                )

                propertyName to schemaName
            }

        ensure(unwrapped.size == variants.size)

        val mapping = unwrapped.mapValues { (_, schemaName) -> "$SCHEMA_PREFIX$schemaName" }
        unwrapped.values.toList() to Discriminator(propertyName = "type", mapping = mapping)
    }

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.toTypeRef(contextName: String? = null): TypeRef = contextName?.let { toInlineTypeRef(it) }
        ?: (resolveName() ?: allOf?.singleOrNull()?.resolveName())?.let(TypeRef::Reference)
        ?: TypeRef.Unknown.takeIf { (allOf?.size ?: 0) > 1 }
        ?: resolveByType(contextName)

    /** Resolves a [TypeRef] based on the schema's structural type/format, ignoring component identity. */
    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.resolveByType(contextName: String? = null): TypeRef = when (type) {
        "string" -> STRING_FORMAT_MAP[format] ?: TypeRef.Primitive(PrimitiveType.STRING)

        "integer" -> INTEGER_FORMAT_MAP[format] ?: TypeRef.Primitive(PrimitiveType.INT)

        "number" -> NUMBER_FORMAT_MAP[format] ?: TypeRef.Primitive(PrimitiveType.DOUBLE)

        "boolean" -> TypeRef.Primitive(PrimitiveType.BOOLEAN)

        "array" -> TypeRef.Array(items?.toTypeRef(contextName?.let { "${it}Item" }) ?: TypeRef.Unknown)

        "object" -> when (val ap = additionalProperties) {
            is Schema<*> -> TypeRef.Map(ap.toTypeRef())
            is Boolean -> if (ap) TypeRef.Map(TypeRef.Unknown) else TypeRef.Unknown
            else -> title?.let(TypeRef::Reference) ?: TypeRef.Unknown
        }

        else -> TypeRef.Unknown
    }

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.toInlineTypeRef(contextName: String): TypeRef? = takeIf { isInlineObject }?.let {
        val required = required.orEmpty().toSet()
        TypeRef.Inline(
            properties = propertyModels(required) { "$contextName.${it.toPascalCase()}" }.values.toList(),
            requiredProperties = required,
            contextHint = contextName,
        )
    }

    context(componentSchemaIdentity: ComponentSchemaIdentity)
    private fun Schema<*>.resolveName(): String? = `$ref`?.removePrefix(SCHEMA_PREFIX) ?: componentSchemaIdentity[this]

    context(componentSchemaIdentity: ComponentSchemaIdentity)
    private val Schema<*>.isInlineObject
        get(): Boolean = `$ref` == null &&
            this !in componentSchemaIdentity && type == "object" && !properties.isNullOrEmpty()

    private val Schema<*>.isEnumSchema get(): Boolean = !enum.isNullOrEmpty()

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.propertyModels(required: Set<String>, createContext: (String) -> String? = { null }) =
        properties
            .orEmpty()
            .mapValues { (propName, propSchema) ->
                PropertyModel(
                    name = propName,
                    type = propSchema.toTypeRef(createContext(propName)),
                    description = propSchema.description,
                    nullable = propName !in required,
                    defaultValue = propSchema.default,
                )
            }

    private fun generateOperationId(method: HttpMethod, path: String): String {
        val segments = path
            .split("/")
            .filter { it.isNotEmpty() }
            .joinToString("") { segment ->
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    "By${segment.removePrefix("{").removeSuffix("}").toPascalCase()}"
                } else {
                    segment.toPascalCase()
                }
            }
        return method.name.lowercase() + segments
    }

    private fun String.toPascalCase(): String =
        split("-", "_", ".").joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    private const val JSON_CONTENT_TYPE = "application/json"
    private const val SCHEMA_PREFIX = "#/components/schemas/"

    private val STRING_FORMAT_MAP = mapOf(
        "byte" to TypeRef.Primitive(PrimitiveType.BYTE_ARRAY),
        "date-time" to TypeRef.Primitive(PrimitiveType.DATE_TIME),
        "date" to TypeRef.Primitive(PrimitiveType.DATE),
    )

    private val INTEGER_FORMAT_MAP = mapOf(
        "int64" to TypeRef.Primitive(PrimitiveType.LONG),
    )

    private val NUMBER_FORMAT_MAP = mapOf(
        "float" to TypeRef.Primitive(PrimitiveType.FLOAT),
    )
}
