package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.ContentType
import com.avsystem.justworks.core.model.EnumBackingType
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.TypeRef
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecParserTest : SpecParserTestBase() {
    private lateinit var petstore: ApiSpec

    @BeforeTest
    fun setUp() {
        if (!::petstore.isInitialized) {
            petstore = parseSpec(loadResource("petstore.yaml"))
        }
    }

    private fun parseSpecIssues(file: File): List<String> {
        val result = SpecParser.parse(file)
        check(result is ParseResult.Failure) { "Expected failure" }
        return result.warnings.map { it.message } + result.error.message
    }

    // -- SPEC-01: OpenAPI 3.0 parsing --

    @Test
    fun `parse petstore yaml produces Success with endpoints`() {
        assertEquals(3, petstore.endpoints.size, "Expected 3 endpoints")
    }

    @Test
    fun `parse petstore yaml produces schemas`() {
        val schemaNames = petstore.schemas.map { it.name }.toSet()
        assertTrue("Pet" in schemaNames, "Pet schema missing")
        assertTrue("NewPet" in schemaNames, "NewPet schema missing")
        assertTrue("Error" in schemaNames, "Error schema missing")
    }

    @Test
    fun `parse petstore yaml produces enums`() {
        val petStatus = petstore.enums.find { it.name == "PetStatus" }
        assertNotNull(petStatus, "PetStatus enum missing")
        assertEquals(EnumBackingType.STRING, petStatus.type)
        assertEquals(
            listOf(EnumModel.Value("available"), EnumModel.Value("pending"), EnumModel.Value("sold")),
            petStatus.values,
        )
    }

    @Test
    fun `parsed Pet schema has correct properties`() {
        val pet =
            petstore.schemas.find { it.name == "Pet" }
                ?: fail("Pet schema not found")

        val propMap = pet.properties.associateBy { it.name }
        assertEquals(4, propMap.size, "Pet should have 4 properties: id, name, tag, status")

        // id: integer (int64)
        val idType = propMap["id"]?.type
        assertIs<TypeRef.Primitive>(idType)
        assertEquals(PrimitiveType.LONG, idType.type)

        // name: string
        val nameType = propMap["name"]?.type
        assertIs<TypeRef.Primitive>(nameType)
        assertEquals(PrimitiveType.STRING, nameType.type)

        // tag: string, nullable (not required)
        val tagProp = propMap["tag"]
        assertNotNull(tagProp)
        assertIs<TypeRef.Primitive>(tagProp.type)
        assertTrue(tagProp.nullable, "tag should be nullable (not required)")

        // status: reference to PetStatus
        val statusType = propMap["status"]?.type
        assertIs<TypeRef.Reference>(statusType)
        assertEquals("PetStatus", statusType.schemaName)
    }

    @Test
    fun `parsed GET pets endpoint has query parameter limit with INT type`() {
        val listPets =
            petstore.endpoints.find { it.operationId == "listPets" }
                ?: fail("listPets endpoint not found")

        assertEquals(HttpMethod.GET, listPets.method)
        assertEquals("/pets", listPets.path)

        val limitParam =
            listPets.parameters.find { it.name == "limit" }
                ?: fail("limit parameter not found")
        assertEquals(ParameterLocation.QUERY, limitParam.location)
        val limitType = assertIs<TypeRef.Primitive>(limitParam.schema)
        assertEquals(PrimitiveType.INT, limitType.type)
    }

    @Test
    fun `parsed GET pets petId has path parameter`() {
        val getPet =
            petstore.endpoints.find { it.operationId == "getPetById" }
                ?: fail("getPetById endpoint not found")

        assertEquals(HttpMethod.GET, getPet.method)

        val petIdParam =
            getPet.parameters.find { it.name == "petId" }
                ?: fail("petId parameter not found")
        assertEquals(ParameterLocation.PATH, petIdParam.location)
        assertTrue(petIdParam.required, "Path parameter should be required")
    }

    @Test
    fun `parsed POST pets has requestBody referencing NewPet`() {
        val createPet =
            petstore.endpoints.find { it.operationId == "createPet" }
                ?: fail("createPet endpoint not found")

        assertEquals(HttpMethod.POST, createPet.method)

        val body = assertNotNull(createPet.requestBody, "createPet should have a request body")
        assertTrue(body.required, "Request body should be required")
        assertEquals(ContentType.JSON_CONTENT_TYPE, body.contentType)

        val bodyType = assertIs<TypeRef.Reference>(body.schema)
        assertEquals("NewPet", bodyType.schemaName)
    }

    @Test
    fun `parsed endpoints have tags`() {
        val listPets = petstore.endpoints.find { it.operationId == "listPets" }!!

        assertTrue(listPets.tags.contains("pets"), "listPets should have 'pets' tag")
    }

    @Test
    fun `parsed GET pets response is array of Pet`() {
        val listPets = petstore.endpoints.find { it.operationId == "listPets" }!!

        val okResponse =
            listPets.responses["200"]
                ?: fail("200 response not found")
        val schema = assertNotNull(okResponse.schema, "200 response should have a schema")
        val arrayType = assertIs<TypeRef.Array>(schema)
        val itemType = assertIs<TypeRef.Reference>(arrayType.items)
        assertEquals("Pet", itemType.schemaName)
    }

    // -- SPEC-01b: Warnings for unknown schemas --

    @Test
    fun `parse spec with unresolvable schema emits warning`() {
        val result = SpecParser.parse(
            """
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Container:
                  type: object
                  properties:
                    data:
                      type: object
            """.trimIndent().toTempFile(),
        )
        assertIs<ParseResult.Success>(result)
        val warningMessages = result.warnings.map { it.message }
        assertTrue(
            warningMessages.any {
                it.contains("Container") && it.contains("data") && it.contains("JsonElement")
            },
            "Expected warning about unresolvable type, got: $warningMessages",
        )
    }

    @Test
    fun `parse spec without unknown schemas has no unknown-type warnings`() {
        val result = SpecParser.parse(loadResource("petstore.yaml"))
        assertIs<ParseResult.Success>(result)
        val unknownWarnings = result.warnings.filter {
            it.message.contains("JsonElement")
        }
        assertTrue(
            unknownWarnings.isEmpty(),
            "Petstore should have no unknown-type warnings, got: $unknownWarnings",
        )
    }

    // -- SPEC-02: $ref resolution --

    @Test
    fun `parse refs spec resolves all references`() {
        val spec = parseSpec(loadResource("refs-spec.yaml"))

        // All schema names that are referenced should exist in schemas
        val allSchemaNames = (spec.schemas.map { it.name } + spec.enums.map { it.name }).toSet()

        // Collect all TypeRef.Reference from endpoints and schemas
        val allRefs = mutableSetOf<String>()
        spec.endpoints.forEach { endpoint ->
            endpoint.responses.values.forEach { resp ->
                collectRefs(resp.schema, allRefs)
            }
            endpoint.requestBody?.let { collectRefs(it.schema, allRefs) }
        }
        spec.schemas.forEach { schema ->
            schema.properties.forEach { prop ->
                collectRefs(prop.type, allRefs)
            }
        }

        // Every referenced schema name should exist in the model
        allRefs.forEach { refName ->
            assertTrue(
                refName in allSchemaNames,
                "Referenced schema '$refName' not found in parsed model schemas: $allSchemaNames",
            )
        }
    }

    @Test
    fun `refs spec nested references are resolved in model`() {
        val spec = parseSpec(loadResource("refs-spec.yaml"))

        // Order -> Item -> ItemDetails (chain of refs)
        val order =
            spec.schemas.find { it.name == "Order" }
                ?: fail("Order schema not found")
        assert(order.properties.any { it.name == "item" }) {
            "item property not found on Order"
        }
        // After resolveFully, the item property may be inlined or a reference
        // Either way, ItemDetails should exist as a named schema
        val itemDetails = spec.schemas.find { it.name == "ItemDetails" }
        assertNotNull(itemDetails, "ItemDetails schema should exist (nested ref resolved)")
    }

    @Test
    fun `refs spec parameter ref is resolved`() {
        val spec = parseSpec(loadResource("refs-spec.yaml"))

        val listOrders =
            spec.endpoints.find { it.operationId == "listOrders" }
                ?: fail("listOrders endpoint not found")

        // The $ref parameter (LimitParam) should be resolved to an actual parameter
        val limitParam =
            listOrders.parameters.find { it.name == "limit" }
                ?: fail($$"limit parameter not found -- $ref parameter not resolved")
        assertEquals(ParameterLocation.QUERY, limitParam.location)
    }

    // -- SPEC-03: Warning reporting --

    @Test
    fun `parse spec with missing info produces warnings`() {
        val result = SpecParser.parse(loadResource("invalid-spec.yaml"))
        assertIs<ParseResult.Success>(result)
        assertTrue(result.warnings.isNotEmpty(), "Spec with missing info should produce warnings")
    }

    @Test
    fun `parse spec with missing info has descriptive warning messages`() {
        val result = SpecParser.parse(loadResource("invalid-spec.yaml"))
        assertIs<ParseResult.Success>(result)
        assertTrue(result.warnings.isNotEmpty(), "Should have warning messages")
        result.warnings.forEach { warning ->
            assertTrue(warning.message.length > 5, "Warning message too short to be useful: '$warning'")
        }
    }

    // -- SPEC-04: Swagger 2.0 auto-conversion --

    @Test
    fun `parse swagger 2 json returns Success`() {
        val result = SpecParser.parse(loadResource("petstore-v2.json"))
        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun `swagger 2 spec produces endpoints and schemas`() {
        val spec = parseSpec(loadResource("petstore-v2.json"))

        assertTrue(spec.endpoints.isNotEmpty(), "v2 spec should produce endpoints")
        assertTrue(
            spec.schemas.isNotEmpty() || spec.enums.isNotEmpty(),
            "v2 spec should produce schemas or enums",
        )

        // Should have at least the 2 endpoints from the v2 spec
        assertTrue(spec.endpoints.size >= 2, "v2 spec should have at least 2 endpoints")

        // Should have Pet schema
        val pet = spec.schemas.find { it.name == "Pet" }
        assertNotNull(pet, "v2 spec should have Pet schema after conversion")
    }

    // -- ANYF-01 through ANYF-05: anyOf support --

    @Test
    fun `anyOf without discriminator parses successfully`() {
        val spec = parseSpec(loadResource("anyof-spec.yaml"))

        val unionPayment = spec.schemas.find { it.name == "UnionPayment" }
        assertNotNull(unionPayment, "UnionPayment schema should exist")
        val anyOf = assertNotNull(unionPayment.anyOf, "UnionPayment should have anyOf")
        assertEquals(2, anyOf.size, "UnionPayment should have 2 anyOf variants")
        assertEquals(null, unionPayment.discriminator, "UnionPayment should have no discriminator")
    }

    @Test
    fun `anyOf with discriminator parses successfully`() {
        val spec = parseSpec(loadResource("anyof-valid-spec.yaml"))

        val payment = spec.schemas.find { it.name == "Payment" }
        assertNotNull(payment, "Payment schema should exist")
        val anyOf = assertNotNull(payment.anyOf, "Payment should have anyOf")
        assertEquals(2, anyOf.size, "Payment should have 2 anyOf variants")
        val discriminator = assertNotNull(payment.discriminator, "Payment should have discriminator")
        assertEquals("paymentType", discriminator.propertyName)
    }

    @Test
    fun `mixed anyOf and oneOf raises error`() {
        val errors = parseSpecIssues(loadResource("mixed-combinator-spec.yaml"))

        val errorMessages = errors.joinToString("\n")
        assertTrue(
            "both oneOf and anyOf" in errorMessages,
            "Expected error about mixed combinators, got: $errorMessages",
        )
    }

    // -- allOf property reference resolution --

    @Test
    fun `property with allOf reference resolves to referenced type`() {
        val spec =
            $$"""
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                TaskConfig:
                  type: object
                  properties:
                    timeout:
                      type: integer
                Task:
                  type: object
                  properties:
                    name:
                      type: string
                    config:
                      allOf:
                        - $ref: '#/components/schemas/TaskConfig'
                  required:
                    - name
            """.trimIndent()

        val apiSpec = parseSpec(spec.toTempFile())

        val task = apiSpec.schemas.find { it.name == "Task" }
        assertNotNull(task)

        val configProp = task.properties.find { it.name == "config" }
        assertNotNull(configProp)

        // Should be Reference("TaskConfig"), not Primitive(STRING)
        val configType = assertIs<TypeRef.Reference>(configProp.type)
        assertEquals("TaskConfig", configType.schemaName)
    }

    // -- underlyingType resolution --

    @Test
    fun `primitive integer schema has underlyingType INT`() {
        val spec = parseSpec(
            """
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                GroupId:
                  type: integer
                  format: int64
            """.trimIndent().toTempFile(),
        )

        val groupId = spec.schemas.find { it.name == "GroupId" }
            ?: fail("GroupId schema not found")
        val underlying = assertNotNull(groupId.underlyingType, "underlyingType should be set")
        val primitive = assertIs<TypeRef.Primitive>(underlying)
        assertEquals(PrimitiveType.LONG, primitive.type)
    }

    @Test
    fun `primitive boolean schema has underlyingType BOOLEAN`() {
        val spec = parseSpec(
            """
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Active:
                  type: boolean
            """.trimIndent().toTempFile(),
        )

        val active = spec.schemas.find { it.name == "Active" }
            ?: fail("Active schema not found")
        val underlying = assertNotNull(active.underlyingType, "underlyingType should be set")
        val primitive = assertIs<TypeRef.Primitive>(underlying)
        assertEquals(PrimitiveType.BOOLEAN, primitive.type)
    }

    @Test
    fun `array schema has underlyingType Array`() {
        val spec = parseSpec(
            """
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                TagList:
                  type: array
                  items:
                    type: string
            """.trimIndent().toTempFile(),
        )

        val tagList = spec.schemas.find { it.name == "TagList" }
            ?: fail("TagList schema not found")
        val underlying = assertNotNull(tagList.underlyingType, "underlyingType should be set")
        assertIs<TypeRef.Array>(underlying)
    }

    @Test
    fun `ref wrapper schema has underlyingType Reference`() {
        val spec = parseSpec(
            $$"""
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Pet:
                  type: object
                  properties:
                    name:
                      type: string
                PetAlias:
                  $ref: '#/components/schemas/Pet'
            """.trimIndent().toTempFile(),
        )

        val petAlias = spec.schemas.find { it.name == "PetAlias" }
            ?: fail("PetAlias schema not found")
        val underlying = assertNotNull(petAlias.underlyingType, "underlyingType should be set")
        val ref = assertIs<TypeRef.Reference>(underlying)
        assertEquals("Pet", ref.schemaName)
    }

    @Test
    fun `object schema with properties has no underlyingType`() {
        val spec = parseSpec(
            """
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                User:
                  type: object
                  properties:
                    name:
                      type: string
            """.trimIndent().toTempFile(),
        )

        val user = spec.schemas.find { it.name == "User" }
            ?: fail("User schema not found")
        assertEquals(null, user.underlyingType, "object with properties should not have underlyingType")
    }

    @Test
    fun `schema with allOf has no underlyingType`() {
        val spec = parseSpec(
            $$"""
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Base:
                  type: object
                  properties:
                    id:
                      type: integer
                Extended:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                  type: object
                  properties:
                    name:
                      type: string
            """.trimIndent().toTempFile(),
        )

        val extended = spec.schemas.find { it.name == "Extended" }
            ?: fail("Extended schema not found")
        assertEquals(null, extended.underlyingType, "allOf schema should not have underlyingType")
    }

    // -- x-enum-descriptions --

    @Test
    fun `enum with x-enum-descriptions as list populates value descriptions`() {
        val spec = parseSpec(
            """
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Color:
                  type: string
                  enum:
                    - red
                    - green
                    - blue
                  x-enum-descriptions:
                    - The color red
                    - The color green
                    - The color blue
            """.trimIndent().toTempFile(),
        )

        val color = spec.enums.find { it.name == "Color" } ?: fail("Color enum not found")
        assertEquals(
            listOf(
                EnumModel.Value("red", "The color red"),
                EnumModel.Value("green", "The color green"),
                EnumModel.Value("blue", "The color blue"),
            ),
            color.values,
        )
    }

    @Test
    fun `enum with x-enum-descriptions as map populates value descriptions`() {
        val spec = parseSpec(
            """
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Priority:
                  type: string
                  enum:
                    - low
                    - medium
                    - high
                  x-enum-descriptions:
                    low: Low priority
                    high: High priority
            """.trimIndent().toTempFile(),
        )

        val priority = spec.enums.find { it.name == "Priority" } ?: fail("Priority enum not found")
        assertEquals(
            listOf(
                EnumModel.Value("low", "Low priority"),
                EnumModel.Value("medium", null),
                EnumModel.Value("high", "High priority"),
            ),
            priority.values,
        )
    }

    // -- SCHM-03/04/05: Extended format type mapping --

    @Test
    fun `format type mapping produces correct PrimitiveType`() {
        val cases = listOf(
            Triple("string", "uuid", PrimitiveType.UUID),
            Triple("string", "uri", PrimitiveType.STRING),
            Triple("string", "url", PrimitiveType.STRING),
            Triple("string", "binary", PrimitiveType.BYTE_ARRAY),
            Triple("string", "email", PrimitiveType.STRING),
            Triple("string", "hostname", PrimitiveType.STRING),
            Triple("string", "ipv4", PrimitiveType.STRING),
            Triple("string", "ipv6", PrimitiveType.STRING),
            Triple("string", "password", PrimitiveType.STRING),
            Triple("string", "byte", PrimitiveType.BYTE_ARRAY),
            Triple("string", "date", PrimitiveType.DATE),
            Triple("string", "date-time", PrimitiveType.DATE_TIME),
            Triple("integer", "int32", PrimitiveType.INT),
            Triple("integer", "int64", PrimitiveType.LONG),
            Triple("number", "float", PrimitiveType.FLOAT),
            Triple("number", "double", PrimitiveType.DOUBLE),
        )
        for ((oasType, format, expected) in cases) {
            val prop = parseSpec(formatSpec(oasType, format)).schemas[0].properties[0]
            val type = assertIs<TypeRef.Primitive>(prop.type, "Expected Primitive for $oasType/$format")
            assertEquals(expected, type.type, "$oasType with format $format should produce $expected")
        }
    }

    // -- Helpers --

    private fun formatSpec(type: String, format: String): File =
        """
        openapi: 3.0.0
        info:
          title: Test
          version: 1.0.0
        paths: {}
        components:
          schemas:
            TestModel:
              type: object
              properties:
                field:
                  type: $type
                  format: $format
        """.trimIndent().toTempFile()

    private fun String.toTempFile(): File {
        val tempFile = File.createTempFile("test-spec-", ".yaml")
        tempFile.deleteOnExit()
        tempFile.writeText(this)
        return tempFile
    }

    private fun collectRefs(typeRef: TypeRef?, refs: MutableSet<String>) {
        when (typeRef) {
            is TypeRef.Reference -> {
                refs.add(typeRef.schemaName)
            }

            is TypeRef.Array -> {
                collectRefs(typeRef.items, refs)
            }

            is TypeRef.Map -> {
                collectRefs(typeRef.valueType, refs)
            }

            is TypeRef.Inline -> {
                // Recursively collect refs from inline schema properties
                typeRef.properties.forEach { prop ->
                    collectRefs(prop.type, refs)
                }
            }

            is TypeRef.Primitive, TypeRef.Unknown, null -> {}
        }
    }
}
