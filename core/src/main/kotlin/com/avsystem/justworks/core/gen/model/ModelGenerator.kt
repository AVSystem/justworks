package com.avsystem.justworks.core.gen.model

import arrow.core.raise.catch
import com.avsystem.justworks.core.gen.DECODER
import com.avsystem.justworks.core.gen.ENCODER
import com.avsystem.justworks.core.gen.EXPERIMENTAL_SERIALIZATION_API
import com.avsystem.justworks.core.gen.EXPERIMENTAL_UUID_API
import com.avsystem.justworks.core.gen.INSTANT
import com.avsystem.justworks.core.gen.InlineSchemaKey
import com.avsystem.justworks.core.gen.JSON_CLASS_DISCRIMINATOR
import com.avsystem.justworks.core.gen.JSON_CONTENT_POLYMORPHIC_SERIALIZER
import com.avsystem.justworks.core.gen.JSON_ELEMENT
import com.avsystem.justworks.core.gen.JSON_OBJECT_EXT
import com.avsystem.justworks.core.gen.K_SERIALIZER
import com.avsystem.justworks.core.gen.LOCAL_DATE
import com.avsystem.justworks.core.gen.ModelPackage
import com.avsystem.justworks.core.gen.NameRegistry
import com.avsystem.justworks.core.gen.OPT_IN
import com.avsystem.justworks.core.gen.PRIMITIVE_KIND
import com.avsystem.justworks.core.gen.PRIMITIVE_SERIAL_DESCRIPTOR_FUN
import com.avsystem.justworks.core.gen.SERIALIZABLE
import com.avsystem.justworks.core.gen.SERIALIZATION_EXCEPTION
import com.avsystem.justworks.core.gen.SERIALIZERS_MODULE
import com.avsystem.justworks.core.gen.SERIAL_DESCRIPTOR
import com.avsystem.justworks.core.gen.SERIAL_NAME
import com.avsystem.justworks.core.gen.USE_SERIALIZERS
import com.avsystem.justworks.core.gen.UUID_SERIALIZER
import com.avsystem.justworks.core.gen.UUID_TYPE
import com.avsystem.justworks.core.gen.invoke
import com.avsystem.justworks.core.gen.resolveInlineTypes
import com.avsystem.justworks.core.gen.resolveTypeRef
import com.avsystem.justworks.core.gen.shared.SerializersModuleGenerator
import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toEnumConstantName
import com.avsystem.justworks.core.gen.toInlinedName
import com.avsystem.justworks.core.gen.toTypeName
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
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Generates KotlinPoet [com.squareup.kotlinpoet.FileSpec] instances from an [com.avsystem.justworks.core.model.ApiSpec].
 *
 * Produces one file per [com.avsystem.justworks.core.model.SchemaModel] (data class, sealed class hierarchy, or allOf composed class)
 * and one file per [com.avsystem.justworks.core.model.EnumModel] (enum class), all annotated with kotlinx.serialization annotations.
 */
internal object ModelGenerator {
    data class GenerateResult(val files: List<FileSpec>, val resolvedSpec: ApiSpec)

    context(_: ModelPackage)
    fun generate(spec: ApiSpec, nameRegistry: NameRegistry): List<FileSpec> =
        generateWithResolvedSpec(spec, nameRegistry).files

