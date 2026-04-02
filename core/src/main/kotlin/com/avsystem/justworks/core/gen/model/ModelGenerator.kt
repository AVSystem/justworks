package com.avsystem.justworks.core.gen.model

import arrow.core.raise.catch
import com.avsystem.justworks.core.gen.DECODER
import com.avsystem.justworks.core.gen.ENCODER
import com.avsystem.justworks.core.gen.EXPERIMENTAL_SERIALIZATION_API
import com.avsystem.justworks.core.gen.EXPERIMENTAL_UUID_API
import com.avsystem.justworks.core.gen.Hierarchy
import com.avsystem.justworks.core.gen.INSTANT
import com.avsystem.justworks.core.gen.InlineSchemaKey
import com.avsystem.justworks.core.gen.JSON_CLASS_DISCRIMINATOR
import com.avsystem.justworks.core.gen.JSON_CONTENT_POLYMORPHIC_SERIALIZER
import com.avsystem.justworks.core.gen.JSON_ELEMENT
import com.avsystem.justworks.core.gen.JSON_OBJECT_EXT
import com.avsystem.justworks.core.gen.K_SERIALIZER
import com.avsystem.justworks.core.gen.LOCAL_DATE
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
import com.avsystem.justworks.core.gen.resolveSerialName
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
 * Generates KotlinPoet [FileSpec] instances from an [ApiSpec].
 *
 * Produces one file per [SchemaModel] (data class, sealed class hierarchy, or allOf composed class)
 * and one file per [EnumModel] (enum class), all annotated with kotlinx.serialization annotations.
 */
internal object ModelGenerator {
    data class GenerateResult(val files: List<FileSpec>, val resolvedSpec: ApiSpec)

    context(_: Hierarchy, _: NameRegistry)
    fun generate(spec: ApiSpec): List<FileSpec> = generateWithResolvedSpec(spec).files

