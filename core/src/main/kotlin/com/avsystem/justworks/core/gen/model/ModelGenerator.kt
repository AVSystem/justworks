package com.avsystem.justworks.core.gen.model

import arrow.core.raise.catch
import com.avsystem.justworks.core.gen.DECODER
import com.avsystem.justworks.core.gen.ENCODER
import com.avsystem.justworks.core.gen.EXPERIMENTAL_SERIALIZATION_API
import com.avsystem.justworks.core.gen.EXPERIMENTAL_UUID_API
import com.avsystem.justworks.core.gen.INSTANT
import com.avsystem.justworks.core.gen.InlineSchemaDeduplicator
import com.avsystem.justworks.core.gen.InlineSchemaKey
import com.avsystem.justworks.core.gen.JSON_CLASS_DISCRIMINATOR
import com.avsystem.justworks.core.gen.JSON_CONTENT_POLYMORPHIC_SERIALIZER
import com.avsystem.justworks.core.gen.JSON_ELEMENT
import com.avsystem.justworks.core.gen.JSON_OBJECT_EXT
import com.avsystem.justworks.core.gen.K_SERIALIZER
import com.avsystem.justworks.core.gen.LOCAL_DATE
import com.avsystem.justworks.core.gen.ModelPackage
import com.avsystem.justworks.core.gen.OPT_IN
import com.avsystem.justworks.core.gen.PRIMITIVE_KIND
import com.avsystem.justworks.core.gen.PRIMITIVE_SERIAL_DESCRIPTOR_FUN
import com.avsystem.justworks.core.gen.SERIALIZABLE
import com.avsystem.justworks.core.gen.SERIALIZATION_EXCEPTION
import com.avsystem.justworks.core.gen.SERIAL_DESCRIPTOR
import com.avsystem.justworks.core.gen.SERIAL_NAME
import com.avsystem.justworks.core.gen.TypeMapping
import com.avsystem.justworks.core.gen.USE_SERIALIZERS
import com.avsystem.justworks.core.gen.UUID_TYPE
import com.avsystem.justworks.core.gen.invoke
import com.avsystem.justworks.core.gen.shared.SerializersModuleGenerator
import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toEnumConstantName
import com.avsystem.justworks.core.gen.toInlinedName
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
import kotlin.sequences.flatMap
import kotlin.time.Instant

/**
 * Generates KotlinPoet [com.squareup.kotlinpoet.FileSpec] instances from an [com.avsystem.justworks.core.model.ApiSpec].
 *
 * Produces one file per [com.avsystem.justworks.core.model.SchemaModel] (data class, sealed interface, or allOf composed class)
 * and one file per [com.avsystem.justworks.core.model.EnumModel] (enum class), all annotated with kotlinx.serialization annotations.
 */
internal object ModelGenerator {
    context(_: ModelPackage)
    fun generate(spec: ApiSpec): List<FileSpec> = context(
        buildHierarchyInfo(spec.schemas),
        InlineSchemaDeduplicator(spec.schemas.map { it.name }.toSet()),
    ) {
        val schemaFiles = spec.schemas.flatMap { generateSchemaFiles(it) }

        val inlineSchemaFiles = collectAllInlineSchemas(spec).map {
            if (it.isNested) generateNestedInlineClass(it) else generateDataClass(it)
        }

        val enumFiles = spec.enums.map { generateEnumClass(it) }

        val serializersModuleFile = SerializersModuleGenerator.generate()

        val uuidSerializerFile = if (spec.usesUuid()) generateUuidSerializer() else null

        schemaFiles + inlineSchemaFiles + enumFiles + listOfNotNull(serializersModuleFile, uuidSerializerFile)
    }

    data class HierarchyInfo(
        val sealedHierarchies: Map<String, List<String>>,
        val variantParents: Map<String, Map<ClassName, String>>,
        val anyOfWithoutDiscriminator: Set<String>,
        val schemas: List<SchemaModel>,
    )

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
                val parentClass = ClassName.Companion(modelPackage, schema.name)
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

