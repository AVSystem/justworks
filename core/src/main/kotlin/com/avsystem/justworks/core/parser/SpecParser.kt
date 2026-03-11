package com.avsystem.justworks.core.parser

import arrow.core.raise.nullable
import arrow.core.raise.recover
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
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.collections.mapValues
import kotlin.collections.orEmpty
import io.swagger.v3.oas.models.parameters.Parameter as SwaggerParameter

object SpecParser {
    // Identity map from resolved Schema objects to their component schema names.
    // Populated during transformToModel to detect inlined refs after resolveFully.
    typealias ComponentSchemaIdentity = IdentityHashMap<Schema<*>, String>

    // Component schemas keyed by name, for resolving $ref in allOf sub-schemas.
    typealias ComponentSchemas = MutableMap<String, Schema<*>>

    fun parse(specFile: File): ParseResult {
        val parseOptions =
            ParseOptions().apply {
                isResolve = true
                isResolveFully = true
                isResolveCombinators = false
            }

        val swaggerResult = OpenAPIParser().readLocation(specFile.absolutePath, null, parseOptions)

        val messages = swaggerResult.messages.orEmpty()
        val openApi =
            swaggerResult.openAPI
                ?: return ParseResult.Failure(messages.ifEmpty { listOf("Failed to parse spec: ${specFile.name}") })

        recover(
            { SpecValidator.validate(openApi) },
            { validationIssues ->
                val (errors, warnings) = validationIssues.partition { it is SpecValidator.ValidationIssue.Error }
                warnings.forEach { println("[justworks] Warning: $it") }

                return@parse ParseResult.Failure(errors.map { it.message })
            },
        )

        return try {
            val apiSpec = openApi.toApiSpec()
            ParseResult.Success(apiSpec, warnings = messages)
        } catch (e: IllegalArgumentException) {
            ParseResult.Failure(listOf(e.message ?: "Schema validation failed"))
        }
    }

    fun OpenAPI.toApiSpec(): ApiSpec {
        val allSchemas = this.components?.schemas.orEmpty()

        val componentSchemaIdentity = ComponentSchemaIdentity(allSchemas.size).apply {
            allSchemas.forEach { (name, schema) -> this[schema] = name }
        }

        context(componentSchemaIdentity, allSchemas.toMutableMap()) {
            val (enumModels, schemaModels) =
                allSchemas.asSequence().partition { (_, schema) -> schema.isEnumSchema }

            return ApiSpec(
                title = this.info?.title ?: "Untitled",
                version = this.info?.version ?: "0.0.0",
                endpoints = extractEndpoints(this.paths.orEmpty()),
                schemas = schemaModels.map { (name, schema) -> extractSchemaModel(name, schema) },
                enums = enumModels.map { (name, schema) -> extractEnumModel(name, schema) },
            )
        }
    }

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun extractEndpoints(paths: Map<String, PathItem>): List<Endpoint> = paths.flatMap { (path, pathItem) ->
        pathItem.readOperationsMap().map { (method, operation) ->
            // Merge path-level and operation-level parameters
            // Operation-level takes precedence (unique key = name + location)
            val mergedParams = (pathItem.parameters.orEmpty() + operation.parameters.orEmpty())
                .distinctBy { "${it.name}:${it.`in`}" }
                .map { it.toParameter() }

            val requestBody = nullable {
                val body = operation.requestBody.bind()

                val (contentType, mediaType) = body.content
                    ?.entries
                    ?.firstOrNull()
                    .bind()

                val schema = mediaType.schema.bind()

                RequestBody(
                    required = body.required ?: false,
                    contentType = contentType,
                    schema = schema.toTypeRef("${generateOperationId(method.name, path).upperCase()}Request"),
                )
            }
            val responses = operation.responses
                ?.mapValues { (code, resp) ->
                    val schema = resp.content?.get("application/json")?.schema
                    Response(
                        statusCode = code,
                        description = resp.description,
                        schema = schema?.toTypeRef("${generateOperationId(method.name, path).upperCase()}Response"),
                    )
                }.orEmpty()

            Endpoint(
                path = path,
                method = HttpMethod.valueOf(method.name),
                operationId = operation.operationId ?: generateOperationId(method.name, path),
                summary = operation.summary,
                tags = operation.tags.orEmpty(),
                parameters = mergedParams,
                requestBody = requestBody,
                responses = responses,
            )
        }
    }

    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun SwaggerParameter.toParameter(): Parameter = Parameter(
        name = name ?: "",
        location = ParameterLocation.parse(`in`) ?: ParameterLocation.QUERY,
        required = required ?: false,
        schema = schema?.toTypeRef() ?: TypeRef.Primitive(PrimitiveType.STRING),
        description = description,
    )

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.toTypeRef(contextName: String? = null): TypeRef = contextName?.let { toInlineTypeRef(it) }
        ?: (this.name ?: allOf.takeIf { it.size == 1 }?.firstOrNull()?.name)?.let(TypeRef::Reference)
        ?: toPrimitiveOrCompositeTypeRef()

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.toPrimitiveOrCompositeTypeRef(): TypeRef = when (this.type) {
        "string" -> resolvePrimitive(STRING_FORMAT_MAP, PrimitiveType.STRING)

        "integer" -> resolvePrimitive(INTEGER_FORMAT_MAP, PrimitiveType.INT)

        "number" -> resolvePrimitive(NUMBER_FORMAT_MAP, PrimitiveType.DOUBLE)

        "boolean" -> TypeRef.Primitive(PrimitiveType.BOOLEAN)

        "array" -> this.items?.toTypeRef()?.let(TypeRef::Array) ?: TypeRef.Primitive(PrimitiveType.STRING)

        "object" -> (this.additionalProperties as? Schema<*>)?.toTypeRef()
            ?: TypeRef.Reference(this.title ?: "Unknown")

        else -> TypeRef.Primitive(PrimitiveType.STRING)
    }

