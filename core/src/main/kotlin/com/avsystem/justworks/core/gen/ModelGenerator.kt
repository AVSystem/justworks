package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import java.io.File
import kotlin.time.Instant

/**
 * Generates KotlinPoet [FileSpec] instances from an [ApiSpec].
 *
 * Produces one file per [SchemaModel] (data class, sealed interface, or allOf composed class)
 * and one file per [EnumModel] (enum class), all annotated with kotlinx.serialization annotations.
 */
class ModelGenerator(private val modelPackage: String) {
    // Maps sealed parent name -> list of variant schema names
    private val sealedHierarchies = mutableMapOf<String, List<String>>()

    // Maps variant schema name -> (parent ClassName, serialName)
    private val variantParents = mutableMapOf<String, MutableList<Pair<ClassName, String>>>()

    // Set of schema names that are anyOf without discriminator (use JsonContentPolymorphicSerializer)
    private val anyOfWithoutDiscriminator = mutableSetOf<String>()

    fun getSealedHierarchies(): Map<String, List<String>> = sealedHierarchies.toMap()

    fun generate(spec: ApiSpec): List<FileSpec> {
        // Reset state
        sealedHierarchies.clear()
        variantParents.clear()
        anyOfWithoutDiscriminator.clear()

        val schemasById = spec.schemas.associateBy { it.name }

        // Initialize deduplicator and register component schemas
        val deduplicator = InlineSchemaDeduplicator()
        deduplicator.registerComponentSchemas(spec.schemas)

        // Collect all inline TypeRefs from the spec
        val inlineTypeRefs = mutableListOf<TypeRef.Inline>()

        // Scan endpoints for inline schemas in request/response bodies
        for (endpoint in spec.endpoints) {
            endpoint.requestBody?.schema?.let { collectInlineTypeRefs(it, inlineTypeRefs) }
            endpoint.responses.values.forEach { response ->
                response.schema?.let { collectInlineTypeRefs(it, inlineTypeRefs) }
            }
        }

        // Scan component schemas for inline property schemas
        for (schema in spec.schemas) {
            for (property in schema.properties) {
                collectInlineTypeRefs(property.type, inlineTypeRefs)
            }
        }

        // Generate SchemaModels for inline schemas with deduplication
        val inlineSchemas = mutableListOf<SchemaModel>()
        val processedKeys = mutableSetOf<InlineSchemaKey>()

        for (inlineTypeRef in inlineTypeRefs) {
            val key = InlineSchemaKey.from(inlineTypeRef.properties, inlineTypeRef.requiredProperties)
            if (key !in processedKeys) {
                processedKeys.add(key)
                val name =
                    deduplicator.getOrGenerateName(
                        inlineTypeRef.properties,
                        inlineTypeRef.requiredProperties,
                        inlineTypeRef.contextHint,
                    )

                // Check if this is a nested inline (contains dot)
                val isNested = name.contains(".")

                inlineSchemas.add(
                    SchemaModel(
                        name = name,
                        description = null,
                        properties = inlineTypeRef.properties,
                        requiredProperties = inlineTypeRef.requiredProperties,
                        isEnum = false,
                        allOf = null,
                        oneOf = null,
                        anyOf = null,
                        discriminator = null,
                    ),
                )
            }
        }

        // First pass: scan all schemas to build variantParents map and detect anyOf-without-discriminator
        for (schema in spec.schemas) {
            if (schema.isEnum) continue
            val variants = schema.oneOf ?: schema.anyOf
            if (!variants.isNullOrEmpty()) {
                val parentClassName = ClassName(modelPackage, schema.name)
                val variantNames = mutableListOf<String>()

                for (ref in variants) {
                    if (ref is TypeRef.Reference) {
                        val variantName = ref.schemaName
                        variantNames.add(variantName)

                        // Determine serial name from discriminator mapping or default to schema name
                        val serialName = resolveSerialName(schema, variantName)

                        variantParents
                            .getOrPut(variantName) { mutableListOf() }
                            .add(parentClassName to serialName)
                    }
                }

                sealedHierarchies[schema.name] = variantNames

                // Track anyOf schemas without discriminator for JsonContentPolymorphicSerializer generation
                if (!schema.anyOf.isNullOrEmpty() && schema.discriminator == null) {
                    anyOfWithoutDiscriminator.add(schema.name)
                }
            }
        }

        // Second pass: generate FileSpecs for component schemas
        val schemaFiles =
            spec.schemas
                .filter { !it.isEnum }
                .flatMap { schema ->
                    when {
                        !schema.oneOf.isNullOrEmpty() || !schema.anyOf.isNullOrEmpty() -> {
                            val sealedFile = generateSealedInterface(schema)
                            if (schema.name in anyOfWithoutDiscriminator) {
                                // Also generate the JsonContentPolymorphicSerializer companion object
                                val serializerFile = generatePolymorphicSerializer(schema, schemasById)
                                listOf(sealedFile, serializerFile)
                            } else {
                                listOf(sealedFile)
                            }
                        }

                        !schema.allOf.isNullOrEmpty() -> {
                            listOf(generateAllOfDataClass(schema, schemasById))
                        }

                        isPrimitiveOnly(schema) -> {
                            // For primitive-only schemas, generate type alias
                            // TODO: Extend SchemaModel to include primitiveType field for primitive-only schemas
                            // For now, defaulting to String as the most common case
                            listOf(generateTypeAlias(schema, STRING))
                        }

                        else -> {
                            listOf(generateDataClass(schema))
                        }
                    }
                }

        // Generate FileSpecs for inline schemas (non-nested first, nested later)
        val nonNestedInlineSchemas = inlineSchemas.filter { !it.name.contains(".") }
        val nestedInlineSchemas = inlineSchemas.filter { it.name.contains(".") }

        val inlineSchemaFiles =
            nonNestedInlineSchemas.map { generateDataClass(it) } +
                nestedInlineSchemas.map { generateNestedInlineClass(it) }

        val enumFiles = spec.enums.map { generateEnumClass(it) }

        // Generate SerializersModule if any sealed hierarchies exist
        val serializersModuleFile = SerializersModuleGenerator(modelPackage).generate(sealedHierarchies)

        return schemaFiles + inlineSchemaFiles + enumFiles + listOfNotNull(serializersModuleFile)
    }

