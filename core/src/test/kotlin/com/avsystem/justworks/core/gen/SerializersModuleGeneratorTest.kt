package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.shared.SerializersModuleGenerator
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.PropertySpec
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SerializersModuleGeneratorTest {
    private val modelPackage = ModelPackage("com.example.model")

    private fun buildHierarchy(
        sealedHierarchies: Map<String, List<String>>,
        anyOfWithoutDiscriminator: Set<String> = emptySet(),
    ): Hierarchy {
        val schemas = sealedHierarchies.flatMap { (parent, variants) ->
            val oneOf = variants.map { TypeRef.Reference(it) }
            val parentSchema = SchemaModel(
                name = parent,
                description = null,
                properties = emptyList(),
                requiredProperties = emptySet(),
                oneOf = if (parent !in anyOfWithoutDiscriminator) oneOf else null,
                anyOf = if (parent in anyOfWithoutDiscriminator) oneOf else null,
                allOf = null,
                discriminator = null,
            )
            val variantSchemas = variants.map { variant ->
                SchemaModel(
                    name = variant,
                    description = null,
                    properties = emptyList(),
                    requiredProperties = emptySet(),
                    oneOf = null,
                    anyOf = null,
                    allOf = null,
                    discriminator = null,
                )
            }
            listOf(parentSchema) + variantSchemas
        }
        return Hierarchy(schemas, modelPackage)
    }

    private fun generate(hierarchy: Hierarchy): FileSpec? =
        context(hierarchy) { SerializersModuleGenerator.generate() }

    @Test
    fun `generates SerializersModule with polymorphic registration`() {
        val hierarchies = mapOf("Shape" to listOf("Circle", "Square"))
        val fileSpec = generate(buildHierarchy(hierarchies))

        assertNotNull(fileSpec, "Should generate a FileSpec for non-empty hierarchies")

        val prop = fileSpec.members.filterIsInstance<PropertySpec>().find { it.name == "generatedSerializersModule" }
        assertNotNull(prop, "Should contain generatedSerializersModule property")

        val initializer = prop.initializer.toString()
        assertTrue(initializer.contains("polymorphic"), "Initializer should contain 'polymorphic' call")
        assertTrue(initializer.contains("subclass"), "Initializer should contain 'subclass' call")
    }

    @Test
    fun `generates module with multiple hierarchies`() {
        val hierarchies =
            mapOf(
                "Shape" to listOf("Circle", "Square"),
                "Animal" to listOf("Cat", "Dog"),
            )
        val fileSpec = generate(buildHierarchy(hierarchies))
        assertNotNull(fileSpec)

        val initializer =
            fileSpec.members
                .filterIsInstance<PropertySpec>()
                .first { it.name == "generatedSerializersModule" }
                .initializer
                .toString()

        // Both hierarchies should appear
        assertTrue(initializer.contains("Shape"), "Should contain Shape hierarchy")
        assertTrue(initializer.contains("Animal"), "Should contain Animal hierarchy")
        assertTrue(initializer.contains("Circle"), "Should contain Circle subclass")
        assertTrue(initializer.contains("Dog"), "Should contain Dog subclass")
    }

    @Test
    fun `returns null for empty hierarchies`() {
        val result = generate(buildHierarchy(emptyMap<String, List<String>>()))
        assertNull(result, "Should return null for empty hierarchies")
    }

    @Test
    fun `excludes anyOf hierarchies without discriminator`() {
        val hierarchies = mapOf(
            "Shape" to listOf("Circle", "Square"),
            "Pet" to listOf("Cat", "Dog"),
        )
        val info = buildHierarchy(hierarchies, anyOfWithoutDiscriminator = setOf("Pet"))
        val fileSpec = generate(info)

        assertNotNull(fileSpec)
        val initializer = fileSpec.members
            .filterIsInstance<PropertySpec>()
            .first { it.name == "generatedSerializersModule" }
            .initializer
            .toString()

        assertTrue(initializer.contains("Shape"), "Should contain discriminator-based hierarchy")
        assertTrue(!initializer.contains("Pet"), "Should exclude anyOf without discriminator")
    }

    @Test
    fun `returns null when all hierarchies are anyOf without discriminator`() {
        val hierarchies = mapOf("Pet" to listOf("Cat", "Dog"))
        val info = buildHierarchy(hierarchies, anyOfWithoutDiscriminator = setOf("Pet"))
        val result = generate(info)

        assertNull(result, "Should return null when only non-discriminator anyOf hierarchies exist")
    }
}
