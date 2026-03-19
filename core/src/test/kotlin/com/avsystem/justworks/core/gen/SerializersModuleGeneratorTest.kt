package com.avsystem.justworks.core.gen

import com.squareup.kotlinpoet.PropertySpec
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SerializersModuleGeneratorTest {
    private val modelPackage = "com.example.model"
    private val generator = SerializersModuleGenerator(modelPackage)

    private fun hierarchyInfo(
        sealedHierarchies: Map<String, List<String>>,
        anyOfWithoutDiscriminator: Set<String> = emptySet(),
    ) = ModelGenerator.HierarchyInfo(
        sealedHierarchies = sealedHierarchies,
        variantParents = emptyMap(),
        anyOfWithoutDiscriminator = anyOfWithoutDiscriminator,
        schemas = emptyList(),
    )

    @Test
    fun `generates SerializersModule with polymorphic registration`() {
        val hierarchies = mapOf("Shape" to listOf("Circle", "Square"))
        val fileSpec = context(hierarchyInfo(hierarchies)) { generator.generate() }

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
        val fileSpec = context(hierarchyInfo(hierarchies)) { generator.generate() }
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
        val result = context(hierarchyInfo(emptyMap())) { generator.generate() }
        assertNull(result, "Should return null for empty hierarchies")
    }

    @Test
    fun `excludes anyOf hierarchies without discriminator`() {
        val hierarchies = mapOf(
            "Shape" to listOf("Circle", "Square"),
            "Pet" to listOf("Cat", "Dog"),
        )
        val info = hierarchyInfo(hierarchies, anyOfWithoutDiscriminator = setOf("Pet"))
        val fileSpec = context(info) { generator.generate() }

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
        val info = hierarchyInfo(hierarchies, anyOfWithoutDiscriminator = setOf("Pet"))
        val result = context(info) { generator.generate() }

        assertNull(result, "Should return null when only non-discriminator anyOf hierarchies exist")
    }
}