    /**
     * Generates model files from [spec] and writes them to [outputDir].
     * Returns the number of files written.
     */
    fun generateTo(
        spec: ApiSpec,
        outputDir: File,
    ): Int {
        val files = generate(spec)
        for (fileSpec in files) {
            fileSpec.writeTo(outputDir)
        }
        return files.size
    }

    /**
     * Generates a sealed interface for a oneOf/anyOf schema.
     * - anyOf without discriminator: @Serializable(with = XxxSerializer::class)
     * - oneOf or anyOf with discriminator: plain @Serializable + @JsonClassDiscriminator
     */
    private fun generateSealedInterface(schema: SchemaModel): FileSpec {
        val className = ClassName(modelPackage, schema.name)

        val typeSpec = TypeSpec.interfaceBuilder(className).addModifiers(KModifier.SEALED)

        if (schema.name in anyOfWithoutDiscriminator) {
            // anyOf without discriminator: use JsonContentPolymorphicSerializer
            val serializerClassName = ClassName(modelPackage, "${schema.name}Serializer")
            typeSpec.addAnnotation(
                AnnotationSpec
                    .builder(SERIALIZABLE)
                    .addMember("with = %T::class", serializerClassName)
                    .build(),
            )
        } else {
            typeSpec.addAnnotation(SERIALIZABLE)
        }

        if (schema.discriminator != null) {
            typeSpec.addAnnotation(
                AnnotationSpec
                    .builder(JSON_CLASS_DISCRIMINATOR)
                    .addMember("%S", schema.discriminator.propertyName)
                    .build(),
            )
        }

        if (schema.description != null) {
            typeSpec.addKdoc("%L", schema.description)
        }

        val fileBuilder =
            FileSpec
                .builder(className)
                .addType(typeSpec.build())

        // Add @OptIn for ExperimentalSerializationApi when discriminator is used
        if (schema.discriminator != null) {
            fileBuilder.addAnnotation(
                AnnotationSpec
                    .builder(OPT_IN)
                    .addMember("%T::class", EXPERIMENTAL_SERIALIZATION_API)
                    .build(),
            )
        }

        return fileBuilder.build()
    }