    private fun Schema<*>.resolvePrimitive(
        formatMap: Map<String, PrimitiveType>,
        default: PrimitiveType,
    ): TypeRef.Primitive = TypeRef.Primitive(this.format?.let { formatMap[it] } ?: default)

    private fun extractEnumModel(name: String, schema: Schema<*>): EnumModel = EnumModel(
        name = name,
        description = schema.description,
        type = EnumBackingType.parse(schema.type) ?: EnumBackingType.STRING,
        values = schema.enum.map { it.toString() },
    )

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
        // Must have oneOf and no explicit discriminator
        ensure(!schema.oneOf.isNullOrEmpty() && schema.discriminator == null)

        val (discriminatorMapping, unwrappedRefs) = schema.oneOf
            .orEmpty()
            .fold((emptyMap<String, String>() to emptyList<String>())) { (mappings, refs), variant ->
                // Skip direct references — only process wrapper objects
                ensure(variant.`$ref` == null && componentSchemaIdentity[variant] == null)

                // Must be an inline object with exactly one property
                ensure(variant.isInlineObject)

                val properties = variant.properties.orEmpty()
                ensure(properties.size == 1)

                // Extract the single property
                val (propertyName, propertySchema) = properties.entries.first()

                // The property value must be a reference or inline object
                val res = propertySchema.resolveName()?.let { schemaName -> "$SCHEMA_PREFIX$schemaName" to schemaName }
                    ?: propertySchema.takeIf { it.isInlineObject }?.let { propertySchema ->
                        // Property is an inline object - create a component schema for it
                        val inlineSchemaName = propertyName.toPascalCase()

                        // Register this inline schema as a component for future lookups
                        componentSchemas += (inlineSchemaName to propertySchema)
                        componentSchemaIdentity[propertySchema] = inlineSchemaName

                        "$SCHEMA_PREFIX$inlineSchemaName" to inlineSchemaName
                    }

                res?.let { (mapping, ref) -> mappings + (propertyName to mapping) to refs + ref } ?: (mappings to refs)
            }

        // All variants matched the wrapper pattern - create synthetic discriminator
        val syntheticDiscriminator = Discriminator(propertyName = "type", mapping = discriminatorMapping)