    context(deduplicator: InlineSchemaDeduplicator)
    private fun collectAllInlineSchemas(spec: ApiSpec): List<SchemaModel> {
        val endpointRefs = spec.endpoints.flatMap { endpoint ->
            val requestRef = endpoint.requestBody?.schema
            val responseRefs = endpoint.responses.values.map { it.schema }
            responseRefs + requestRef
        }

        val schemaPropertyRefs = spec.schemas.flatMap { schema -> schema.properties.map { it.type } }

        return collectInlineTypeRefs(endpointRefs + schemaPropertyRefs)
            .asSequence()
            .sortedBy { it.contextHint }
            .distinctBy { InlineSchemaKey.Companion.from(it.properties, it.requiredProperties) }
            .map { ref ->
                SchemaModel(
                    name = deduplicator.getOrGenerateName(ref.properties, ref.requiredProperties, ref.contextHint),
                    description = null,
                    properties = ref.properties,
                    requiredProperties = ref.requiredProperties,
                    allOf = null,
                    oneOf = null,
                    anyOf = null,
                    discriminator = null,
                )
            }.toList()
    }

    context(hierarchy: HierarchyInfo, _: ModelPackage)
    private fun generateSchemaFiles(schema: SchemaModel): List<FileSpec> = when {
        !schema.anyOf.isNullOrEmpty() || !schema.oneOf.isNullOrEmpty() -> {
            if (schema.name in hierarchy.anyOfWithoutDiscriminator) {
                listOf(generateSealedInterface(schema), generatePolymorphicSerializer(schema))
            } else {
                listOf(generateSealedInterface(schema))
            }
        }

        schema.isPrimitiveOnly -> {
            val targetType = schema.underlyingType
                ?.let { TypeMapping.toTypeName(it) }
                ?: STRING
            listOf(generateTypeAlias(schema, targetType))
        }

        else -> {
            listOf(generateDataClass(schema))
        }
    }

    /**
     * Generates a sealed interface for a oneOf/anyOf schema.
     * - anyOf without discriminator: @Serializable(with = XxxSerializer::class)
     * - oneOf or anyOf with discriminator: plain @Serializable + @JsonClassDiscriminator
     */
    context(hierarchy: HierarchyInfo, modelPackage: ModelPackage)
    private fun generateSealedInterface(schema: SchemaModel): FileSpec {
        val className = ClassName.Companion(modelPackage, schema.name)

        val typeSpec = TypeSpec.Companion.interfaceBuilder(className).addModifiers(KModifier.SEALED)

        if (schema.name in hierarchy.anyOfWithoutDiscriminator) {
            val serializerClassName = ClassName.Companion(modelPackage, "${schema.name}Serializer")
            typeSpec.addAnnotation(
                AnnotationSpec.Companion
                    .builder(SERIALIZABLE)
                    .addMember("with = %T::class", serializerClassName)
                    .build(),
            )
        } else {
            typeSpec.addAnnotation(SERIALIZABLE)
        }

        if (schema.discriminator != null) {
            typeSpec.addAnnotation(
                AnnotationSpec.Companion
                    .builder(JSON_CLASS_DISCRIMINATOR)
                    .addMember("%S", schema.discriminator.propertyName)
                    .build(),
            )
        }

        if (schema.description != null) {
            typeSpec.addKdoc("%L", schema.description)
        }

        val fileBuilder = FileSpec.Companion.builder(className).addType(typeSpec.build())

        if (schema.discriminator != null) {
            fileBuilder.addAnnotation(
                AnnotationSpec.Companion
                    .builder(OPT_IN)
                    .addMember("%T::class", EXPERIMENTAL_SERIALIZATION_API)
                    .build(),
            )
        }

        return fileBuilder.build()
    }