    /**
     * Generates a JsonContentPolymorphicSerializer object for an anyOf schema without discriminator.
     *
     * The serializer uses field-presence heuristic: for each variant, finds a field unique to that variant
     * (not shared with any other variant) and uses it as a discriminating condition in selectDeserializer.
     *
     * If no unique field is found for a variant, a TODO() placeholder is emitted.
     */
    private fun generatePolymorphicSerializer(
        schema: SchemaModel,
        schemasById: Map<String, SchemaModel>,
    ): FileSpec {
        val sealedClassName = ClassName(modelPackage, schema.name)
        val serializerClassName = ClassName(modelPackage, "${schema.name}Serializer")

        // Collect property names per variant
        val variantProperties: List<Pair<String, Set<String>>> = schema.anyOf
            .orEmpty()
            .filterIsInstance<TypeRef.Reference>()
            .map { ref ->
                val variantSchema = schemasById[ref.schemaName]
                val propNames = variantSchema?.properties?.map { it.name }?.toSet() ?: emptySet()
                ref.schemaName to propNames
            }

        // Find unique fields per variant: a field is unique if no other variant has it
        val allFieldSets = variantProperties.map { it.second }
        val uniqueFieldsPerVariant: List<Pair<String, String?>> = variantProperties.map { (variantName, fields) ->
            val otherFields = allFieldSets.filter { it !== fields }.flatten().toSet()
            val uniqueField = fields.firstOrNull { it !in otherFields }
            variantName to uniqueField
        }

        // Build selectDeserializer function body
        val selectDeserializerBody = buildSelectDeserializerBody(schema.name, sealedClassName, uniqueFieldsPerVariant)

        val deserializationStrategy = ClassName("kotlinx.serialization", "DeserializationStrategy")
            .parameterizedBy(com.squareup.kotlinpoet.STAR)

        val selectFun = FunSpec
            .builder("selectDeserializer")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter(ParameterSpec.builder("element", JSON_ELEMENT).build())
            .returns(deserializationStrategy)
            .addCode(selectDeserializerBody)
            .build()

        val objectSpec = TypeSpec
            .objectBuilder(serializerClassName)
            .superclass(
                JSON_CONTENT_POLYMORPHIC_SERIALIZER.parameterizedBy(sealedClassName),
            ).addSuperclassConstructorParameter("%T::class", sealedClassName)
            .addFunction(selectFun)
            .build()

        return FileSpec
            .builder(serializerClassName)
            .addType(objectSpec)
            .build()
    }

    /**
     * Builds the body code for selectDeserializer using field-presence heuristics.
     * For each variant with a unique field: when-clause checking field presence.
     * For variants with no unique fields: TODO() with descriptive message.
     */
    private fun buildSelectDeserializerBody(
        parentName: String,
        sealedClassName: ClassName,
        uniqueFieldsPerVariant: List<Pair<String, String?>>,
    ): CodeBlock {
        val builder = CodeBlock.builder()
        builder.beginControlFlow("return when")

        for ((variantName, uniqueField) in uniqueFieldsPerVariant) {
            val variantClassName = ClassName(modelPackage, variantName)
            if (uniqueField != null) {
                builder.addStatement(
                    "%S·in·element.%M -> %T.serializer()",
                    uniqueField,
                    JSON_OBJECT_EXT,
                    variantClassName,
                )
            } else {
                builder.addStatement(
                    "// No unique discriminating fields found for variant '$variantName'",
                )
                builder.addStatement(
                    "else -> TODO(%S)",
                    "No unique discriminating fields found for variant '$variantName' of anyOf '$parentName' - manual selectDeserializer required",
                )
            }
        }

        // Add a final else clause with SerializationException (only if all variants had unique fields)
        val allHaveUniqueFields = uniqueFieldsPerVariant.all { it.second != null }
        if (allHaveUniqueFields) {
            builder.addStatement(
                "else -> throw %T(%S + element)",
                SERIALIZATION_EXCEPTION,
                "Unknown $parentName variant: ",
            )
        }

        builder.endControlFlow()
        return builder.build()
    }