        return unwrappedRefs to syntheticDiscriminator
    }

    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun extractSchemaModel(name: String, schema: Schema<*>): SchemaModel {
        val allOf = schema.allOf.resolveRefs()

        // Check for oneOf wrapper pattern before standard extraction
        val (oneOf, discriminatorFromWrapper) = detectAndUnwrapOneOfWrappers(schema)
            ?: (schema.oneOf.resolveRefs() to null)

        val anyOf = schema.anyOf.resolveRefs()

        require(oneOf.isNullOrEmpty() || anyOf.isNullOrEmpty()) {
            "Schema '$name' has both oneOf and anyOf. Use one combinator only."
        }

        // For allOf schemas, merge properties from all sub-schemas
        val (properties, requiredProps) =
            if (!schema.allOf.isNullOrEmpty()) {
                extractAllOfProperties(schema)
            } else {
                val requiredProps = schema.requiredSet
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
            allOf = allOf?.toTypeRefs(),
            oneOf = oneOf?.toTypeRefs(),
            anyOf = anyOf?.toTypeRefs(),
            discriminator = discriminator,
        )
    }

    /**
     * Extracts and merges properties from all allOf sub-schemas.
     * For $ref sub-schemas (resolved by identity), looks up properties from the resolved schema.
     * For inline sub-schemas, extracts properties directly.
     * Also includes any top-level properties defined alongside allOf.
     * Deduplicates by property name (later definition wins).
     */

    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun extractAllOfProperties(schema: Schema<*>): Pair<List<PropertyModel>, Set<String>> {
        val topRequired = schema.requiredSet

        // Collect properties from each allOf sub-schema.
        // For $ref sub-schemas (or identity-matched resolved refs), look up the
        // referenced component schema to get its properties and required fields.
        val (required, properties) = schema.allOf
            .orEmpty()
            .fold(topRequired to emptyMap<String, PropertyModel>()) { (accRequired, accProperties), subSchema ->
                val resolvedSchema = subSchema.resolveSubSchema()
                val mergedRequired = accRequired + resolvedSchema.required.orEmpty().toSet()

                mergedRequired to accProperties + resolvedSchema.propertyModels(mergedRequired)
            }

        val topLevelProperties = schema.propertyModels(required)

        // Recompute nullable based on final merged required set
        val finalProperties =
            properties.plus(topLevelProperties).values.map { prop -> prop.copy(nullable = prop.name !in required) }

        return finalProperties to required
    }

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    fun createPropertyModel(
        propName: String,
        propSchema: Schema<*>,
        required: Set<String>,
        contextName: String? = null,
    ) = PropertyModel(
        name = propName,
        type = propSchema.toTypeRef(contextName),
        description = propSchema.description,
        nullable = propName !in required,
        defaultValue = propSchema.default,
    )

    /**
     * Resolves a sub-schema that may be a `$ref` or an identity-matched ref to the
     * actual component schema with properties. Returns the sub-schema itself if it is
     * an inline schema (not a reference).
     */
    context(_: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun Schema<*>.resolveSubSchema(): Schema<*> =
        // Check for explicit $ref string // Check for identity-matched resolved ref
        this.resolveName()?.let { componentSchemas[it] } ?: this

    /**
     * Checks if a schema is an inline object schema (not a $ref, not in componentSchemaIdentity,
     * is type object with properties).
     */
    context(componentSchemaIdentity: ComponentSchemaIdentity)
    private val Schema<*>.isInlineObject
        get(): Boolean = `$ref` == null &&
            this !in componentSchemaIdentity && type == "object" && !properties.isNullOrEmpty()

    /**
     * Creates a TypeRef.Inline for an inline object schema.
     */
    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.toInlineTypeRef(contextName: String): TypeRef? = if (isInlineObject) {
        val requiredProps = requiredSet
        val properties = propertyModels(requiredProps) { propName -> "$contextName.${propName.toPascalCase()}" }
        TypeRef.Inline(
            properties = properties.values.toList(),
            requiredProperties = requiredProps,
            contextHint = contextName,
        )
    } else {
        null
    }

    private fun generateOperationId(method: String, path: String): String {
        val segments =
            path
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

    private val Schema<*>.isEnumSchema get(): Boolean = !this.enum.isNullOrEmpty()

    private fun String.toPascalCase(): String = split("-", "_", ".").joinToString("") { it.upperCase() }

    context(componentSchemaIdentity: ComponentSchemaIdentity)
    private fun Schema<*>.resolveName(): String? =
        this.`$ref`?.removePrefix(SCHEMA_PREFIX) ?: componentSchemaIdentity[this]

    context(_: ComponentSchemaIdentity)
    private fun List<Schema<*>>?.resolveRefs(): List<String>? = this?.mapNotNull { it.resolveName() }

    private fun List<String>.toTypeRefs(): List<TypeRef.Reference>? = map(TypeRef::Reference).ifEmpty { null }

    private val Schema<*>.requiredSet: Set<String> get() = required.orEmpty().toSet()

    private fun String.upperCase() = replaceFirstChar { it.uppercase() }

    context(_: ComponentSchemaIdentity, _: ComponentSchemas)
    private fun Schema<*>.propertyModels(required: Set<String>, createContext: (String) -> String? = { null }) =
        properties
            .orEmpty()
            .mapValues { (propName, propSchema) ->
                createPropertyModel(propName, propSchema, required, createContext(propName))
            }
}

private const val SCHEMA_PREFIX = "#/components/schemas/"

private val STRING_FORMAT_MAP = mapOf(
    "byte" to PrimitiveType.BYTE_ARRAY,
    "date-time" to PrimitiveType.DATE_TIME,
    "date" to PrimitiveType.DATE,
)

private val INTEGER_FORMAT_MAP = mapOf(
    "int64" to PrimitiveType.LONG,
)

private val NUMBER_FORMAT_MAP = mapOf(
    "float" to PrimitiveType.FLOAT,
)