    context(modelPackage: ModelPackage)
    fun generateWithResolvedSpec(spec: ApiSpec, nameRegistry: NameRegistry): GenerateResult {
        ensureReserved(spec, nameRegistry)
        val (inlineSchemas, nameMap) = collectAllInlineSchemas(spec, nameRegistry)
        val resolvedSpec = spec.resolveInlineTypes(nameMap)

        val resolvedInlineSchemas = inlineSchemas.map { schema ->
            schema.copy(
                properties = schema.properties.map { prop ->
                    prop.copy(type = resolvedSpec.resolveTypeRef(prop.type, nameMap))
                },
            )
        }

        val hierarchy = buildHierarchyInfo(resolvedSpec.schemas)
        val classNameLookup = context(hierarchy, modelPackage) { buildClassNameLookup() }

        val files = context(hierarchy) {
            val variantNames = hierarchy.sealedHierarchies.values
                .flatten()
                .toSet()
            val schemaFiles = resolvedSpec.schemas
                .filter { it.name !in variantNames || it.name in hierarchy.anyOfWithoutDiscriminatorVariants }
                .flatMap { generateSchemaFiles(it, classNameLookup) }

            val inlineSchemaFiles = resolvedInlineSchemas.map {
                if (it.isNested) generateNestedInlineClass(it, classNameLookup) else generateDataClass(it, classNameLookup)
            }

            val enumFiles = resolvedSpec.enums.map { generateEnumClass(it) }

            val serializersModuleFile = SerializersModuleGenerator.generate()

            val uuidSerializerFile = if (resolvedSpec.usesUuid()) generateUuidSerializer() else null

            schemaFiles + inlineSchemaFiles + enumFiles + listOfNotNull(serializersModuleFile, uuidSerializerFile)
        }

        return GenerateResult(files, resolvedSpec)
    }

    data class HierarchyInfo(
        val sealedHierarchies: Map<String, List<String>>,
        val variantParents: Map<String, Map<ClassName, String>>,
        val anyOfWithoutDiscriminator: Set<String>,
        val schemas: List<SchemaModel>,
    ) {
        /** Variant names that belong to anyOf-without-discriminator hierarchies (still use interface pattern). */
        val anyOfWithoutDiscriminatorVariants: Set<String> by lazy {
            sealedHierarchies
                .filterKeys { it in anyOfWithoutDiscriminator }
                .values
                .flatten()
                .toSet()
        }
    }

    /**
     * Builds a classNameLookup map from an ApiSpec for use by other generators (e.g. ClientGenerator).
     * Maps variant schema names to their nested ClassName (e.g. "Circle" -> Shape.Circle).
     */
    fun buildClassNameLookup(spec: ApiSpec, modelPackage: String): Map<String, ClassName> {
        val mp = ModelPackage(modelPackage)
        val hierarchy = context(mp) { buildHierarchyInfo(spec.schemas) }
        return context(hierarchy, mp) { buildClassNameLookup() }
    }

    context(modelPackage: ModelPackage)
    private fun buildHierarchyInfo(schemas: List<SchemaModel>): HierarchyInfo {
        fun SchemaModel.variants() = oneOf ?: anyOf ?: emptyList()

        val polymorphicSchemas = schemas.filter { it.variants().isNotEmpty() }

        val sealedHierarchies = polymorphicSchemas.associate { schema ->
            schema.name to schema
                .variants()
                .asSequence()
                .filterIsInstance<TypeRef.Reference>()
                .map { it.schemaName }
                .toList()
        }

        val variantParents = polymorphicSchemas
            .asSequence()
            .flatMap { schema ->
                val parentClass = ClassName(modelPackage, schema.name)
                schema.variants().filterIsInstance<TypeRef.Reference>().map { ref ->
                    ref.schemaName to (parentClass to resolveSerialName(schema, ref.schemaName))
                }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.toMap() }

        val anyOfWithoutDiscriminator = polymorphicSchemas
            .asSequence()
            .filter { !it.anyOf.isNullOrEmpty() && it.discriminator == null }
            .map { it.name }
            .toSet()

        return HierarchyInfo(sealedHierarchies, variantParents, anyOfWithoutDiscriminator, schemas)
    }

    context(hierarchy: HierarchyInfo, modelPackage: ModelPackage)
    private fun buildClassNameLookup(): Map<String, ClassName> {
        val lookup = mutableMapOf<String, ClassName>()

        for ((parent, variants) in hierarchy.sealedHierarchies) {
            // Skip anyOf-without-discriminator — those still use flat names
            if (parent in hierarchy.anyOfWithoutDiscriminator) continue

            val parentClass = ClassName(modelPackage, parent)
            lookup[parent] = parentClass
            for (variant in variants) {
                lookup[variant] = parentClass.nestedClass(variant)
            }
        }

        return lookup
    }

