package com.avsystem.justworks.core.parser

import arrow.core.raise.either
import com.avsystem.justworks.core.model.TypeRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SpecParserPolymorphicTest {
    private fun loadResource(name: String): File {
        val url =
            javaClass.getResource("/$name")
                ?: fail("Test resource not found: $name")
        return File(url.toURI())
    }

    private fun parseSpec(file: File) =
        either { SpecParser.parse(file) }.getOrElse { fail("Expected success but got errors: $it") }

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
}
