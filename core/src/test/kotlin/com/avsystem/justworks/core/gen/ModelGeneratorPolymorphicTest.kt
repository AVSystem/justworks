package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Discriminator
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelGeneratorPolymorphicTest {
    private val modelPackage = "com.example.model"
    private val generator = ModelGenerator(modelPackage)

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
        description: String? = null,
    ) = SchemaModel(
        name = name,
        description = description,
        properties = properties,
        requiredProperties = requiredProperties,
        allOf = allOf,
        oneOf = oneOf,
        anyOf = anyOf,
        discriminator = discriminator,
    )

    /**
     * Finds a TypeSpec by name, searching top-level and nested typeSpecs recursively.
     */
    private fun findType(files: List<com.squareup.kotlinpoet.FileSpec>, name: String): TypeSpec {
        fun searchNested(types: List<TypeSpec>): TypeSpec? {
            for (type in types) {
                if (type.name == name) return type
                val nested = searchNested(type.typeSpecs)
                if (nested != null) return nested
            }
            return null
        }
        for (file in files) {
            val found = searchNested(file.members.filterIsInstance<TypeSpec>())
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

    // -- oneOf generates sealed CLASS with nested subtypes --

    @Test
    fun `oneOf schema generates sealed class with SEALED modifier`() {
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val shapeType = findType(files, "Shape")

        assertTrue(KModifier.SEALED in shapeType.modifiers, "Expected SEALED modifier on Shape")
        assertEquals(TypeSpec.Kind.CLASS, shapeType.kind, "Expected CLASS kind for oneOf sealed hierarchy")
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val shapeType = findType(files, "Shape")

        val annotations = shapeType.annotations.map { it.typeName.toString() }
        assertTrue("kotlinx.serialization.Serializable" in annotations, "Expected @Serializable on sealed class")
    }

    // -- oneOf subtypes are nested inside parent --

    @Test
    fun `oneOf subtypes are nested inside parent sealed class`() {
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val shapeType = findType(files, "Shape")

        val nestedNames = shapeType.typeSpecs.map { it.name }
        assertTrue("Circle" in nestedNames, "Expected Circle nested inside Shape. Nested: $nestedNames")
        assertTrue("Square" in nestedNames, "Expected Square nested inside Shape. Nested: $nestedNames")
    }

    @Test
    fun `oneOf hierarchy produces single file`() {
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))

        // No separate files for Circle or Square
        val fileNames = files.map { it.name }
        assertTrue("Circle" !in fileNames, "Should not have separate Circle file. Files: $fileNames")
        assertTrue("Square" !in fileNames, "Should not have separate Square file. Files: $fileNames")
        assertTrue("Shape" in fileNames, "Should have Shape file. Files: $fileNames")
    }

    @Test
    fun `variant data class extends sealed class via superclass`() {
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema)))
        val circleType = findType(files, "Circle")

        // Should extend via superclass, not superinterface
        val superclass = circleType.superclass.toString()
        assertTrue(
            "$modelPackage.Shape" in superclass,
            "Circle should extend Shape via superclass. Superclass: $superclass",
        )
        assertTrue(
            circleType.superinterfaces.isEmpty(),
            "Circle should not have superinterfaces. Superinterfaces: ${circleType.superinterfaces}",
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema)))
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

    @Test
    fun `variant data class has Serializable annotation`() {
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema)))
        val circleType = findType(files, "Circle")

        val annotations = circleType.annotations.map { it.typeName.toString() }
        assertTrue(
            "kotlinx.serialization.Serializable" in annotations,
            "Circle should have @Serializable. Annotations: $annotations",
        )
    }

    // -- Discriminator --

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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema)))
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema)))
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

    // -- allOf property merging --

    @Test
    fun `allOf schema produces data class with merged properties`() {
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

        val files = generator.generate(spec(schemas = listOf(dogSchema, extendedDogSchema)))
        val extendedDogType = findType(files, "ExtendedDog")
        val constructor = assertNotNull(extendedDogType.primaryConstructor, "Expected primary constructor")

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

        val files = generator.generate(spec(schemas = listOf(dogSchema, extendedDogSchema)))
        val extendedDogType = findType(files, "ExtendedDog")
        val constructor = assertNotNull(extendedDogType.primaryConstructor)

        for (param in constructor.parameters) {
            assertTrue(
                !param.type.isNullable,
                "Required property '${param.name}' should be non-nullable",
            )
        }
    }

    // -- oneOf with wrapper objects --

    @Test
    fun `oneOf with wrapper objects generates sealed class with JsonClassDiscriminator`() {
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

        val files = generator.generate(
            spec(schemas = listOf(networkMeshSchema, extenderPropsSchema, ethernetPropsSchema)),
        )
        val networkMeshType = findType(files, "NetworkMeshDevice")

        assertTrue(KModifier.SEALED in networkMeshType.modifiers)
        assertEquals(TypeSpec.Kind.CLASS, networkMeshType.kind, "Expected CLASS kind for sealed hierarchy")
        val discriminatorAnnotation =
            networkMeshType.annotations.find {
                it.typeName.toString() == "kotlinx.serialization.json.JsonClassDiscriminator"
            }
        assertNotNull(discriminatorAnnotation)
        assertTrue(discriminatorAnnotation.members.any { it.toString().contains("\"type\"") })
    }

    @Test
    fun `oneOf with wrapper objects generates correct SerialName on nested variants`() {
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

        val files = generator.generate(spec(schemas = listOf(networkMeshSchema, extenderPropsSchema)))
        val extenderType = findType(files, "ExtenderDeviceProperties")

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

    // -- anyOf without discriminator -> JsonContentPolymorphicSerializer (UNCHANGED) --

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

        val files = generator.generate(spec(schemas = listOf(unionSchema, creditCardSchema, bankTransferSchema)))
        val paymentType = findType(files, "Payment")

        // anyOf without discriminator still uses sealed INTERFACE
        assertEquals(TypeSpec.Kind.INTERFACE, paymentType.kind, "anyOf without discriminator should remain INTERFACE")

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

        val files = generator.generate(spec(schemas = listOf(unionSchema, creditCardSchema, bankTransferSchema)))
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

        val files = generator.generate(spec(schemas = listOf(unionSchema, creditCardSchema, bankTransferSchema)))
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

        val files = generator.generate(spec(schemas = listOf(unionSchema, typeASchema, typeBSchema)))
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

        val files = generator.generate(spec(schemas = listOf(unionSchema, creditCardSchema, bankTransferSchema)))
        val creditCardType = findType(files, "CreditCard")

        val annotations = creditCardType.annotations.map { it.typeName.toString() }
        assertTrue(
            "kotlinx.serialization.Serializable" in annotations,
            "CreditCard variant should still have @Serializable. Annotations: $annotations",
        )
    }

    @Test
    fun `anyOf with discriminator generates sealed class with nested subtypes`() {
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

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val shapeType = findType(files, "Shape")

        // anyOf WITH discriminator should now be a sealed CLASS with nested subtypes
        assertEquals(TypeSpec.Kind.CLASS, shapeType.kind, "Discriminated anyOf should be sealed CLASS")
        assertTrue(KModifier.SEALED in shapeType.modifiers)

        val nestedNames = shapeType.typeSpecs.map { it.name }
        assertTrue("Circle" in nestedNames, "Expected Circle nested inside Shape. Nested: $nestedNames")
        assertTrue("Square" in nestedNames, "Expected Square nested inside Shape. Nested: $nestedNames")

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

    // -- allOf with sealed parent --

    @Test
    fun `allOf referencing oneOf parent - variant is nested inside parent`() {
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

        val files = generator.generate(spec(schemas = listOf(petSchema, dogSchema)))
        val dogType = findType(files, "Dog")

        // Dog should extend Pet via superclass (nested inside Pet)
        val superclass = dogType.superclass.toString()
        assertTrue(
            "$modelPackage.Pet" in superclass,
            "Dog should have Pet as superclass. Superclass: $superclass",
        )
    }

    // -- SerializersModule uses nested class references --

    @Test
    fun `SerializersModule uses nested ClassName for variants`() {
        val shapeSchema = schema(
            name = "Shape",
            oneOf = listOf(TypeRef.Reference("Circle"), TypeRef.Reference("Square")),
            discriminator = Discriminator(
                propertyName = "shapeType",
                mapping = mapOf("circle" to "#/components/schemas/Circle", "square" to "#/components/schemas/Square"),
            ),
        )
        val circleSchema = schema(name = "Circle")
        val squareSchema = schema(name = "Square")

        val files = generator.generate(spec(schemas = listOf(shapeSchema, circleSchema, squareSchema)))
        val serializersModuleFile = files.find { it.name == "SerializersModule" }
        assertNotNull(serializersModuleFile, "SerializersModule file should be generated")

        val code = serializersModuleFile.toString()
        // Should reference Shape.Circle::class, not just Circle::class
        assertTrue(
            "Shape.Circle" in code,
            "SerializersModule should reference nested Shape.Circle. Code: $code",
        )
        assertTrue(
            "Shape.Square" in code,
            "SerializersModule should reference nested Shape.Square. Code: $code",
        )
    }

    // -- TypeMapping with classNameLookup --

    @Test
    fun `TypeMapping resolves variant to nested ClassName when lookup provided`() {
        val parentClass = ClassName(modelPackage, "Shape")
        val lookup = mapOf(
            "Circle" to parentClass.nestedClass("Circle"),
            "Square" to parentClass.nestedClass("Square"),
        )

        val result = TypeMapping.toTypeName(TypeRef.Reference("Circle"), modelPackage, lookup)
        assertEquals(
            parentClass.nestedClass("Circle"),
            result,
            "TypeMapping should resolve Circle to Shape.Circle with lookup",
        )
    }

    @Test
    fun `TypeMapping falls back to flat ClassName when lookup is empty`() {
        val result = TypeMapping.toTypeName(TypeRef.Reference("Circle"), modelPackage)
        assertEquals(
            ClassName(modelPackage, "Circle"),
            result,
            "TypeMapping should fall back to flat ClassName without lookup",
        )
    }
}