    /**
     * Generates a data class for an allOf schema with merged properties.
     * If any allOf ref target is a oneOf sealed interface, adds it as a superinterface.
     */
    private fun generateAllOfDataClass(
        schema: SchemaModel,
        schemasById: Map<String, SchemaModel>,
    ): FileSpec {
        // Determine superinterfaces from allOf refs that point to sealed interfaces
        val superinterfaces = mutableListOf<ClassName>()
        for (ref in schema.allOf.orEmpty()) {
            if (ref is TypeRef.Reference) {
                val refSchema = schemasById[ref.schemaName]
                if (refSchema != null && !refSchema.oneOf.isNullOrEmpty()) {
                    superinterfaces.add(ClassName(modelPackage, ref.schemaName))
                }
            }
        }

        // Check if this schema is a variant of a sealed parent (via variantParents map)
        val parentEntries = variantParents[schema.name]
        val serialName = parentEntries?.firstOrNull()?.second

        // Merge superinterfaces from allOf refs and variantParents
        val allSuperinterfaces = superinterfaces.toMutableList()
        parentEntries?.forEach { (parentClass, _) ->
            if (parentClass !in allSuperinterfaces) {
                allSuperinterfaces.add(parentClass)
            }
        }

        return generateDataClass(schema, allSuperinterfaces, serialName)
    }

    /**
     * Generates a data class FileSpec, optionally with superinterfaces and @SerialName.
     */
    private fun generateDataClass(
        schema: SchemaModel,
        superinterfaces: List<ClassName> = emptyList(),
        serialName: String? = null,
    ): FileSpec {
        val className = ClassName(modelPackage, schema.name)

        // Check if this variant has parent info from oneOf scanning
        val effectiveSuperinterfaces = superinterfaces.toMutableList()
        val effectiveSerialName = serialName ?: variantParents[schema.name]?.firstOrNull()?.second

        variantParents[schema.name]?.forEach { (parentClass, _) ->
            if (parentClass !in effectiveSuperinterfaces) {
                effectiveSuperinterfaces.add(parentClass)
            }
        }

        // Sort properties: required without default, properties with defaults, nullable/optional
        val requiredWithoutDefault =
            schema.properties.filter {
                it.name in schema.requiredProperties && it.defaultValue == null
            }
        val propertiesWithDefaults =
            schema.properties.filter {
                it.defaultValue != null
            }
        val nullableWithoutDefault =
            schema.properties.filter {
                it.name !in schema.requiredProperties && it.defaultValue == null
            }
        val sortedProps = requiredWithoutDefault + propertiesWithDefaults + nullableWithoutDefault

        val constructorBuilder = FunSpec.constructorBuilder()
        val propertySpecs = mutableListOf<PropertySpec>()

        for (prop in sortedProps) {
            val baseType = TypeMapping.toTypeName(prop.type, modelPackage)
            val kotlinName = prop.name.toKotlinIdentifier()

            // Determine final type and default value
            val (type, defaultValue) =
                when {
                    // Nullable with default -> honor nullable, ignore OpenAPI default
                    prop.nullable && prop.defaultValue != null -> {
                        baseType.copy(nullable = true) to "null"
                    }

                    // Non-nullable with default -> use OpenAPI default
                    !prop.nullable && prop.defaultValue != null -> {
                        baseType to formatDefaultValue(prop)
                    }

                    // Nullable without default -> nullable with null default
                    prop.nullable && prop.defaultValue == null -> {
                        baseType.copy(nullable = true) to "null"
                    }

                    // Required without default -> no default value
                    else -> {
                        baseType to null
                    }
                }

            val paramBuilder = ParameterSpec.builder(kotlinName, type)
            if (defaultValue != null) {
                paramBuilder.defaultValue(defaultValue)
            }
            constructorBuilder.addParameter(paramBuilder.build())

            val propSpec =
                PropertySpec
                    .builder(kotlinName, type)
                    .initializer(kotlinName)
                    .addAnnotation(
                        AnnotationSpec
                            .builder(SERIAL_NAME)
                            .addMember("%S", prop.name)
                            .build(),
                    )
            propertySpecs.add(propSpec.build())
        }

        val typeSpec =
            TypeSpec
                .classBuilder(className)
                .addModifiers(KModifier.DATA)
                .primaryConstructor(constructorBuilder.build())
                .addProperties(propertySpecs)
                .addAnnotation(SERIALIZABLE)

        // Add superinterfaces
        for (si in effectiveSuperinterfaces) {
            typeSpec.addSuperinterface(si)
        }

        // Add @SerialName for variants
        if (effectiveSerialName != null) {
            typeSpec.addAnnotation(
                AnnotationSpec
                    .builder(SERIAL_NAME)
                    .addMember("%S", effectiveSerialName)
                    .build(),
            )
        }

        if (schema.description != null) {
            typeSpec.addKdoc("%L", schema.description)
        }

        return FileSpec
            .builder(className)
            .addType(typeSpec.build())
            .build()
    }