    context(hierarchy: Hierarchy, nameRegistry: NameRegistry)
    fun generateWithResolvedSpec(spec: ApiSpec): GenerateResult {
        ensureReserved(spec, nameRegistry)
        val (inlineSchemas, nameMap) = collectAllInlineSchemas(spec)
        val resolvedSpec = spec.resolveInlineTypes(nameMap)

        val resolvedInlineSchemas = inlineSchemas.map { schema ->
            schema.copy(
                properties = schema.properties.map { prop ->
                    prop.copy(type = resolvedSpec.resolveTypeRef(prop.type, nameMap))
                },
            )
        }

        hierarchy.addSchemas(resolvedSpec.schemas + resolvedInlineSchemas)

        val nestedVariantNames = hierarchy.sealedHierarchies
            .asSequence()
            .filterNot { (key, _) -> key in hierarchy.anyOfWithoutDiscriminator }
            .flatMap { (_, names) -> names }
            .toSet()

        val schemaFiles = resolvedSpec.schemas
            .asSequence()
            .filterNot { it.name in nestedVariantNames }
            .flatMap { generateSchemaFiles(it) }
            .toList()

        val inlineSchemaFiles = resolvedInlineSchemas.map {
            if (it.isNested) {
                generateNestedInlineClass(it)
            } else {
                generateDataClass(it)
            }
        }

        val enumFiles = resolvedSpec.enums.map { generateEnumClass(it) }

        val serializersModuleFile = SerializersModuleGenerator.generate()

        val uuidSerializerFile = if (resolvedSpec.usesUuid()) generateUuidSerializer() else null

        val files =
            schemaFiles + inlineSchemaFiles + enumFiles + listOfNotNull(serializersModuleFile, uuidSerializerFile)

        return GenerateResult(files, resolvedSpec)
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

    context(nameRegistry: NameRegistry)
    private fun collectAllInlineSchemas(spec: ApiSpec): Pair<List<SchemaModel>, Map<InlineSchemaKey, String>> {
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

    context(hierarchy: Hierarchy)
    private fun generateSchemaFiles(schema: SchemaModel): List<FileSpec> = when {
        !schema.anyOf.isNullOrEmpty() || !schema.oneOf.isNullOrEmpty() -> {
            if (schema.name in hierarchy.anyOfWithoutDiscriminator) {
                listOf(
                    generateSealedInterface(schema),
                    generatePolymorphicSerializer(schema),
                )
            } else {
                listOf(generateSealedHierarchy(schema))
            }
        }

        schema.isPrimitiveOnly -> {
            val targetType = schema.underlyingType?.toTypeName() ?: STRING
            listOf(generateTypeAlias(schema, targetType))
        }

        else -> {
            listOf(generateDataClass(schema))
        }
    }

    /**
     * Generates a sealed class with nested data class subtypes for oneOf or anyOf-with-discriminator schemas.
     */
    context(hierarchy: Hierarchy)
    private fun generateSealedHierarchy(schema: SchemaModel): FileSpec {
        val className = ClassName(hierarchy.modelPackage, schema.name)

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
        val variants = hierarchy.sealedHierarchies[schema.name]
        variants?.forEach { variantName ->
            val variantSchema = hierarchy.schemasById[variantName]
            val serialName = schema.resolveSerialName(variantName)
            val nestedType = buildNestedVariant(variantSchema, variantName, className, serialName)
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
    context(hierarchy: Hierarchy)
    private fun buildNestedVariant(
        variantSchema: SchemaModel?,
        variantName: String,
        parentClassName: ClassName,
        serialName: String,
    ): TypeSpec {
        val variantClassName = parentClassName.nestedClass(variantName)
        val builder = TypeSpec.classBuilder(variantClassName).addModifiers(KModifier.DATA)
        builder.superclass(parentClassName)
        // sealed class has no constructor params, but KotlinPoet requires this call to emit `Shape()`
        builder.addSuperclassConstructorParameter("")
        builder.addAnnotation(SERIALIZABLE)
        builder.addAnnotation(AnnotationSpec.builder(SERIAL_NAME).addMember("%S", serialName).build())

        if (variantSchema != null) {
            buildConstructorAndProperties(variantSchema, builder)
        } else {
            builder.primaryConstructor(FunSpec.constructorBuilder().build())
        }

        return builder.build()
    }

    /**
     * Builds primary constructor and data class properties from a schema's property list.
     * Shared by [generateDataClass] and [buildNestedVariant].
     */
    context(_: Hierarchy)
    private fun buildConstructorAndProperties(schema: SchemaModel, builder: TypeSpec.Builder) {
        val sortedProps = schema.properties.sortedBy { prop ->
            when {
                prop.name in schema.requiredProperties && prop.defaultValue == null -> 1
                prop.defaultValue != null -> 2
                else -> 3
            }
        }

        val constructorBuilder = FunSpec.constructorBuilder()
        val propertySpecs = sortedProps.map { prop ->
            val type = prop.type.toTypeName().copy(nullable = prop.nullable)
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

        if (schema.description != null) {
            builder.addKdoc("%L", schema.description)
        }
    }

    /**
     * Generates a sealed interface for anyOf without discriminator schemas.
     * Only used for the JsonContentPolymorphicSerializer pattern.
     */
    context(hierarchy: Hierarchy)
    private fun generateSealedInterface(schema: SchemaModel): FileSpec {
        val className = ClassName(hierarchy.modelPackage, schema.name)

        val typeSpec = TypeSpec.interfaceBuilder(className).addModifiers(KModifier.SEALED)

        val serializerClassName = ClassName(hierarchy.modelPackage, "${schema.name}Serializer")
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
    context(hierarchy: Hierarchy)
    private fun generatePolymorphicSerializer(schema: SchemaModel): FileSpec {
        val sealedClassName = ClassName(hierarchy.modelPackage, schema.name)
        val serializerClassName = ClassName(hierarchy.modelPackage, "${schema.name}Serializer")

        val variantProperties = schema.anyOf
            .orEmpty()
            .asSequence()
            .filterIsInstance<TypeRef.Reference>()
            .associate { ref ->
                val props = hierarchy.schemasById[ref.schemaName]
                    ?.properties
                    ?.map { it.name }
                    ?.toSet()
                    .orEmpty()
                ref.schemaName to props
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

        val selectDeserializerBody = buildSelectDeserializerBody(schema.name, uniqueFieldsPerVariant)

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
    context(hierarchy: Hierarchy)
    private fun buildSelectDeserializerBody(
        parentName: String,
        uniqueFieldsPerVariant: Map<String, String?>,
    ): CodeBlock {
        val builder = CodeBlock.builder()
        builder.beginControlFlow("return when")

        val notUnique = uniqueFieldsPerVariant.mapNotNull { (variantName, uniqueField) ->
            if (uniqueField != null) {
                builder.addStatement(
                    "%S·in·element.%M -> %T.serializer()",
                    uniqueField,
                    JSON_OBJECT_EXT,
                    hierarchy[variantName],
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
    context(hierarchy: Hierarchy)
    private fun generateDataClass(schema: SchemaModel): FileSpec {
        val className = ClassName(hierarchy.modelPackage, schema.name)

        // For anyOf-without-discriminator variants: find parent interfaces and serialName
        val parentNames = hierarchy.sealedHierarchies
            .filter { (parent, variants) ->
                parent in hierarchy.anyOfWithoutDiscriminator && schema.name in variants
            }.keys
        val superinterfaces = parentNames.map { ClassName(hierarchy.modelPackage, it) }
        val serialName = parentNames.firstOrNull()?.let { parentName ->
            hierarchy.schemasById[parentName]?.resolveSerialName(schema.name)
        }

        val typeSpec = TypeSpec
            .classBuilder(className)
            .addModifiers(KModifier.DATA)
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

        buildConstructorAndProperties(schema, typeSpec)

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

    context(hierarchy: Hierarchy)
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
            CodeBlock.of("%T.%L", ClassName(hierarchy.modelPackage, prop.type.schemaName), constantName)
        }

        else -> {
            throw IllegalArgumentException("Unsupported default value type: ${prop.type}")
        }
    }

    context(hierarchy: Hierarchy)
    private fun generateEnumClass(enum: EnumModel): FileSpec {
        val className = ClassName(hierarchy.modelPackage, enum.name)

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

    context(hierarchy: Hierarchy)
    private fun generateNestedInlineClass(schema: SchemaModel): FileSpec {
        val flatName = schema.name.toInlinedName()
        val className = ClassName(hierarchy.modelPackage, flatName)

        val typeSpec = TypeSpec
            .classBuilder(className)
            .addModifiers(KModifier.DATA)
            .addAnnotation(SERIALIZABLE)

        buildConstructorAndProperties(schema, typeSpec)

        return FileSpec.builder(className).addType(typeSpec.build()).build()
    }

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

    context(hierarchy: Hierarchy)
    private fun generateTypeAlias(schema: SchemaModel, primitiveType: TypeName): FileSpec {
        val className = ClassName(hierarchy.modelPackage, schema.name)

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
