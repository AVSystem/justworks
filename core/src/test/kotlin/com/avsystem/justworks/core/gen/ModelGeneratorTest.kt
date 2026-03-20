package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.EnumBackingType
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.KModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelGeneratorTest {
    private val modelPackage = "com.example.model"
    private val generator = ModelGenerator(modelPackage)

    private fun spec(schemas: List<SchemaModel> = emptyList(), enums: List<EnumModel> = emptyList()) = ApiSpec(
        title = "Test",
        version = "1.0",
        endpoints = emptyList(),
        schemas = schemas,
        enums = enums,
    )

    private val petSchema =
        SchemaModel(
            name = "Pet",
            description = "A pet in the store",
            properties =
                listOf(
                    PropertyModel("id", TypeRef.Primitive(PrimitiveType.LONG), null, false),
                    PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                    PropertyModel("tag", TypeRef.Primitive(PrimitiveType.STRING), null, true),
                ),
            requiredProperties = setOf("id", "name"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )

    // -- Data class generation (MODL-01 through MODL-06) --

    @Test
    fun `generates data class with DATA modifier`() {
        val files = generator.generate(spec(schemas = listOf(petSchema)))
        assertEquals(1, files.size)
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        assertTrue(KModifier.DATA in typeSpec.modifiers, "Expected DATA modifier")
    }

    @Test
    fun `generates class with Serializable annotation`() {
        val files = generator.generate(spec(schemas = listOf(petSchema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val annotations = typeSpec.annotations.map { it.typeName.toString() }
        assertTrue("kotlinx.serialization.Serializable" in annotations, "Expected @Serializable")
    }

    @Test
    fun `required property is non-nullable in constructor`() {
        val files = generator.generate(spec(schemas = listOf(petSchema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor, "Expected primary constructor")
        val idParam = constructor.parameters.first { it.name == "id" }
        assertTrue(!idParam.type.isNullable, "Required property 'id' should be non-nullable")
    }

    @Test
    fun `optional property is nullable with default null`() {
        val files = generator.generate(spec(schemas = listOf(petSchema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val tagParam = constructor.parameters.first { it.name == "tag" }
        assertTrue(tagParam.type.isNullable, "Optional property 'tag' should be nullable")
        assertNotNull(tagParam.defaultValue, "Optional property 'tag' should have a default value")
        assertEquals("null", tagParam.defaultValue.toString())
    }

    @Test
    fun `every property has SerialName annotation with wire name`() {
        val files = generator.generate(spec(schemas = listOf(petSchema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        for (prop in typeSpec.propertySpecs) {
            val serialName =
                prop.annotations.firstOrNull {
                    it.typeName.toString() == "kotlinx.serialization.SerialName"
                }
            assertNotNull(serialName, "Property '${prop.name}' should have @SerialName")
        }
    }

    @Test
    fun `snake_case property becomes camelCase with SerialName preserving wire name`() {
        val schema =
            SchemaModel(
                name = "Item",
                description = null,
                properties =
                    listOf(
                        PropertyModel("created_at", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                    ),
                requiredProperties = setOf("created_at"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val prop = typeSpec.propertySpecs.first()
        assertEquals("createdAt", prop.name)
        val serialNameAnnotation =
            prop.annotations.first {
                it.typeName.toString() == "kotlinx.serialization.SerialName"
            }
        assertTrue(
            serialNameAnnotation.members.any { it.toString().contains("\"created_at\"") },
            "Expected @SerialName(\"created_at\")",
        )
    }

    @Test
    fun `schema with description produces KDoc`() {
        val files = generator.generate(spec(schemas = listOf(petSchema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        assertTrue(typeSpec.kdoc.toString().contains("A pet in the store"), "Expected KDoc from description")
    }

    @Test
    fun `property with Reference type produces ClassName in correct package`() {
        val schema =
            SchemaModel(
                name = "Order",
                description = null,
                properties =
                    listOf(
                        PropertyModel("pet", TypeRef.Reference("Pet"), null, false),
                    ),
                requiredProperties = setOf("pet"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val petProp = typeSpec.propertySpecs.first { it.name == "pet" }
        assertEquals("com.example.model.Pet", petProp.type.toString())
    }

    @Test
    fun `generate produces one FileSpec per schema`() {
        val schema2 = petSchema.copy(name = "Category", description = null)
        val files = generator.generate(spec(schemas = listOf(petSchema, schema2)))
        assertEquals(2, files.size)
    }

    @Test
    fun `required properties ordered before optional in constructor`() {
        val files = generator.generate(spec(schemas = listOf(petSchema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        // id and name are required, tag is optional -> tag should be last
        assertEquals(listOf("id", "name", "tag"), paramNames)
    }

    // -- Enum generation (MODL-07 through MODL-09) --

    private val statusEnum =
        EnumModel(
            name = "PetStatus",
            description = null,
            type = EnumBackingType.STRING,
            values = listOf("available", "pending", "sold"),
        )

    @Test
    fun `string enum has Serializable annotation`() {
        val files = generator.generate(spec(enums = listOf(statusEnum)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val annotations = typeSpec.annotations.map { it.typeName.toString() }
        assertTrue("kotlinx.serialization.Serializable" in annotations, "Expected @Serializable on enum")
    }

    @Test
    fun `string enum constants have SerialName with wire value`() {
        val files = generator.generate(spec(enums = listOf(statusEnum)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        assertEquals(3, typeSpec.enumConstants.size)
        for ((name, spec) in typeSpec.enumConstants) {
            val serialName =
                spec.annotations.firstOrNull {
                    it.typeName.toString() == "kotlinx.serialization.SerialName"
                }
            assertNotNull(serialName, "Enum constant '$name' should have @SerialName")
        }
    }

    @Test
    fun `enum constant names are UPPER_SNAKE_CASE`() {
        val files = generator.generate(spec(enums = listOf(statusEnum)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val names = typeSpec.enumConstants.keys.toList()
        assertEquals(listOf("AVAILABLE", "PENDING", "SOLD"), names)
    }

    @Test
    fun `enum constants do not produce anonymous class body`() {
        val files = generator.generate(spec(enums = listOf(statusEnum)))
        val source = files.first().toString()
        // Assert no class body braces on enum constants
        assertFalse(
            source.contains(Regex("""[A-Z_]+\(\) \{""")),
            "Enum constants should not have anonymous class body: $source",
        )
        assertFalse(
            source.contains(Regex("""[A-Z_]+ \{""")),
            "Enum constants should not have class body: $source",
        )
        // Assert @SerialName present
        assertTrue(source.contains("""@SerialName("available")"""), "Missing @SerialName for available: $source")
        assertTrue(source.contains("""@SerialName("pending")"""), "Missing @SerialName for pending: $source")
        assertTrue(source.contains("""@SerialName("sold")"""), "Missing @SerialName for sold: $source")
        // Assert @Serializable on enum class
        assertTrue(source.contains("@Serializable"), "Missing @Serializable on enum class: $source")
    }

    @Test
    fun `integer enum values have SerialName with numeric string`() {
        val intEnum =
            EnumModel(
                name = "Priority",
                description = null,
                type = EnumBackingType.INTEGER,
                values = listOf("1", "2", "3"),
            )
        val files = generator.generate(spec(enums = listOf(intEnum)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constants = typeSpec.enumConstants.entries.toList()
        assertEquals("1", constants[0].key)
        assertEquals("2", constants[1].key)
        assertEquals("3", constants[2].key)
        // Check @SerialName values
        for ((i, entry) in constants.withIndex()) {
            val serialName =
                entry.value.annotations.first {
                    it.typeName.toString() == "kotlinx.serialization.SerialName"
                }
            assertTrue(
                serialName.members.any { it.toString().contains("\"${i + 1}\"") },
                "Expected @SerialName(\"${i + 1}\") on ${entry.key}",
            )
        }
    }

    // -- Integration --

    @Test
    fun `generate returns FileSpecs for schemas and enums combined`() {
        val schema2 = petSchema.copy(name = "Category", description = null)
        val files = generator.generate(spec(schemas = listOf(petSchema, schema2), enums = listOf(statusEnum)))
        assertEquals(3, files.size)
    }

    @Test
    fun `all FileSpecs have correct package name`() {
        val files = generator.generate(spec(schemas = listOf(petSchema), enums = listOf(statusEnum)))
        for (file in files) {
            assertEquals(modelPackage, file.packageName)
        }
    }

    // -- ANYF-01 through ANYF-05: anyOf support --

    @Test
    fun `anyOf schema generates sealed interface with SEALED modifier`() {
        val paymentSchema =
            SchemaModel(
                name = "Payment",
                description = null,
                properties = emptyList(),
                requiredProperties = emptySet(),
                allOf = null,
                oneOf = null,
                anyOf = listOf(TypeRef.Reference("CreditCard"), TypeRef.Reference("BankTransfer")),
                discriminator = null,
            )
        val creditCardSchema =
            SchemaModel(
                name = "CreditCard",
                description = null,
                properties = listOf(PropertyModel("cardNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
                requiredProperties = setOf("cardNumber"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )

        val files = generator.generate(spec(schemas = listOf(paymentSchema, creditCardSchema)))
        val paymentType =
            files
                .first { it.name == "Payment" }
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()

        assertTrue(KModifier.SEALED in paymentType.modifiers, "Expected SEALED modifier on Payment")
        assertEquals(com.squareup.kotlinpoet.TypeSpec.Kind.INTERFACE, paymentType.kind, "Expected INTERFACE kind")
    }

    @Test
    fun `anyOf variants have SerialName annotation`() {
        val paymentSchema =
            SchemaModel(
                name = "Payment",
                description = null,
                properties = emptyList(),
                requiredProperties = emptySet(),
                allOf = null,
                oneOf = null,
                anyOf = listOf(TypeRef.Reference("CreditCard")),
                discriminator = null,
            )
        val creditCardSchema =
            SchemaModel(
                name = "CreditCard",
                description = null,
                properties = listOf(PropertyModel("cardNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
                requiredProperties = setOf("cardNumber"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )

        val files = generator.generate(spec(schemas = listOf(paymentSchema, creditCardSchema)))
        val creditCardType =
            files
                .first { it.name == "CreditCard" }
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()

        val serialNameAnnotation =
            creditCardType.annotations.find {
                it.typeName.toString() == "kotlinx.serialization.SerialName"
            }
        assertNotNull(serialNameAnnotation, "CreditCard should have @SerialName annotation")
    }

    @Test
    fun `anyOf with discriminator has JsonClassDiscriminator annotation`() {
        val paymentSchema =
            SchemaModel(
                name = "Payment",
                description = null,
                properties = emptyList(),
                requiredProperties = emptySet(),
                allOf = null,
                oneOf = null,
                anyOf = listOf(TypeRef.Reference("CreditCard")),
                discriminator =
                    com.avsystem.justworks.core.model.Discriminator(
                        propertyName = "paymentType",
                        mapping = mapOf("card" to "#/components/schemas/CreditCard"),
                    ),
            )
        val creditCardSchema =
            SchemaModel(
                name = "CreditCard",
                description = null,
                properties = listOf(PropertyModel("cardNumber", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
                requiredProperties = setOf("cardNumber"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )

        val files = generator.generate(spec(schemas = listOf(paymentSchema, creditCardSchema)))
        val paymentType =
            files
                .first { it.name == "Payment" }
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()

        val discriminatorAnnotation =
            paymentType.annotations.find {
                it.typeName.toString() == "kotlinx.serialization.json.JsonClassDiscriminator"
            }
        assertNotNull(discriminatorAnnotation, "Payment should have @JsonClassDiscriminator")
        assertTrue(
            discriminatorAnnotation.members.any { it.toString().contains("\"paymentType\"") },
            "Expected @JsonClassDiscriminator(\"paymentType\")",
        )
    }

    // -- Default value tests (DFLT-01 through DFLT-05) --

    @Test
    fun `string default generates default parameter with quoted string`() {
        val schema =
            SchemaModel(
                name = "Config",
                description = null,
                properties =
                    listOf(
                        PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false, "default-name"),
                    ),
                requiredProperties = setOf("name"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val param = constructor.parameters.first { it.name == "name" }
        assertEquals("\"default-name\"", param.defaultValue.toString())
    }

    @Test
    fun `numeric default generates default parameter with literal`() {
        val schema =
            SchemaModel(
                name = "Config",
                description = null,
                properties =
                    listOf(
                        PropertyModel("age", TypeRef.Primitive(PrimitiveType.INT), null, false, 42),
                        PropertyModel("price", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false, 19.99),
                    ),
                requiredProperties = setOf("age", "price"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val ageParam = constructor.parameters.first { it.name == "age" }
        assertEquals("42", ageParam.defaultValue.toString())
        val priceParam = constructor.parameters.first { it.name == "price" }
        assertEquals("19.99", priceParam.defaultValue.toString())
    }

    @Test
    fun `boolean default generates default parameter with literal`() {
        val schema =
            SchemaModel(
                name = "Config",
                description = null,
                properties =
                    listOf(
                        PropertyModel("active", TypeRef.Primitive(PrimitiveType.BOOLEAN), null, false, true),
                    ),
                requiredProperties = setOf("active"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val param = constructor.parameters.first { it.name == "active" }
        assertEquals("true", param.defaultValue.toString())
    }

    @Test
    fun `date-time default generates Instant parse call`() {
        val schema =
            SchemaModel(
                name = "Event",
                description = null,
                properties =
                    listOf(
                        PropertyModel(
                            "createdAt",
                            TypeRef.Primitive(PrimitiveType.DATE_TIME),
                            null,
                            false,
                            "2024-01-01T00:00:00Z",
                        ),
                    ),
                requiredProperties = setOf("createdAt"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val param = constructor.parameters.first { it.name == "createdAt" }
        assertTrue(
            param.defaultValue.toString().contains("Instant.parse"),
            "Expected Instant.parse() call, got: ${param.defaultValue}",
        )
        assertTrue(
            param.defaultValue.toString().contains("2024-01-01T00:00:00Z"),
            "Expected date-time string in default value",
        )
    }

    @Test
    fun `date default generates LocalDate parse call`() {
        val schema =
            SchemaModel(
                name = "Event",
                description = null,
                properties =
                    listOf(
                        PropertyModel("eventDate", TypeRef.Primitive(PrimitiveType.DATE), null, false, "2024-01-01"),
                    ),
                requiredProperties = setOf("eventDate"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val param = constructor.parameters.first { it.name == "eventDate" }
        assertTrue(
            param.defaultValue.toString().contains("kotlinx.datetime.LocalDate.parse"),
            "Expected LocalDate.parse() call, got: ${param.defaultValue}",
        )
        assertTrue(param.defaultValue.toString().contains("2024-01-01"), "Expected date string in default value")
    }

    @Test
    fun `properties with defaults ordered after required properties without defaults`() {
        val schema =
            SchemaModel(
                name = "Config",
                description = null,
                properties =
                    listOf(
                        PropertyModel("optional", TypeRef.Primitive(PrimitiveType.STRING), null, true, null),
                        PropertyModel("withDefault", TypeRef.Primitive(PrimitiveType.STRING), null, false, "default"),
                        PropertyModel("required", TypeRef.Primitive(PrimitiveType.STRING), null, false, null),
                    ),
                requiredProperties = setOf("required", "withDefault"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        // Expected order: required (no default), withDefault (has default), optional (nullable)
        assertEquals(listOf("required", "withDefault", "optional"), paramNames)
    }

    @Test
    fun `nullable property with default uses null default ignoring OpenAPI default`() {
        val schema =
            SchemaModel(
                name = "Config",
                description = null,
                properties =
                    listOf(
                        PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, true, "ignored-default"),
                    ),
                requiredProperties = emptySet(),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec =
            files
                .first()
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val param = constructor.parameters.first { it.name == "name" }
        assertTrue(param.type.isNullable, "Property should be nullable")
        assertNotNull(param.defaultValue, "Nullable property should have a default value")
        assertEquals("null", param.defaultValue.toString(), "Nullable property should use null default")
    }

    @Test
    fun `enum default generates enum constant reference`() {
        val statusEnum =
            EnumModel(
                name = "Status",
                description = null,
                type = EnumBackingType.STRING,
                values = listOf("active", "pending", "closed"),
            )
        val schema =
            SchemaModel(
                name = "Task",
                description = null,
                properties =
                    listOf(
                        PropertyModel("status", TypeRef.Reference("Status"), null, false, "active"),
                    ),
                requiredProperties = setOf("status"),
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        val files = generator.generate(spec(schemas = listOf(schema), enums = listOf(statusEnum)))
        val typeSpec =
            files
                .first { it.name == "Task" }
                .members
                .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
                .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        val param = constructor.parameters.first { it.name == "status" }
        assertTrue(
            param.defaultValue.toString().contains("Status.ACTIVE"),
            "Expected Status.ACTIVE, got: ${param.defaultValue}",
        )
    }

    // -- Primitive-only type alias tests --

    @Test
    fun `primitive only schema generates type alias`() {
        val groupIdSchema = SchemaModel(
            name = "GroupId",
            description = null,
            properties = emptyList(),
            requiredProperties = emptySet(),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(groupIdSchema)))
        assertEquals(1, files.size)

        val file = files.first()
        assertEquals("GroupId", file.name)

        // Verify it contains a TypeAliasSpec, not a TypeSpec
        val typeAliases = file.members.filterIsInstance<com.squareup.kotlinpoet.TypeAliasSpec>()
        assertEquals(1, typeAliases.size, "Expected one type alias")

        val typeAlias = typeAliases.first()
        assertEquals("GroupId", typeAlias.name)
    }

    @Test
    fun `primitive only schema with description generates type alias with kdoc`() {
        val userIdSchema = SchemaModel(
            name = "UserId",
            description = "Unique identifier for a user",
            properties = emptyList(),
            requiredProperties = emptySet(),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(userIdSchema)))
        val typeAlias = files
            .first()
            .members
            .filterIsInstance<com.squareup.kotlinpoet.TypeAliasSpec>()
            .first()

        assertTrue(
            typeAlias.kdoc.toString().contains("Unique identifier for a user"),
            "Expected KDoc from description",
        )
    }

    @Test
    fun `schema with properties generates data class not type alias`() {
        // Verify existing behavior unchanged - schemas with properties still get data classes
        val files = generator.generate(spec(schemas = listOf(petSchema)))

        val typeSpecs = files.first().members.filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
        val typeAliases = files.first().members.filterIsInstance<com.squareup.kotlinpoet.TypeAliasSpec>()

        assertEquals(1, typeSpecs.size, "Schema with properties should generate TypeSpec")
        assertEquals(0, typeAliases.size, "Schema with properties should not generate TypeAliasSpec")
    }

    // -- SER-03: Kotlin keyword escaping --

    @Test
    fun `property named with Kotlin keyword generates backtick-escaped name with correct SerialName`() {
        val schema = SchemaModel(
            name = "Item",
            description = null,
            properties = listOf(
                PropertyModel("object", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            ),
            requiredProperties = setOf("object"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec = files
            .first()
            .members
            .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
            .first()

        val prop = typeSpec.propertySpecs.first()

        // @SerialName should still use the original wire name
        val serialName = prop.annotations.first { it.typeName.toString() == "kotlinx.serialization.SerialName" }
        assertTrue(
            serialName.members.any { it.toString().contains("\"object\"") },
            "Expected @SerialName(\"object\") for wire name",
        )
    }

    // -- ROB-01: Circular schema visited-set guard --

    @Test
    fun `collectInlineTypeRefs with nested inline TypeRef does not stack overflow`() {
        // Build a schema model containing a deeply nested inline structure
        // (inline -> property -> another inline with the same shape)
        val innerInline = TypeRef.Inline(
            properties = listOf(
                PropertyModel("value", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            ),
            requiredProperties = emptySet(),
            contextHint = "treeNode",
        )

        val selfReferencingInline = TypeRef.Inline(
            properties = listOf(
                PropertyModel("children", TypeRef.Array(innerInline), null, true),
            ),
            requiredProperties = emptySet(),
            contextHint = "treeNode",
        )

        val schema = SchemaModel(
            name = "TreeNode",
            description = null,
            properties = listOf(
                PropertyModel("value", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                PropertyModel("children", TypeRef.Array(selfReferencingInline), null, true),
            ),
            requiredProperties = setOf("value"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )

        // Should complete without StackOverflowError
        val files = generator.generate(spec(schemas = listOf(schema)))
        assertNotNull(files, "generate should return results without StackOverflowError")
    }

    // -- SER-02: Nullable/optional property defaults regression tests --

    @Test
    fun `non-required property without default generates as nullable with null default`() {
        val schema = SchemaModel(
            name = "User",
            description = null,
            properties = listOf(
                PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                PropertyModel("nickname", TypeRef.Primitive(PrimitiveType.STRING), null, true),
            ),
            requiredProperties = setOf("name"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val typeSpec = files
            .first()
            .members
            .filterIsInstance<com.squareup.kotlinpoet.TypeSpec>()
            .first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)

        val nicknameParam = constructor.parameters.first { it.name == "nickname" }
        assertTrue(nicknameParam.type.isNullable, "Non-required property should be nullable")
        assertNotNull(nicknameParam.defaultValue, "Non-required property should have a default value")
        assertEquals("null", nicknameParam.defaultValue.toString(), "Non-required property should have = null default")

        val nameParam = constructor.parameters.first { it.name == "name" }
        assertTrue(!nameParam.type.isNullable, "Required property should be non-nullable")
        assertEquals(null, nameParam.defaultValue, "Required property should have no default")
    }

    @Test
    fun `allOf merged non-required property generates as nullable with null default`() {
        val baseSchema = SchemaModel(
            name = "Base",
            description = null,
            properties = listOf(
                PropertyModel("id", TypeRef.Primitive(PrimitiveType.LONG), null, false),
            ),
            requiredProperties = setOf("id"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val composedSchema = SchemaModel(
            name = "Child",
            description = null,
            properties = listOf(
                PropertyModel("id", TypeRef.Primitive(PrimitiveType.LONG), null, false),
                PropertyModel("optionalField", TypeRef.Primitive(PrimitiveType.STRING), null, true),
            ),
            requiredProperties = setOf("id"),
            allOf = listOf(TypeRef.Reference("Base")),
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(baseSchema, composedSchema)))
        val childFile = files.first { it.name == "Child" }
        val typeSpec = childFile.members.filterIsInstance<com.squareup.kotlinpoet.TypeSpec>().first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)

        val optionalParam = constructor.parameters.first { it.name == "optionalField" }
        assertTrue(optionalParam.type.isNullable, "Non-required allOf property should be nullable")
        assertNotNull(optionalParam.defaultValue, "Non-required allOf property should have a default value")
        assertEquals(
            "null",
            optionalParam.defaultValue.toString(),
            "Non-required allOf property should have = null default",
        )
    }

    // -- UUID support (SCHM-03/04/05) --

    @Test
    fun `data class with UUID property generates UuidSerializer file`() {
        val schema = SchemaModel(
            name = "Device",
            description = null,
            properties = listOf(
                PropertyModel("id", TypeRef.Primitive(PrimitiveType.UUID), null, false),
            ),
            requiredProperties = setOf("id"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val uuidSerializerFile = files.find { it.name == "UuidSerializer" }
        assertNotNull(uuidSerializerFile, "Expected UuidSerializer file to be generated")
        val content = uuidSerializerFile.toString()
        assertTrue(content.contains("object UuidSerializer"), "Expected object UuidSerializer")
        assertTrue(content.contains("KSerializer<Uuid>"), "Expected KSerializer<Uuid>")
    }

    @Test
    fun `data class with UUID property has Serializable with UuidSerializer annotation`() {
        val schema = SchemaModel(
            name = "Device",
            description = null,
            properties = listOf(
                PropertyModel("id", TypeRef.Primitive(PrimitiveType.UUID), null, false),
            ),
            requiredProperties = setOf("id"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val deviceFile = files.find { it.name == "Device" }
        assertNotNull(deviceFile)
        val content = deviceFile.toString()
        assertTrue(
            content.contains("@Serializable(with = UuidSerializer::class)"),
            "Expected @Serializable(with = UuidSerializer::class) on UUID property, got:\n$content",
        )
    }

    @Test
    fun `data class with UUID property has file-level OptIn annotation`() {
        val schema = SchemaModel(
            name = "Device",
            description = null,
            properties = listOf(
                PropertyModel("id", TypeRef.Primitive(PrimitiveType.UUID), null, false),
            ),
            requiredProperties = setOf("id"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val deviceFile = files.find { it.name == "Device" }
        assertNotNull(deviceFile)
        val content = deviceFile.toString()
        assertTrue(
            content.contains("ExperimentalUuidApi"),
            "Expected @OptIn(ExperimentalUuidApi::class) file annotation, got:\n$content",
        )
    }

    @Test
    fun `data class with UUID array property generates UuidSerializer and OptIn annotation`() {
        val schema = SchemaModel(
            name = "DeviceWithUuidList",
            description = null,
            properties = listOf(
                PropertyModel(
                    "ids",
                    TypeRef.Array(TypeRef.Primitive(PrimitiveType.UUID)),
                    null,
                    false,
                ),
            ),
            requiredProperties = setOf("ids"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val allContent = files.joinToString("\n") { it.toString() }

        assertTrue(
            allContent.contains("@Serializable(with = UuidSerializer::class)"),
            "Expected @Serializable(with = UuidSerializer::class) on UUID used inside array, got:\n$allContent",
        )

        val uuidSerializerFile = files.find { it.name == "UuidSerializer" }
        assertNotNull(uuidSerializerFile, "Expected UuidSerializer file to be generated for UUID used inside array")

        assertTrue(
            allContent.contains("ExperimentalUuidApi"),
            "Expected @OptIn(ExperimentalUuidApi::class) annotation when UUID is used inside array, got:\n$allContent",
        )
    }

    @Test
    fun `data class with UUID map property generates UuidSerializer and OptIn annotation`() {
        val schema = SchemaModel(
            name = "DeviceWithUuidMap",
            description = null,
            properties = listOf(
                PropertyModel(
                    "idByKey",
                    TypeRef.Map(TypeRef.Primitive(PrimitiveType.UUID)),
                    null,
                    false,
                ),
            ),
            requiredProperties = setOf("idByKey"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(schema)))
        val allContent = files.joinToString("\n") { it.toString() }

        assertTrue(
            allContent.contains("@Serializable(with = UuidSerializer::class)"),
            "Expected @Serializable(with = UuidSerializer::class) on UUID used inside map, got:\n$allContent",
        )

        val uuidSerializerFile = files.find { it.name == "UuidSerializer" }
        assertNotNull(uuidSerializerFile, "Expected UuidSerializer file to be generated for UUID used inside map")

        assertTrue(
            allContent.contains("ExperimentalUuidApi"),
            "Expected @OptIn(ExperimentalUuidApi::class) annotation when UUID is used inside map, got:\n$allContent",
        )
    }

    @Test
    fun `data class without UUID property does not generate UuidSerializer`() {
        val files = generator.generate(spec(schemas = listOf(petSchema)))
        val uuidSerializerFile = files.find { it.name == "UuidSerializer" }
        assertEquals(null, uuidSerializerFile, "Expected no UuidSerializer file without UUID properties")
    }

    @Test
    fun `required property in allOf schema generates as non-nullable without default`() {
        val composedSchema = SchemaModel(
            name = "Child",
            description = null,
            properties = listOf(
                PropertyModel("id", TypeRef.Primitive(PrimitiveType.LONG), null, false),
                PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            ),
            requiredProperties = setOf("id", "name"),
            allOf = listOf(TypeRef.Reference("Base")),
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )
        val files = generator.generate(spec(schemas = listOf(composedSchema)))
        val childFile = files.first { it.name == "Child" }
        val typeSpec = childFile.members.filterIsInstance<com.squareup.kotlinpoet.TypeSpec>().first()
        val constructor = assertNotNull(typeSpec.primaryConstructor)

        val idParam = constructor.parameters.first { it.name == "id" }
        assertTrue(!idParam.type.isNullable, "Required property should be non-nullable")
        assertEquals(null, idParam.defaultValue, "Required property should have no default")
    }
}