    /**
     * Formats a default value from a PropertyModel for use in KotlinPoet ParameterSpec.defaultValue().
     * Handles primitives (string, number, boolean) and date/time types.
     * Validates date/time defaults at generation time.
     */
    private fun formatDefaultValue(prop: PropertyModel): String = when (prop.type) {
        is TypeRef.Primitive -> {
            when (prop.type.type) {
                PrimitiveType.STRING -> {
                    // Return the string with quotes for KotlinPoet
                    "\"${prop.defaultValue}\""
                }

                PrimitiveType.INT,
                PrimitiveType.LONG,
                PrimitiveType.DOUBLE,
                PrimitiveType.FLOAT,
                PrimitiveType.BOOLEAN,
                -> {
                    prop.defaultValue.toString()
                }

                PrimitiveType.DATE_TIME -> {
                    // Validate at generation time
                    try {
                        Instant.parse(prop.defaultValue as String)
                        "kotlin.time.Instant.parse(\"${prop.defaultValue}\")"
                    } catch (e: Exception) {
                        throw IllegalArgumentException(
                            "Invalid ISO-8601 date-time default '${prop.defaultValue}' " +
                                "for property ${prop.name}: ${e.message}",
                        )
                    }
                }

                PrimitiveType.DATE -> {
                    try {
                        kotlinx.datetime.LocalDate.parse(prop.defaultValue as String)
                        "kotlinx.datetime.LocalDate.parse(\"${prop.defaultValue}\")"
                    } catch (e: Exception) {
                        throw IllegalArgumentException(
                            "Invalid ISO-8601 date default '${prop.defaultValue}' " +
                                "for property ${prop.name}: ${e.message}",
                        )
                    }
                }

                else -> {
                    throw IllegalArgumentException(
                        "Unsupported default value type: ${prop.type}",
                    )
                }
            }
        }

        is TypeRef.Reference -> {
            // Enum default: use constant name conversion
            val constantName = prop.defaultValue.toString().toEnumConstantName()
            "${prop.type.schemaName}.$constantName"
        }

        else -> {
            throw IllegalArgumentException(
                "Unsupported default value type: ${prop.type}",
            )
        }
    }