    /**
     * Ensures all top-level schema/enum names are reserved in [nameRegistry],
     * preventing inline schemas from colliding with component types even if
     * the caller supplied an empty registry.
     */
    private fun ensureReserved(spec: ApiSpec, nameRegistry: NameRegistry) {
        spec.schemas.forEach { nameRegistry.reserve(it.name) }
        spec.enums.forEach { nameRegistry.reserve(it.name) }
        nameRegistry.reserve(UUID_SERIALIZER.simpleName)
        nameRegistry.reserve(SERIALIZERS_MODULE.simpleName)
    }

    private fun collectAllInlineSchemas(
        spec: ApiSpec,
        nameRegistry: NameRegistry,
    ): Pair<List<SchemaModel>, Map<InlineSchemaKey, String>> {
        val endpointRefs = spec.endpoints.flatMap { endpoint ->
            val requestRef = endpoint.requestBody?.schema
            val responseRefs = endpoint.responses.values.map { it.schema }
            responseRefs + requestRef
        }

        val schemaPropertyRefs = spec.schemas.flatMap { schema -> schema.properties.map { it.type } }

        val nameMap = mutableMapOf<InlineSchemaKey, String>()

        val schemas = collectInlineTypeRefs(endpointRefs + schemaPropertyRefs)
            .asSequence()
            .sortedBy { it.contextHint }
            .distinctBy { InlineSchemaKey.from(it.properties, it.requiredProperties) }
            .map { ref ->
                val key = InlineSchemaKey.from(ref.properties, ref.requiredProperties)
                val generatedName = nameRegistry.register(ref.contextHint.toInlinedName())
                nameMap[key] = generatedName
                SchemaModel(
                    name = generatedName,
                    description = null,
                    properties = ref.properties,
                    requiredProperties = ref.requiredProperties,
                    allOf = null,
                    oneOf = null,
                    anyOf = null,
                    discriminator = null,
                )
            }.toList()

        return schemas to nameMap
    }

    context(hierarchy: HierarchyInfo, _: ModelPackage)
    private fun generateSchemaFiles(schema: SchemaModel, classNameLookup: Map<String, ClassName>): List<FileSpec> =
        when {
            !schema.anyOf.isNullOrEmpty() || !schema.oneOf.isNullOrEmpty() -> {
                if (schema.name in hierarchy.anyOfWithoutDiscriminator) {
                    listOf(
                        generateSealedInterface(schema),
                        generatePolymorphicSerializer(schema, classNameLookup),
                    )
                } else {
                    listOf(generateSealedHierarchy(schema, classNameLookup))
                }
            }

            schema.isPrimitiveOnly -> {
                val targetType = schema.underlyingType?.toTypeName(classNameLookup) ?: STRING
                listOf(generateTypeAlias(schema, targetType))
            }

            else -> {
                listOf(generateDataClass(schema, classNameLookup))
            }
        }

