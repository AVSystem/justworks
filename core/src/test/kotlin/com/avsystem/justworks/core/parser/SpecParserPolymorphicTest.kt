package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SpecParserPolymorphicTest : SpecParserTestBase() {
    @Test
    fun `allOf schema has merged properties from referenced schema`() {
        val spec = parseSpec(loadResource("polymorphic-spec.yaml"))

        val extendedDog =
            spec.schemas.find { it.name == "ExtendedDog" }
                ?: fail("ExtendedDog schema not found. Schemas: ${spec.schemas.map { it.name }}")

        val propNames = extendedDog.properties.map { it.name }.toSet()

        // ExtendedDog allOf merges Dog properties (name, breed) + inline (tricks)
        assertTrue("tricks" in propNames, "Expected 'tricks' from inline. Properties: $propNames")
        assertTrue(
            "name" in propNames,
            "Expected 'name' from Dog via allOf merge. Properties: $propNames. " +
                "allOf: ${extendedDog.allOf}, required: ${extendedDog.requiredProperties}",
        )
        assertTrue("breed" in propNames, "Expected 'breed' from Dog. Properties: $propNames")
    }

    @Test
    fun `oneOf schema preserves oneOf refs`() {
        val spec = parseSpec(loadResource("polymorphic-spec.yaml"))

        val shape =
            spec.schemas.find { it.name == "Shape" }
                ?: fail("Shape schema not found")

        val oneOf = assertNotNull(shape.oneOf, "Shape should have oneOf")
        val refNames = oneOf.filterIsInstance<TypeRef.Reference>().map { it.schemaName }
        assertTrue("Circle" in refNames, "Expected Circle in oneOf refs. Refs: $refNames")
        assertTrue("Square" in refNames, "Expected Square in oneOf refs. Refs: $refNames")
    }

    @Test
    fun `discriminator is preserved in parsed model`() {
        val spec = parseSpec(loadResource("polymorphic-spec.yaml"))

        val shape =
            spec.schemas.find { it.name == "Shape" }
                ?: fail("Shape schema not found")

        val discriminator = assertNotNull(shape.discriminator, "Shape should have discriminator")
        assertEquals("shapeType", discriminator.propertyName)
        assertTrue(discriminator.mapping.isNotEmpty(), "Discriminator mapping should not be empty")
        assertEquals("#/components/schemas/Circle", discriminator.mapping["circle"])
        assertEquals("#/components/schemas/Square", discriminator.mapping["square"])
    }

    // -- Synthetic schemas from wrapper unwrapping --

    @Test
    fun `boolean discriminator spec produces sanitized schema names`() {
        val spec = parseSpec(loadResource("boolean-discriminator-spec.yaml"))
        val schemaNames = spec.schemas.map { it.name }.toSet()

        assertTrue(
            "DeviceStatusTrue" in schemaNames,
            "Expected sanitized schema 'DeviceStatusTrue'. Schemas: $schemaNames",
        )
        assertTrue(
            "DeviceStatusFalse" in schemaNames,
            "Expected sanitized schema 'DeviceStatusFalse'. Schemas: $schemaNames",
        )
        assertFalse(
            "true" in schemaNames,
            "Raw keyword 'true' should not appear as schema name. Schemas: $schemaNames",
        )
        assertFalse(
            "false" in schemaNames,
            "Raw keyword 'false' should not appear as schema name. Schemas: $schemaNames",
        )
    }

    @Test
    fun `boolean discriminator mapping preserves original values as keys`() {
        val spec = parseSpec(loadResource("boolean-discriminator-spec.yaml"))

        val deviceStatus =
            spec.schemas.find { it.name == "DeviceStatus" }
                ?: fail("DeviceStatus schema not found. Schemas: ${spec.schemas.map { it.name }}")

        val discriminator = assertNotNull(deviceStatus.discriminator, "DeviceStatus should have discriminator")
        val mappingKeys = discriminator.mapping.keys

        assertTrue(
            "true" in mappingKeys,
            "Discriminator mapping should have 'true' as key. Keys: $mappingKeys",
        )
        assertTrue(
            "false" in mappingKeys,
            "Discriminator mapping should have 'false' as key. Keys: $mappingKeys",
        )

        // Values should reference sanitized schema names
        assertTrue(
            discriminator.mapping["true"]!!.endsWith("DeviceStatusTrue"),
            "Mapping for 'true' should reference DeviceStatusTrue. Value: ${discriminator.mapping["true"]}",
        )
        assertTrue(
            discriminator.mapping["false"]!!.endsWith("DeviceStatusFalse"),
            "Mapping for 'false' should reference DeviceStatusFalse. Value: ${discriminator.mapping["false"]}",
        )
    }

    @Test
    fun `wrapper-unwrapped synthetic schemas appear in parsed output`() {
        val spec = parseSpec(loadResource("boolean-discriminator-spec.yaml"))
        val schemaNames = spec.schemas.map { it.name }.toSet()

        // DeviceStatus parent + 2 synthetic variants
        assertTrue(
            "DeviceStatus" in schemaNames,
            "Parent schema 'DeviceStatus' should be in output. Schemas: $schemaNames",
        )
        assertTrue(
            spec.schemas.size >= 3,
            "Should have at least 3 schemas (parent + 2 variants). Got: ${spec.schemas.size}. Schemas: $schemaNames",
        )
    }

    @Test
    fun `polymorphic-spec regression test - all schemas present`() {
        val spec = parseSpec(loadResource("polymorphic-spec.yaml"))
        val schemaNames = spec.schemas.map { it.name }.toSet()

        val expectedSchemas = setOf("Shape", "Circle", "Square", "Pet", "Cat", "Dog", "ExtendedDog")
        for (expected in expectedSchemas) {
            assertTrue(
                expected in schemaNames,
                "Expected schema '$expected' in output. Schemas: $schemaNames",
            )
        }
    }
}