    /**
     * Generates a JsonContentPolymorphicSerializer object for an anyOf schema without discriminator.
     */
    context(hierarchy: HierarchyInfo, modelPackage: ModelPackage)
    private fun generatePolymorphicSerializer(schema: SchemaModel): FileSpec {
        val sealedClassName = ClassName.Companion(modelPackage, schema.name)
        val serializerClassName = ClassName.Companion(modelPackage, "${schema.name}Serializer")

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

        val selectDeserializerBody = buildSelectDeserializerBody(schema.name, uniqueFieldsPerVariant)

        val deserializationStrategy = ClassName("kotlinx.serialization", "DeserializationStrategy")
            .parameterizedBy(WildcardTypeName.Companion.producerOf(sealedClassName))

        val selectFun = FunSpec.Companion
            .builder("selectDeserializer")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter(ParameterSpec.Companion.builder("element", JSON_ELEMENT).build())
            .returns(deserializationStrategy)
            .addCode(selectDeserializerBody)
            .build()

        val objectSpec = TypeSpec.Companion
            .objectBuilder(serializerClassName)
            .superclass(JSON_CONTENT_POLYMORPHIC_SERIALIZER.parameterizedBy(sealedClassName))
            .addSuperclassConstructorParameter("%T::class", sealedClassName)
            .addFunction(selectFun)
            .build()

        return FileSpec.Companion
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
    ): CodeBlock {
        val builder = CodeBlock.Companion.builder()
        builder.beginControlFlow("return when")

        val notUnique = uniqueFieldsPerVariant.mapNotNull { (variantName, uniqueField) ->
            if (uniqueField != null) {
                builder.addStatement(
                    "%S·in·element.%M -> %T.serializer()",
                    uniqueField,
                    JSON_OBJECT_EXT,
                    ClassName.Companion(modelPackage, variantName),
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
     */
    context(hierarchy: HierarchyInfo, modelPackage: ModelPackage)
    private fun generateDataClass(schema: SchemaModel): FileSpec {
        val className = ClassName.Companion(modelPackage, schema.name)

        val parentEntries = hierarchy.variantParents[schema.name].orEmpty()
        val serialName = parentEntries.values.firstOrNull()
        val superinterfaces = parentEntries.keys

        val sortedProps = schema.properties.sortedBy { prop ->
            when {
                prop.name in schema.requiredProperties && prop.defaultValue == null -> 1
                prop.defaultValue != null -> 2
                else -> 3
            }
        }

        val constructorBuilder = FunSpec.Companion.constructorBuilder()
        val propertySpecs = sortedProps.map { prop ->
            val type = TypeMapping.toTypeName(prop.type).copy(nullable = prop.nullable)
            val kotlinName = prop.name.toCamelCase()

            val paramBuilder = ParameterSpec.Companion.builder(kotlinName, type)

            when {
                prop.nullable -> paramBuilder.defaultValue(CodeBlock.Companion.of("null"))
                prop.defaultValue != null -> paramBuilder.defaultValue(formatDefaultValue(prop))
            }

            constructorBuilder.addParameter(paramBuilder.build())

            val propBuilder = PropertySpec.Companion
                .builder(kotlinName, type)
                .initializer(kotlinName)
                .addAnnotation(
                    AnnotationSpec.Companion
                        .builder(SERIAL_NAME)
                        .addMember("%S", prop.name)
                        .build(),
                )

            propBuilder.build()
        }

        val typeSpec = TypeSpec.Companion
            .classBuilder(className)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(constructorBuilder.build())
            .addProperties(propertySpecs)
            .addAnnotation(SERIALIZABLE)
            .addSuperinterfaces(superinterfaces)

        if (serialName != null) {
            typeSpec.addAnnotation(
                AnnotationSpec.Companion
                    .builder(SERIAL_NAME)
                    .addMember("%S", serialName)
                    .build(),
            )
        }

        if (schema.description != null) {
            typeSpec.addKdoc("%L", schema.description)
        }

        val fileBuilder = FileSpec.Companion.builder(className).addType(typeSpec.build())

        val hasUuid = schema.properties.any { it.type.containsUuid() }
        if (hasUuid) {
            fileBuilder.addAnnotation(
                AnnotationSpec.Companion
                    .builder(OPT_IN)
                    .addMember("%T::class", EXPERIMENTAL_UUID_API)
                    .build(),
            )
            fileBuilder.addAnnotation(
                AnnotationSpec.Companion
                    .builder(USE_SERIALIZERS)
                    .addMember("%T::class", ClassName.Companion(modelPackage, "UuidSerializer"))
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
                PrimitiveType.STRING -> CodeBlock.Companion.of("%S", prop.defaultValue)

                PrimitiveType.INT,
                PrimitiveType.LONG,
                PrimitiveType.DOUBLE,
                PrimitiveType.FLOAT,
                PrimitiveType.BOOLEAN,
                -> CodeBlock.Companion.of("%L", prop.defaultValue)

                PrimitiveType.DATE_TIME -> catch(
                    { Instant.Companion.parse(prop.defaultValue as String) },
                    { CodeBlock.Companion.of("%T.parse(%S)", INSTANT, prop.defaultValue) },
                    { e ->
                        throw IllegalArgumentException(
                            "Invalid ISO-8601 date-time default '${prop.defaultValue}' for property ${prop.name}: ${e.message}",
                        )
                    },
                )

                PrimitiveType.DATE -> catch(
                    { LocalDate.Companion.parse(prop.defaultValue as String) },
                    { CodeBlock.Companion.of("%T.parse(%S)", LOCAL_DATE, prop.defaultValue) },
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
            CodeBlock.Companion.of("%T.%L", ClassName.Companion(modelPackage, prop.type.schemaName), constantName)
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
        val className = ClassName.Companion(modelPackage, enum.name)

        val typeSpec = TypeSpec.Companion.enumBuilder(className).addAnnotation(SERIALIZABLE)

        enum.values.forEach { value ->
            val anonymousClass = TypeSpec.Companion
                .anonymousClassBuilder()
                .addAnnotation(
                    AnnotationSpec.Companion
                        .builder(SERIAL_NAME)
                        .addMember("%S", value)
                        .build(),
                ).build()
            typeSpec.addEnumConstant(value.toEnumConstantName(), anonymousClass)
        }

        if (enum.description != null) {
            typeSpec.addKdoc("%L", enum.description)
        }

        return FileSpec.Companion
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
    private fun generateNestedInlineClass(schema: SchemaModel): FileSpec =
        generateDataClass(schema.copy(name = schema.name.toInlinedName()))

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

    context(modelPackage: ModelPackage)
    private fun generateUuidSerializer(): FileSpec {
        val uuidSerializerClass = ClassName.Companion(modelPackage, "UuidSerializer")

        val descriptorProp = PropertySpec.Companion
            .builder("descriptor", SERIAL_DESCRIPTOR)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%M(%S, %T.STRING)", PRIMITIVE_SERIAL_DESCRIPTOR_FUN, "Uuid", PRIMITIVE_KIND)
            .build()

        val serializeFun = FunSpec.Companion
            .builder("serialize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("encoder", ENCODER)
            .addParameter("value", UUID_TYPE)
            .addStatement("encoder.encodeString(value.toString())")
            .build()

        val deserializeFun = FunSpec.Companion
            .builder("deserialize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("decoder", DECODER)
            .returns(UUID_TYPE)
            .addStatement("return %T.parse(decoder.decodeString())", UUID_TYPE)
            .build()

        val objectSpec = TypeSpec.Companion
            .objectBuilder(uuidSerializerClass)
            .addSuperinterface(K_SERIALIZER.parameterizedBy(UUID_TYPE))
            .addProperty(descriptorProp)
            .addFunction(serializeFun)
            .addFunction(deserializeFun)
            .build()

        return FileSpec.Companion
            .builder(uuidSerializerClass)
            .addAnnotation(
                AnnotationSpec.Companion
                    .builder(OPT_IN)
                    .addMember("%T::class", EXPERIMENTAL_UUID_API)
                    .build(),
            ).addType(objectSpec)
            .build()
    }

    context(modelPackage: ModelPackage)
    private fun generateTypeAlias(schema: SchemaModel, primitiveType: TypeName): FileSpec {
        val className = ClassName.Companion(modelPackage, schema.name)

        val typeAlias = TypeAliasSpec.Companion.builder(schema.name, primitiveType)

        if (schema.description != null) {
            typeAlias.addKdoc("%L", schema.description)
        }

        return FileSpec.Companion
            .builder(className)
            .addTypeAlias(typeAlias.build())
            .build()
    }
}