    /**
     * Generates a sealed class with nested data class subtypes for oneOf or anyOf-with-discriminator schemas.
     */
    context(hierarchy: HierarchyInfo, modelPackage: ModelPackage)
    private fun generateSealedHierarchy(schema: SchemaModel, classNameLookup: Map<String, ClassName>): FileSpec {
        val className = ClassName(modelPackage, schema.name)
        val schemasById = hierarchy.schemas.associateBy { it.name }

        val parentBuilder = TypeSpec.classBuilder(className).addModifiers(KModifier.SEALED)
        parentBuilder.addAnnotation(SERIALIZABLE)

        if (schema.discriminator != null) {
            parentBuilder.addAnnotation(
                AnnotationSpec
                    .builder(JSON_CLASS_DISCRIMINATOR)
                    .addMember("%S", schema.discriminator.propertyName)
                    .build(),
            )
        }

        if (schema.description != null) {
            parentBuilder.addKdoc("%L", schema.description)
        }

        // Generate nested subtypes
        val variants = hierarchy.sealedHierarchies[schema.name].orEmpty()
        for (variantName in variants) {
            val variantSchema = schemasById[variantName]
            val serialName = resolveSerialName(schema, variantName)
            val nestedType = buildNestedVariant(variantSchema, variantName, className, serialName, classNameLookup)
            parentBuilder.addType(nestedType)
        }

        val fileBuilder = FileSpec.builder(className).addType(parentBuilder.build())

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
     * Builds a nested data class TypeSpec for a variant inside a sealed class hierarchy.
     */
    context(modelPackage: ModelPackage)
    private fun buildNestedVariant(
        variantSchema: SchemaModel?,
        variantName: String,
        parentClassName: ClassName,
        serialName: String,
        classNameLookup: Map<String, ClassName>,
    ): TypeSpec {
        val variantClassName = parentClassName.nestedClass(variantName)
        val builder = TypeSpec.classBuilder(variantClassName).addModifiers(KModifier.DATA)
        builder.superclass(parentClassName)
        builder.addSuperclassConstructorParameter("")
        builder.addAnnotation(SERIALIZABLE)
        builder.addAnnotation(AnnotationSpec.builder(SERIAL_NAME).addMember("%S", serialName).build())

        if (variantSchema != null) {
            val sortedProps = variantSchema.properties.sortedBy { prop ->
                when {
                    prop.name in variantSchema.requiredProperties && prop.defaultValue == null -> 1
                    prop.defaultValue != null -> 2
                    else -> 3
                }
            }

            val constructorBuilder = FunSpec.constructorBuilder()
            val propertySpecs = sortedProps.map { prop ->
                val type = prop.type.toTypeName(classNameLookup).copy(nullable = prop.nullable)
                val kotlinName = prop.name.toCamelCase()

                val paramBuilder = ParameterSpec.builder(kotlinName, type)
                when {
                    prop.nullable -> paramBuilder.defaultValue(CodeBlock.of("null"))
                    prop.defaultValue != null -> paramBuilder.defaultValue(formatDefaultValue(prop))
                }
                constructorBuilder.addParameter(paramBuilder.build())

                PropertySpec
                    .builder(kotlinName, type)
                    .initializer(kotlinName)
                    .addAnnotation(AnnotationSpec.builder(SERIAL_NAME).addMember("%S", prop.name).build())
                    .build()
            }
            builder.primaryConstructor(constructorBuilder.build())
            builder.addProperties(propertySpecs)

            if (variantSchema.description != null) {
                builder.addKdoc("%L", variantSchema.description)
            }
        } else {
            // Empty variant with no properties — still need a constructor for data class
            builder.primaryConstructor(FunSpec.constructorBuilder().build())
        }

        return builder.build()
    }

    /**
     * Generates a sealed interface for anyOf without discriminator schemas.
     * Only used for the JsonContentPolymorphicSerializer pattern.
     */
    context(hierarchy: HierarchyInfo, modelPackage: ModelPackage)
    private fun generateSealedInterface(schema: SchemaModel): FileSpec {
        val className = ClassName(modelPackage, schema.name)

        val typeSpec = TypeSpec.interfaceBuilder(className).addModifiers(KModifier.SEALED)

        val serializerClassName = ClassName(modelPackage, "${schema.name}Serializer")
        typeSpec.addAnnotation(
            AnnotationSpec
                .builder(SERIALIZABLE)
                .addMember("with = %T::class", serializerClassName)
                .build(),
        )

        if (schema.description != null) {
            typeSpec.addKdoc("%L", schema.description)
        }

        return FileSpec.builder(className).addType(typeSpec.build()).build()
    }

    /**
     * Generates a JsonContentPolymorphicSerializer object for an anyOf schema without discriminator.
     */
    context(hierarchy: HierarchyInfo, modelPackage: ModelPackage)
    private fun generatePolymorphicSerializer(schema: SchemaModel, classNameLookup: Map<String, ClassName>): FileSpec {
        val sealedClassName = ClassName(modelPackage, schema.name)
        val serializerClassName = ClassName(modelPackage, "${schema.name}Serializer")

        val schemasById = hierarchy.schemas.associateBy { it.name }

        val variantProperties = schema.anyOf
            .orEmpty()
            .asSequence()
            .filterIsInstance<TypeRef.Reference>()
            .associate { ref ->
                val propNames = schemasById[ref.schemaName]?.properties?.map { it.name }?.toSet() ?: emptySet()
                ref.schemaName to propNames
            }

        val allFields = variantProperties.values
            .asSequence()
            .flatten()
            .groupingBy { it }
            .eachCount()

        val uniqueFieldsPerVariant = variantProperties
            .mapValues { (_, fields) ->
                fields.firstOrNull { allFields[it] == 1 }
            }

        val selectDeserializerBody = buildSelectDeserializerBody(schema.name, uniqueFieldsPerVariant, classNameLookup)

        val deserializationStrategy = ClassName("kotlinx.serialization", "DeserializationStrategy")
            .parameterizedBy(WildcardTypeName.producerOf(sealedClassName))

        val selectFun = FunSpec
            .builder("selectDeserializer")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter(ParameterSpec.builder("element", JSON_ELEMENT).build())
            .returns(deserializationStrategy)
            .addCode(selectDeserializerBody)
            .build()

        val objectSpec = TypeSpec
            .objectBuilder(serializerClassName)
            .superclass(JSON_CONTENT_POLYMORPHIC_SERIALIZER.parameterizedBy(sealedClassName))
            .addSuperclassConstructorParameter("%T::class", sealedClassName)
            .addFunction(selectFun)
            .build()

        return FileSpec
            .builder(serializerClassName)
            .addType(objectSpec)
            .build()
    }

    /**
     * Builds the body code for selectDeserializer using field-presence heuristics.
     */
    context(modelPackage: ModelPackage)
    private fun buildSelectDeserializerBody(
        parentName: String,
        uniqueFieldsPerVariant: Map<String, String?>,
        classNameLookup: Map<String, ClassName>,
    ): CodeBlock {
        val builder = CodeBlock.builder()
        builder.beginControlFlow("return when")

        val notUnique = uniqueFieldsPerVariant.mapNotNull { (variantName, uniqueField) ->
            if (uniqueField != null) {
                val variantClass = classNameLookup[variantName] ?: ClassName(modelPackage, variantName)
                builder.addStatement(
                    "%S\u00b7in\u00b7element.%M -> %T.serializer()",
                    uniqueField,
                    JSON_OBJECT_EXT,
                    variantClass,
                )
                null
            } else {
                builder.addStatement("// No unique discriminating fields found for variant '$variantName'")
                variantName
            }
        }

        if (notUnique.isNotEmpty()) {
            builder.addStatement(
                "else -> TODO(%S)",
                "Cannot discriminate variants [${notUnique.joinToString()}] of anyOf '$parentName' - manual selectDeserializer required",
            )
        } else {
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
     * Generates a data class FileSpec, with superinterfaces and @SerialName resolved from hierarchy.
     * Used for: standalone schemas, allOf composed classes, and anyOf-without-discriminator variants.
     */
    context(hierarchy: HierarchyInfo, modelPackage: ModelPackage)
    private fun generateDataClass(schema: SchemaModel, classNameLookup: Map<String, ClassName>): FileSpec {
        val className = ClassName(modelPackage, schema.name)

        // Only apply superinterfaces for anyOf-without-discriminator variants
        val parentEntries = hierarchy.variantParents[schema.name]
            .orEmpty()
            .filterKeys { parentClass ->
                val parentName = parentClass.simpleName
                parentName in hierarchy.anyOfWithoutDiscriminator
            }
        val serialName = parentEntries.values.firstOrNull()
        val superinterfaces = parentEntries.keys

        val sortedProps = schema.properties.sortedBy { prop ->
            when {
                prop.name in schema.requiredProperties && prop.defaultValue == null -> 1
                prop.defaultValue != null -> 2
                else -> 3
            }
        }

        val constructorBuilder = FunSpec.constructorBuilder()
        val propertySpecs = sortedProps.map { prop ->
            val type = prop.type.toTypeName(classNameLookup).copy(nullable = prop.nullable)
            val kotlinName = prop.name.toCamelCase()

            val paramBuilder = ParameterSpec.builder(kotlinName, type)

            when {
                prop.nullable -> paramBuilder.defaultValue(CodeBlock.of("null"))
                prop.defaultValue != null -> paramBuilder.defaultValue(formatDefaultValue(prop))
            }

            constructorBuilder.addParameter(paramBuilder.build())

            val propBuilder = PropertySpec
                .builder(kotlinName, type)
                .initializer(kotlinName)
                .addAnnotation(
                    AnnotationSpec
                        .builder(SERIAL_NAME)
                        .addMember("%S", prop.name)
                        .build(),
                )

            propBuilder.build()
        }

        val typeSpec = TypeSpec
            .classBuilder(className)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(constructorBuilder.build())
            .addProperties(propertySpecs)
            .addAnnotation(SERIALIZABLE)
            .addSuperinterfaces(superinterfaces)

        if (serialName != null) {
            typeSpec.addAnnotation(
                AnnotationSpec
                    .builder(SERIAL_NAME)
                    .addMember("%S", serialName)
                    .build(),
            )
        }

        if (schema.description != null) {
            typeSpec.addKdoc("%L", schema.description)
        }

        val fileBuilder = FileSpec.builder(className).addType(typeSpec.build())

        val hasUuid = schema.properties.any { it.type.containsUuid() }
        if (hasUuid) {
            fileBuilder.addAnnotation(
                AnnotationSpec
                    .builder(OPT_IN)
                    .addMember("%T::class", EXPERIMENTAL_UUID_API)
                    .build(),
            )
            fileBuilder.addAnnotation(
                AnnotationSpec
                    .builder(USE_SERIALIZERS)
                    .addMember("%T::class", UUID_SERIALIZER)
                    .build(),
            )
        }

        return fileBuilder.build()
    }

    /**
     * Formats a default value from a PropertyModel for use in KotlinPoet ParameterSpec.defaultValue().
     */

    context(modelPackage: ModelPackage)
    private fun formatDefaultValue(prop: PropertyModel): CodeBlock = when (prop.type) {
        is TypeRef.Primitive -> {
            when (prop.type.type) {
                PrimitiveType.STRING -> CodeBlock.of("%S", prop.defaultValue)

                PrimitiveType.INT,
                PrimitiveType.LONG,
                PrimitiveType.DOUBLE,
                PrimitiveType.FLOAT,
                PrimitiveType.BOOLEAN,
                -> CodeBlock.of("%L", prop.defaultValue)

                PrimitiveType.DATE_TIME -> catch(
                    { Instant.parse(prop.defaultValue as String) },
                    { CodeBlock.of("%T.parse(%S)", INSTANT, prop.defaultValue) },
                    { e ->
                        throw IllegalArgumentException(
                            "Invalid ISO-8601 date-time default '${prop.defaultValue}' for property ${prop.name}: ${e.message}",
                        )
                    },
                )

                PrimitiveType.DATE -> catch(
                    { LocalDate.parse(prop.defaultValue as String) },
                    { CodeBlock.of("%T.parse(%S)", LOCAL_DATE, prop.defaultValue) },
                    { e ->
                        throw IllegalArgumentException(
                            "Invalid ISO-8601 date default '${prop.defaultValue}' for property ${prop.name}: ${e.message}",
                        )
                    },
                )

                else -> throw IllegalArgumentException("Unsupported default value type: ${prop.type}")
            }
        }

        is TypeRef.Reference -> {
            val constantName = prop.defaultValue.toString().toEnumConstantName()
            CodeBlock.of("%T.%L", ClassName(modelPackage, prop.type.schemaName), constantName)
        }

        else -> {
            throw IllegalArgumentException("Unsupported default value type: ${prop.type}")
        }
    }

    /**
     * Resolves the @SerialName value for a variant within a oneOf schema.
     */
    private fun resolveSerialName(parentSchema: SchemaModel, variantSchemaName: String): String =
        parentSchema.discriminator
            ?.mapping
            .orEmpty()
            .firstNotNullOfOrNull { (serialName, refPath) ->
                serialName.takeIf { refPath.removePrefix("#/components/schemas/") == variantSchemaName }
            }
            ?: variantSchemaName

    context(modelPackage: ModelPackage)
    private fun generateEnumClass(enum: EnumModel): FileSpec {
        val className = ClassName(modelPackage, enum.name)

        val typeSpec = TypeSpec.enumBuilder(className).addAnnotation(SERIALIZABLE)

        val enumRegistry = NameRegistry()
        enum.values.forEach { value ->
            val anonymousClass = TypeSpec
                .anonymousClassBuilder()
                .addAnnotation(
                    AnnotationSpec
                        .builder(SERIAL_NAME)
                        .addMember("%S", value)
                        .build(),
                ).build()
            typeSpec.addEnumConstant(enumRegistry.register(value.toEnumConstantName()), anonymousClass)
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
     * Iteratively collects all [TypeRef.Inline] instances from a [TypeRef] tree.
     */
    private fun collectInlineTypeRefs(initialTodo: List<TypeRef?>): List<TypeRef.Inline> {
        val todo = ArrayDeque(initialTodo.filterNotNull())
        val visited = linkedSetOf<TypeRef.Inline>()

        while (todo.isNotEmpty()) {
            when (val current = todo.removeFirst()) {
                is TypeRef.Inline if visited.add(current) -> {
                    todo.addAll(current.properties.map { it.type })
                }

                is TypeRef.Array -> {
                    todo.addFirst(current.items)
                }

                is TypeRef.Map -> {
                    todo.addFirst(current.valueType)
                }

                else -> {}
            }
        }
        return visited.toList()
    }

    context(_: HierarchyInfo, _: ModelPackage)
    private fun generateNestedInlineClass(schema: SchemaModel, classNameLookup: Map<String, ClassName>): FileSpec =
        generateDataClass(schema.copy(name = schema.name.toInlinedName()), classNameLookup)


    private val SchemaModel.isPrimitiveOnly: Boolean
        get() = properties.isEmpty() && allOf == null && oneOf == null && anyOf == null

    private fun TypeRef.containsUuid(): Boolean = when (this) {
        is TypeRef.Primitive -> type == PrimitiveType.UUID
        is TypeRef.Array -> items.containsUuid()
        is TypeRef.Map -> valueType.containsUuid()
        is TypeRef.Inline -> properties.any { it.type.containsUuid() }
        is TypeRef.Reference, TypeRef.Unknown -> false
    }

    private fun ApiSpec.usesUuid(): Boolean {
        val schemaRefs = schemas.asSequence().flatMap { schema -> schema.properties.map { it.type } }
        val endpointRefs = endpoints.asSequence().flatMap { endpoint ->
            val responseRefs = endpoint.responses.values
                .asSequence()
                .mapNotNull { it.schema }
            val requestRef = endpoint.requestBody?.schema
            val parameterRefs = endpoint.parameters
                .asSequence()
                .map { it.schema }
            responseRefs + listOfNotNull(requestRef) + parameterRefs
        }
        return schemaRefs.plus(endpointRefs).any { it.containsUuid() }
    }

    private fun generateUuidSerializer(): FileSpec {
        val descriptorProp = PropertySpec
            .builder("descriptor", SERIAL_DESCRIPTOR)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%M(%S, %T.STRING)", PRIMITIVE_SERIAL_DESCRIPTOR_FUN, "Uuid", PRIMITIVE_KIND)
            .build()

        val serializeFun = FunSpec
            .builder("serialize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("encoder", ENCODER)
            .addParameter("value", UUID_TYPE)
            .addStatement("encoder.encodeString(value.toString())")
            .build()

        val deserializeFun = FunSpec
            .builder("deserialize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("decoder", DECODER)
            .returns(UUID_TYPE)
            .addStatement("return %T.parse(decoder.decodeString())", UUID_TYPE)
            .build()

        val objectSpec = TypeSpec
            .objectBuilder(UUID_SERIALIZER)
            .addSuperinterface(K_SERIALIZER.parameterizedBy(UUID_TYPE))
            .addProperty(descriptorProp)
            .addFunction(serializeFun)
            .addFunction(deserializeFun)
            .build()

        return FileSpec
            .builder(UUID_SERIALIZER)
            .addAnnotation(
                AnnotationSpec
                    .builder(OPT_IN)
                    .addMember("%T::class", EXPERIMENTAL_UUID_API)
                    .build(),
            ).addType(objectSpec)
            .build()
    }

    context(modelPackage: ModelPackage)
    private fun generateTypeAlias(schema: SchemaModel, primitiveType: TypeName): FileSpec {
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
