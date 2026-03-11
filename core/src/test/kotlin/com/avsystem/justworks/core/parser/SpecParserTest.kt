package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.EnumBackingType
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.TypeRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SpecParserTest {
    private val parser = SpecParser()

    private fun loadResource(name: String): File {
        val url =
            javaClass.getResource("/$name")
                ?: fail("Test resource not found: $name")
        return File(url.toURI())
    }

    // -- SPEC-01: OpenAPI 3.0 parsing --

    @Test
    fun `parse petstore yaml produces Success with endpoints`() {
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec

        assertEquals(3, spec.endpoints.size, "Expected 3 endpoints")
    }

    @Test
    fun `parse petstore yaml produces schemas`() {
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec

        val schemaNames = spec.schemas.map { it.name }.toSet()
        assertTrue("Pet" in schemaNames, "Pet schema missing")
        assertTrue("NewPet" in schemaNames, "NewPet schema missing")
        assertTrue("Error" in schemaNames, "Error schema missing")
    }

    @Test
    fun `parse petstore yaml produces enums`() {
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec

        val petStatus = spec.enums.find { it.name == "PetStatus" }
        assertNotNull(petStatus, "PetStatus enum missing")
        assertEquals(EnumBackingType.STRING, petStatus.type)
        assertEquals(listOf("available", "pending", "sold"), petStatus.values)
    }

    @Test
    fun `parsed Pet schema has correct properties`() {
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec
        val pet =
            spec.schemas.find { it.name == "Pet" }
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
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec
        val listPets =
            spec.endpoints.find { it.operationId == "listPets" }
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
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec
        val getPet =
            spec.endpoints.find { it.operationId == "getPetById" }
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
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec
        val createPet =
            spec.endpoints.find { it.operationId == "createPet" }
                ?: fail("createPet endpoint not found")

        assertEquals(HttpMethod.POST, createPet.method)

        val body = assertNotNull(createPet.requestBody, "createPet should have a request body")
        assertTrue(body.required, "Request body should be required")
        assertEquals("application/json", body.contentType)

        val bodyType = assertIs<TypeRef.Reference>(body.schema)
        assertEquals("NewPet", bodyType.schemaName)
    }

    @Test
    fun `parsed endpoints have tags`() {
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec
        val listPets = spec.endpoints.find { it.operationId == "listPets" }!!

        assertTrue(listPets.tags.contains("pets"), "listPets should have 'pets' tag")
    }

    @Test
    fun `parsed GET pets response is array of Pet`() {
        val result = parser.parse(loadResource("petstore.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec
        val listPets = spec.endpoints.find { it.operationId == "listPets" }!!

        val okResponse =
            listPets.responses["200"]
                ?: fail("200 response not found")
        val schema = assertNotNull(okResponse.schema, "200 response should have a schema")
        val arrayType = assertIs<TypeRef.Array>(schema)
        val itemType = assertIs<TypeRef.Reference>(arrayType.items)
        assertEquals("Pet", itemType.schemaName)
    }

    // -- SPEC-02: $ref resolution --

    @Test
    fun `parse refs spec resolves all references`() {
        val result = parser.parse(loadResource("refs-spec.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec

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
        val result = parser.parse(loadResource("refs-spec.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec

        // Order -> Item -> ItemDetails (chain of refs)
        val order =
            spec.schemas.find { it.name == "Order" }
                ?: fail("Order schema not found")
        val itemProp =
            order.properties.find { it.name == "item" }
                ?: fail("item property not found on Order")

        // After resolveFully, the item property may be inlined or a reference
        // Either way, ItemDetails should exist as a named schema
        val itemDetails = spec.schemas.find { it.name == "ItemDetails" }
        assertNotNull(itemDetails, "ItemDetails schema should exist (nested ref resolved)")
    }

    @Test
    fun `refs spec parameter ref is resolved`() {
        val result = parser.parse(loadResource("refs-spec.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec

        val listOrders =
            spec.endpoints.find { it.operationId == "listOrders" }
                ?: fail("listOrders endpoint not found")

        // The $ref parameter (LimitParam) should be resolved to an actual parameter
        val limitParam =
            listOrders.parameters.find { it.name == "limit" }
                ?: fail("limit parameter not found -- \$ref parameter not resolved")
        assertEquals(ParameterLocation.QUERY, limitParam.location)
    }

    // -- SPEC-03: Error reporting --

    @Test
    fun `parse invalid spec returns Failure`() {
        val result = parser.parse(loadResource("invalid-spec.yaml"))
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `parse invalid spec has descriptive error messages`() {
        val result = parser.parse(loadResource("invalid-spec.yaml"))
        val failure = assertIs<ParseResult.Failure>(result)

        assertTrue(failure.errors.isNotEmpty(), "Failure should have error messages")
        // Errors should be human-readable, not empty or codes-only
        failure.errors.forEach { error ->
            assertTrue(error.length > 5, "Error message too short to be useful: '$error'")
        }
    }

    // -- SPEC-04: Swagger 2.0 auto-conversion --

    @Test
    fun `parse swagger 2 json returns Success`() {
        val result = parser.parse(loadResource("petstore-v2.json"))
        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun `swagger 2 spec produces endpoints and schemas`() {
        val result = parser.parse(loadResource("petstore-v2.json"))
        val spec = assertIs<ParseResult.Success>(result).spec

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
        val result = parser.parse(loadResource("anyof-spec.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec

        val unionPayment = spec.schemas.find { it.name == "UnionPayment" }
        assertNotNull(unionPayment, "UnionPayment schema should exist")
        val anyOf = assertNotNull(unionPayment.anyOf, "UnionPayment should have anyOf")
        assertEquals(2, anyOf.size, "UnionPayment should have 2 anyOf variants")
        assertEquals(null, unionPayment.discriminator, "UnionPayment should have no discriminator")
    }

    @Test
    fun `anyOf with discriminator parses successfully`() {
        val result = parser.parse(loadResource("anyof-valid-spec.yaml"))
        val spec = assertIs<ParseResult.Success>(result).spec

        val payment = spec.schemas.find { it.name == "Payment" }
        assertNotNull(payment, "Payment schema should exist")
        val anyOf = assertNotNull(payment.anyOf, "Payment should have anyOf")
        assertEquals(2, anyOf.size, "Payment should have 2 anyOf variants")
        val discriminator = assertNotNull(payment.discriminator, "Payment should have discriminator")
        assertEquals("paymentType", discriminator.propertyName)
    }

    @Test
    fun `mixed anyOf and oneOf throws IllegalArgumentException`() {
        val result = parser.parse(loadResource("mixed-combinator-spec.yaml"))
        val failure = assertIs<ParseResult.Failure>(result)

        val errorMessages = failure.errors.joinToString("\n")
        assertTrue(
            "both oneOf and anyOf" in errorMessages,
            "Expected error about mixed combinators, got: $errorMessages",
        )
    }

    // -- allOf property reference resolution --

    @Test
    fun `property with allOf reference resolves to referenced type`() {
        val spec =
            """
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
                        - ${'$'}ref: '#/components/schemas/TaskConfig'
                  required:
                    - name
            """.trimIndent()

        val result = parser.parse(spec.toTempFile())
        val apiSpec = assertIs<ParseResult.Success>(result).spec

        val task = apiSpec.schemas.find { it.name == "Task" }
        assertNotNull(task)

        val configProp = task.properties.find { it.name == "config" }
        assertNotNull(configProp)

        // Should be Reference("TaskConfig"), not Primitive(STRING)
        val configType = assertIs<TypeRef.Reference>(configProp.type)
        assertEquals("TaskConfig", configType.schemaName)
    }

    // -- Helpers --

    private fun String.toTempFile(): File {
        val tempFile = File.createTempFile("test-spec-", ".yaml")
        tempFile.deleteOnExit()
        tempFile.writeText(this)
        return tempFile
    }

    private fun collectRefs(
        typeRef: TypeRef?,
        refs: MutableSet<String>,
    ) {
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

            is TypeRef.Primitive, null -> {}
        }
    }
}
