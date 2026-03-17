package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.SchemaModel
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.PropertySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SerializersModuleGeneratorTest {
    private val modelPackage = "com.example.model"
    private val generator = SerializersModuleGenerator(modelPackage)

    private fun hierarchyInfo(sealedHierarchies: Map<String, List<String>>) = ModelGenerator.HierarchyInfo(
        sealedHierarchies = sealedHierarchies,
        variantParents = emptyMap(),
        anyOfWithoutDiscriminator = emptySet(),
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
}
