package com.avsystem.justworks.core.parser

import arrow.core.mapValuesNotNull
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

object SpecParser {

    // --- Public API ---

    sealed interface ParseResult {
        data class Success(val apiSpec: ApiSpec, val warnings: List<String> = emptyList()) : ParseResult
        data class Failure(val errors: List<String>, val warnings: List<String> = emptyList()) : ParseResult
    }

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

        return ParseResult.Success(openApi.toApiSpec(), warnings = allWarnings)
    }.merge()

    // --- Internal type aliases ---

    private typealias ComponentSchemaIdentity = IdentityHashMap<Schema<*>, String>
    private typealias ComponentSchemas = MutableMap<String, Schema<*>>

    // --- Top-level transformation ---

    context(_: Raise<ParseResult.Failure>)
    private fun OpenAPI.toApiSpec(): ApiSpec {
        val allSchemas = components?.schemas.orEmpty()

        val componentSchemaIdentity = ComponentSchemaIdentity(allSchemas.size).apply {
            allSchemas.forEach { (name, schema) -> this[schema] = name }
        }

        context(componentSchemaIdentity, allSchemas.toMutableMap()) {
            val (enumModels, schemaModels) =
                allSchemas.asSequence().partition { (_, schema) -> schema.isEnumSchema }

            return ApiSpec(
                title = info?.title ?: "Untitled",
                version = info?.version ?: "0.0.0",
                endpoints = extractEndpoints(paths.orEmpty()),
                schemas = schemaModels.map { (name, schema) -> extractSchemaModel(name, schema) },
                enums = enumModels.map { (name, schema) -> extractEnumModel(name, schema) },
            )
        }
    }

    // --- Endpoint extraction ---

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun extractEndpoints(paths: Map<String, PathItem>): List<Endpoint> = paths.flatMap { (path, pathItem) ->
        pathItem.readOperationsMap().map { (method, operation) ->
            val operationId = operation.operationId ?: generateOperationId(method.name, path)

            val mergedParams = (pathItem.parameters.orEmpty() + operation.parameters.orEmpty())
                .distinctBy { "${it.name}:${it.`in`}" }
                .map { it.toParameter() }

            val requestBody = nullable {
                val body = operation.requestBody.bind()
                val (contentType, mediaType) = body.content?.entries?.firstOrNull().bind()
                val schema = mediaType.schema.bind()
                RequestBody(
                    required = body.required ?: false,
                    contentType = contentType,
                    schema = schema.toTypeRef("${operationId.replaceFirstChar { it.uppercase() }}Request"),
                )
            }

            val responses = operation.responses
                .orEmpty()
                .mapValues { (code, resp) ->
                    Response(
                        statusCode = code,
                        description = resp.description,
                        schema = resp.content?.get("application/json")?.schema
                            ?.toTypeRef("${operationId.replaceFirstChar { it.uppercase() }}Response"),
                    )
                }

            Endpoint(
                path = path,
                method = HttpMethod.valueOf(method.name),
                operationId = operationId,
                summary = operation.summary,
                tags = operation.tags.orEmpty(),
                parameters = mergedParams,
                requestBody = requestBody,
                responses = responses,
            )
        }
    }

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

        val (oneOf, discriminatorFromWrapper) = detectAndUnwrapOneOfWrappers(schema)
            ?: (schema.oneOf?.mapNotNull { it.resolveName() } to null)

        val anyOf = schema.anyOf?.mapNotNull { it.resolveName() }

        ensure(oneOf.isNullOrEmpty() || anyOf.isNullOrEmpty()) {
            ParseResult.Failure(listOf("Schema '$name' has both oneOf and anyOf. Use one combinator only."))
        }

        val (properties, requiredProps) =
            if (!schema.allOf.isNullOrEmpty()) {
                extractAllOfProperties(schema)
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

        return SchemaModel(
            name = name,
            description = schema.description,
            properties = properties,
            requiredProperties = requiredProps,
            isEnum = false,
            allOf = allOf?.let { it.map(TypeRef::Reference).ifEmpty { null } },
            oneOf = oneOf?.let { it.map(TypeRef::Reference).ifEmpty { null } },
            anyOf = anyOf?.let { it.map(TypeRef::Reference).ifEmpty { null } },
            discriminator = discriminator,
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
    private fun extractAllOfProperties(schema: Schema<*>): Pair<List<PropertyModel>, Set<String>> {
        val topRequired = schema.required.orEmpty().toSet()

        val (required, properties) = schema.allOf
            .orEmpty()
            .fold(topRequired to emptyMap<String, PropertyModel>()) { (accRequired, accProperties), subSchema ->
                val resolvedSchema = subSchema.resolveSubSchema()
                val mergedRequired = accRequired + resolvedSchema.required.orEmpty().toSet()
                mergedRequired to accProperties + resolvedSchema.propertyModels(mergedRequired)
            }

        val topLevelProperties = schema.propertyModels(required)
        val finalProperties =
            properties.plus(topLevelProperties).values.map { prop -> prop.copy(nullable = prop.name !in required) }

        return finalProperties to required
    }

    context(_: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun Schema<*>.resolveSubSchema(): Schema<*> =
        resolveName()?.let { componentSchemas[it] } ?: this

    // --- oneOf wrapper pattern detection ---

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

        val unwrapped = schema.oneOf
            .orEmpty()
            .asSequence()
            .filter { it.isInlineObject }
            .mapNotNull { it.properties?.entries?.singleOrNull()?.toPair() }
            .toMap()
            .mapValuesNotNull { (propertyName, propertySchema) ->
                propertySchema.resolveName() ?: propertyName.takeIf { propertySchema.isInlineObject }?.also { name ->
                    componentSchemas[name] = propertySchema
                    componentSchemaIdentity[propertySchema] = name
                }
            }

        ensure(unwrapped.isNotEmpty())

        val mapping = unwrapped.mapValues { (_, schemaName) -> "$SCHEMA_PREFIX$schemaName" }
        unwrapped.values.toList() to Discriminator(propertyName = "type", mapping = mapping)
    }

    // --- Type resolution ---

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.toTypeRef(contextName: String? = null): TypeRef = contextName?.let { toInlineTypeRef(it) }
        ?: (resolveName() ?: allOf?.singleOrNull()?.resolveName())?.let(TypeRef::Reference)
        ?: when (type) {
            "string" -> STRING_FORMAT_MAP[format] ?: TypeRef.Primitive(PrimitiveType.STRING)
            "integer" -> INTEGER_FORMAT_MAP[format] ?: TypeRef.Primitive(PrimitiveType.INT)
            "number" -> NUMBER_FORMAT_MAP[format] ?: TypeRef.Primitive(PrimitiveType.DOUBLE)
            "boolean" -> TypeRef.Primitive(PrimitiveType.BOOLEAN)
            "array" -> items?.toTypeRef()?.let(TypeRef::Array) ?: TypeRef.Primitive(PrimitiveType.STRING)
            "object" -> (additionalProperties as? Schema<*>)?.toTypeRef()
                ?: title?.let(TypeRef::Reference)
                ?: TypeRef.Unknown

            else -> TypeRef.Primitive(PrimitiveType.STRING)
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

    // --- Schema<*> extensions ---

    context(componentSchemaIdentity: ComponentSchemaIdentity)
    private fun Schema<*>.resolveName(): String? =
        `$ref`?.removePrefix(SCHEMA_PREFIX) ?: componentSchemaIdentity[this]

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

    // --- Naming helpers ---

    private fun generateOperationId(method: String, path: String): String {
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
        return method.lowercase() + segments
    }

    private fun String.toPascalCase(): String =
        split("-", "_", ".").joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    // --- Constants ---

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
