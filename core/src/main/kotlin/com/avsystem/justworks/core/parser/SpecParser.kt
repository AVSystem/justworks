package com.avsystem.justworks.core.parser

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

class SpecParser {
    // Identity map from resolved Schema objects to their component schema names.
    // Populated during transformToModel to detect inlined refs after resolveFully.
    private var componentSchemaIdentity: IdentityHashMap<Schema<*>, String> = IdentityHashMap()

    // Component schemas keyed by name, for resolving $ref in allOf sub-schemas.
    private var componentSchemas: Map<String, Schema<*>> = emptyMap()

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
                ?: return ParseResult.Failure(
                    messages.ifEmpty {
                        listOf("Failed to parse spec: ${specFile.name}")
                    },
                )

        val validationErrors = SpecValidator.validate(openApi)
        if (validationErrors.isNotEmpty()) {
            return ParseResult.Failure(validationErrors)
        }

        return try {
            val apiSpec = transformToModel(openApi)
            ParseResult.Success(apiSpec, warnings = messages)
        } catch (e: IllegalArgumentException) {
            ParseResult.Failure(listOf(e.message ?: "Schema validation failed"))
        }
    }

    private fun transformToModel(openApi: OpenAPI): ApiSpec {
        val allSchemas = openApi.components?.schemas.orEmpty()

        // Build identity map from resolved Schema objects to their component names.
        // After resolveFully=true, $ref pointers are replaced with the actual Schema
        // object from components, so we can detect resolved refs by object identity.
        componentSchemas = allSchemas
        componentSchemaIdentity =
            IdentityHashMap<Schema<*>, String>().apply {
                allSchemas.forEach { (name, schema) -> put(schema, name) }
            }

        val enumModels = mutableListOf<EnumModel>()
        val schemaModels = mutableListOf<SchemaModel>()

        allSchemas.forEach { (name, schema) ->
            if (isEnumSchema(schema)) {
                enumModels.add(extractEnumModel(name, schema))
            } else {
                schemaModels.add(extractSchemaModel(name, schema))
            }
        }

        // Extract any inline schemas that were created during wrapper unwrapping
        // These are now in componentSchemas but weren't in the original allSchemas
        val newInlineSchemas = componentSchemas.filter { (name, _) -> name !in allSchemas.keys }
        newInlineSchemas.forEach { (name, schema) ->
            if (isEnumSchema(schema)) {
                enumModels.add(extractEnumModel(name, schema))
            } else {
                schemaModels.add(extractSchemaModel(name, schema))
            }
        }

        val endpoints = extractEndpoints(openApi.paths.orEmpty())

        return ApiSpec(
            title = openApi.info?.title ?: "Untitled",
            version = openApi.info?.version ?: "0.0.0",
            endpoints = endpoints,
            schemas = schemaModels,
            enums = enumModels,
        )
    }

    private fun extractEndpoints(paths: Map<String, PathItem>): List<Endpoint> = paths.flatMap { (path, pathItem) ->
        pathItem.readOperationsMap().map { (method, operation) ->
            // Merge path-level and operation-level parameters
            // Operation-level takes precedence (unique key = name + location)
            val pathParams = pathItem.parameters.orEmpty()
            val opParams = operation.parameters.orEmpty()
            val mergedParams =
                (pathParams + opParams)
                    .associateBy { "${it.name}:${it.`in`}" }
                    .values
                    .map { toParameter(it) }

            val requestBody =
                operation.requestBody?.let { body ->
                    val contentEntry = body.content?.entries?.firstOrNull()
                    contentEntry?.let { (contentType, mediaType) ->
                        mediaType.schema?.let { schema ->
                            val schemaTypeRef = if (isInlineObjectSchema(schema)) {
                                // Inline request body schema
                                val contextName = generateOperationId(method.name, path)
                                    .replaceFirstChar { it.uppercase() } + "Request"
                                createInlineTypeRef(schema, contextName)
                            } else {
                                schemaToTypeRef(schema)
                            }
                            RequestBody(
                                required = body.required ?: false,
                                contentType = contentType,
                                schema = schemaTypeRef,
                            )
                        }
                    }
                }

            val responses =
                operation.responses
                    ?.map { (code, resp) ->
                        val schema =
                            resp.content
                                ?.get("application/json")
                                ?.schema
                        val schemaTypeRef = schema?.let { s ->
                            if (isInlineObjectSchema(s)) {
                                // Inline response body schema
                                val contextName = generateOperationId(method.name, path)
                                    .replaceFirstChar { it.uppercase() } + "Response"
                                createInlineTypeRef(s, contextName)
                            } else {
                                schemaToTypeRef(s)
                            }
                        }
                        code to
                            Response(
                                statusCode = code,
                                description = resp.description,
                                schema = schemaTypeRef,
                            )
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

    private fun toParameter(param: io.swagger.v3.oas.models.parameters.Parameter): Parameter {
        val location =
            when (param.`in`?.lowercase()) {
                "path" -> ParameterLocation.PATH
                "query" -> ParameterLocation.QUERY
                "header" -> ParameterLocation.HEADER
                else -> ParameterLocation.QUERY
            }
        return Parameter(
            name = param.name ?: "",
            location = location,
            required = param.required ?: false,
            schema =
                param.schema?.let { schemaToTypeRef(it) }
                    ?: TypeRef.Primitive(PrimitiveType.STRING),
            description = param.description,
        )
    }

    private fun schemaToTypeRef(schema: Schema<*>): TypeRef {
        schema.`$ref`?.let { ref ->
            val schemaName = ref.removePrefix("#/components/schemas/")
            return TypeRef.Reference(schemaName)
        }

        // After resolveFully, $ref is null but the schema object may be the same
        // instance as a component schema. Detect by identity to preserve references.
        componentSchemaIdentity[schema]?.let { schemaName ->
            return TypeRef.Reference(schemaName)
        }

        // Check for allOf with single reference (common pattern for property schemas with defaults)
        schema.allOf?.takeIf { it.size == 1 }?.firstOrNull()?.let { allOfSchema ->
            allOfSchema.`$ref`?.let { ref ->
                return TypeRef.Reference(ref.removePrefix("#/components/schemas/"))
            }
            componentSchemaIdentity[allOfSchema]?.let { schemaName ->
                return TypeRef.Reference(schemaName)
            }
        }

        return when (schema.type) {
            "string" -> {
                when (schema.format) {
                    "byte" -> TypeRef.Primitive(PrimitiveType.BYTE_ARRAY)
                    "date-time" -> TypeRef.Primitive(PrimitiveType.DATE_TIME)
                    "date" -> TypeRef.Primitive(PrimitiveType.DATE)
                    else -> TypeRef.Primitive(PrimitiveType.STRING)
                }
            }

            "integer" -> {
                when (schema.format) {
                    "int64" -> TypeRef.Primitive(PrimitiveType.LONG)
                    else -> TypeRef.Primitive(PrimitiveType.INT)
                }
            }

            "number" -> {
                when (schema.format) {
                    "float" -> TypeRef.Primitive(PrimitiveType.FLOAT)
                    else -> TypeRef.Primitive(PrimitiveType.DOUBLE)
                }
            }

            "boolean" -> {
                TypeRef.Primitive(PrimitiveType.BOOLEAN)
            }

            "array" -> {
                val items =
                    schema.items
                        ?: return TypeRef.Primitive(PrimitiveType.STRING)
                TypeRef.Array(schemaToTypeRef(items))
            }

            "object" -> {
                val additionalProperties = schema.additionalProperties
                if (additionalProperties is Schema<*>) {
                    TypeRef.Map(schemaToTypeRef(additionalProperties))
                } else {
                    TypeRef.Reference(schema.title ?: "Unknown")
                }
            }

            else -> {
                TypeRef.Primitive(PrimitiveType.STRING)
            }
        }
    }

    private fun isEnumSchema(schema: Schema<*>): Boolean = !schema.enum.isNullOrEmpty()

    private fun extractEnumModel(name: String, schema: Schema<*>): EnumModel {
        val backingType =
            when (schema.type) {
                "integer" -> EnumBackingType.INTEGER
                else -> EnumBackingType.STRING
            }
        return EnumModel(
            name = name,
            description = schema.description,
            type = backingType,
            values = schema.enum.map { it.toString() },
        )
    }

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
    private fun detectAndUnwrapOneOfWrappers(schema: Schema<*>): Pair<List<TypeRef>, Discriminator>? {
        // Must have oneOf and no explicit discriminator
        if (schema.oneOf.isNullOrEmpty() || schema.discriminator != null) {
            return null
        }

        val unwrappedRefs = mutableListOf<TypeRef>()
        val discriminatorMapping = mutableMapOf<String, String>()

        for (variant in schema.oneOf.orEmpty()) {
            // Check if variant is a wrapper object (not a $ref, not in identity map)
            if (variant.`$ref` != null || componentSchemaIdentity[variant] != null) {
                // This variant is a direct reference, not a wrapper object
                return null
            }

            // Check if variant is object type with exactly one property
            if (variant.type != "object" || variant.properties.isNullOrEmpty()) {
                return null
            }

            val properties = variant.properties.orEmpty()
            if (properties.size != 1) {
                return null
            }

            // Extract the single property
            val (propertyName, propertySchema) = properties.entries.first()

            // The property value must be a reference or inline object
            val unwrappedRef = if (propertySchema.`$ref` != null) {
                // Property points to a $ref - extract schema name
                val schemaName = propertySchema.`$ref`.removePrefix("#/components/schemas/")
                discriminatorMapping[propertyName] = propertySchema.`$ref`
                TypeRef.Reference(schemaName)
            } else if (componentSchemaIdentity[propertySchema] != null) {
                // Property is a resolved reference (after resolveFully)
                val schemaName = componentSchemaIdentity[propertySchema]!!
                discriminatorMapping[propertyName] = "#/components/schemas/$schemaName"
                TypeRef.Reference(schemaName)
            } else if (propertySchema.type == "object" && !propertySchema.properties.isNullOrEmpty()) {
                // Property is an inline object - create a component schema for it
                val inlineSchemaName = propertyName.toPascalCase()
                val inlineSchema = propertySchema

                // Register this inline schema as a component for future lookups
                componentSchemas = componentSchemas + (inlineSchemaName to inlineSchema)
                componentSchemaIdentity[inlineSchema] = inlineSchemaName

                discriminatorMapping[propertyName] = "#/components/schemas/$inlineSchemaName"
                TypeRef.Reference(inlineSchemaName)
            } else {
                // Property value is neither a ref nor an inline object
                return null
            }

            unwrappedRefs.add(unwrappedRef)
        }

        // All variants matched the wrapper pattern - create synthetic discriminator
        val syntheticDiscriminator = Discriminator(
            propertyName = "type",
            mapping = discriminatorMapping,
        )

        return unwrappedRefs to syntheticDiscriminator
    }

    private fun extractSchemaModel(name: String, schema: Schema<*>): SchemaModel {
        val allOf =
            schema.allOf?.mapNotNull { subSchema ->
                subSchema.`$ref`?.let { ref ->
                    TypeRef.Reference(ref.removePrefix("#/components/schemas/"))
                } ?: componentSchemaIdentity[subSchema]?.let { TypeRef.Reference(it) }
            }

        // Check for oneOf wrapper pattern before standard extraction
        val (oneOf, discriminatorFromWrapper) = detectAndUnwrapOneOfWrappers(schema)
            ?: run {
                // Standard oneOf extraction (no wrapper pattern)
                val standardOneOf = schema.oneOf?.mapNotNull { subSchema ->
                    subSchema.`$ref`?.let { ref ->
                        TypeRef.Reference(ref.removePrefix("#/components/schemas/"))
                    } ?: componentSchemaIdentity[subSchema]?.let { TypeRef.Reference(it) }
                }
                standardOneOf to null
            }

        val anyOf =
            schema.anyOf?.mapNotNull { subSchema ->
                subSchema.`$ref`?.let { ref ->
                    TypeRef.Reference(ref.removePrefix("#/components/schemas/"))
                } ?: componentSchemaIdentity[subSchema]?.let { TypeRef.Reference(it) }
            }

        // Validate no mixed anyOf+oneOf (check first, before discriminator check)
        if (oneOf != null && oneOf.isNotEmpty() && anyOf != null && anyOf.isNotEmpty()) {
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
                        val propType = if (isInlineObjectSchema(propSchema)) {
                            // Inline property schema - use Parent.PropertyName pattern
                            val contextName = "$name.${propName.toPascalCase()}"
                            createInlineTypeRef(propSchema, contextName)
                        } else {
                            schemaToTypeRef(propSchema)
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

        val discriminator =
            discriminatorFromWrapper ?: schema.discriminator?.let { disc ->
                disc.propertyName?.let {
                    Discriminator(propertyName = it, mapping = disc.mapping.orEmpty())
                }
            }

        return SchemaModel(
            name = name,
            description = schema.description,
            properties = properties,
            requiredProperties = requiredProps,
            isEnum = false,
            allOf = allOf?.ifEmpty { null },
            oneOf = oneOf?.ifEmpty { null },
            anyOf = anyOf?.ifEmpty { null },
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
                        type = schemaToTypeRef(propSchema),
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
                    type = schemaToTypeRef(propSchema),
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
    private fun resolveSubSchema(subSchema: Schema<*>): Schema<*> {
        // Check for explicit $ref string
        subSchema.`$ref`?.let { ref ->
            val name = ref.removePrefix("#/components/schemas/")
            componentSchemas[name]?.let { return it }
        }
        // Check for identity-matched resolved ref
        componentSchemaIdentity[subSchema]?.let { name ->
            componentSchemas[name]?.let { return it }
        }
        return subSchema
    }

    /**
     * Checks if a schema is an inline object schema (not a $ref, not in componentSchemaIdentity,
     * is type object with properties).
     */
    private fun isInlineObjectSchema(schema: Schema<*>): Boolean = schema.`$ref` == null &&
        componentSchemaIdentity[schema] == null &&
        schema.type == "object" &&
        !schema.properties.isNullOrEmpty()

    /**
     * Creates a TypeRef.Inline for an inline object schema.
     */
    private fun createInlineTypeRef(schema: Schema<*>, contextName: String): TypeRef {
        val requiredProps = schema.required.orEmpty().toSet()
        val properties = schema.properties.orEmpty().map { (propName, propSchema) ->
            val propType = if (isInlineObjectSchema(propSchema)) {
                // Nested inline property schema
                val nestedContextName = "$contextName.${propName.toPascalCase()}"
                createInlineTypeRef(propSchema, nestedContextName)
            } else {
                schemaToTypeRef(propSchema)
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

    private fun String.toPascalCase(): String = split("-", "_", ".").joinToString("") { part ->
        part.replaceFirstChar {
            it.uppercase()
        }
    }
}