    /**
     * Resolves the @SerialName value for a variant within a oneOf schema.
     * Uses discriminator mapping if available, otherwise defaults to the schema name.
     */
    private fun resolveSerialName(
        parentSchema: SchemaModel,
        variantSchemaName: String,
    ): String {
        val mapping = parentSchema.discriminator?.mapping
        if (!mapping.isNullOrEmpty()) {
            // mapping is: serialName -> ref path (e.g., "circle" -> "#/components/schemas/Circle")
            for ((serialName, refPath) in mapping) {
                val refName = refPath.removePrefix("#/components/schemas/")
                if (refName == variantSchemaName) {
                    return serialName
                }
            }
        }
        // Default: use schema name as serial name
        return variantSchemaName
    }

    private fun generateEnumClass(enum: EnumModel): FileSpec {
        val className = ClassName(modelPackage, enum.name)

        val typeSpec =
            TypeSpec
                .enumBuilder(className)
                .addAnnotation(SERIALIZABLE)

        for (value in enum.values) {
            val constantName = value.toEnumConstantName()
            val anonymousClass =
                TypeSpec
                    .anonymousClassBuilder()
                    .addAnnotation(
                        AnnotationSpec
                            .builder(SERIAL_NAME)
                            .addMember("%S", value)
                            .build(),
                    ).build()
            typeSpec.addEnumConstant(constantName, anonymousClass)
        }

        if (enum.description != null) {
            typeSpec.addKdoc("%L", enum.description)
        }

        return FileSpec
            .builder(className)
            .addType(typeSpec.build())
            .build()
    }

    /**
     * Recursively collects all TypeRef.Inline instances from a TypeRef tree.
     * The [visited] set guards against infinite recursion in circular schemas.
     */
    private fun collectInlineTypeRefs(
        typeRef: TypeRef,
        result: MutableList<TypeRef.Inline>,
        visited: MutableSet<TypeRef.Inline> = mutableSetOf(),
    ) {
        when (typeRef) {
            is TypeRef.Inline -> {
                if (!visited.add(typeRef)) return
                result.add(typeRef)
                // Recursively collect from properties
                typeRef.properties.forEach { prop ->
                    collectInlineTypeRefs(prop.type, result, visited)
                }
            }

            is TypeRef.Array -> {
                collectInlineTypeRefs(typeRef.items, result, visited)
            }

            is TypeRef.Map -> {
                collectInlineTypeRefs(typeRef.valueType, result, visited)
            }

            is TypeRef.Primitive, is TypeRef.Reference, is TypeRef.Unknown -> {}
        }
    }

    /**
     * Generates a nested inline class (e.g., Pet.Address).
     * The name format is "Parent.Child" which we split and generate as a nested class.
     * For now, we generate it as a top-level class with the full name.
     * TODO: In the future, this could use TypeSpec.addType() for true nested classes.
     */
    private fun generateNestedInlineClass(schema: SchemaModel): FileSpec {
        // For nested classes like "Pet.Address", generate as top-level for now
        // Replace dot with underscore to create valid class name
        val sanitizedName = schema.name.replace(".", "")
        val modifiedSchema = schema.copy(name = sanitizedName)
        return generateDataClass(modifiedSchema)
    }

    /**
     * Checks if a schema is primitive-only (has no properties and no composite types).
     * Primitive-only schemas should be generated as type aliases instead of data classes.
     */
    private fun isPrimitiveOnly(schema: SchemaModel): Boolean = schema.properties.isEmpty() &&
        !schema.isEnum && schema.allOf == null && schema.oneOf == null && schema.anyOf == null

    /**
     * Generates a type alias FileSpec for primitive-only schemas.
     * Example: typealias GroupId = String
     */
    private fun generateTypeAlias(
        schema: SchemaModel,
        primitiveType: com.squareup.kotlinpoet.TypeName,
    ): FileSpec {
        val className = ClassName(modelPackage, schema.name)

        val typeAlias = TypeAliasSpec.builder(schema.name, primitiveType)

        if (schema.description != null) {
            typeAlias.addKdoc("%L", schema.description)
        }

        return FileSpec
            .builder(className)
            .addTypeAlias(typeAlias.build())
            .build()
    }
}
