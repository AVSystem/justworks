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
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
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
            // Build identity map from resolved Schema objects to their component names.
            // After resolveFully=true, $ref pointers are replaced with the actual Schema
            // object from components, so we can detect resolved refs by object identity.
            allSchemas.forEach { (name, schema) -> this[schema] = name }
        }
        context(componentSchemaIdentity, allSchemas.toMutableMap()) {
            val (enumModels, schemaModels) =
                allSchemas.asSequence().partition { (_, schema) -> schema.isEnumSchema }

            // Extract any inline schemas that were created during wrapper unwrapping
            // These are now in componentSchemas but weren't in the original allSchemas
            val newInlineSchemas = allSchemas.filterKeys { it !in allSchemas.keys }

            val (inlineEnumModels, inlineSchemaModels) =
                newInlineSchemas.asSequence().partition { (_, schema) -> schema.isEnumSchema }

            return ApiSpec(
                title = this.info?.title ?: "Untitled",
                version = this.info?.version ?: "0.0.0",
                endpoints = extractEndpoints(this.paths.orEmpty()),
                schemas = schemaModels
                    .plus(inlineSchemaModels)
                    .map { (name, schema) -> extractSchemaModel(name, schema) },
                enums = enumModels
                    .plus(inlineEnumModels)
                    .map { (name, schema) -> extractEnumModel(name, schema) },
            )
        }
    }

    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun extractEndpoints(paths: Map<String, PathItem>): List<Endpoint> = paths.flatMap { (path, pathItem) ->
        pathItem.readOperationsMap().map { (method, operation) ->
            // Merge path-level and operation-level parameters
            // Operation-level takes precedence (unique key = name + location)
            val mergedParams = (pathItem.parameters.orEmpty() + operation.parameters.orEmpty())
                .associateBy { "${it.name}:${it.`in`}" }
                .values
                .map { it.toParameter() }

            val requestBody = nullable {
                val body = operation.requestBody.bind()

                val (contentType, mediaType) = body.content
                    ?.entries
                    ?.firstOrNull()
                    .bind()

                val schema = mediaType.schema.bind()

                val schemaTypeRef = if (schema.isInlineObject) {
                    // Inline request body schema
                    createInlineTypeRef(schema, generateOperationId(method.name, path).upperCase() + "Request")
                } else {
                    schema.toTypeRef()
                }
                RequestBody(
                    required = body.required ?: false,
                    contentType = contentType,
                    schema = schemaTypeRef,
                )
            }
            val responses =
                operation.responses
                    ?.map { (code, resp) ->
                        val schema = resp.content?.get("application/json")?.schema
                        val schemaTypeRef = schema?.let { s ->
                            if (s.isInlineObject) {
                                // Inline response body schema
                                createInlineTypeRef(
                                    s,
                                    generateOperationId(method.name, path).upperCase() + "Response",
                                )
                            } else {
                                s.toTypeRef()
                            }
                        }
                        code to Response(statusCode = code, description = resp.description, schema = schemaTypeRef)
                    }?.toMap()
                    .orEmpty()

            Endpoint(
                path = path,
                method = HttpMethod.valueOf(method.name),
                operationId =
                    operation.operationId
                        ?: generateOperationId(method.name, path),
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

    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun Schema<*>.toTypeRef(): TypeRef =
        (this.name ?: allOf.takeIf { it.size == 1 }?.firstOrNull()?.name)?.let(TypeRef::Reference) ?: when (this.type) {
            "string" if this.format == "byte" -> TypeRef.Primitive(PrimitiveType.BYTE_ARRAY)

            "string" if this.format == "date-time" -> TypeRef.Primitive(PrimitiveType.DATE_TIME)

            "string" if this.format == "date" -> TypeRef.Primitive(PrimitiveType.DATE)

            "string" -> TypeRef.Primitive(PrimitiveType.STRING)

            "integer" if this.format == "int64" -> TypeRef.Primitive(PrimitiveType.LONG)

            "integer" -> TypeRef.Primitive(PrimitiveType.INT)

            "number" if this.format == "float" -> TypeRef.Primitive(PrimitiveType.FLOAT)

            "number" -> TypeRef.Primitive(PrimitiveType.DOUBLE)

            "boolean" -> TypeRef.Primitive(PrimitiveType.BOOLEAN)

            "array" -> this.items?.toTypeRef()?.let(TypeRef::Array) ?: TypeRef.Primitive(PrimitiveType.STRING)

            "object" -> (this.additionalProperties as? Schema<*>)?.toTypeRef() ?: TypeRef.Reference(
                this.title ?: "Unknown",
            )

            else -> TypeRef.Primitive(PrimitiveType.STRING)
        }

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
        ensure(schema.oneOf.isNullOrEmpty() || schema.discriminator != null)

        val (discriminatorMapping, unwrappedRefs) = schema.oneOf
            .orEmpty()
            .fold((emptyMap<String, String>() to emptyList<String>())) { (mappings, refs), variant ->
                // Check if variant is a wrapper object (not a $ref, not in identity map)
                // This variant is a direct reference, not a wrapper object
                ensure(variant.`$ref` != null || componentSchemaIdentity[variant] != null)

                // Check if variant is object type with exactly one property
                ensure(variant.type != "object" || variant.properties.isNullOrEmpty())

                val properties = variant.properties.orEmpty()
                ensure(properties.size != 1)

                // Extract the single property
                val (propertyName, propertySchema) = properties.entries.first()

                // The property value must be a reference or inline object
                val res =
                    propertySchema.resolveName()?.let { schemaName ->
                        "$SCHEMA_PREFIX$schemaName" to schemaName
                    } ?: propertySchema
                        .takeIf { it.type == "object" && !it.properties.isNullOrEmpty() }
                        ?.let { propertySchema ->
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
        val allOf = schema.allOf?.mapNotNull { it.resolveName() }

        // Check for oneOf wrapper pattern before standard extraction
        val (oneOf, discriminatorFromWrapper) = detectAndUnwrapOneOfWrappers(schema)
            ?: // Standard oneOf extraction (no wrapper pattern)
            (schema.oneOf?.mapNotNull { it.resolveName() } to null)

        val anyOf = schema.anyOf?.mapNotNull { it.resolveName() }

        // Validate no mixed anyOf+oneOf (check first, before discriminator check)
        if (!oneOf.isNullOrEmpty() && !anyOf.isNullOrEmpty()) {
            throw IllegalArgumentException("Schema '$name' has both oneOf and anyOf. Use one combinator only.")
        }

        // For allOf schemas, merge properties from all sub-schemas
        val (properties, requiredProps) =
            if (!schema.allOf.isNullOrEmpty()) {
                extractAllOfProperties(schema)
            } else {
                val requiredProps = schema.required.orEmpty().toSet()
                val props =
                    schema.properties.orEmpty().map { (propName, propSchema) ->
                        val propType = if (propSchema.isInlineObject) {
                            // Inline property schema - use Parent.PropertyName pattern
                            createInlineTypeRef(propSchema, "$name.${propName.toPascalCase()}")
                        } else {
                            propSchema.toTypeRef()
                        }
                        PropertyModel(
                            name = propName,
                            type = propType,
                            description = propSchema.description,
                            nullable = propName !in requiredProps,
                            defaultValue = propSchema.default,
                        )
                    }
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
            allOf = allOf?.map(TypeRef::Reference)?.ifEmpty { null },
            oneOf = oneOf?.map(TypeRef::Reference)?.ifEmpty { null },
            anyOf = anyOf?.map(TypeRef::Reference)?.ifEmpty { null },
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
        val mergedRequired = mutableSetOf<String>()
        val mergedProperties = mutableMapOf<String, PropertyModel>()

        // Collect properties from each allOf sub-schema.
        // For $ref sub-schemas (or identity-matched resolved refs), look up the
        // referenced component schema to get its properties and required fields.
        for (subSchema in schema.allOf.orEmpty()) {
            val resolvedSchema = resolveSubSchema(subSchema)
            val subRequired = resolvedSchema.required.orEmpty().toSet()
            mergedRequired.addAll(subRequired)

            resolvedSchema.properties.orEmpty().forEach { (propName, propSchema) ->
                mergedProperties[propName] =
                    PropertyModel(
                        name = propName,
                        type = propSchema.toTypeRef(),
                        description = propSchema.description,
                        nullable = propName !in mergedRequired,
                        defaultValue = propSchema.default,
                    )
            }
        }

        // Add top-level properties (inline alongside allOf)
        val topRequired = schema.required.orEmpty().toSet()
        mergedRequired.addAll(topRequired)

        schema.properties.orEmpty().forEach { (propName, propSchema) ->
            mergedProperties[propName] =
                PropertyModel(
                    name = propName,
                    type = propSchema.toTypeRef(),
                    description = propSchema.description,
                    nullable = propName !in mergedRequired,
                    defaultValue = propSchema.default,
                )
        }

        // Recompute nullable based on final merged required set
        val finalProperties =
            mergedProperties.values.map { prop ->
                prop.copy(nullable = prop.name !in mergedRequired)
            }

        return finalProperties to mergedRequired
    }

    /**
     * Resolves a sub-schema that may be a `$ref` or an identity-matched ref to the
     * actual component schema with properties. Returns the sub-schema itself if it is
     * an inline schema (not a reference).
     */
    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun resolveSubSchema(subSchema: Schema<*>): Schema<*> =
        // Check for explicit $ref string // Check for identity-matched resolved ref
        subSchema.name?.let { componentSchemas[it] } ?: subSchema

    /**
     * Checks if a schema is an inline object schema (not a $ref, not in componentSchemaIdentity,
     * is type object with properties).
     */
    context(componentSchemaIdentity: ComponentSchemaIdentity)
    private val Schema<*>.isInlineObject
        get(): Boolean = `$ref` == null &&
            this !in componentSchemaIdentity && type == "object" && properties.isNullOrEmpty()

    /**
     * Creates a TypeRef.Inline for an inline object schema.
     */
    context(componentSchemaIdentity: ComponentSchemaIdentity, componentSchemas: ComponentSchemas)
    private fun createInlineTypeRef(schema: Schema<*>, contextName: String): TypeRef {
        val requiredProps = schema.required.orEmpty().toSet()
        val properties = schema.properties.orEmpty().map { (propName, propSchema) ->
            val propType = if (propSchema.isInlineObject) {
                // Nested inline property schema
                createInlineTypeRef(propSchema, "$contextName.${propName.toPascalCase()}")
            } else {
                propSchema.toTypeRef()
            }
            PropertyModel(
                name = propName,
                type = propType,
                description = propSchema.description,
                nullable = propName !in requiredProps,
                defaultValue = propSchema.default,
            )
        }
        return TypeRef.Inline(
            properties = properties,
            requiredProperties = requiredProps,
            contextHint = contextName,
        )
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
    private fun Schema<*>.resolveName(): String? = this.`$ref`?.stripSchemaPrefix() ?: componentSchemaIdentity[this]

    private fun String.stripSchemaPrefix() = removePrefix(SCHEMA_PREFIX)

    private fun String.upperCase() = replaceFirstChar { it.uppercase() }
}

private const val SCHEMA_PREFIX = "#/components/schemas/"
