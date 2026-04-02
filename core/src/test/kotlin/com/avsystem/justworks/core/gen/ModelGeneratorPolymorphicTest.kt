package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.model.ModelGenerator
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Discriminator
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelGeneratorPolymorphicTest {
    private val modelPackage = "com.example.model"

    private fun generate(spec: ApiSpec) = context(ModelPackage(modelPackage)) {
        ModelGenerator.generate(spec, NameRegistry())
    }

    private fun spec(schemas: List<SchemaModel> = emptyList(), enums: List<EnumModel> = emptyList()) = ApiSpec(
        title = "Test",
        version = "1.0",
        endpoints = emptyList(),
        schemas = schemas,
        enums = enums,
    )

    private fun schema(
        name: String,
        properties: List<PropertyModel> = emptyList(),
        requiredProperties: Set<String> = emptySet(),
        oneOf: List<TypeRef>? = null,
        anyOf: List<TypeRef>? = null,
        allOf: List<TypeRef>? = null,
        discriminator: Discriminator? = null,
    ) = SchemaModel(
        name = name,
        description = null,
        properties = properties,
        requiredProperties = requiredProperties,
        allOf = allOf,
        oneOf = oneOf,
        anyOf = anyOf,
        discriminator = discriminator,
    )

    private fun findType(files: List<com.squareup.kotlinpoet.FileSpec>, name: String): TypeSpec {
        for (file in files) {
            val found = file.members.filterIsInstance<TypeSpec>().find { it.name == name }
            if (found != null) return found
        }
        throw AssertionError("TypeSpec '$name' not found in generated files")
    }

    private fun findFile(
        files: List<com.squareup.kotlinpoet.FileSpec>,
        typeName: String,
    ): com.squareup.kotlinpoet.FileSpec {
        for (file in files) {
            if (file.members.filterIsInstance<TypeSpec>().any { it.name == typeName }) return file
        }
        throw AssertionError("FileSpec containing '$typeName' not found")
    }

    // -- POLY-01: Sealed interface from oneOf --

    @Test
    fun `oneOf schema generates sealed interface with SEALED modifier`() {
        val shapeSchema =
            schema(
                name = "Shape",
                oneOf = listOf(TypeRef.Reference("Circle"), TypeRef.Reference("Square")),
            )
        val circleSchema =
            schema(
                name = "Circle",
                properties = listOf(PropertyModel("radius", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false)),
                requiredProperties = setOf("radius"),
            )
        val squareSchema =
            schema(
                name = "Square",
                properties = listOf(PropertyModel("sideLength", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false)),
                requiredProperties = setOf("sideLength"),
            )

        val files = generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val shapeType = findType(files, "Shape")

        assertTrue(KModifier.SEALED in shapeType.modifiers, "Expected SEALED modifier on Shape")
        assertEquals(TypeSpec.Kind.INTERFACE, shapeType.kind, "Expected INTERFACE kind")
    }

    @Test
    fun `oneOf schema has Serializable annotation`() {
        val shapeSchema =
            schema(
                name = "Shape",
                oneOf = listOf(TypeRef.Reference("Circle"), TypeRef.Reference("Square")),
            )
        val circleSchema = schema(name = "Circle")
        val squareSchema = schema(name = "Square")

        val files = generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val shapeType = findType(files, "Shape")

        val annotations = shapeType.annotations.map { it.typeName.toString() }
        assertTrue("kotlinx.serialization.Serializable" in annotations, "Expected @Serializable on sealed interface")
    }

    // -- POLY-02: Variant data classes implement sealed interface --

    @Test
    fun `variant data class implements sealed interface`() {
        val shapeSchema =
            schema(
                name = "Shape",
                oneOf = listOf(TypeRef.Reference("Circle")),
            )
        val circleSchema =
            schema(
                name = "Circle",
                properties = listOf(PropertyModel("radius", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false)),
                requiredProperties = setOf("radius"),
            )

        val files = generate(spec(schemas = listOf(shapeSchema, circleSchema)))
        val circleType = findType(files, "Circle")

        val superinterfaces = circleType.superinterfaces.keys.map { it.toString() }
        assertTrue(
            "$modelPackage.Shape" in superinterfaces,
            "Circle should implement Shape. Superinterfaces: $superinterfaces",
        )
    }

    @Test
    fun `variant data class has SerialName annotation`() {
        val shapeSchema =
            schema(
                name = "Shape",
                oneOf = listOf(TypeRef.Reference("Circle")),
            )
        val circleSchema =
            schema(
                name = "Circle",
                properties = listOf(PropertyModel("radius", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false)),
                requiredProperties = setOf("radius"),
            )

        val files = generate(spec(schemas = listOf(shapeSchema, circleSchema)))
        val circleType = findType(files, "Circle")

        val serialNameAnnotation =
            circleType.annotations.find {
                it.typeName.toString() == "kotlinx.serialization.SerialName"
            }
        assertNotNull(serialNameAnnotation, "Circle should have @SerialName annotation")
        assertTrue(
            serialNameAnnotation.members.any { it.toString().contains("\"Circle\"") },
            "Expected @SerialName(\"Circle\") (default, no discriminator mapping)",
        )
    }

    // -- POLY-03: Discriminator --

    @Test
    fun `discriminated oneOf has JsonClassDiscriminator annotation`() {
        val shapeSchema =
            schema(
                name = "Shape",
                oneOf = listOf(TypeRef.Reference("Circle"), TypeRef.Reference("Square")),
                discriminator =
                    Discriminator(
                        propertyName = "shapeType",
                        mapping = mapOf(
                            "circle" to "#/components/schemas/Circle",
                            "square" to "#/components/schemas/Square",
                        ),
                    ),
            )
        val circleSchema = schema(name = "Circle")
        val squareSchema = schema(name = "Square")

        val files = generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val shapeType = findType(files, "Shape")

        val discriminatorAnnotation =
            shapeType.annotations.find {
                it.typeName.toString() == "kotlinx.serialization.json.JsonClassDiscriminator"
            }
        assertNotNull(discriminatorAnnotation, "Shape should have @JsonClassDiscriminator")
        assertTrue(
            discriminatorAnnotation.members.any { it.toString().contains("\"shapeType\"") },
            "Expected @JsonClassDiscriminator(\"shapeType\")",
        )
    }

    @Test
    fun `discriminated variant has SerialName matching mapping key`() {
        val shapeSchema =
            schema(
                name = "Shape",
                oneOf = listOf(TypeRef.Reference("Circle")),
                discriminator =
                    Discriminator(
                        propertyName = "shapeType",
                        mapping = mapOf("circle" to "#/components/schemas/Circle"),
                    ),
            )
        val circleSchema =
            schema(
                name = "Circle",
                properties = listOf(PropertyModel("radius", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false)),
                requiredProperties = setOf("radius"),
            )

        val files = generate(spec(schemas = listOf(shapeSchema, circleSchema)))
        val circleType = findType(files, "Circle")

        val serialNameAnnotation =
            circleType.annotations.find {
                it.typeName.toString() == "kotlinx.serialization.SerialName"
            }
        assertNotNull(serialNameAnnotation, "Circle should have @SerialName")
        assertTrue(
            serialNameAnnotation.members.any { it.toString().contains("\"circle\"") },
            "Expected @SerialName(\"circle\") from discriminator mapping",
        )
    }

    @Test
    fun `file has OptIn for ExperimentalSerializationApi`() {
        val shapeSchema =
            schema(
                name = "Shape",
                oneOf = listOf(TypeRef.Reference("Circle")),
                discriminator =
                    Discriminator(
                        propertyName = "shapeType",
                        mapping = mapOf("circle" to "#/components/schemas/Circle"),
                    ),
            )
        val circleSchema = schema(name = "Circle")

        val files = generate(spec(schemas = listOf(shapeSchema, circleSchema)))
        val shapeFile = findFile(files, "Shape")

        val optInAnnotation =
            shapeFile.annotations.find {
                it.typeName.toString() == "kotlin.OptIn"
            }
        assertNotNull(optInAnnotation, "Shape file should have @OptIn annotation")
        assertTrue(
            optInAnnotation.members.any { it.toString().contains("ExperimentalSerializationApi") },
            "Expected @OptIn(ExperimentalSerializationApi::class)",
        )
    }

    // -- POLY-05: allOf property merging --

    @Test
    fun `allOf schema produces data class with merged properties`() {
        // SpecParser merges allOf properties before ModelGenerator sees them.
        // So ExtendedDog already has all properties (from Dog + inline) in its SchemaModel.
        val dogSchema =
            schema(
                name = "Dog",
                properties =
                    listOf(
                        PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("breed", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                    ),
                requiredProperties = setOf("name", "breed"),
            )
        val extendedDogSchema =
            schema(
                name = "ExtendedDog",
                properties =
                    listOf(
                        PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("breed", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("tricks", TypeRef.Array(TypeRef.Primitive(PrimitiveType.STRING)), null, false),
                    ),
                requiredProperties = setOf("name", "breed", "tricks"),
                allOf = listOf(TypeRef.Reference("Dog")),
            )

        val files = generate(spec(schemas = listOf(dogSchema, extendedDogSchema)))
        val extendedDogType = findType(files, "ExtendedDog")
        val constructor = assertNotNull(extendedDogType.primaryConstructor, "Expected primary constructor")

        // Should have all merged properties: name, breed from Dog + tricks from inline
        val paramNames = constructor.parameters.map { it.name }
        assertTrue("name" in paramNames, "Expected 'name' from Dog. Params: $paramNames")
        assertTrue("breed" in paramNames, "Expected 'breed' from Dog. Params: $paramNames")
        assertTrue("tricks" in paramNames, "Expected 'tricks' from inline. Params: $paramNames")
    }

    @Test
    fun `allOf required properties are non-nullable`() {
        val dogSchema =
            schema(
                name = "Dog",
                properties =
                    listOf(
                        PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("breed", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                    ),
                requiredProperties = setOf("name", "breed"),
            )
        val extendedDogSchema =
            schema(
                name = "ExtendedDog",
                properties =
                    listOf(
                        PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("breed", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("tricks", TypeRef.Array(TypeRef.Primitive(PrimitiveType.STRING)), null, false),
                    ),
                requiredProperties = setOf("name", "breed", "tricks"),
                allOf = listOf(TypeRef.Reference("Dog")),
            )

        val files = generate(spec(schemas = listOf(dogSchema, extendedDogSchema)))
        val extendedDogType = findType(files, "ExtendedDog")
        val constructor = assertNotNull(extendedDogType.primaryConstructor)

        for (param in constructor.parameters) {
            assertTrue(
                !param.type.isNullable,
                "Required property '${param.name}' should be non-nullable",
            )
        }
    }

    // -- POLY-07: oneOf with wrapper objects --

    @Test
    fun `oneOf with wrapper objects generates sealed interface with JsonClassDiscriminator`() {
        // Create wrapper schema like AWS CloudControl's NetworkMeshDevice
        val extenderPropsSchema =
            schema(
                name = "ExtenderDeviceProperties",
                properties = listOf(PropertyModel("deviceId", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
                requiredProperties = setOf("deviceId"),
            )
        val ethernetPropsSchema =
            schema(
                name = "EthernetDeviceProperties",
                properties = listOf(PropertyModel("macAddress", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
                requiredProperties = setOf("macAddress"),
            )

        // Parent schema with oneOf pointing to wrapper variants
        // Note: This test verifies the SpecParser has already unwrapped, so we pass the unwrapped form
        val networkMeshSchema =
            schema(
                name = "NetworkMeshDevice",
                oneOf =
                    listOf(
                        TypeRef.Reference("ExtenderDeviceProperties"),
                        TypeRef.Reference("EthernetDeviceProperties"),
                    ),
                discriminator =
                    Discriminator(
                        propertyName = "type",
                        mapping =
                            mapOf(
                                "ExtenderDevice" to "#/components/schemas/ExtenderDeviceProperties",
                                "EthernetDevice" to "#/components/schemas/EthernetDeviceProperties",
                            ),
                    ),
            )

        val files = generate(
            spec(schemas = listOf(networkMeshSchema, extenderPropsSchema, ethernetPropsSchema)),
        )
        val networkMeshType = findType(files, "NetworkMeshDevice")

        // Verify sealed interface with discriminator
        assertTrue(KModifier.SEALED in networkMeshType.modifiers)
        val discriminatorAnnotation =
            networkMeshType.annotations.find {
                it.typeName.toString() == "kotlinx.serialization.json.JsonClassDiscriminator"
            }
        assertNotNull(discriminatorAnnotation)
        assertTrue(discriminatorAnnotation.members.any { it.toString().contains("\"type\"") })
    }

    @Test
    fun `oneOf with wrapper objects generates correct SerialName on variants`() {
        // Same setup as above
        val extenderPropsSchema =
            schema(
                name = "ExtenderDeviceProperties",
                properties = listOf(PropertyModel("deviceId", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
                requiredProperties = setOf("deviceId"),
            )

        val networkMeshSchema =
            schema(
                name = "NetworkMeshDevice",
                oneOf = listOf(TypeRef.Reference("ExtenderDeviceProperties")),
                discriminator =
                    Discriminator(
                        propertyName = "type",
                        mapping = mapOf("ExtenderDevice" to "#/components/schemas/ExtenderDeviceProperties"),
                    ),
            )

        val files = generate(spec(schemas = listOf(networkMeshSchema, extenderPropsSchema)))
        val extenderType = findType(files, "ExtenderDeviceProperties")

        // Verify @SerialName uses wrapper property name
        val serialNameAnnotation =
            extenderType.annotations.find {
                it.typeName.toString() == "kotlinx.serialization.SerialName"
            }
        assertNotNull(serialNameAnnotation, "Variant should have @SerialName")
        assertTrue(
            serialNameAnnotation.members.any { it.toString().contains("\"ExtenderDevice\"") },
            "Expected @SerialName(\"ExtenderDevice\") from wrapper property name",
        )
    }

    // -- POLY-08: anyOf without discriminator -> JsonContentPolymorphicSerializer --

    @Test
    fun `anyOf without discriminator generates sealed interface with Serializable(with) annotation`() {
        val unionSchema = schema(
            name = "Payment",
            anyOf = listOf(TypeRef.Reference("CreditCard"), TypeRef.Reference("BankTransfer")),
        )
        val creditCardSchema = schema(
            name = "CreditCard",
            properties = listOf(PropertyModel("cardNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("cardNumber"),
        )
        val bankTransferSchema = schema(
            name = "BankTransfer",
            properties = listOf(PropertyModel("accountNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("accountNumber"),
        )

        val files = generate(spec(schemas = listOf(unionSchema, creditCardSchema, bankTransferSchema)))
        val paymentType = findType(files, "Payment")

        val serializableAnnotation = paymentType.annotations.find {
            it.typeName.toString() == "kotlinx.serialization.Serializable"
        }
        assertNotNull(serializableAnnotation, "Payment should have @Serializable annotation")
        assertTrue(
            serializableAnnotation.members.any { it.toString().contains("PaymentSerializer") },
            "Expected @Serializable(with = PaymentSerializer::class), got: ${serializableAnnotation.members}",
        )
    }

    @Test
    fun `anyOf without discriminator generates JsonContentPolymorphicSerializer object`() {
        val unionSchema = schema(
            name = "Payment",
            anyOf = listOf(TypeRef.Reference("CreditCard"), TypeRef.Reference("BankTransfer")),
        )
        val creditCardSchema = schema(
            name = "CreditCard",
            properties = listOf(PropertyModel("cardNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("cardNumber"),
        )
        val bankTransferSchema = schema(
            name = "BankTransfer",
            properties = listOf(PropertyModel("accountNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("accountNumber"),
        )

        val files = generate(spec(schemas = listOf(unionSchema, creditCardSchema, bankTransferSchema)))
        val serializerType = findType(files, "PaymentSerializer")

        assertEquals(TypeSpec.Kind.OBJECT, serializerType.kind, "PaymentSerializer should be an object")
        val superclass = serializerType.superclass.toString()
        assertTrue(
            "JsonContentPolymorphicSerializer" in superclass,
            "PaymentSerializer should extend JsonContentPolymorphicSerializer. Superclass: $superclass",
        )
    }

    @Test
    fun `anyOf without discriminator serializer selectDeserializer uses unique fields`() {
        val unionSchema = schema(
            name = "Payment",
            anyOf = listOf(TypeRef.Reference("CreditCard"), TypeRef.Reference("BankTransfer")),
        )
        val creditCardSchema = schema(
            name = "CreditCard",
            properties = listOf(PropertyModel("cardNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("cardNumber"),
        )
        val bankTransferSchema = schema(
            name = "BankTransfer",
            properties = listOf(PropertyModel("accountNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("accountNumber"),
        )

        val files = generate(spec(schemas = listOf(unionSchema, creditCardSchema, bankTransferSchema)))
        val serializerType = findType(files, "PaymentSerializer")
        val selectDeserializer = serializerType.funSpecs.find { it.name == "selectDeserializer" }
        assertNotNull(selectDeserializer, "PaymentSerializer should have selectDeserializer function")

        val bodyCode = selectDeserializer.body.toString()
        assertTrue(
            "cardNumber" in bodyCode,
            "selectDeserializer should check for 'cardNumber' unique field. Body: $bodyCode",
        )
        assertTrue(
            "accountNumber" in bodyCode,
            "selectDeserializer should check for 'accountNumber' unique field. Body: $bodyCode",
        )
    }

    @Test
    fun `anyOf without discriminator with overlapping fields generates TODO body`() {
        val unionSchema = schema(
            name = "Payment",
            anyOf = listOf(TypeRef.Reference("TypeA"), TypeRef.Reference("TypeB")),
        )
        // Both variants share the same field "amount" - no unique fields
        val typeASchema = schema(
            name = "TypeA",
            properties = listOf(PropertyModel("amount", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false)),
            requiredProperties = setOf("amount"),
        )
        val typeBSchema = schema(
            name = "TypeB",
            properties = listOf(PropertyModel("amount", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false)),
            requiredProperties = setOf("amount"),
        )

        val files = generate(spec(schemas = listOf(unionSchema, typeASchema, typeBSchema)))
        val serializerType = findType(files, "PaymentSerializer")
        val selectDeserializer = serializerType.funSpecs.find { it.name == "selectDeserializer" }
        assertNotNull(selectDeserializer, "PaymentSerializer should have selectDeserializer function")

        val bodyCode = selectDeserializer.body.toString()
        assertTrue(
            "TODO" in bodyCode || "manual" in bodyCode.lowercase(),
            "selectDeserializer should contain TODO for overlapping fields. Body: $bodyCode",
        )
    }

    @Test
    fun `anyOf without discriminator variant subclasses retain their own Serializable annotation`() {
        val unionSchema = schema(
            name = "Payment",
            anyOf = listOf(TypeRef.Reference("CreditCard"), TypeRef.Reference("BankTransfer")),
        )
        val creditCardSchema = schema(
            name = "CreditCard",
            properties = listOf(PropertyModel("cardNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("cardNumber"),
        )
        val bankTransferSchema = schema(
            name = "BankTransfer",
            properties = listOf(PropertyModel("accountNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("accountNumber"),
        )

        val files = generate(spec(schemas = listOf(unionSchema, creditCardSchema, bankTransferSchema)))
        val creditCardType = findType(files, "CreditCard")

        val annotations = creditCardType.annotations.map { it.typeName.toString() }
        assertTrue(
            "kotlinx.serialization.Serializable" in annotations,
            "CreditCard variant should still have @Serializable. Annotations: $annotations",
        )
    }

    @Test
    fun `anyOf with discriminator NOT affected by JsonContentPolymorphicSerializer path`() {
        // Ensure the discriminator-present anyOf still uses the old SerializersModule path
        val shapeSchema = schema(
            name = "Shape",
            anyOf = listOf(TypeRef.Reference("Circle"), TypeRef.Reference("Square")),
            discriminator = Discriminator(
                propertyName = "shapeType",
                mapping = mapOf("circle" to "#/components/schemas/Circle", "square" to "#/components/schemas/Square"),
            ),
        )
        val circleSchema = schema(name = "Circle")
        val squareSchema = schema(name = "Square")

        val files = generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val shapeType = findType(files, "Shape")

        // Should have plain @Serializable, NOT @Serializable(with = ...)
        val serializableAnnotation = shapeType.annotations.find {
            it.typeName.toString() == "kotlinx.serialization.Serializable"
        }
        assertNotNull(serializableAnnotation, "Shape should have @Serializable")
        assertTrue(
            serializableAnnotation.members.isEmpty(),
            "Discriminated anyOf should use plain @Serializable, not @Serializable(with = ...). Members: ${serializableAnnotation.members}",
        )

        // ShapeSerializer should NOT be generated
        val serializerTypes = files.flatMap { it.members.filterIsInstance<TypeSpec>() }
        val shapeSerializerType = serializerTypes.find { it.name == "ShapeSerializer" }
        assertEquals(
            null,
            shapeSerializerType,
            "Discriminated anyOf should NOT generate a JsonContentPolymorphicSerializer",
        )
    }

    // -- CEM-01: boolean discriminator names (KotlinPoet handles escaping) --

    @Test
    fun `boolean discriminator names produce valid data classes`() {
        val deviceStatusSchema = schema(
            name = "DeviceStatus",
            oneOf = listOf(
                TypeRef.Reference("true"),
                TypeRef.Reference("false"),
            ),
            discriminator = Discriminator(
                propertyName = "online",
                mapping = mapOf(
                    "true" to "#/components/schemas/true",
                    "false" to "#/components/schemas/false",
                ),
            ),
        )
        val trueSchema = schema(
            name = "true",
            properties = listOf(
                PropertyModel("connectedSince", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            ),
            requiredProperties = setOf("connectedSince"),
        )
        val falseSchema = schema(
            name = "false",
            properties = listOf(
                PropertyModel("lastSeen", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            ),
            requiredProperties = setOf("lastSeen"),
        )

        val files = generate(
            spec(schemas = listOf(deviceStatusSchema, trueSchema, falseSchema)),
        )

        val trueType = findType(files, "true")
        assertTrue(KModifier.DATA in trueType.modifiers, "'true' should be data class")

        val falseType = findType(files, "false")
        assertTrue(KModifier.DATA in falseType.modifiers, "'false' should be data class")

        // Both implement DeviceStatus sealed interface
        val trueSuperinterfaces = trueType.superinterfaces.keys.map { it.toString() }
        assertTrue(
            "$modelPackage.DeviceStatus" in trueSuperinterfaces,
            "'true' should implement DeviceStatus. Superinterfaces: $trueSuperinterfaces",
        )
        val falseSuperinterfaces = falseType.superinterfaces.keys.map { it.toString() }
        assertTrue(
            "$modelPackage.DeviceStatus" in falseSuperinterfaces,
            "'false' should implement DeviceStatus. Superinterfaces: $falseSuperinterfaces",
        )
    }

    @Test
    fun `all oneOf variant schemas generate data classes even with many subtypes`() {
        val variantNames = listOf(
            "ExtenderDevice",
            "EthernetDevice",
            "WanDevice",
            "USBDevice",
            "WiFiDevice",
            "OtherDevice",
        )

        val networkMeshSchema = schema(
            name = "NetworkMeshDevice",
            oneOf = variantNames.map { TypeRef.Reference(it) },
            discriminator = Discriminator(
                propertyName = "deviceType",
                mapping = variantNames.associateWith { "#/components/schemas/$it" },
            ),
        )

        val variantSchemas = variantNames.map { name ->
            schema(
                name = name,
                properties = listOf(
                    PropertyModel("deviceId", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                ),
                requiredProperties = setOf("deviceId"),
            )
        }

        val files = generate(
            spec(schemas = listOf(networkMeshSchema) + variantSchemas),
        )

        // All 6 variants generated
        for (name in variantNames) {
            val variantType = findType(files, name)
            assertTrue(
                KModifier.DATA in variantType.modifiers,
                "$name should be a data class",
            )
            val superinterfaces = variantType.superinterfaces.keys.map { it.toString() }
            assertTrue(
                "$modelPackage.NetworkMeshDevice" in superinterfaces,
                "$name should implement NetworkMeshDevice. Superinterfaces: $superinterfaces",
            )
        }

        // SerializersModule contains all variants
        val serializersModuleFile = files.find { it.name == "SerializersModule" }
        assertNotNull(serializersModuleFile, "SerializersModule file should be generated")
        val moduleCode = serializersModuleFile.toString()
        for (name in variantNames) {
            assertTrue(
                name in moduleCode,
                "SerializersModule should reference $name. Code: $moduleCode",
            )
        }
    }

    @Test
    fun `SerializersModule includes boolean variant names`() {
        val deviceStatusSchema = schema(
            name = "DeviceStatus",
            oneOf = listOf(
                TypeRef.Reference("true"),
                TypeRef.Reference("false"),
            ),
            discriminator = Discriminator(
                propertyName = "online",
                mapping = mapOf(
                    "true" to "#/components/schemas/true",
                    "false" to "#/components/schemas/false",
                ),
            ),
        )
        val trueSchema = schema(
            name = "true",
            properties = listOf(
                PropertyModel("connectedSince", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            ),
            requiredProperties = setOf("connectedSince"),
        )
        val falseSchema = schema(
            name = "false",
            properties = listOf(
                PropertyModel("lastSeen", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            ),
            requiredProperties = setOf("lastSeen"),
        )

        val files = generate(
            spec(schemas = listOf(deviceStatusSchema, trueSchema, falseSchema)),
        )

        val serializersModuleFile = files.find { it.name == "SerializersModule" }
        assertNotNull(serializersModuleFile, "SerializersModule file should be generated")
        val moduleCode = serializersModuleFile.toString()
        assertTrue(
            "`true`" in moduleCode,
            "SerializersModule should reference `true`. Code: $moduleCode",
        )
        assertTrue(
            "`false`" in moduleCode,
            "SerializersModule should reference `false`. Code: $moduleCode",
        )
    }

    // -- POLY-06: allOf with sealed parent --

    @Test
    fun `allOf referencing oneOf parent adds superinterface`() {
        val petSchema =
            schema(
                name = "Pet",
                oneOf = listOf(TypeRef.Reference("Dog")),
            )
        val dogSchema =
            schema(
                name = "Dog",
                properties =
                    listOf(
                        PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                    ),
                requiredProperties = setOf("name"),
                allOf = listOf(TypeRef.Reference("Pet")),
            )

        val files = generate(spec(schemas = listOf(petSchema, dogSchema)))
        val dogType = findType(files, "Dog")

        val superinterfaces = dogType.superinterfaces.keys.map { it.toString() }
        assertTrue(
            "$modelPackage.Pet" in superinterfaces,
            "Dog should have Pet as superinterface. Superinterfaces: $superinterfaces",
        )
    }
}
